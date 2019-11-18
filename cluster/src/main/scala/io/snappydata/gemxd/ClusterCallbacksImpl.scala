/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package io.snappydata.gemxd

import java.io.{File, InputStream}
import java.util.{Iterator => JIterator}
import java.{lang, util}

import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember
import com.gemstone.gemfire.internal.cache.{ExternalTableMetaData, GemFireCacheImpl}
import com.gemstone.gemfire.internal.shared.Version
import com.gemstone.gemfire.internal.{ByteArrayDataInput, ClassPathLoader, GemFireVersion}
import com.pivotal.gemfirexd.Attribute
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow
import com.pivotal.gemfirexd.internal.snappy.{CallbackFactoryProvider, ClusterCallbacks, LeadNodeExecutionContext, SparkSQLExecute}
import io.snappydata.cluster.ExecutorInitiator
import io.snappydata.impl.LeadImpl
import io.snappydata.recovery.RecoveryService
import io.snappydata.sql.catalog.{CatalogObjectType, SnappyExternalCatalog}
import io.snappydata.util.ServiceUtils
import io.snappydata.{ServiceManager, SnappyEmbeddedTableStatsProviderService}

import org.apache.spark.Logging
import org.apache.spark.scheduler.cluster.SnappyClusterManager
import org.apache.spark.serializer.{KryoSerializerPool, StructTypeSerializer}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable}
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils.CaseInsensitiveMutableHashMap
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.internal.SnappySessionCatalog
import org.apache.spark.sql.sources.DataSourceRegister

/**
 * Callbacks that are sent by GemXD to Snappy for cluster management
 */
object ClusterCallbacksImpl extends ClusterCallbacks with Logging {

  CallbackFactoryProvider.setClusterCallbacks(this)

  private val PASSWORD_MATCH = "(?i)(password|passwd|secret).*".r

  private[snappydata] def initialize(): Unit = {
    // nothing to be done; singleton constructor does all
  }

  override def getLeaderGroup: java.util.HashSet[String] = {
    val leaderServerGroup = new java.util.HashSet[String]
    leaderServerGroup.add(LeadImpl.LEADER_SERVERGROUP)
    leaderServerGroup
  }

  override def launchExecutor(driverUrl: String,
      driverDM: InternalDistributedMember): Unit = {
    val url = if (driverUrl == null || driverUrl == "") {
      logInfo(s"call to launchExecutor but driverUrl is invalid. $driverUrl")
      None
    }
    else {
      Some(driverUrl)
    }
    logInfo(s"invoking startOrTransmute with $url")
    ExecutorInitiator.startOrTransmuteExecutor(url, driverDM)
  }

  override def getDriverURL: String = {
    SnappyClusterManager.cm.map(_.schedulerBackend) match {
      case Some(backend) if backend ne null =>
        val driverUrl = backend.driverUrl
        if ((driverUrl ne null) && !driverUrl.isEmpty) {
          logInfo(s"returning driverUrl=$driverUrl")
        }
        driverUrl
      case _ => null
    }
  }

  override def stopExecutor(): Unit = {
    ExecutorInitiator.stop()
  }

  override def getSQLExecute(sql: String, schema: String, ctx: LeadNodeExecutionContext,
      v: Version, isPreparedStatement: Boolean, isPreparedPhase: Boolean,
      pvs: ParameterValueSet): SparkSQLExecute = {
    if (isPreparedStatement && isPreparedPhase) {
      new SparkSQLPrepareImpl(sql, schema, ctx, v)
    } else {
      new SparkSQLExecuteImpl(sql, schema, ctx, v, Option(pvs))
    }
  }

  override  def getSampleInsertExecute(baseTable: String, ctx: LeadNodeExecutionContext,
    v: Version, dvdRows: util.List[Array[DataValueDescriptor]],
    serializedDVDs: Array[Byte]): SparkSQLExecute = {
     new SparkSampleInsertExecuteImpl(baseTable, dvdRows, serializedDVDs, ctx, v)
  }

  override def readDataType(in: ByteArrayDataInput): AnyRef = {
    // read the DataType
    KryoSerializerPool.deserialize(in.array(), in.position(), in.available(), (kryo, input) => {
      val result = StructTypeSerializer.readType(kryo, input)
      // move the cursor to the new position
      in.setPosition(input.position())
      result
    })
  }

  override def getRowIterator(dvds: Array[DataValueDescriptor],
      types: Array[Int], precisions: Array[Int], scales: Array[Int],
      dataTypes: Array[AnyRef], in: ByteArrayDataInput): JIterator[ValueRow] = {
    SparkSQLExecuteImpl.getRowIterator(dvds, types, precisions, scales,
      dataTypes, in)
  }

  override def clearSnappySessionForConnection(
      connectionId: java.lang.Long): Unit = {
    SnappySessionPerConnection.removeSnappySession(connectionId)
  }

  override def publishColumnTableStats(): Unit = {
    SnappyEmbeddedTableStatsProviderService.publishColumnTableRowCountStats()
  }

  override def getClusterType: String = {
    GemFireCacheImpl.setGFXDSystem(true)
    // AQP version if available
    val is: InputStream = ClassPathLoader.getLatest.getResourceAsStream(
      classOf[SnappyDataVersion], SnappyDataVersion.AQP_VERSION_PROPERTIES)
    if (is ne null) try {
      GemFireVersion.getInstance(classOf[SnappyDataVersion], SnappyDataVersion
          .AQP_VERSION_PROPERTIES)
    } finally {
      is.close()
    }
    GemFireVersion.getClusterType
  }

  override def exportData(connId: lang.Long, exportUri: String,
      formatType: String, tableNames: String, ignoreError: lang.Boolean): Unit = {
    val session = SnappySessionPerConnection.getSnappySessionForConnection(connId)
    if (Misc.isSecurityEnabled) {
      session.conf.set(Attribute.USERNAME_ATTR,
        Misc.getMemStore.getBootProperty(Attribute.USERNAME_ATTR))
      session.conf.set(Attribute.PASSWORD_ATTR,
        Misc.getMemStore.getBootProperty(Attribute.PASSWORD_ATTR))
    }

    val tablesArr = if (tableNames.equalsIgnoreCase("all")) {
      RecoveryService.getTables.map(ct =>
        ct.storage.locationUri match {
          case Some(_) => null // external tables will not be exported
          case None =>
            ct.identifier.database match {
              case Some(db) => db + "." + ct.identifier.table
              case None => ct.identifier.table
            }
        }
      ).filter(_ != null)
    } else tableNames.split(",").map(_.trim).toSeq

    logDebug(s"Using connection ID: $connId\n Export path:" +
        s" $exportUri\n Format Type: $formatType\n Table names: $tableNames")

    val filePath = if (exportUri.endsWith(File.separator)) {
      exportUri.substring(0, exportUri.length - 1) +
          s"_${System.currentTimeMillis()}" + File.separator
    } else {
      exportUri + s"_${System.currentTimeMillis()}" + File.separator
    }
    RecoveryService.captureArguments(formatType, tablesArr, filePath, ignoreError)
    tablesArr.foreach(f = table => {
      Try {
        val tableData = session.sql(s"select * from $table;")
        logDebug(s"EXPORT_DATA procedure is writing table: $table.")
        tableData.write.mode(SaveMode.Overwrite).option("header", "true").format(formatType)
            .save(filePath + File.separator + table.toUpperCase)
      } match {
        case scala.util.Success(_) =>
        case scala.util.Failure(exception) =>
          logError(s"Error recovering table: $table.")
          if (!ignoreError) {
            throw new Exception(exception)
          }
      }
    })
  }

  override def exportDDLs(connId: lang.Long, exportUri: String): Unit = {
    val session = SnappySessionPerConnection.getSnappySessionForConnection(connId)
    val filePath = if (exportUri.endsWith(File.separator)) {
      exportUri.substring(0, exportUri.length - 1) +
          s"_${System.currentTimeMillis()}" + File.separator
    } else {
      exportUri + s"_${System.currentTimeMillis()}" + File.separator
    }
    val arrBuf: ArrayBuffer[String] = ArrayBuffer.empty

    RecoveryService.getAllDDLs.foreach(ddl => {
      arrBuf.append(ddl.trim + ";\n")
    })
    session.sparkContext.parallelize(arrBuf, 1).saveAsTextFile(filePath)
  }

  /**
   * generates spark-shell code which helps user to reload data exported through EXPORT_DATA
   * procedure
   *
   */
  def generateLoadScripts(connId: lang.Long): Unit = {
    val session = SnappySessionPerConnection.getSnappySessionForConnection(connId)
    var loadScriptString = ""

    RecoveryService.exportDataArgsList.foreach(args => {
      val generatedScriptPath = s"${args.outputDir.replaceAll("/$", "")}_load_scripts"
      args.tables.foreach(table => {
        val tableExternal = s"temp_${table.replace('.', '_')}"
        val additionalOptions = args.formatType match {
          case "csv" => ",header 'true'"
          case _ => ""
        }
        // todo do testing for all formats and ensure generated scripts handles all scenarios
        loadScriptString += s"""
          |CREATE EXTERNAL TABLE $tableExternal USING ${args.formatType}
          |OPTIONS (PATH '${args.outputDir}/${table.toUpperCase}'$additionalOptions);
          |INSERT OVERWRITE $table SELECT * FROM $tableExternal;
          |
        """.stripMargin
      })
      session.sparkContext.parallelize(Seq(loadScriptString), 1).saveAsTextFile(generatedScriptPath)
    })
  }

  override def setLeadClassLoader(): Unit = {
    val instance = ServiceManager.currentFabricServiceInstance
    instance match {
      case li: LeadImpl =>
        val loader = li.urlclassloader
        if (loader != null) {
          Thread.currentThread().setContextClassLoader(loader)
        }
      case _ =>
    }
  }

  override def getHiveTablesMetadata(connectionId: Long): util.Collection[ExternalTableMetaData] = {
    val session = SnappySessionPerConnection.getAllSessions.head
    val catalogTables = session.sessionState.catalog.asInstanceOf[SnappySessionCatalog]
        .getHiveCatalogTables
    import scala.collection.JavaConverters._
    getTablesMetadata(catalogTables).asJava
  }

  private def getTablesMetadata(catalogTables: Seq[CatalogTable]): Seq[ExternalTableMetaData] = {
    catalogTables.map(catalogTableToMetadata)
  }

  //todo[vatsal] simplify this code. may be some conditions can be removed as tables will be always
  // from hive metastore.
  private def catalogTableToMetadata(table: CatalogTable) = {
    val tableType = CatalogObjectType.getTableType(table)
    val parameters = new CaseInsensitiveMutableHashMap[String](table.storage.properties)
    val tblDataSourcePath = getDataSourcePath(parameters, table.storage)
    val driverClass = parameters.get("driver") match {
      case None => ""
      case Some(c) => c
    }
    // exclude policies also from the list of hive tables
    val metaData = new ExternalTableMetaData(table.identifier.table,
      table.database, tableType.toString, null, -1,
      -1, null, null, null, null,
      tblDataSourcePath, driverClass, false)
    metaData.provider = table.provider match {
      case None => ""
      case Some(p) => p
    }
    metaData.shortProvider = metaData.provider
    try {
      val c = DataSource.lookupDataSource(metaData.provider)
      if (classOf[DataSourceRegister].isAssignableFrom(c)) {
        metaData.shortProvider = c.newInstance.asInstanceOf[DataSourceRegister].shortName()
      }
    } catch {
      case NonFatal(_) => // ignore
    }
    metaData.columns = ExternalStoreUtils.getColumnMetadata(table.schema)
    if (tableType == CatalogObjectType.View) {
      metaData.viewText = table.viewOriginalText match {
        case None => table.viewText match {
          case None => ""
          case Some(t) => t
        }
        case Some(t) => t
      }
    }
    metaData
  }

  private def getDataSourcePath(properties: scala.collection.Map[String, String],
      storage: CatalogStorageFormat): String = {
    properties.get("path") match {
      case Some(p) if !p.isEmpty => ServiceUtils.maskLocationURI(p)
      case _ => properties.get("region.path") match { // for external GemFire connector
        case Some(p) if !p.isEmpty => ServiceUtils.maskLocationURI(p)
        case _ => properties.get("url") match { // jdbc
          case Some(p) if !p.isEmpty =>
            // mask the password if present
            val url = ServiceUtils.maskLocationURI(p)
            // add dbtable if present
            properties.get(SnappyExternalCatalog.DBTABLE_PROPERTY) match {
              case Some(d) if !d.isEmpty => s"$url; ${SnappyExternalCatalog.DBTABLE_PROPERTY}=$d"
              case _ => url
            }
          case _ => storage.locationUri match { // fallback to locationUri
            case None => ""
            case Some(l) => ServiceUtils.maskLocationURI(l)
          }
        }
      }
    }
  }
}
