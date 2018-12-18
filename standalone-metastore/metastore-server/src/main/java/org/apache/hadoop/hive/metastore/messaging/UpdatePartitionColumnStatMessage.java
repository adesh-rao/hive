/* * Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.hadoop.hive.metastore.messaging;

import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import java.util.List;
import java.util.Map;

/**
 * HCat message sent when an table partition statistics update is done.
 */
public abstract class UpdatePartitionColumnStatMessage extends EventMessage {

  protected UpdatePartitionColumnStatMessage() {
    super(EventType.UPDATE_TABLE_COLUMN_STAT);
  }

  public abstract ColumnStatistics getColumnStatistics();

  public abstract String getValidWriteIds();

  public abstract Long getWriteId();

  public abstract Map<String, String> getParameters();

  public abstract List<String> getPartVals();
}
