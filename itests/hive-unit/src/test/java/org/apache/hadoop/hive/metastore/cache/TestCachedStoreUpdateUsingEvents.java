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
package org.apache.hadoop.hive.metastore.cache;

import java.util.*;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.common.TableName;
import org.apache.hadoop.hive.conf.Constants;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.*;
import org.apache.hadoop.hive.metastore.MetaStoreTestUtils;
import org.apache.hadoop.hive.metastore.api.*;
import org.apache.hadoop.hive.metastore.client.builder.DatabaseBuilder;
import org.apache.hadoop.hive.metastore.client.builder.TableBuilder;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf.ConfVars;
import org.apache.hadoop.hive.metastore.txn.TxnCommonUtils;
import org.apache.hadoop.hive.metastore.txn.TxnDbUtil;
import org.apache.hadoop.hive.metastore.utils.FileUtils;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat;
import org.apache.hadoop.util.StringUtils;
import org.apache.hive.hcatalog.listener.DbNotificationListener;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import jline.internal.Log;

import static org.apache.hadoop.hive.metastore.Warehouse.DEFAULT_CATALOG_NAME;

public class TestCachedStoreUpdateUsingEvents {

  private RawStore rawStore;
  private SharedCache sharedCache;
  private Configuration conf;
  private HiveMetaStore.HMSHandler hmsHandler;
  private String[] colType = new String[] {"double", "string"};

  @Before
  public void setUp() throws Exception {
    conf = MetastoreConf.newMetastoreConf();
    MetastoreConf.setBoolVar(conf, MetastoreConf.ConfVars.HIVE_IN_TEST, true);
    // Disable memory estimation for this test class
    MetastoreConf.setVar(conf, MetastoreConf.ConfVars.CACHED_RAW_STORE_MAX_CACHE_MEMORY, "-1Kb");
    MetastoreConf.setVar(conf, ConfVars.TRANSACTIONAL_EVENT_LISTENERS, DbNotificationListener.class.getName());
    MetastoreConf.setVar(conf, ConfVars.RAW_STORE_IMPL, "org.apache.hadoop.hive.metastore.cache.CachedStore");
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CACHE_CAN_USE_EVENT, true);
    MetastoreConf.setBoolVar(conf, ConfVars.HIVE_TXN_STATS_ENABLED, true);
    MetastoreConf.setBoolVar(conf, ConfVars.AGGREGATE_STATS_CACHE_ENABLED, false);
    MetastoreConf.setBoolVar(conf, ConfVars.REPLCMENABLED, true);
    MetastoreConf.setVar(conf, ConfVars.REPLCMDIR, "cmroot");
    MetaStoreTestUtils.setConfForStandloneMode(conf);

    TxnDbUtil.prepDb(conf);

    hmsHandler = new HiveMetaStore.HMSHandler("testCachedStore", conf, true);

    rawStore = new ObjectStore();
    rawStore.setConf(hmsHandler.getConf());

    CachedStore cachedStore = new CachedStore();
    CachedStore.clearSharedCache();
    cachedStore.setConfForTest(conf);
    sharedCache = CachedStore.getSharedCache();

    // Stop the CachedStore cache update service. We'll start it explicitly to control the test
    CachedStore.stopCacheUpdateService(1);

    // Create the 'hive' catalog with new warehouse directory
    HiveMetaStore.HMSHandler.createDefaultCatalog(rawStore, new Warehouse(conf));
  }

  private Database createTestDb(String dbName, String dbOwner) {
    String dbDescription = dbName;
    String dbLocation = "file:/tmp";
    Map<String, String> dbParams = new HashMap<>();
    Database db = new Database(dbName, dbDescription, dbLocation, dbParams);
    db.setOwnerName(dbOwner);
    db.setOwnerType(PrincipalType.USER);
    db.setCatalogName(DEFAULT_CATALOG_NAME);
    return db;
  }

  private Table createTestTblParam(String dbName, String tblName, String tblOwner,
                              List<FieldSchema> cols, List<FieldSchema> ptnCols, Map<String, String> tblParams) {
    String serdeLocation = "file:/tmp";
    Map<String, String> serdeParams = new HashMap<>();
    SerDeInfo serdeInfo = new SerDeInfo("serde", "seriallib", new HashMap<>());
    StorageDescriptor sd = new StorageDescriptor(cols, serdeLocation,
            null, null, false, 3,
            serdeInfo, null, null, serdeParams);
    sd.setInputFormat(OrcInputFormat.class.getName());
    sd.setOutputFormat(OrcOutputFormat.class.getName());
    sd.setStoredAsSubDirectories(false);
    Table tbl = new Table(tblName, dbName, tblOwner, 0, 0, 0, sd, ptnCols, tblParams,
            null, null,
            TableType.MANAGED_TABLE.toString());
    tbl.setCatName(DEFAULT_CATALOG_NAME);
    return tbl;
  }

  private Table createTestTbl(String dbName, String tblName, String tblOwner,
                              List<FieldSchema> cols, List<FieldSchema> ptnCols) {
    return createTestTblParam(dbName, tblName, tblOwner, cols, ptnCols, new HashMap<>());
  }

  private void compareTables(Table tbl1, Table tbl2) {
    Assert.assertEquals(tbl1.getDbName(), tbl2.getDbName());
    Assert.assertEquals(tbl1.getSd(), tbl2.getSd());
    Assert.assertEquals(tbl1.getParameters(), tbl2.getParameters());
    Assert.assertEquals(tbl1.getTableName(), tbl2.getTableName());
    Assert.assertEquals(tbl1.getCatName(), tbl2.getCatName());
    Assert.assertEquals(tbl1.getCreateTime(), tbl2.getCreateTime());
    Assert.assertEquals(tbl1.getCreationMetadata(), tbl2.getCreationMetadata());
    Assert.assertEquals(tbl1.getId(), tbl2.getId());
  }

  private void comparePartitions(Partition part1, Partition part2) {
    Assert.assertEquals(part1.getParameters(), part2.getParameters());
    Assert.assertEquals(part1.getCatName(), part2.getCatName());
    Assert.assertEquals(part1.getCreateTime(), part2.getCreateTime());
    Assert.assertEquals(part1.getTableName(), part2.getTableName());
    Assert.assertEquals(part1.getDbName().toLowerCase(), part2.getDbName().toLowerCase());
    Assert.assertEquals(part1.getLastAccessTime(), part2.getLastAccessTime());
  }

  @Test
  public void testDatabaseOpsForUpdateUsingEvents() throws Exception {
    RawStore rawStore = hmsHandler.getMS();

    // Prewarm CachedStore
    CachedStore.setCachePrewarmedState(false);
    CachedStore.prewarm(rawStore);

    // Add a db via rawStore
    String dbName = "testDatabaseOps";
    String dbOwner = "user1";
    Database db = createTestDb(dbName, dbOwner);

    hmsHandler.create_database(db);
    db = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName);

    // Read database via CachedStore
    Database dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName);
    Assert.assertEquals(db, dbRead);

    // Add another db via rawStore
    final String dbName1 = "testDatabaseOps1";
    Database db1 = createTestDb(dbName1, dbOwner);
    hmsHandler.create_database(db1);
    db1 = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName1);

    // Read database via CachedStore
    dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName1);
    Assert.assertEquals(db1, dbRead);

    // Alter the db via rawStore (can only alter owner or parameters)
    dbOwner = "user2";
    Database newdb = new Database(db);
    newdb.setOwnerName(dbOwner);
    hmsHandler.alter_database(dbName, newdb);
    newdb = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName);

    // Read db via cachedStore
    dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName);
    Assert.assertEquals(newdb, dbRead);

    // Add another db via rawStore
    final String dbName2 = "testDatabaseOps2";
    Database db2 = createTestDb(dbName2, dbOwner);
    hmsHandler.create_database(db2);
    db2 = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName2);

    // Alter db "testDatabaseOps" via rawStore
    dbOwner = "user1";
    newdb = new Database(db);
    newdb.setOwnerName(dbOwner);
    hmsHandler.alter_database(dbName, newdb);
    newdb = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName);

    // Drop db "testDatabaseOps1" via rawStore
    Database dropDb = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName1);
    hmsHandler.drop_database(dbName1, true, true);

    // Read the newly added db via CachedStore
    dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName2);
    Assert.assertEquals(db2, dbRead);

    // Read the altered db via CachedStore (altered user from "user2" to "user1")
    dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName);
    Assert.assertEquals(newdb, dbRead);

    // Try to read the dropped db after cache update
    dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName1);
    Assert.assertEquals(null, dbRead);

    // Clean up
    hmsHandler.drop_database(dbName, true, true);
    hmsHandler.drop_database(dbName2, true, true);
    sharedCache.getDatabaseCache().clear();
    sharedCache.clearTableCache();
    sharedCache.getSdCache().clear();
  }

  @Test
  public void testTableOpsForUpdateUsingEvents() throws Exception {
    long lastEventId = -1;
    RawStore rawStore = hmsHandler.getMS();

    // Prewarm CachedStore
    CachedStore.setCachePrewarmedState(false);
    CachedStore.prewarm(rawStore);

    // Add a db via rawStore
    String dbName = "test_table_ops";
    String dbOwner = "user1";
    Database db = createTestDb(dbName, dbOwner);
    hmsHandler.create_database(db);
    db = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName);

    // Add a table via rawStore
    String tblName = "tbl";
    String tblOwner = "user1";
    FieldSchema col1 = new FieldSchema("col1", "int", "integer column");
    FieldSchema col2 = new FieldSchema("col2", "string", "string column");
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(col1);
    cols.add(col2);
    List<FieldSchema> ptnCols = new ArrayList<FieldSchema>();
    Table tbl = createTestTbl(dbName, tblName, tblOwner, cols, ptnCols);
    hmsHandler.create_table(tbl);
    tbl = rawStore.getTable(DEFAULT_CATALOG_NAME, dbName, tblName);

    // Read database, table via CachedStore
    Database dbRead= sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName);
    Assert.assertEquals(db, dbRead);
    Table tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName);
    compareTables(tblRead, tbl);

    // Add a new table via rawStore
    String tblName2 = "tbl2";
    Table tbl2 = createTestTbl(dbName, tblName2, tblOwner, cols, ptnCols);
    hmsHandler.create_table(tbl2);
    tbl2 = rawStore.getTable(DEFAULT_CATALOG_NAME, dbName, tblName2);

    // Alter table "tbl" via rawStore
    tblOwner = "role1";
    Table newTable = new Table(tbl);
    newTable.setOwner(tblOwner);
    newTable.setOwnerType(PrincipalType.ROLE);
    hmsHandler.alter_table(dbName, tblName, newTable);
    newTable = rawStore.getTable(DEFAULT_CATALOG_NAME, dbName, tblName);

    Assert.assertEquals("Owner of the table did not change.", tblOwner, newTable.getOwner());
    Assert.assertEquals("Owner type of the table did not change", PrincipalType.ROLE, newTable.getOwnerType());

    // Drop table "tbl2" via rawStore
    hmsHandler.drop_table(dbName, tblName2, true);

    // Read the altered "tbl" via CachedStore
    tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName);
    compareTables(tblRead, newTable);

    // Try to read the dropped "tbl2" via CachedStore (should throw exception)
    tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName2);
    Assert.assertNull(tblRead);

    // Clean up
    hmsHandler.drop_database(dbName, true, true);

    tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName2);
    Assert.assertNull(tblRead);

    tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName);
    Assert.assertNull(tblRead);

    sharedCache.getDatabaseCache().clear();
    sharedCache.clearTableCache();
    sharedCache.getSdCache().clear();
  }

  @Test
  public void testConstraintsForUpdateUsingEvents() throws Exception {
    long lastEventId = -1;
    RawStore rawStore = hmsHandler.getMS();

    // Prewarm CachedStore
    CachedStore.setCachePrewarmedState(false);
    CachedStore.prewarm(rawStore);

    // Add a db via rawStore
    String dbName = "test_table_ops";
    String dbOwner = "user1";
    Database db = createTestDb(dbName, dbOwner);
    hmsHandler.create_database(db);
    db = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName);

    String foreignDbName = "test_table_ops_foreign";
    Database foreignDb = createTestDb(foreignDbName, dbOwner);
    hmsHandler.create_database(foreignDb);
    foreignDb = rawStore.getDatabase(DEFAULT_CATALOG_NAME, foreignDbName);
    // Add a table via rawStore
    String tblName = "tbl";
    String tblOwner = "user1";
    FieldSchema col1 = new FieldSchema("col1", "int", "integer column");
    FieldSchema col2 = new FieldSchema("col2", "string", "string column");
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(col1);
    cols.add(col2);
    List<FieldSchema> ptnCols = new ArrayList<FieldSchema>();
    Table tbl = createTestTbl(dbName, tblName, tblOwner, cols, ptnCols);
    String foreignTblName = "ftbl";
    Table foreignTbl = createTestTbl(foreignDbName, foreignTblName, tblOwner, cols, ptnCols);

    SQLPrimaryKey key = new SQLPrimaryKey(dbName, tblName, col1.getName(), 1, "pk1",
            false, false, false);
    SQLUniqueConstraint uC = new SQLUniqueConstraint(DEFAULT_CATALOG_NAME, dbName, tblName,
            col1.getName(), 2, "uc1", false, false, false);
    SQLNotNullConstraint nN = new SQLNotNullConstraint(DEFAULT_CATALOG_NAME, dbName, tblName,
            col1.getName(), "nn1", false, false, false);
    SQLForeignKey foreignKey = new SQLForeignKey(key.getTable_db(), key.getTable_name(), key.getColumn_name(),
            foreignDbName, foreignTblName, key.getColumn_name(), 2, 1,2,
            "fk1", key.getPk_name(), false, false, false);

    hmsHandler.create_table_with_constraints(tbl,
            Arrays.asList(key), null, Arrays.asList(uC), Arrays.asList(nN), null, null);
    hmsHandler.create_table_with_constraints(foreignTbl, null, Arrays.asList(foreignKey),
            null, null, null, null);

    tbl = rawStore.getTable(DEFAULT_CATALOG_NAME, dbName, tblName);
    foreignTbl = rawStore.getTable(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName);

    // Read database, table via CachedStore
    Database dbRead= sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME, dbName);
    Assert.assertEquals(db, dbRead);
    Table tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName);
    compareTables(tblRead, tbl);

    Table foreignTblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName);
    compareTables(foreignTblRead, foreignTbl);

    List<SQLPrimaryKey> keys = rawStore.getPrimaryKeys(DEFAULT_CATALOG_NAME, dbName, tblName);
    List<SQLPrimaryKey> keysRead = sharedCache.listCachedPrimaryKeys(DEFAULT_CATALOG_NAME, dbName, tblName);
    assertsForPrimarkaryKey(keysRead, 1, 0, keys.get(0));

    List<SQLNotNullConstraint> nNs = rawStore.getNotNullConstraints(DEFAULT_CATALOG_NAME, dbName, tblName);
    List<SQLNotNullConstraint> nNsRead = sharedCache.listCachedNotNullConstraints(DEFAULT_CATALOG_NAME, dbName, tblName);
    assertsForNotNullConstraints(nNsRead, 1, 0, nNs.get(0));

    List<SQLUniqueConstraint> uns = rawStore.getUniqueConstraints(DEFAULT_CATALOG_NAME, dbName, tblName);
    List<SQLUniqueConstraint> unsRead = sharedCache.listCachedUniqueConstraint(DEFAULT_CATALOG_NAME, dbName, tblName);
    assertsForUniqueConstraints(unsRead, 1, 0, uns.get(0));

    List<SQLForeignKey> fks = rawStore.getForeignKeys(DEFAULT_CATALOG_NAME, dbName, tblName, foreignDbName, foreignTblName);
    List<SQLForeignKey> fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName,
            foreignTblName, dbName, tblName);
    assertsForForeignKey(fksRead, 1, 0, fks.get(0));

    fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName,
            dbName, foreignTblName);
    Assert.assertEquals(fksRead.size(), 0);
    fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName,
            foreignDbName, tblName);
    Assert.assertEquals(fksRead.size(), 0);
    fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName,
            foreignDbName, foreignTblName);
    Assert.assertEquals(fksRead.size(), 0);

    fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName,
            null, null);
    Assert.assertEquals(fksRead.size(), 1);

    // Dropping the constraint
    DropConstraintRequest dropConstraintRequest = new DropConstraintRequest(foreignDbName, foreignTblName, foreignKey.getFk_name());
    hmsHandler.drop_constraint(dropConstraintRequest);
    dropConstraintRequest = new DropConstraintRequest(dbName, tblName, key.getPk_name());
    hmsHandler.drop_constraint(dropConstraintRequest);
    dropConstraintRequest = new DropConstraintRequest(dbName, tblName, nN.getNn_name());
    hmsHandler.drop_constraint(dropConstraintRequest);
    dropConstraintRequest = new DropConstraintRequest(dbName, tblName, uC.getUk_name());
    hmsHandler.drop_constraint(dropConstraintRequest);

    keys = sharedCache.listCachedPrimaryKeys(DEFAULT_CATALOG_NAME, dbName, tblName);
    nNs = sharedCache.listCachedNotNullConstraints(DEFAULT_CATALOG_NAME, dbName, tblName);
    uns = sharedCache.listCachedUniqueConstraint(DEFAULT_CATALOG_NAME, dbName, tblName);
    fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName, dbName, tblName);
    Assert.assertEquals(keys.size(), 0);
    Assert.assertEquals(nNs.size(), 0);
    Assert.assertEquals(uns.size(), 0);
    Assert.assertEquals(fksRead.size(), 0);

    // Adding keys back
    AddPrimaryKeyRequest req = new AddPrimaryKeyRequest(Arrays.asList(key));
    hmsHandler.add_primary_key(req);
    keys = sharedCache.listCachedPrimaryKeys(DEFAULT_CATALOG_NAME, dbName, tblName);
    assertsForPrimarkaryKey(keys, 1, 0, key);

    AddUniqueConstraintRequest uniqueConstraintRequest = new AddUniqueConstraintRequest(Arrays.asList(uC));
    hmsHandler.add_unique_constraint(uniqueConstraintRequest);
    uns = sharedCache.listCachedUniqueConstraint(DEFAULT_CATALOG_NAME, dbName, tblName);
    assertsForUniqueConstraints(uns, 1, 0, uC);

    AddNotNullConstraintRequest notNullConstraintRequest = new AddNotNullConstraintRequest(Arrays.asList(nN));
    hmsHandler.add_not_null_constraint(notNullConstraintRequest);
    nNs = sharedCache.listCachedNotNullConstraints(DEFAULT_CATALOG_NAME, dbName, tblName);
    assertsForNotNullConstraints(nNs, 1, 0, nN);

    AddForeignKeyRequest foreignKeyRequest = new AddForeignKeyRequest(Arrays.asList(foreignKey));
    hmsHandler.add_foreign_key(foreignKeyRequest);
    fksRead = sharedCache.listCachedForeignKeys(DEFAULT_CATALOG_NAME, foreignDbName, foreignTblName, dbName, tblName);
    assertsForForeignKey(fksRead, 1, 0, foreignKey);

    sharedCache.getDatabaseCache().clear();
    sharedCache.clearTableCache();
    sharedCache.getSdCache().clear();
  }

  private void assertsForPrimarkaryKey(List<SQLPrimaryKey> keys, int size, int ele, SQLPrimaryKey key) {
    Assert.assertEquals(keys.size(), size);
    Assert.assertEquals(keys.get(ele).getPk_name(), key.getPk_name());
    Assert.assertEquals(keys.get(ele).getColumn_name(), key.getColumn_name());
    Assert.assertEquals(keys.get(ele).getTable_name(), key.getTable_name());
    Assert.assertEquals(keys.get(ele).getTable_db(), key.getTable_db());
  }

  private void assertsForForeignKey(List<SQLForeignKey> keys, int size, int ele, SQLForeignKey key) {
    Assert.assertEquals(keys.size(), size);
    Assert.assertEquals(keys.get(ele).getPk_name(), key.getPk_name());
    Assert.assertEquals(keys.get(ele).getFk_name(), key.getFk_name());
    Assert.assertEquals(keys.get(ele).getFktable_db(), key.getFktable_db());
    Assert.assertEquals(keys.get(ele).getFktable_name(), key.getFktable_name());
    Assert.assertEquals(keys.get(ele).getPktable_db(), key.getPktable_db());
    Assert.assertEquals(keys.get(ele).getPktable_name(), key.getPktable_name());
    Assert.assertEquals(keys.get(ele).getPkcolumn_name(), key.getPkcolumn_name());
    Assert.assertEquals(keys.get(ele).getFkcolumn_name(), key.getFkcolumn_name());
  }

  private void assertsForNotNullConstraints(List<SQLNotNullConstraint> nns, int size, int ele, SQLNotNullConstraint nN) {
    Assert.assertEquals(nns.size(), size);
    Assert.assertEquals(nns.get(ele).getNn_name(), nN.getNn_name());
    Assert.assertEquals(nns.get(ele).getColumn_name(), nN.getColumn_name());
    Assert.assertEquals(nns.get(ele).getTable_name(), nN.getTable_name());
    Assert.assertEquals(nns.get(ele).getTable_db(), nN.getTable_db());
  }

  private void assertsForUniqueConstraints(List<SQLUniqueConstraint> nns, int size, int ele, SQLUniqueConstraint nN) {
    Assert.assertEquals(nns.size(), size);
    Assert.assertEquals(nns.get(ele).getUk_name(), nN.getUk_name());
    Assert.assertEquals(nns.get(ele).getColumn_name(), nN.getColumn_name());
    Assert.assertEquals(nns.get(ele).getTable_name(), nN.getTable_name());
    Assert.assertEquals(nns.get(ele).getTable_db(), nN.getTable_db());
  }

  @Test
  public void testPartitionOpsForUpdateUsingEvents() throws Exception {
    long lastEventId = -1;
    RawStore rawStore = hmsHandler.getMS();

    // Prewarm CachedStore
    CachedStore.setCachePrewarmedState(false);
    CachedStore.prewarm(rawStore);

    // Add a db via rawStore
    String dbName = "test_partition_ops";
    String dbOwner = "user1";
    Database db = createTestDb(dbName, dbOwner);
    hmsHandler.create_database(db);
    db = rawStore.getDatabase(DEFAULT_CATALOG_NAME, dbName);

    // Add a table via rawStore
    String tblName = "tbl";
    String tblOwner = "user1";
    FieldSchema col1 = new FieldSchema("col1", "int", "integer column");
    FieldSchema col2 = new FieldSchema("col2", "string", "string column");
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(col1);
    cols.add(col2);
    FieldSchema ptnCol1 = new FieldSchema("part1", "string", "string partition column");
    List<FieldSchema> ptnCols = new ArrayList<FieldSchema>();
    ptnCols.add(ptnCol1);
    Table tbl = createTestTbl(dbName, tblName, tblOwner, cols, ptnCols);
    hmsHandler.create_table(tbl);
    tbl = rawStore.getTable(DEFAULT_CATALOG_NAME, dbName, tblName);

    final String ptnColVal1 = "aaa";
    Map<String, String> partParams = new HashMap<String, String>();
    Partition ptn1 =
            new Partition(Arrays.asList(ptnColVal1), dbName, tblName, 0,
                    0, tbl.getSd(), partParams);
    ptn1.setCatName(DEFAULT_CATALOG_NAME);
    hmsHandler.add_partition(ptn1);
    ptn1 = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal1));

    final String ptnColVal2 = "bbb";
    Partition ptn2 =
            new Partition(Arrays.asList(ptnColVal2), dbName, tblName, 0,
                    0, tbl.getSd(), partParams);
    ptn2.setCatName(DEFAULT_CATALOG_NAME);
    hmsHandler.add_partition(ptn2);
    ptn2 = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal2));

    // Read database, table, partition via CachedStore
    Database dbRead = sharedCache.getDatabaseFromCache(DEFAULT_CATALOG_NAME.toLowerCase(), dbName.toLowerCase());
    Assert.assertEquals(db, dbRead);
    Table tblRead = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME.toLowerCase(),
            dbName.toLowerCase(), tblName.toLowerCase());
    compareTables(tbl, tblRead);
    Partition ptn1Read = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME.toLowerCase(),
            dbName.toLowerCase(), tblName.toLowerCase(), Arrays.asList(ptnColVal1));
    comparePartitions(ptn1, ptn1Read);
    Partition ptn2Read = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME.toLowerCase(),
            dbName.toLowerCase(), tblName.toLowerCase(), Arrays.asList(ptnColVal2));
    comparePartitions(ptn2, ptn2Read);

    // Add a new partition via rawStore
    final String ptnColVal3 = "ccc";
    Partition ptn3 =
            new Partition(Arrays.asList(ptnColVal3), dbName, tblName, 0,
                    0, tbl.getSd(), partParams);
    ptn3.setCatName(DEFAULT_CATALOG_NAME);
    hmsHandler.add_partition(ptn3);
    ptn3 = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal3));

    // Alter an existing partition ("aaa") via rawStore
    ptn1 = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal1));
    final String ptnColVal1Alt = "aaa";
    Partition ptn1Atl =
            new Partition(Arrays.asList(ptnColVal1Alt), dbName, tblName, 0,
                    0, tbl.getSd(), partParams);
    ptn1Atl.setCatName(DEFAULT_CATALOG_NAME);
    hmsHandler.alter_partitions(dbName, tblName, Arrays.asList(ptn1Atl));
    ptn1Atl = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal1Alt));

    // Drop an existing partition ("bbb") via rawStore
    Partition ptnDrop = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal2));
    hmsHandler.drop_partition(dbName, tblName, Arrays.asList(ptnColVal2), false);

    // Read the newly added partition via CachedStore
    Partition ptnRead = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME, dbName,
            tblName, Arrays.asList(ptnColVal3));
    comparePartitions(ptn3, ptnRead);

    // Read the altered partition via CachedStore
    ptnRead = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal1Alt));
    Assert.assertEquals(ptn1Atl.getParameters(), ptnRead.getParameters());

    ptnRead = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal2));
    Assert.assertEquals(null, ptnRead);

    // Drop table "tbl" via rawStore, it should remove the partition also
    hmsHandler.drop_table(dbName, tblName, true);

    ptnRead = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal1Alt));
    Assert.assertEquals(null, ptnRead);

    ptnRead = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME, dbName, tblName, Arrays.asList(ptnColVal3));
    Assert.assertEquals(null, ptnRead);

    // Clean up
    rawStore.dropDatabase(DEFAULT_CATALOG_NAME, dbName);
    sharedCache.getDatabaseCache().clear();
    sharedCache.clearTableCache();
    sharedCache.getSdCache().clear();
  }

  private void updateTableColStats(String dbName, String tblName, String[] colName,
                                   double highValue, double avgColLen, boolean isTxnTable) throws Throwable {
    long writeId = -1;
    String validWriteIds = null;
    if (isTxnTable) {
      writeId = allocateWriteIds(allocateTxns(1), dbName, tblName).get(0).getWriteId();
      validWriteIds = getValidWriteIds(dbName, tblName);
    }

    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setIsTblLevel(true);
    statsDesc.setPartName(null);

    ColumnStatistics colStats = new ColumnStatistics();
    colStats.setStatsDesc(statsDesc);
    colStats.setStatsObj(getStatsObjects(dbName, tblName, colName, highValue, avgColLen));
    colStats.setEngine(Constants.HIVE_ENGINE);

    SetPartitionsStatsRequest setTblColStat = new SetPartitionsStatsRequest(Collections.singletonList(colStats), Constants.HIVE_ENGINE);
    setTblColStat.setWriteId(writeId);
    setTblColStat.setValidWriteIdList(validWriteIds);

    // write stats objs persistently
    hmsHandler.update_table_column_statistics_req(setTblColStat);
    validateTablePara(dbName, tblName);

    ColumnStatistics colStatsCache = sharedCache.getTableColStatsFromCache(DEFAULT_CATALOG_NAME,
            dbName, tblName, Lists.newArrayList(colName[0]), validWriteIds, true);
    Assert.assertEquals(colStatsCache.getStatsObj().get(0).getColName(), colName[0]);
    verifyStatDouble(colStatsCache.getStatsObj().get(0), colName[0], highValue);

    colStatsCache = sharedCache.getTableColStatsFromCache(DEFAULT_CATALOG_NAME,
            dbName, tblName, Lists.newArrayList(colName[1]), validWriteIds, true);
    Assert.assertEquals(colStatsCache.getStatsObj().get(0).getColName(), colName[1]);
    verifyStatString(colStatsCache.getStatsObj().get(0), colName[1], avgColLen);
  }

  private void updatePartColStats(String dbName, String tblName, boolean isTxnTable, String[] colName,
                                  String partName, double highValue, double avgColLen) throws Throwable {
    long writeId = -1;
    String validWriteIds = null;
    List<Long> txnIds = null;

    if (isTxnTable) {
      txnIds = allocateTxns(1);
      writeId = allocateWriteIds(txnIds, dbName, tblName).get(0).getWriteId();
      validWriteIds = getValidWriteIds(dbName, tblName);
    }

    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setIsTblLevel(false);
    statsDesc.setPartName(partName);

    ColumnStatistics colStats = new ColumnStatistics();
    colStats.setStatsDesc(statsDesc);
    colStats.setStatsObj(getStatsObjects(dbName, tblName, colName, highValue, avgColLen));
    colStats.setEngine(Constants.HIVE_ENGINE);

    SetPartitionsStatsRequest setTblColStat = new SetPartitionsStatsRequest(Collections.singletonList(colStats), Constants.HIVE_ENGINE);
    setTblColStat.setWriteId(writeId);
    setTblColStat.setValidWriteIdList(validWriteIds);

    // write stats objs persistently
    hmsHandler.update_partition_column_statistics_req(setTblColStat);

    if (isTxnTable) {
      CommitTxnRequest rqst = new CommitTxnRequest(txnIds.get(0));
      hmsHandler.commit_txn(rqst);
      writeId = allocateWriteIds(allocateTxns(1), dbName, tblName).get(0).getWriteId();
      validWriteIds = getValidWriteIds(dbName, tblName);
    }

    Deadline.startTimer("getPartitionColumnStatistics");
    List<ColumnStatistics> statRowStore = rawStore.getPartitionColumnStatistics(DEFAULT_CATALOG_NAME, dbName, tblName,
            Collections.singletonList(partName), Collections.singletonList(colName[1]), Constants.HIVE_ENGINE, validWriteIds);
    Deadline.stopTimer();
    verifyStatString(statRowStore.get(0).getStatsObj().get(0), colName[1], avgColLen);
    if (isTxnTable) {
      Assert.assertEquals(statRowStore.get(0).isIsStatsCompliant(), true);
    } else {
      Assert.assertEquals(statRowStore.get(0).isIsStatsCompliant(), false);
    }

    List<ColumnStatistics> statSharedCache = sharedCache.getPartitionColStatsListFromCache(DEFAULT_CATALOG_NAME,
            dbName, tblName, Collections.singletonList(partName), Collections.singletonList(colName[1]),
            validWriteIds, true);
    verifyStatString(statSharedCache.get(0).getStatsObj().get(0), colName[1], avgColLen);
    if (isTxnTable) {
      Assert.assertEquals(statSharedCache.get(0).isIsStatsCompliant(), true);
    } else {
      Assert.assertEquals(statSharedCache.get(0).isIsStatsCompliant(), false);
    }

    SharedCache.ColumStatsWithWriteId statPartCache = sharedCache.getPartitionColStatsFromCache(DEFAULT_CATALOG_NAME,
            dbName, tblName, CachedStore.partNameToVals(partName), colName[0], validWriteIds);
    verifyStatDouble(statPartCache.getColumnStatisticsObj(), colName[0], highValue);

    statPartCache = sharedCache.getPartitionColStatsFromCache(DEFAULT_CATALOG_NAME, dbName, tblName,
            CachedStore.partNameToVals(partName), colName[1], validWriteIds);
    verifyStatString(statPartCache.getColumnStatisticsObj(), colName[1], avgColLen);
  }

  private List<ColumnStatisticsObj> getStatsObjects(String dbName, String tblName, String[] colName,
                                                    double highValue, double avgColLen) throws Throwable {
    double lowValue = 50000.21;
    long numNulls = 3;
    long numDVs = 22;
    long maxColLen = 102;
    boolean isTblLevel = true;
    String partName = null;
    List<ColumnStatisticsObj> statsObjs = new ArrayList<>();

    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setIsTblLevel(isTblLevel);
    statsDesc.setPartName(partName);

    ColumnStatisticsObj statsObj = new ColumnStatisticsObj();
    statsObj.setColName(colName[0]);
    statsObj.setColType(colType[0]);

    ColumnStatisticsData statsData = new ColumnStatisticsData();
    DoubleColumnStatsData numericStats = new DoubleColumnStatsData();
    statsData.setDoubleStats(numericStats);

    statsData.getDoubleStats().setHighValue(highValue);
    statsData.getDoubleStats().setLowValue(lowValue);
    statsData.getDoubleStats().setNumDVs(numDVs);
    statsData.getDoubleStats().setNumNulls(numNulls);

    statsObj.setStatsData(statsData);
    statsObjs.add(statsObj);

    statsObj = new ColumnStatisticsObj();
    statsObj.setColName(colName[1]);
    statsObj.setColType(colType[1]);

    statsData = new ColumnStatisticsData();
    StringColumnStatsData stringStats = new StringColumnStatsData();
    statsData.setStringStats(stringStats);
    statsData.getStringStats().setAvgColLen(avgColLen);
    statsData.getStringStats().setMaxColLen(maxColLen);
    statsData.getStringStats().setNumDVs(numDVs);
    statsData.getStringStats().setNumNulls(numNulls);

    statsObj.setStatsData(statsData);
    statsObjs.add(statsObj);
    return statsObjs;
  }

  private void verifyStatDouble(ColumnStatisticsObj colStats, String colName, double highValue) {
    double lowValue = 50000.21;
    long numNulls = 3;
    long numDVs = 22;
    Assert.assertEquals(colStats.getColName(), colName);
    Assert.assertEquals(colStats.getStatsData().getDoubleStats().getLowValue(), lowValue, 0.01);
    Assert.assertEquals(colStats.getStatsData().getDoubleStats().getHighValue(), highValue, 0.01);
    Assert.assertEquals(colStats.getStatsData().getDoubleStats().getNumNulls(), numNulls);
    Assert.assertEquals(colStats.getStatsData().getDoubleStats().getNumDVs(), numDVs);
  }

  private void verifyStatString(ColumnStatisticsObj colStats, String colName, double avgColLen) {
    long numNulls = 3;
    long numDVs = 22;
    long maxColLen = 102;
    Assert.assertEquals(colStats.getColName(), colName);
    Assert.assertEquals(colStats.getStatsData().getStringStats().getMaxColLen(), maxColLen);
    Assert.assertEquals(colStats.getStatsData().getStringStats().getAvgColLen(), avgColLen, 0.01);
    Assert.assertEquals(colStats.getStatsData().getStringStats().getNumNulls(), numNulls);
    Assert.assertEquals(colStats.getStatsData().getStringStats().getNumDVs(), numDVs);
  }

  private void verifyStat(List<ColumnStatisticsObj> colStats, String[] colName, double highValue, double avgColLen) {
    //verifyStatDouble(colStats.get(0), colName[0], highValue);
    verifyStatString(colStats.get(0), colName[1], avgColLen);
  }

  private void setUpBeforeTest(String dbName, String tblName, String[] colName, boolean isTxnTable) throws Throwable {
    String dbOwner = "user1";

    // Prewarm CachedStore
    CachedStore.setCachePrewarmedState(false);
    CachedStore.prewarm(rawStore);

    // Add a db via rawStore
    Database db = createTestDb(dbName, dbOwner);
    hmsHandler.create_database(db);
    if (tblName != null) {
      createTestTable(dbName, tblName, colName, isTxnTable);
    }
  }

  private void createTestTable(String dbName, String tblName, String[] colName, boolean isTxnTable) throws Throwable {
    // Add a table via rawStore
    List<FieldSchema> cols = new ArrayList<FieldSchema>();
    cols.add(new FieldSchema(colName[0], "int", "integer column"));
    cols.add(new FieldSchema(colName[1], "string", "string column"));

    Map<String, String> tableParams =  new HashMap<>();
    tableParams.put("test_param_1", "hi");
    tableParams.put("test_param_2", "50");
    if (isTxnTable) {
      tableParams.put(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL, "true");
    }

    String tblOwner = "testowner";

    List<FieldSchema> ptnCols = new ArrayList<FieldSchema>();
    ptnCols.add(new FieldSchema("ds", "string", "string partition column"));
    ptnCols.add(new FieldSchema("hr", "int", "integer partition column"));

    Table tbl = createTestTblParam(dbName, tblName, tblOwner, cols, null, tableParams);
    hmsHandler.create_table(tbl);
  }

  private void createTableWithPart(String dbName, String tblName, String[] colName, boolean isTxnTbl) throws Throwable {
    List<FieldSchema> cols = new ArrayList<>();
    cols.add(new FieldSchema(colName[0], colType[0], null));
    List<FieldSchema> partCols = new ArrayList<>();
    partCols.add(new FieldSchema(colName[0], colType[0], null));
    Map<String, String> tableParams =  new HashMap<>();
    tableParams.put("test_param_1", "hi");
    tableParams.put("test_param_2", "50");
    if (isTxnTbl) {
      tableParams.put(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL, "true");
      StatsSetupConst.setBasicStatsState(tableParams, StatsSetupConst.TRUE);
    }
    StorageDescriptor sd =
            new StorageDescriptor(cols, null, "orc",
                    "orc", false,
                    0, new SerDeInfo("serde", "seriallib", new HashMap<>()),
                    null, null, tableParams);
    sd.setInputFormat(OrcInputFormat.class.getName());
    sd.setOutputFormat(OrcOutputFormat.class.getName());

    Table tbl = new Table(tblName, dbName, null, 0, 0, 0, sd,
            partCols, tableParams, null, null, TableType.MANAGED_TABLE.toString());
    tbl.setCatName(DEFAULT_CATALOG_NAME);

    hmsHandler.create_table(tbl);

    List<String> partVals1 = new ArrayList<>();
    partVals1.add("1");
    List<String> partVals2 = new ArrayList<>();
    partVals2.add("2");
    Map<String, String> partParams =  new HashMap<>();
    StatsSetupConst.setBasicStatsState(partParams, StatsSetupConst.TRUE);
    EnvironmentContext environmentContext = new EnvironmentContext();
    environmentContext.putToProperties(StatsSetupConst.STATS_GENERATED, StatsSetupConst.TASK);

    Partition ptn1 =
            new Partition(partVals1, dbName, tblName, 0, 0, sd, partParams);
    ptn1.setCatName(DEFAULT_CATALOG_NAME);
    hmsHandler.add_partition_with_environment_context(ptn1, environmentContext);
    Partition ptn2 =
            new Partition(partVals2, dbName, tblName, 0, 0, sd, partParams);
    ptn2.setCatName(DEFAULT_CATALOG_NAME);
    hmsHandler.add_partition_with_environment_context(ptn2, environmentContext);
  }

  private List<Long> allocateTxns(int numTxns) throws Throwable {
    OpenTxnRequest openTxnRequest = new OpenTxnRequest(1, "user", "host");
    return hmsHandler.open_txns(openTxnRequest).getTxn_ids();
  }

  private List<TxnToWriteId> allocateWriteIds(List<Long> txnIds, String dbName, String tblName) throws Throwable {
    AllocateTableWriteIdsRequest allocateTableWriteIdsRequest = new AllocateTableWriteIdsRequest(dbName, tblName);
    allocateTableWriteIdsRequest.setTxnIds(txnIds);
    return hmsHandler.allocate_table_write_ids(allocateTableWriteIdsRequest).getTxnToWriteIds();
  }

  private String getValidWriteIds(String dbName, String tblName) throws Throwable {
    GetValidWriteIdsRequest validWriteIdsRequest = new GetValidWriteIdsRequest(
            Collections.singletonList(TableName.getDbTable(dbName, tblName)));
    GetValidWriteIdsResponse validWriteIdsResponse = hmsHandler.get_valid_write_ids(validWriteIdsRequest);
    return TxnCommonUtils.createValidReaderWriteIdList(validWriteIdsResponse.
            getTblValidWriteIds().get(0)).writeToString();
  }

  private void validateTablePara(String dbName, String tblName) throws Throwable {
    Table tblRead = rawStore.getTable(DEFAULT_CATALOG_NAME, dbName, tblName);
    Table tblRead1 = sharedCache.getTableFromCache(DEFAULT_CATALOG_NAME, dbName, tblName);
    Assert.assertEquals(tblRead.getParameters(), tblRead1.getParameters());
  }

  private void validatePartPara(String dbName, String tblName, String partName) throws Throwable {
    //Partition part1 = rawStore.getPartition(DEFAULT_CATALOG_NAME, dbName, tblName, partName);
    //Partition part2 = sharedCache.getPartitionFromCache(DEFAULT_CATALOG_NAME, dbName, tblName, partName);
    //Assert.assertEquals(part1.getParameters(), part2.getParameters());
  }

  private void deleteColStats(String dbName, String tblName, String[] colName) throws Throwable {
    boolean status = hmsHandler.delete_table_column_statistics(dbName, tblName, null, Constants.HIVE_ENGINE);
    Assert.assertEquals(status, true);
    Assert.assertEquals(sharedCache.getTableColStatsFromCache(DEFAULT_CATALOG_NAME, dbName, tblName,
            Lists.newArrayList(colName[0]),  null, true).getStatsObj().isEmpty(), true);
    Assert.assertEquals(sharedCache.getTableColStatsFromCache(DEFAULT_CATALOG_NAME, dbName, tblName,
            Lists.newArrayList(colName[1]), null, true).getStatsObj().isEmpty(), true);
    validateTablePara(dbName, tblName);
  }

  private void deletePartColStats(String dbName, String tblName, String[] colName,
                                  String partName) throws Throwable {
    boolean status = hmsHandler.delete_partition_column_statistics(dbName, tblName, partName, colName[1], Constants.HIVE_ENGINE);
    Assert.assertEquals(status, true);

    SharedCache.ColumStatsWithWriteId colStats = sharedCache.getPartitionColStatsFromCache(DEFAULT_CATALOG_NAME, dbName,
            tblName, CachedStore.partNameToVals(partName), colName[1], null);
    Assert.assertEquals(colStats.getColumnStatisticsObj(), null);
    validateTablePara(dbName, tblName);
  }

  private void testTableColStatInternal(String dbName, String tblName, boolean isTxnTable) throws Throwable {
    String[] colName = new String[]{"income", "name"};
    double highValue = 1200000.4525;
    double avgColLen = 50.30;

    setUpBeforeTest(dbName, tblName, colName, isTxnTable);
    updateTableColStats(dbName, tblName, colName, highValue, avgColLen, isTxnTable);
    if (!isTxnTable) {
      deleteColStats(dbName, tblName, colName);
    }

    tblName = "tbl_part";
    createTableWithPart(dbName, tblName, colName, isTxnTable);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short)-1);
    String partName = partitions.get(0);
    updatePartColStats(dbName, tblName, isTxnTable, colName, partName, highValue, avgColLen);
    if (!isTxnTable) {
      deletePartColStats(dbName, tblName, colName, partName);
    }
  }

  @Test
  public void testTableColumnStatistics() throws Throwable {
    String dbName = "column_stats_test_db";
    String tblName = "tbl";
    testTableColStatInternal(dbName, tblName, false);
  }

  @Test
  public void testTableColumnStatisticsTxnTable() throws Throwable {
    String dbName = "column_stats_test_db_txn";
    String tblName = "tbl_txn";
    testTableColStatInternal(dbName, tblName, true);
  }

  @Test
  public void testTableColumnStatisticsTxnTableMulti() throws Throwable {
    String dbName = "column_stats_test_db_txn_multi";
    String tblName = "tbl_part";
    String[] colName = new String[]{"income", "name"};
    double highValue = 1200000.4525;
    double avgColLen = 50.30;

    setUpBeforeTest(dbName, null, colName, true);
    createTableWithPart(dbName, tblName, colName, true);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short)-1);
    String partName = partitions.get(0);
    updatePartColStats(dbName, tblName, true, colName, partName, highValue, avgColLen);
    updatePartColStats(dbName, tblName, true, colName, partName, 1200000.4521, avgColLen);
    updatePartColStats(dbName, tblName, true, colName, partName, highValue, 34.78);
  }

  @Test
  public void testTableColumnStatisticsTxnTableMultiAbort() throws Throwable {
    String dbName = "column_stats_test_db_txn_multi_abort";
    String tblName = "tbl_part";
    String[] colName = new String[]{"income", "name"};
    double highValue = 1200000.4525;
    double avgColLen = 50.30;

    setUpBeforeTest(dbName, null, colName, true);
    createTableWithPart(dbName, tblName, colName, true);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short)-1);
    String partName = partitions.get(0);

    List<Long> txnIds = allocateTxns(1);
    long writeId = allocateWriteIds(txnIds, dbName, tblName).get(0).getWriteId();
    String validWriteIds = getValidWriteIds(dbName, tblName);

    // create a new columnstatistics desc to represent partition level column stats
    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setPartName(partName);
    statsDesc.setIsTblLevel(false);

    ColumnStatistics colStats = new ColumnStatistics();
    colStats.setStatsDesc(statsDesc);
    colStats.setStatsObj(getStatsObjects(dbName, tblName, colName, highValue, avgColLen));
    colStats.setEngine(Constants.HIVE_ENGINE);

    SetPartitionsStatsRequest setTblColStat = new SetPartitionsStatsRequest(Collections.singletonList(colStats), Constants.HIVE_ENGINE);
    setTblColStat.setWriteId(writeId);
    setTblColStat.setValidWriteIdList(validWriteIds);

    // write stats objs persistently
    hmsHandler.update_partition_column_statistics_req(setTblColStat);

    // abort the txn and verify that the stats got is not compliant.
    AbortTxnRequest rqst = new AbortTxnRequest(txnIds.get(0));
    hmsHandler.abort_txn(rqst);

    allocateWriteIds(allocateTxns(1), dbName, tblName);
    validWriteIds = getValidWriteIds(dbName, tblName);

    Deadline.startTimer("getPartitionColumnStatistics");
    List<ColumnStatistics> statRawStore = rawStore.getPartitionColumnStatistics(DEFAULT_CATALOG_NAME, dbName, tblName,
            Collections.singletonList(partName), Collections.singletonList(colName[1]), Constants.HIVE_ENGINE, validWriteIds);
    Deadline.stopTimer();

    verifyStat(statRawStore.get(0).getStatsObj(), colName, highValue, avgColLen);
    Assert.assertEquals(statRawStore.get(0).isIsStatsCompliant(), false);

    List<ColumnStatistics> statsListFromCache = sharedCache.getPartitionColStatsListFromCache(DEFAULT_CATALOG_NAME,
            dbName, tblName, Collections.singletonList(partName), Collections.singletonList(colName[1]),
            validWriteIds, true);
    verifyStat(statsListFromCache.get(0).getStatsObj(), colName, highValue, avgColLen);
    Assert.assertEquals(statsListFromCache.get(0).isIsStatsCompliant(), false);

    SharedCache.ColumStatsWithWriteId columStatsWithWriteId =
            sharedCache.getPartitionColStatsFromCache(DEFAULT_CATALOG_NAME, dbName, tblName,
              CachedStore.partNameToVals(partName), colName[1], validWriteIds);
    Assert.assertEquals(columStatsWithWriteId, null);
    validatePartPara(dbName, tblName, partName);
  }

  @Test
  public void testTableColumnStatisticsTxnTableOpenTxn() throws Throwable {
    String dbName = "column_stats_test_db_txn_multi_open";
    String tblName = "tbl_part";
    String[] colName = new String[]{"income", "name"};
    double highValue = 1200000.4121;
    double avgColLen = 23.30;

    setUpBeforeTest(dbName, null, colName, true);
    createTableWithPart(dbName, tblName, colName, true);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short)-1);
    String partName = partitions.get(0);

    // update part col stats successfully.
    updatePartColStats(dbName, tblName, true, colName, partName, 1.2, 12.2);

    List<Long> txnIds = allocateTxns(1);
    long writeId = allocateWriteIds(txnIds, dbName, tblName).get(0).getWriteId();
    String validWriteIds = getValidWriteIds(dbName, tblName);

    // create a new columnstatistics desc to represent partition level column stats
    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setPartName(partName);
    statsDesc.setIsTblLevel(false);

    ColumnStatistics colStats = new ColumnStatistics();
    colStats.setStatsDesc(statsDesc);
    colStats.setStatsObj(getStatsObjects(dbName, tblName, colName, highValue, avgColLen));
    colStats.setEngine(Constants.HIVE_ENGINE);

    SetPartitionsStatsRequest setTblColStat = new SetPartitionsStatsRequest(Collections.singletonList(colStats), Constants.HIVE_ENGINE);
    setTblColStat.setWriteId(writeId);
    setTblColStat.setValidWriteIdList(validWriteIds);

    // write stats objs persistently
    hmsHandler.update_partition_column_statistics_req(setTblColStat);

    // keep the txn open and verify that the stats got is not compliant.

    allocateWriteIds(allocateTxns(1), dbName, tblName);
    validWriteIds = getValidWriteIds(dbName, tblName);

    Deadline.startTimer("getPartitionColumnStatistics");
    List<ColumnStatistics> statRawStore = rawStore.getPartitionColumnStatistics(DEFAULT_CATALOG_NAME, dbName, tblName,
            Collections.singletonList(partName), Collections.singletonList(colName[1]), Constants.HIVE_ENGINE, validWriteIds);
    Deadline.stopTimer();

    verifyStat(statRawStore.get(0).getStatsObj(), colName, highValue, avgColLen);
    Assert.assertEquals(statRawStore.get(0).isIsStatsCompliant(), false);

    List<ColumnStatistics> statsListFromCache = sharedCache.getPartitionColStatsListFromCache(DEFAULT_CATALOG_NAME,
            dbName, tblName, Collections.singletonList(partName), Collections.singletonList(colName[1]),
            validWriteIds, true);
    verifyStat(statsListFromCache.get(0).getStatsObj(), colName, highValue, avgColLen);
    Assert.assertEquals(statsListFromCache.get(0).isIsStatsCompliant(), false);

    SharedCache.ColumStatsWithWriteId columStatsWithWriteId =
            sharedCache.getPartitionColStatsFromCache(DEFAULT_CATALOG_NAME, dbName,
              tblName, CachedStore.partNameToVals(partName), colName[1], validWriteIds);
    Assert.assertEquals(columStatsWithWriteId, null);
    validatePartPara(dbName, tblName, partName);
  }

  private void verifyAggrStat(String dbName, String tblName, String[] colName, List<String> partitions,
                              boolean isTxnTbl, double highValue) throws Throwable {
    List<Long> txnIds = allocateTxns(1);
    allocateWriteIds(txnIds, dbName, tblName).get(0).getWriteId();
    String validWriteIds = getValidWriteIds(dbName, tblName);

    Deadline.startTimer("getPartitionSpecsByFilterAndProjection");
    AggrStats aggrStats = rawStore.get_aggr_stats_for(DEFAULT_CATALOG_NAME, dbName, tblName, partitions,
            Collections.singletonList(colName[0]), Constants.HIVE_ENGINE, validWriteIds);
    Deadline.stopTimer();
    Assert.assertEquals(aggrStats.getPartsFound(), 2);
    Assert.assertEquals(aggrStats.getColStats().get(0).getStatsData().getDoubleStats().getHighValue(), highValue, 0.01);
    //Assert.assertEquals(aggrStats.isIsStatsCompliant(), true);

    // This will update the cache for non txn table.
    PartitionsStatsRequest request = new PartitionsStatsRequest(dbName, tblName,
            Collections.singletonList(colName[0]), partitions, Constants.HIVE_ENGINE);
    request.setCatName(DEFAULT_CATALOG_NAME);
    request.setValidWriteIdList(validWriteIds);
    AggrStats aggrStatsCached = hmsHandler.get_aggr_stats_for(request);
    Assert.assertEquals(aggrStatsCached, aggrStats);
    //Assert.assertEquals(aggrStatsCached.isIsStatsCompliant(), true);

    List<ColumnStatisticsObj> stats = sharedCache.getAggrStatsFromCache(DEFAULT_CATALOG_NAME, dbName, tblName,
            Collections.singletonList(colName[0]), SharedCache.StatsType.ALL);
    Assert.assertEquals(stats.get(0).getStatsData().getDoubleStats().getHighValue(), highValue, 0.01);
  }

  @Test
  public void testAggrStat() throws Throwable {
    String dbName = "aggr_stats_test";
    String tblName = "tbl_part";
    String[] colName = new String[]{"income", "name"};

    setUpBeforeTest(dbName, null, colName, false);
    createTableWithPart(dbName, tblName, colName, false);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short) -1);
    String partName = partitions.get(0);

    // update part col stats successfully.
    updatePartColStats(dbName, tblName, false, colName, partitions.get(0), 2, 12);
    updatePartColStats(dbName, tblName, false, colName, partitions.get(1), 4, 10);
    verifyAggrStat(dbName, tblName, colName, partitions, false, 4);

    updatePartColStats(dbName, tblName, false, colName, partitions.get(1), 3, 10);
    verifyAggrStat(dbName, tblName, colName, partitions, false, 3);
  }

  @Test
  public void testAggrStatTxnTable() throws Throwable {
    String dbName = "aggr_stats_test_db_txn";
    String tblName = "tbl_part";
    String[] colName = new String[]{"income", "name"};

    setUpBeforeTest(dbName, null, colName, true);
    createTableWithPart(dbName, tblName, colName, true);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short)-1);
    String partName = partitions.get(0);

    // update part col stats successfully.
    updatePartColStats(dbName, tblName, true, colName, partitions.get(0), 2, 12);
    updatePartColStats(dbName, tblName, true, colName, partitions.get(1), 4, 10);
    verifyAggrStat(dbName, tblName, colName, partitions, true, 4);

    updatePartColStats(dbName, tblName, true, colName, partitions.get(1), 3, 10);
    verifyAggrStat(dbName, tblName, colName, partitions, true, 3);

    List<Long> txnIds = allocateTxns(1);
    long writeId = allocateWriteIds(txnIds, dbName, tblName).get(0).getWriteId();
    String validWriteIds = getValidWriteIds(dbName, tblName);

    // create a new columnstatistics desc to represent partition level column stats
    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setPartName(partName);
    statsDesc.setIsTblLevel(false);

    ColumnStatistics colStats = new ColumnStatistics();
    colStats.setStatsDesc(statsDesc);
    colStats.setStatsObj(getStatsObjects(dbName, tblName, colName, 5, 20));
    colStats.setEngine(Constants.HIVE_ENGINE);

    SetPartitionsStatsRequest setTblColStat = new SetPartitionsStatsRequest(Collections.singletonList(colStats), Constants.HIVE_ENGINE);
    setTblColStat.setWriteId(writeId);
    setTblColStat.setValidWriteIdList(validWriteIds);
    hmsHandler.update_partition_column_statistics_req(setTblColStat);

    Deadline.startTimer("getPartitionSpecsByFilterAndProjection");
    AggrStats aggrStats = rawStore.get_aggr_stats_for(DEFAULT_CATALOG_NAME, dbName, tblName, partitions,
            Collections.singletonList(colName[0]), Constants.HIVE_ENGINE, validWriteIds);
    Deadline.stopTimer();
    Assert.assertEquals(aggrStats, null);

    // keep the txn open and verify that the stats got is not compliant.
    PartitionsStatsRequest request = new PartitionsStatsRequest(dbName, tblName,
            Collections.singletonList(colName[0]), partitions, Constants.HIVE_ENGINE);
    request.setCatName(DEFAULT_CATALOG_NAME);
    request.setValidWriteIdList(validWriteIds);
    AggrStats aggrStatsCached = hmsHandler.get_aggr_stats_for(request);
    Assert.assertEquals(aggrStatsCached, null);
  }

  @Test
  public void testAggrStatAbortTxn() throws Throwable {
    String dbName = "aggr_stats_test_db_txn_abort";
    String tblName = "tbl_part";
    String[] colName = new String[]{"income", "name"};

    setUpBeforeTest(dbName, null, colName, true);
    createTableWithPart(dbName, tblName, colName, true);
    List<String> partitions = hmsHandler.get_partition_names(dbName, tblName, (short)-1);
    String partName = partitions.get(0);

    // update part col stats successfully.
    updatePartColStats(dbName, tblName, true, colName, partitions.get(0), 2, 12);
    updatePartColStats(dbName, tblName, true, colName, partitions.get(1), 4, 10);
    verifyAggrStat(dbName, tblName, colName, partitions, true, 4);

    List<Long> txnIds = allocateTxns(4);
    long writeId = allocateWriteIds(txnIds, dbName, tblName).get(0).getWriteId();
    String validWriteIds = getValidWriteIds(dbName, tblName);

    // create a new columnstatistics desc to represent partition level column stats
    ColumnStatisticsDesc statsDesc = new ColumnStatisticsDesc();
    statsDesc.setDbName(dbName);
    statsDesc.setTableName(tblName);
    statsDesc.setPartName(partName);
    statsDesc.setIsTblLevel(false);

    ColumnStatistics colStats = new ColumnStatistics();
    colStats.setStatsDesc(statsDesc);
    colStats.setStatsObj(getStatsObjects(dbName, tblName, colName, 5, 20));
    colStats.setEngine(Constants.HIVE_ENGINE);

    SetPartitionsStatsRequest setTblColStat = new SetPartitionsStatsRequest(Collections.singletonList(colStats), Constants.HIVE_ENGINE);
    setTblColStat.setWriteId(writeId);
    setTblColStat.setValidWriteIdList(validWriteIds);
    hmsHandler.update_partition_column_statistics_req(setTblColStat);

    AbortTxnRequest abortTxnRequest = new AbortTxnRequest(txnIds.get(0));
    hmsHandler.abort_txn(abortTxnRequest);

    Deadline.startTimer("getPartitionSpecsByFilterAndProjection");
    AggrStats aggrStats = rawStore.get_aggr_stats_for(DEFAULT_CATALOG_NAME, dbName, tblName, partitions,
            Collections.singletonList(colName[0]), Constants.HIVE_ENGINE, validWriteIds);
    Deadline.stopTimer();
    Assert.assertEquals(aggrStats, null);

    // keep the txn open and verify that the stats got is not compliant.
    PartitionsStatsRequest request = new PartitionsStatsRequest(dbName, tblName,
            Collections.singletonList(colName[0]), partitions, Constants.HIVE_ENGINE);
    request.setCatName(DEFAULT_CATALOG_NAME);
    request.setValidWriteIdList(validWriteIds);
    AggrStats aggrStatsCached = hmsHandler.get_aggr_stats_for(request);
    Assert.assertEquals(aggrStatsCached, null);
  }
}
