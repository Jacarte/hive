/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.events.AddPartitionEvent;
import org.apache.hadoop.hive.metastore.events.AlterPartitionEvent;
import org.apache.hadoop.hive.metastore.events.AlterTableEvent;
import org.apache.hadoop.hive.metastore.events.CreateTableEvent;
import org.apache.hadoop.hive.metastore.events.DropTableEvent;
import org.apache.hadoop.hive.metastore.messaging.EventMessage;
import org.apache.hadoop.hive.metastore.utils.FileUtils;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsDesc;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidInputException;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static org.apache.hadoop.hive.metastore.Warehouse.DEFAULT_CATALOG_NAME;
import static org.apache.hadoop.hive.metastore.utils.MetaStoreUtils.getDefaultCatalog;
import static org.apache.hadoop.hive.metastore.utils.StringUtils.normalizeIdentifier;

/**
 * Hive specific implementation of alter
 */
public class HiveAlterHandler implements AlterHandler {

  protected Configuration conf;
  private static final Logger LOG = LoggerFactory.getLogger(HiveAlterHandler.class
      .getName());

  // hiveConf, getConf and setConf are in this class because AlterHandler extends Configurable.
  // Always use the configuration from HMS Handler.  Making AlterHandler not extend Configurable
  // is not in the scope of the fix for HIVE-17942.
  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  @SuppressWarnings("nls")
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public void alterTable(RawStore msdb, Warehouse wh, String catName, String dbname,
      String name, Table newt, EnvironmentContext environmentContext,
      IHMSHandler handler) throws InvalidOperationException, MetaException {
    catName = normalizeIdentifier(catName);
    name = name.toLowerCase();
    dbname = dbname.toLowerCase();

    final boolean cascade = environmentContext != null
        && environmentContext.isSetProperties()
        && StatsSetupConst.TRUE.equals(environmentContext.getProperties().get(
            StatsSetupConst.CASCADE));
    if (newt == null) {
      throw new InvalidOperationException("New table is invalid: " + newt);
    }

    String newTblName = newt.getTableName().toLowerCase();
    String newDbName = newt.getDbName().toLowerCase();

    if (!MetaStoreUtils.validateName(newTblName, handler.getConf())) {
      throw new InvalidOperationException(newTblName + " is not a valid object name");
    }
    String validate = MetaStoreUtils.validateTblColumns(newt.getSd().getCols());
    if (validate != null) {
      throw new InvalidOperationException("Invalid column " + validate);
    }

    Path srcPath = null;
    FileSystem srcFs;
    Path destPath = null;
    FileSystem destFs = null;

    boolean success = false;
    boolean dataWasMoved = false;
    boolean isPartitionedTable = false;

    Table oldt = null;

    List<TransactionalMetaStoreEventListener> transactionalListeners = null;
    List<MetaStoreEventListener> listeners = null;
    Map<String, String> txnAlterTableEventResponses = Collections.emptyMap();
    Map<String, String> txnDropTableEventResponses = Collections.emptyMap();
    Map<String, String> txnCreateTableEventResponses = Collections.emptyMap();
    Map<String, String> txnAddPartitionEventResponses = Collections.emptyMap();

    if (handler != null) {
      transactionalListeners = handler.getTransactionalListeners();
      listeners = handler.getListeners();
    }

    try {
      boolean rename = false;
      List<Partition> parts;

      // Switching tables between catalogs is not allowed.
      if (!catName.equalsIgnoreCase(newt.getCatName())) {
        throw new InvalidOperationException("Tables cannot be moved between catalogs, old catalog" +
            catName + ", new catalog " + newt.getCatName());
      }

      // check if table with the new name already exists
      if (!newTblName.equals(name) || !newDbName.equals(dbname)) {
        if (msdb.getTable(catName, newDbName, newTblName) != null) {
          throw new InvalidOperationException("new table " + newDbName
              + "." + newTblName + " already exists");
        }
        rename = true;
      }

      msdb.openTransaction();
      // get old table
      oldt = msdb.getTable(catName, dbname, name);
      if (oldt == null) {
        throw new InvalidOperationException("table " +
            Warehouse.getCatalogQualifiedTableName(catName, dbname, name) + " doesn't exist");
      }

      if (oldt.getPartitionKeysSize() != 0) {
        isPartitionedTable = true;
      }

      // Views derive the column type from the base table definition.  So the view definition
      // can be altered to change the column types.  The column type compatibility checks should
      // be done only for non-views.
      if (MetastoreConf.getBoolVar(handler.getConf(),
            MetastoreConf.ConfVars.DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES) &&
          !oldt.getTableType().equals(TableType.VIRTUAL_VIEW.toString())) {
        // Throws InvalidOperationException if the new column types are not
        // compatible with the current column types.
        checkColTypeChangeCompatible(oldt.getSd().getCols(), newt.getSd().getCols());
      }

      //check that partition keys have not changed, except for virtual views
      //however, allow the partition comments to change
      boolean partKeysPartiallyEqual = checkPartialPartKeysEqual(oldt.getPartitionKeys(),
          newt.getPartitionKeys());

      if(!oldt.getTableType().equals(TableType.VIRTUAL_VIEW.toString())){
        if (!partKeysPartiallyEqual) {
          throw new InvalidOperationException("partition keys can not be changed.");
        }
      }

      // rename needs change the data location and move the data to the new location corresponding
      // to the new name if:
      // 1) the table is not a virtual view, and
      // 2) the table is not an external table, and
      // 3) the user didn't change the default location (or new location is empty), and
      // 4) the table was not initially created with a specified location
      if (rename
          && !oldt.getTableType().equals(TableType.VIRTUAL_VIEW.toString())
          && (oldt.getSd().getLocation().compareTo(newt.getSd().getLocation()) == 0
            || StringUtils.isEmpty(newt.getSd().getLocation()))
          && !MetaStoreUtils.isExternalTable(oldt)) {
        // TODO(Fabio) See the thesis for an idea on how to implement it
        throw new MetaException("Alter table rename on a managed table is not supported on HopsHive");

      } else {

        if (MetaStoreUtils.requireCalStats(null, null, newt, environmentContext) &&
            (newt.getPartitionKeysSize() == 0)) {
          Database db = msdb.getDatabase(newt.getCatName(), newt.getDbName());
          // Update table stats. For partitioned table, we update stats in
          MetaStoreUtils.updateTableStatsSlow(db, newt, wh, false, true, environmentContext);
        }

        if (isPartitionedTable) {
          //Currently only column related changes can be cascaded in alter table
          if(!MetaStoreUtils.areSameColumns(oldt.getSd().getCols(), newt.getSd().getCols())) {
            parts = msdb.getPartitions(catName, dbname, name, -1);
            for (Partition part : parts) {
              Partition oldPart = new Partition(part);
              List<FieldSchema> oldCols = part.getSd().getCols();
              part.getSd().setCols(newt.getSd().getCols());
              ColumnStatistics colStats = updateOrGetPartitionColumnStats(msdb, catName, dbname, name,
                  part.getValues(), oldCols, oldt, part, null);
              assert(colStats == null);
              if (cascade) {
                msdb.alterPartition(catName, dbname, name, part.getValues(), part);
              } else {
                // update changed properties (stats)
                oldPart.setParameters(part.getParameters());
                msdb.alterPartition(catName, dbname, name, part.getValues(), oldPart);
              }
            }
            msdb.alterTable(catName, dbname, name, newt);
          } else {
            LOG.warn("Alter table not cascaded to partitions.");
            alterTableUpdateTableColumnStats(msdb, oldt, newt);
          }
        } else {
          alterTableUpdateTableColumnStats(msdb, oldt, newt);
        }
      }

      if (transactionalListeners != null && !transactionalListeners.isEmpty()) {
        if (oldt.getDbName().equalsIgnoreCase(newt.getDbName())) {
          txnAlterTableEventResponses = MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                  EventMessage.EventType.ALTER_TABLE,
                  new AlterTableEvent(oldt, newt, false, true, handler),
                  environmentContext);
        } else {
          txnDropTableEventResponses = MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                  EventMessage.EventType.DROP_TABLE,
                  new DropTableEvent(oldt, true, false, handler),
                  environmentContext);
          txnCreateTableEventResponses = MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                  EventMessage.EventType.CREATE_TABLE,
                  new CreateTableEvent(newt, true, handler),
                  environmentContext);
          if (isPartitionedTable) {
            String cName = newt.isSetCatName() ? newt.getCatName() : DEFAULT_CATALOG_NAME;
            parts = msdb.getPartitions(cName, newt.getDbName(), newt.getTableName(), -1);
            txnAddPartitionEventResponses = MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                    EventMessage.EventType.ADD_PARTITION,
                    new AddPartitionEvent(newt, parts, true, handler),
                    environmentContext);
          }
        }
      }
      // commit the changes
      success = msdb.commitTransaction();
    } catch (InvalidObjectException e) {
      LOG.debug("Failed to get object from Metastore ", e);
      throw new InvalidOperationException(
          "Unable to change partition or table."
              + " Check metastore logs for detailed stack." + e.getMessage());
    } catch (NoSuchObjectException e) {
      LOG.debug("Object not found in metastore ", e);
      throw new InvalidOperationException(
          "Unable to change partition or table. Database " + dbname + " does not exist"
              + " Check metastore logs for detailed stack." + e.getMessage());
    } finally {
      if (!success) {
        LOG.error("Failed to alter table " +
            Warehouse.getCatalogQualifiedTableName(catName, dbname, name));
        msdb.rollbackTransaction();
        if (dataWasMoved) {
          try {
            if (destFs.exists(destPath)) {
              if (!destFs.rename(destPath, srcPath)) {
                LOG.error("Failed to restore data from " + destPath + " to " + srcPath
                    + " in alter table failure. Manual restore is needed.");
              }
            }
          } catch (IOException e) {
            LOG.error("Failed to restore data from " + destPath + " to " + srcPath
                +  " in alter table failure. Manual restore is needed.");
          }
        }
      }
    }

    if (!listeners.isEmpty()) {
      // An ALTER_TABLE event will be created for any alter table operation happening inside the same
      // database, otherwise a rename between databases is considered a DROP_TABLE from the old database
      // and a CREATE_TABLE in the new database plus ADD_PARTITION operations if needed.
      if (!success || dbname.equalsIgnoreCase(newDbName)) {
        // I don't think event notifications in case of failures are necessary, but other HMS operations
        // make this call whether the event failed or succeeded. To make this behavior consistent, then
        // this call will be made also for failed events even for renaming the table between databases
        // to avoid a large list of ADD_PARTITION unnecessary failed events.
        MetaStoreListenerNotifier.notifyEvent(listeners, EventMessage.EventType.ALTER_TABLE,
            new AlterTableEvent(oldt, newt, false, success, handler),
            environmentContext, txnAlterTableEventResponses, msdb);
      } else {
        MetaStoreListenerNotifier.notifyEvent(listeners, EventMessage.EventType.DROP_TABLE,
            new DropTableEvent(oldt, true, false, handler),
            environmentContext, txnDropTableEventResponses, msdb);

        MetaStoreListenerNotifier.notifyEvent(listeners, EventMessage.EventType.CREATE_TABLE,
            new CreateTableEvent(newt, true, handler),
            environmentContext, txnCreateTableEventResponses, msdb);

        if (isPartitionedTable) {
          try {
            List<Partition> parts = msdb.getPartitions(catName, newDbName, newTblName, -1);
            MetaStoreListenerNotifier.notifyEvent(listeners, EventMessage.EventType.ADD_PARTITION,
                new AddPartitionEvent(newt, parts, true, handler),
                environmentContext, txnAddPartitionEventResponses, msdb);
          } catch (NoSuchObjectException e) {
            // Just log the error but not throw an exception as this post-commit event should
            // not cause the HMS operation to fail.
            LOG.error("ADD_PARTITION events for ALTER_TABLE rename operation cannot continue because the following " +
                "table was not found on the metastore: " + newDbName + "." + newTblName, e);
          }
        }
      }
    }
  }

  /**
   * MetaException that encapsulates error message from RemoteException from hadoop RPC which wrap
   * the stack trace into e.getMessage() which makes logs/stack traces confusing.
   * @param ex
   * @return
   */
  String getSimpleMessage(Exception ex) {
    if(ex instanceof MetaException) {
      String msg = ex.getMessage();
      if(msg == null || !msg.contains("\n")) {
        return msg;
      }
      return msg.substring(0, msg.indexOf('\n'));
    }
    return ex.getMessage();
  }

  @Override
  public Partition alterPartition(final RawStore msdb, Warehouse wh, final String dbname,
    final String name, final List<String> part_vals, final Partition new_part,
    EnvironmentContext environmentContext)
      throws InvalidOperationException, InvalidObjectException, AlreadyExistsException, MetaException {
    return alterPartition(msdb, wh, DEFAULT_CATALOG_NAME, dbname, name, part_vals, new_part,
        environmentContext, null);
  }

  @Override
  public Partition alterPartition(final RawStore msdb, Warehouse wh, final String catName,
                                  final String dbname, final String name,
                                  final List<String> part_vals, final Partition new_part,
                                  EnvironmentContext environmentContext, IHMSHandler handler)
      throws InvalidOperationException, InvalidObjectException, AlreadyExistsException, MetaException {
    boolean success = false;
    Partition oldPart;
    List<TransactionalMetaStoreEventListener> transactionalListeners = null;
    if (handler != null) {
      transactionalListeners = handler.getTransactionalListeners();
    }

    // Set DDL time to now if not specified
    if (new_part.getParameters() == null ||
        new_part.getParameters().get(hive_metastoreConstants.DDL_TIME) == null ||
        Integer.parseInt(new_part.getParameters().get(hive_metastoreConstants.DDL_TIME)) == 0) {
      new_part.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(System
          .currentTimeMillis() / 1000));
    }

    //alter partition
    if (part_vals == null || part_vals.size() == 0) {
      try {
        msdb.openTransaction();

        Table tbl = msdb.getTable(catName, dbname, name);
        if (tbl == null) {
          throw new InvalidObjectException(
              "Unable to alter partition because table or database does not exist.");
        }
        oldPart = msdb.getPartition(catName, dbname, name, new_part.getValues());
        if (MetaStoreUtils.requireCalStats(oldPart, new_part, tbl, environmentContext)) {
          // if stats are same, no need to update
          if (MetaStoreUtils.isFastStatsSame(oldPart, new_part)) {
            MetaStoreUtils.updateBasicState(environmentContext, new_part.getParameters());
          } else {
            MetaStoreUtils.updatePartitionStatsFast(
                new_part, tbl, wh, false, true, environmentContext, false);
          }
        }

        // PartitionView does not have SD. We do not need update its column stats
        if (oldPart.getSd() != null) {
          updateOrGetPartitionColumnStats(msdb, catName, dbname, name, new_part.getValues(),
              oldPart.getSd().getCols(), tbl, new_part, null);
        }
        msdb.alterPartition(catName, dbname, name, new_part.getValues(), new_part);
        if (transactionalListeners != null && !transactionalListeners.isEmpty()) {
          MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                                                EventMessage.EventType.ALTER_PARTITION,
                                                new AlterPartitionEvent(oldPart, new_part, tbl, false, true, handler),
                                                environmentContext);


        }
        success = msdb.commitTransaction();
      } catch (InvalidObjectException e) {
        throw new InvalidOperationException("alter is not possible");
      } catch (NoSuchObjectException e){
        //old partition does not exist
        throw new InvalidOperationException("alter is not possible");
      } finally {
        if(!success) {
          msdb.rollbackTransaction();
        }
      }
      return oldPart;
    }

    //rename partition
    String oldPartLoc;
    String newPartLoc;
    Path srcPath = null;
    Path destPath = null;
    FileSystem srcFs;
    FileSystem destFs = null;
    boolean dataWasMoved = false;
    try {
      msdb.openTransaction();
      Table tbl = msdb.getTable(DEFAULT_CATALOG_NAME, dbname, name);
      if (tbl == null) {
        throw new InvalidObjectException(
            "Unable to alter partition because table or database does not exist.");
      }
      try {
        oldPart = msdb.getPartition(catName, dbname, name, part_vals);
      } catch (NoSuchObjectException e) {
        // this means there is no existing partition
        throw new InvalidObjectException(
            "Unable to rename partition because old partition does not exist");
      }

      Partition check_part;
      try {
        check_part = msdb.getPartition(catName, dbname, name, new_part.getValues());
      } catch(NoSuchObjectException e) {
        // this means there is no existing partition
        check_part = null;
      }

      if (check_part != null) {
        throw new AlreadyExistsException("Partition already exists:" + dbname + "." + name + "." +
            new_part.getValues());
      }

      // if the external partition is renamed, the file should not change
      if (tbl.getTableType().equals(TableType.EXTERNAL_TABLE.toString())) {
        new_part.getSd().setLocation(oldPart.getSd().getLocation());
      } else {
        //TODO(Fabio) See thesis for an idea on how to implement this
        throw new MetaException("Alter partition rename on a managed partition is not supported in HopsHive");
      }

      if (MetaStoreUtils.requireCalStats(oldPart, new_part, tbl, environmentContext)) {
        MetaStoreUtils.updatePartitionStatsFast(
            new_part, tbl, wh, false, true, environmentContext, false);
      }

      String newPartName = Warehouse.makePartName(tbl.getPartitionKeys(), new_part.getValues());
      ColumnStatistics cs = updateOrGetPartitionColumnStats(msdb, catName, dbname, name, oldPart.getValues(),
          oldPart.getSd().getCols(), tbl, new_part, null);
      msdb.alterPartition(catName, dbname, name, part_vals, new_part);
      if (cs != null) {
        cs.getStatsDesc().setPartName(newPartName);
        try {
          //existing partition column stats is no longer valid, remove
          msdb.updatePartitionColumnStatistics(cs, new_part.getValues());
        } catch (NoSuchObjectException nsoe) {
          //ignore
        } catch (InvalidInputException iie) {
          throw new InvalidOperationException("Unable to update partition stats in table rename." + iie);
        }
      }

      if (transactionalListeners != null && !transactionalListeners.isEmpty()) {
        MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                                              EventMessage.EventType.ALTER_PARTITION,
                                              new AlterPartitionEvent(oldPart, new_part, tbl, false, true, handler),
                                              environmentContext);
      }

      success = msdb.commitTransaction();
    } finally {
      if (!success) {
        LOG.error("Failed to rename a partition. Rollback transaction");
        msdb.rollbackTransaction();
        if (dataWasMoved) {
          LOG.error("Revert the data move in renaming a partition.");
          try {
            if (destFs.exists(destPath)) {
              wh.renameDir(destPath, srcPath, false);
            }
          } catch (MetaException me) {
            LOG.error("Failed to restore partition data from " + destPath + " to " + srcPath
                +  " in alter partition failure. Manual restore is needed.");
          } catch (IOException ioe) {
            LOG.error("Failed to restore partition data from " + destPath + " to " + srcPath
                +  " in alter partition failure. Manual restore is needed.");
          }
        }
      }
    }
    return oldPart;
  }

  @Override
  public List<Partition> alterPartitions(final RawStore msdb, Warehouse wh, final String dbname,
    final String name, final List<Partition> new_parts,
    EnvironmentContext environmentContext)
      throws InvalidOperationException, InvalidObjectException, AlreadyExistsException, MetaException {
    return alterPartitions(msdb, wh, DEFAULT_CATALOG_NAME, dbname, name, new_parts,
        environmentContext, null);
  }

  @Override
  public List<Partition> alterPartitions(final RawStore msdb, Warehouse wh, final String catName,
                                         final String dbname, final String name,
                                         final List<Partition> new_parts,
                                         EnvironmentContext environmentContext, IHMSHandler handler)
      throws InvalidOperationException, InvalidObjectException, AlreadyExistsException, MetaException {
    List<Partition> oldParts = new ArrayList<>();
    List<List<String>> partValsList = new ArrayList<>();
    List<TransactionalMetaStoreEventListener> transactionalListeners = null;
    if (handler != null) {
      transactionalListeners = handler.getTransactionalListeners();
    }

    boolean success = false;
    try {
      msdb.openTransaction();

      Table tbl = msdb.getTable(catName, dbname, name);
      if (tbl == null) {
        throw new InvalidObjectException(
            "Unable to alter partitions because table or database does not exist.");
      }
      for (Partition tmpPart: new_parts) {
        // Set DDL time to now if not specified
        if (tmpPart.getParameters() == null ||
            tmpPart.getParameters().get(hive_metastoreConstants.DDL_TIME) == null ||
            Integer.parseInt(tmpPart.getParameters().get(hive_metastoreConstants.DDL_TIME)) == 0) {
          tmpPart.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(System
              .currentTimeMillis() / 1000));
        }

        Partition oldTmpPart = msdb.getPartition(catName, dbname, name, tmpPart.getValues());
        oldParts.add(oldTmpPart);
        partValsList.add(tmpPart.getValues());

        if (MetaStoreUtils.requireCalStats(oldTmpPart, tmpPart, tbl, environmentContext)) {
          // Check if stats are same, no need to update
          if (MetaStoreUtils.isFastStatsSame(oldTmpPart, tmpPart)) {
            MetaStoreUtils.updateBasicState(environmentContext, tmpPart.getParameters());
          } else {
            MetaStoreUtils.updatePartitionStatsFast(
                tmpPart, tbl, wh, false, true, environmentContext, false);
          }
        }

        // PartitionView does not have SD and we do not need to update its column stats
        if (oldTmpPart.getSd() != null) {
          updateOrGetPartitionColumnStats(msdb, catName, dbname, name, oldTmpPart.getValues(),
              oldTmpPart.getSd().getCols(), tbl, tmpPart, null);
        }
      }

      msdb.alterPartitions(catName, dbname, name, partValsList, new_parts);
      Iterator<Partition> oldPartsIt = oldParts.iterator();
      for (Partition newPart : new_parts) {
        Partition oldPart;
        if (oldPartsIt.hasNext()) {
          oldPart = oldPartsIt.next();
        } else {
          throw new InvalidOperationException("Missing old partition corresponding to new partition " +
              "when invoking MetaStoreEventListener for alterPartitions event.");
        }

        if (transactionalListeners != null && !transactionalListeners.isEmpty()) {
          MetaStoreListenerNotifier.notifyEvent(transactionalListeners,
                                                EventMessage.EventType.ALTER_PARTITION,
                                                new AlterPartitionEvent(oldPart, newPart, tbl, false, true, handler));
        }
      }

      success = msdb.commitTransaction();
    } catch (InvalidObjectException | NoSuchObjectException e) {
      throw new InvalidOperationException("Alter partition operation failed: " + e);
    } finally {
      if(!success) {
        msdb.rollbackTransaction();
      }
    }

    return oldParts;
  }

  private boolean checkPartialPartKeysEqual(List<FieldSchema> oldPartKeys,
      List<FieldSchema> newPartKeys) {
    //return true if both are null, or false if one is null and the other isn't
    if (newPartKeys == null || oldPartKeys == null) {
      return oldPartKeys == newPartKeys;
    }
    if (oldPartKeys.size() != newPartKeys.size()) {
      return false;
    }
    Iterator<FieldSchema> oldPartKeysIter = oldPartKeys.iterator();
    Iterator<FieldSchema> newPartKeysIter = newPartKeys.iterator();
    FieldSchema oldFs;
    FieldSchema newFs;
    while (oldPartKeysIter.hasNext()) {
      oldFs = oldPartKeysIter.next();
      newFs = newPartKeysIter.next();
      // Alter table can change the type of partition key now.
      // So check the column name only.
      if (!oldFs.getName().equals(newFs.getName())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Uses the scheme and authority of the object's current location and the path constructed
   * using the object's new name to construct a path for the object's new location.
   */
  private Path constructRenamedPath(Path defaultNewPath, Path currentPath) {
    URI currentUri = currentPath.toUri();

    return new Path(currentUri.getScheme(), currentUri.getAuthority(),
        defaultNewPath.toUri().getPath());
  }

  @VisibleForTesting
  void alterTableUpdateTableColumnStats(RawStore msdb, Table oldTable, Table newTable)
      throws MetaException, InvalidObjectException {
    String catName = normalizeIdentifier(oldTable.isSetCatName() ? oldTable.getCatName() :
        getDefaultCatalog(conf));
    String dbName = oldTable.getDbName().toLowerCase();
    String tableName = normalizeIdentifier(oldTable.getTableName());
    String newDbName = newTable.getDbName().toLowerCase();
    String newTableName = normalizeIdentifier(newTable.getTableName());

    try {
      List<FieldSchema> oldCols = oldTable.getSd().getCols();
      List<FieldSchema> newCols = newTable.getSd().getCols();
      List<ColumnStatisticsObj> newStatsObjs = new ArrayList<>();
      ColumnStatistics colStats = null;
      boolean updateColumnStats = true;

      // Nothing to update if everything is the same
        if (newDbName.equals(dbName) &&
            newTableName.equals(tableName) &&
            MetaStoreUtils.columnsIncludedByNameType(oldCols, newCols)) {
          updateColumnStats = false;
        }

        if (updateColumnStats) {
          List<String> oldColNames = new ArrayList<>(oldCols.size());
          for (FieldSchema oldCol : oldCols) {
            oldColNames.add(oldCol.getName());
          }

          // Collect column stats which need to be rewritten and remove old stats
          colStats = msdb.getTableColumnStatistics(catName, dbName, tableName, oldColNames);
          if (colStats == null) {
            updateColumnStats = false;
          } else {
            List<ColumnStatisticsObj> statsObjs = colStats.getStatsObj();
            if (statsObjs != null) {
              List<String> deletedCols = new ArrayList<>();
              for (ColumnStatisticsObj statsObj : statsObjs) {
                boolean found = false;
                for (FieldSchema newCol : newCols) {
                  if (statsObj.getColName().equalsIgnoreCase(newCol.getName())
                      && statsObj.getColType().equalsIgnoreCase(newCol.getType())) {
                    found = true;
                    break;
                  }
                }

                if (found) {
                  if (!newDbName.equals(dbName) || !newTableName.equals(tableName)) {
                    msdb.deleteTableColumnStatistics(catName, dbName, tableName, statsObj.getColName());
                    newStatsObjs.add(statsObj);
                    deletedCols.add(statsObj.getColName());
                  }
                } else {
                  msdb.deleteTableColumnStatistics(catName, dbName, tableName, statsObj.getColName());
                  deletedCols.add(statsObj.getColName());
                }
              }
              StatsSetupConst.removeColumnStatsState(newTable.getParameters(), deletedCols);
            }
          }
        }

        // Change to new table and append stats for the new table
        msdb.alterTable(catName, dbName, tableName, newTable);
        if (updateColumnStats && !newStatsObjs.isEmpty()) {
          ColumnStatisticsDesc statsDesc = colStats.getStatsDesc();
          statsDesc.setDbName(newDbName);
          statsDesc.setTableName(newTableName);
          colStats.setStatsObj(newStatsObjs);
          msdb.updateTableColumnStatistics(colStats);
        }
    } catch (NoSuchObjectException nsoe) {
      LOG.debug("Could not find db entry." + nsoe);
    } catch (InvalidInputException e) {
      //should not happen since the input were verified before passed in
      throw new InvalidObjectException("Invalid inputs to update table column stats: " + e);
    }
  }

  private ColumnStatistics updateOrGetPartitionColumnStats(
      RawStore msdb, String catName, String dbname, String tblname, List<String> partVals,
      List<FieldSchema> oldCols, Table table, Partition part, List<FieldSchema> newCols)
          throws MetaException, InvalidObjectException {
    ColumnStatistics newPartsColStats = null;
    try {
      // if newCols are not specified, use default ones.
      if (newCols == null) {
        newCols = part.getSd() == null ? new ArrayList<>() : part.getSd().getCols();
      }
      String oldPartName = Warehouse.makePartName(table.getPartitionKeys(), partVals);
      String newPartName = Warehouse.makePartName(table.getPartitionKeys(), part.getValues());
      boolean rename = !part.getDbName().equals(dbname) || !part.getTableName().equals(tblname)
          || !oldPartName.equals(newPartName);

      // do not need to update column stats if alter partition is not for rename or changing existing columns
      if (!rename && MetaStoreUtils.columnsIncludedByNameType(oldCols, newCols)) {
        return newPartsColStats;
      }
      List<String> oldColNames = new ArrayList<>(oldCols.size());
      for (FieldSchema oldCol : oldCols) {
        oldColNames.add(oldCol.getName());
      }
      List<String> oldPartNames = Lists.newArrayList(oldPartName);
      List<ColumnStatistics> partsColStats = msdb.getPartitionColumnStatistics(catName, dbname, tblname,
          oldPartNames, oldColNames);
      assert (partsColStats.size() <= 1);
      for (ColumnStatistics partColStats : partsColStats) { //actually only at most one loop
        List<ColumnStatisticsObj> newStatsObjs = new ArrayList<>();
        List<ColumnStatisticsObj> statsObjs = partColStats.getStatsObj();
        List<String> deletedCols = new ArrayList<>();
        for (ColumnStatisticsObj statsObj : statsObjs) {
          boolean found =false;
          for (FieldSchema newCol : newCols) {
            if (statsObj.getColName().equalsIgnoreCase(newCol.getName())
                && statsObj.getColType().equalsIgnoreCase(newCol.getType())) {
              found = true;
              break;
            }
          }
          if (found) {
            if (rename) {
              msdb.deletePartitionColumnStatistics(catName, dbname, tblname, partColStats.getStatsDesc().getPartName(),
                  partVals, statsObj.getColName());
              newStatsObjs.add(statsObj);
            }
          } else {
            msdb.deletePartitionColumnStatistics(catName, dbname, tblname, partColStats.getStatsDesc().getPartName(),
                partVals, statsObj.getColName());
            deletedCols.add(statsObj.getColName());
          }
        }
        StatsSetupConst.removeColumnStatsState(part.getParameters(), deletedCols);
        if (!newStatsObjs.isEmpty()) {
          partColStats.setStatsObj(newStatsObjs);
          newPartsColStats = partColStats;
        }
      }
    } catch (NoSuchObjectException nsoe) {
      // ignore this exception, actually this exception won't be thrown from getPartitionColumnStatistics
    } catch (InvalidInputException iie) {
      throw new InvalidObjectException("Invalid input to delete partition column stats." + iie);
    }

    return newPartsColStats;
  }

  private void checkColTypeChangeCompatible(List<FieldSchema> oldCols, List<FieldSchema> newCols)
      throws InvalidOperationException {
    List<String> incompatibleCols = new ArrayList<>();
    int maxCols = Math.min(oldCols.size(), newCols.size());
    for (int i = 0; i < maxCols; i++) {
      if (!ColumnType.areColTypesCompatible(
          ColumnType.getTypeName(oldCols.get(i).getType()),
          ColumnType.getTypeName(newCols.get(i).getType()))) {
        incompatibleCols.add(newCols.get(i).getName());
      }
    }
    if (!incompatibleCols.isEmpty()) {
      throw new InvalidOperationException(
          "The following columns have types incompatible with the existing " +
              "columns in their respective positions :\n" +
              org.apache.commons.lang.StringUtils.join(incompatibleCols, ',')
      );
    }
  }

}
