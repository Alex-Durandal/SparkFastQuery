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

package org.apache.spark.sql.execution.datasources.oap

import java.net.URI

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.mapreduce.{Job, TaskAttemptContext}
import org.apache.parquet.hadoop.util.SerializationUtil

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Expression, JoinedRow}
import org.apache.spark.sql.catalyst.expressions.codegen.{GenerateOrdering, GenerateUnsafeProjection}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.oap.filecache.DataFileHandleCacheManager
import org.apache.spark.sql.execution.datasources.oap.index.{IndexContext, ScannerBuilder}
import org.apache.spark.sql.execution.datasources.oap.io._
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.util.SerializableConfiguration

private[sql] class OapFileFormat extends FileFormat
  with DataSourceRegister
  with Logging
  with Serializable {

  override def initialize(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): FileFormat = {
    super.initialize(sparkSession, options, files)

    val hadoopConf = sparkSession.sparkContext.hadoopConfiguration
    // TODO
    // 1. Make the scanning etc. as lazy loading, as inferSchema probably not be called
    // 2. We need to pass down the oap meta file and its associated partition path

    val parents = files.map(file => file.getPath.getParent)

    // TODO we support partitions, but this only read meta from one of the partitions
    val partition2Meta = parents.distinct.reverse.map { parent =>
      new Path(parent, OapFileFormat.OAP_META_FILE)
    }.find(metaPath => metaPath.getFileSystem(hadoopConf).exists(metaPath))

    meta = partition2Meta.map {
      DataSourceMeta.initialize(_, hadoopConf)
    }

    // OapFileFormat.serializeDataSourceMeta(hadoopConf, meta)
    inferSchema = meta.map(_.schema)

    this
  }

  // TODO inferSchema could be lazy computed
  var inferSchema: Option[StructType] = _
  var meta: Option[DataSourceMeta] = _
  // map of columns->IndexType
  private var hitIndexColumns: Map[String, IndexType] = _

  def getHitIndexColumns: Map[String, IndexType] = {
    if (this.hitIndexColumns == null) {
      logWarning("Trigger buildReaderWithPartitionValues before getHitIndexColumns")
      Map.empty
    } else {
      this.hitIndexColumns
    }
  }

  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job, options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    val parquet = new ParquetFileFormat()
    parquet.prepareWrite(sparkSession, job, options, dataSchema)
  }

  override def shortName(): String = "oap"

  /**
   * Returns whether the reader will return the rows as batch or not.
   */
  override def supportBatch(sparkSession: SparkSession, schema: StructType): Boolean = {
    false
  }

  override def isSplitable(
      sparkSession: SparkSession,
      options: Map[String, String],
      path: Path): Boolean = false

  override def buildReaderWithPartitionValues(
      sparkSession: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration
  ): PartitionedFile => Iterator[InternalRow] = {
    // TODO we need to pass the extra data source meta information via the func parameter
    meta match {
      case Some(m) =>
        logDebug("Building OapDataReader with "
          + m.dataReaderClassName.substring(m.dataReaderClassName.lastIndexOf(".") + 1)
          + " ...")

        // Check whether this filter conforms to certain patterns that could benefit from index
        def canTriggerIndex(filter: Filter): Boolean = {
          var attr: String = null
          def checkAttribute(filter: Filter): Boolean = filter match {
            case Or(left, right) =>
              checkAttribute(left) && checkAttribute(right)
            case And(left, right) =>
              checkAttribute(left) && checkAttribute(right)
            case EqualTo(attribute, _) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case LessThan(attribute, _) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case LessThanOrEqual(attribute, _) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case GreaterThan(attribute, _) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case GreaterThanOrEqual(attribute, _) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case In(attribute, _) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case IsNull(attribute) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case IsNotNull(attribute) =>
              if (attr ==  null || attr == attribute) {attr = attribute; true} else false
            case _ => false
          }

          checkAttribute(filter)
        }

        def order(sf: StructField): Ordering[Key] = GenerateOrdering.create(StructType(Array(sf)))

        val ic = new IndexContext(m)

        if (m.indexMetas.nonEmpty) { // check and use index
          logDebug("Supported Filters by Oap:")
          // filter out the "filters" on which we can use index
          val supportFilters = filters.toArray.filter(canTriggerIndex)
          // After filtered, supportFilter only contains:
          // 1. Or predicate that contains only one attribute internally;
          // 2. Some atomic predicates, such as LessThan, EqualTo, etc.
          if (supportFilters.nonEmpty) {
            // determine whether we can use index
            supportFilters.foreach(filter => logDebug("\t" + filter.toString))
            // get index options such as limit, order, etc.
            val indexOptions = options.filterKeys(OapFileFormat.oapOptimizationKeySeq.contains(_))
            val maxChooseSize = sparkSession.conf.get(SQLConf.OAP_INDEXER_CHOICE_MAX_SIZE)
            ScannerBuilder.build(supportFilters, ic, indexOptions, maxChooseSize)
          }
        }

        val filterScanners = ic.getScanners
        hitIndexColumns = filterScanners match {
          case Some(s) =>
            s.scanners.flatMap { scanner =>
              scanner.keyNames.map( n => n -> scanner.meta.indexType)
            }.toMap
          case _ => Map.empty
        }

        val requiredIds = requiredSchema.map(dataSchema.fields.indexOf(_)).toArray

        hadoopConf.setDouble(SQLConf.OAP_FULL_SCAN_THRESHOLD.key,
          sparkSession.conf.get(SQLConf.OAP_FULL_SCAN_THRESHOLD))
        hadoopConf.setBoolean(SQLConf.OAP_ENABLE_OINDEX.key,
          sparkSession.conf.get(SQLConf.OAP_ENABLE_OINDEX))
        val broadcastedHadoopConf =
          sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

        (file: PartitionedFile) => {
          assert(file.partitionValues.numFields == partitionSchema.size)
          val conf = broadcastedHadoopConf.value.value
          val dataFile = DataFile(file.filePath, m.schema, m.dataReaderClassName, conf)
          val dataFileHandle: DataFileHandle = DataFileHandleCacheManager(dataFile)

            OapIndexInfo.partitionOapIndex.put(file.filePath, false)
            val iter = new OapDataReader(
              new Path(new URI(file.filePath)), m, filterScanners, requiredIds)
              .initialize(conf, options)

            val fullSchema = requiredSchema.toAttributes ++ partitionSchema.toAttributes
            val joinedRow = new JoinedRow()
            val appendPartitionColumns = GenerateUnsafeProjection.generate(fullSchema, fullSchema)

            iter.map(d => appendPartitionColumns(joinedRow(d, file.partitionValues)))

        }
      case None => (_: PartitionedFile) => {
        // TODO need to think about when there is no oap.meta file at all
        Iterator.empty
      }
    }
  }

  /**
   * Check if index satisfies strategies' requirements.
   *
   * @param expressions: Index expressions.
   * @param requiredTypes: Required index metrics by optimization strategies.
   * @return
   */
  def hasAvailableIndex(
      expressions: Seq[Expression],
      requiredTypes: Seq[IndexType] = Nil): Boolean = {
    if (expressions.nonEmpty && sparkSession.conf.get(SQLConf.OAP_ENABLE_OINDEX)) {
      meta match {
        case Some(m) if requiredTypes.isEmpty =>
          expressions.exists(m.isSupportedByIndex(_, None))
        case Some(m) if requiredTypes.length == expressions.length =>
          expressions.zip(requiredTypes).exists{ x =>
            val expression = x._1
            val requirement = Some(x._2)
            m.isSupportedByIndex(expression, requirement)
          }
        case _ => false
      }
    } else false
  }
}

private[sql] object OapFileFormat {
  val OAP_DATA_EXTENSION = ".data"
  val OAP_INDEX_EXTENSION = ".index"
  val OAP_META_EXTENSION = ".meta"
  val OAP_META_FILE = ".oap.meta"
  val OAP_META_SCHEMA = "oap.schema"
  val OAP_DATA_SOURCE_META = "oap.meta.datasource"
//  val OAP_DATA_FILE_CLASSNAME = classOf[OapDataFile].getCanonicalName
  val PARQUET_DATA_FILE_CLASSNAME = classOf[ParquetDataFile].getCanonicalName

  val COMPRESSION = "oap.compression"
  val DEFAULT_COMPRESSION = SQLConf.OAP_COMPRESSION.defaultValueString
  val ROW_GROUP_SIZE = "oap.rowgroup.size"
  val DEFAULT_ROW_GROUP_SIZE = SQLConf.OAP_ROW_GROUP_SIZE.defaultValueString

  def serializeDataSourceMeta(conf: Configuration, meta: Option[DataSourceMeta]): Unit = {
    SerializationUtil.writeObjectToConfAsBase64(OAP_DATA_SOURCE_META, meta, conf)
  }

  def deserializeDataSourceMeta(conf: Configuration): Option[DataSourceMeta] = {
    SerializationUtil.readObjectFromConfAsBase64(OAP_DATA_SOURCE_META, conf)
  }

  /**
   * Oap Optimization Options.
   */
  val OAP_QUERY_ORDER_OPTION_KEY = "oap.scan.file.order"
  val OAP_QUERY_LIMIT_OPTION_KEY = "oap.scan.file.limit"
  val OAP_INDEX_SCAN_NUM_OPTION_KEY = "oap.scan.index.limit"
  val OAP_INDEX_GROUP_BY_OPTION_KEY = "oap.scan.index.group"

  val oapOptimizationKeySeq : Seq[String] = {
      OAP_QUERY_ORDER_OPTION_KEY ::
      OAP_QUERY_LIMIT_OPTION_KEY ::
      OAP_INDEX_SCAN_NUM_OPTION_KEY ::
      OAP_INDEX_GROUP_BY_OPTION_KEY :: Nil
  }
}
