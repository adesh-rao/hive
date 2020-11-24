package org.apache.hadoop.hive.metastore;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hive.metastore.api.AggrStats;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.GetPartitionsByNamesRequest;
import org.apache.hadoop.hive.metastore.api.GetPartitionsByNamesResult;
import org.apache.hadoop.hive.metastore.api.GetTableRequest;
import org.apache.hadoop.hive.metastore.api.GetTableResult;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.PartitionsByExprRequest;
import org.apache.hadoop.hive.metastore.api.PartitionsByExprResult;
import org.apache.hadoop.hive.metastore.api.PartitionsSpecByExprResult;
import org.apache.hadoop.hive.metastore.api.PartitionsStatsRequest;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.TableStatsRequest;
import org.apache.hadoop.hive.metastore.api.TableStatsResult;
import org.apache.thrift.TException;

import java.util.List;

/**
 * Context wrapper around hive metastore client api
 * Context can be persisting to DB, persisting to local cache or persisting to session
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface IMetaStoreClientWithContext extends IMetaStoreClient {
  void setBaseMetaStoreClient(IMetaStoreClient client);

  void create_table_with_environment_context(Table tbl, EnvironmentContext envContext)
      throws AlreadyExistsException, InvalidObjectException, MetaException, NoSuchObjectException, TException;

  void drop_table_with_environment_context(String catName, String dbname,
                                           String name, boolean deleteData, EnvironmentContext envContext)
      throws TException;

  /**
   * This method is called to get the ValidWriteIdList in order to send the same in HMS get_* APIs,
   * if the validWriteIdList is not explicitly passed (as a method argument) to the HMS APIs.
   * This method returns the ValidWriteIdList based on the VALID_TABLES_WRITEIDS_KEY key.
   * Since, VALID_TABLES_WRITEIDS_KEY is set during the lock acquisition phase after query compilation
   * ( DriverTxnHandler.acquireLocks -> recordValidWriteIds -> setValidWriteIds ),
   * this only covers a subset of cases, where we invoke get_* APIs after query compilation,
   * if the validWriteIdList is not explicitly passed (as a method argument) to the HMS APIs.
   */
  String getValidWriteIdList(String dbName, String tblName);

  GetTableResult getTableInternal(GetTableRequest req) throws TException;

  PartitionsByExprResult getPartitionsByExprInternal(PartitionsByExprRequest req) throws TException;

  List<String> listPartitionNamesInternal(String catName, String dbName, String tableName, int maxParts)
      throws TException;

  PartitionsSpecByExprResult getPartitionsSpecByExprInternal(PartitionsByExprRequest req) throws TException;

  TableStatsResult getTableColumnStatisticsInternal(TableStatsRequest req) throws TException;

  AggrStats getAggrStatsForInternal(PartitionsStatsRequest req) throws TException;

  GetPartitionsByNamesResult getPartitionsByNamesInternal(GetPartitionsByNamesRequest rqst) throws TException;
}
