/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.oap.io

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataOutputStream, Path}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Ascending
import org.apache.spark.sql.execution.datasources.oap.{DataSourceMeta, OapFileFormat}
import org.apache.spark.sql.execution.datasources.oap.index._
import org.apache.spark.sql.types._
import org.apache.spark.util.TimeStampedHashMap


private[oap] object OapIndexInfo extends Logging {
  val partitionOapIndex = new TimeStampedHashMap[String, Boolean](updateTimeStampOnGet = true)
}

private[oap] class OapDataReader(
  path: Path,
  meta: DataSourceMeta,
  filterScanners: Option[IndexScanners],
  requiredIds: Array[Int]) extends Logging {

  def initialize(
      conf: Configuration,
      options: Map[String, String] = Map.empty): Iterator[InternalRow] = {
    logDebug("Initializing OapDataReader...")
    // TODO how to save the additional FS operation to get the Split size
    val fileScanner = DataFile(path.toString, meta.schema, meta.dataReaderClassName, conf)

    filterScanners match {
      case Some(indexScanners) if indexScanners.indexIsAvailable(path, conf) =>
        def getRowIds(options: Map[String, String]): Array[Int] = {
          indexScanners.initialize(path, conf)

          // total Row count can be get from the index scanner
          val limit = options.getOrElse(OapFileFormat.OAP_QUERY_LIMIT_OPTION_KEY, "0").toInt
          val rowIds = if (limit > 0) {
            // Order limit scan options
            val isAscending = options.getOrElse(
              OapFileFormat.OAP_QUERY_ORDER_OPTION_KEY, "true").toBoolean
            val sameOrder =
              !((indexScanners.order == Ascending) ^ isAscending)

            if (sameOrder) indexScanners.take(limit).toArray
            else indexScanners.toArray.reverse.take(limit)
          } else indexScanners.toArray

          // Parquet reader does not support backward scan, so rowIds must be sorted.
          if (meta.dataReaderClassName.contains("ParquetDataFile")) rowIds.sorted
          else rowIds
        }

        val start = System.currentTimeMillis()
        val iter = fileScanner.iterator(conf, requiredIds, getRowIds(options))
        val end = System.currentTimeMillis()
        logDebug("Construct File Iterator: " + (end - start) + "ms")
        iter
      case _ =>
        val start = System.currentTimeMillis()
        val iter = fileScanner.iterator(conf, requiredIds)
        val end = System.currentTimeMillis()
        logDebug("Construct File Iterator: " + (end - start) + "ms")

        iter
    }
  }
}
