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

package org.apache.spark.sql.execution.benchmark

import scala.util.Try

import org.apache.spark.SparkConf
import org.apache.spark.benchmark.Benchmark
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.plans.logical.SubqueryAlias
import org.apache.spark.sql.catalyst.util.DateTimeConstants.NANOS_PER_SECOND
import org.apache.spark.sql.catalyst.util.resourceToString
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.internal.SQLConf

/**
 * Created with IDEA
 * Creater: MOBIN
 * Date: 2022/3/25
 * Time: 3:33 pm
 */
object TPCDSQueryBenchmark extends SqlBasedBenchmark with Logging {

  override def getSparkSession: SparkSession = {
    val conf = new SparkConf().setAppName("tpcds-sql-query")

    SparkSession.builder.config(conf)
      .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
      .config("spark.sql.catalog.spark_catalog", "org.apache.iceberg.spark.SparkSessionCatalog")
      .config("spark.sql.catalog.spark_catalog.type", "hive")
      .config("spark.sql.catalog.hadoop_catalog", "org.apache.iceberg.spark.SparkCatalog")
      .config("spark.sql.catalog.hadoop_catalog.type", "hadoop")
      .getOrCreate()
  }

  val tables = Seq("catalog_page", "catalog_returns", "customer", "customer_address",
    "customer_demographics", "date_dim", "household_demographics", "inventory", "item",
    "promotion", "store", "store_returns", "catalog_sales", "web_sales", "store_sales",
    "web_returns", "web_site", "reason", "call_center", "warehouse", "ship_mode", "income_band",
    "time_dim", "web_page")

  def setupTables(dataLocation: String
                  , iceberg: Boolean
                  , createTempView: Boolean): Map[String, Long] = {
    tables.map { tableName =>
      if (createTempView) {
        if (iceberg) {
          // 从Spark配置中获取catalog信息，如果不存在则使用默认值
          val databaseName = spark.conf
            .getOption("spark.app.iceberg.database")
            .getOrElse("tpcds")

          val dataFrame = spark.read
            .option("vectorization-enabled", "true")
            .format("iceberg").load(s"$databaseName.$tableName")
          dataFrame.createOrReplaceTempView(tableName)
        } else {
          val tablePath = s"$dataLocation/$tableName"
          spark.read.parquet(tablePath).createOrReplaceTempView(tableName)
        }

      } else {
        spark.sql(s"DROP TABLE IF EXISTS $tableName")
        spark.catalog.createTable(tableName, s"$dataLocation/$tableName", "parquet")
        // Recover partitions but don't fail if a table is not partitioned.
        Try {
          spark.sql(s"ALTER TABLE $tableName RECOVER PARTITIONS")
        }.getOrElse {
          logInfo(s"Recovering partitions of table $tableName failed")
        }
      }
      tableName -> spark.table(tableName).count()
    }.toMap
  }

  def runTpcdsQueries(queryLocation: String,
                       queries: Seq[String],
                       tableSizes: Map[String, Long],
                       nameSuffix: String = ""): Unit = {
    queries.foreach { name =>
      val queryString = resourceToString(s"$queryLocation/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)

      // This is an indirect hack to estimate the size of each query's input by traversing the
      // logical plan and adding up the sizes of all tables that appear in the plan.
      val queryRelations = scala.collection.mutable.HashSet[String]()
      spark.sql(queryString).queryExecution.analyzed.foreach {
        case SubqueryAlias(alias, _: DataSourceV2Relation) =>
          queryRelations.add(alias.name)
        case SubqueryAlias(alias, _: LogicalRelation) =>
          queryRelations.add(alias.name)
        case LogicalRelation(_, _, Some(catalogTable), _) =>
          queryRelations.add(catalogTable.identifier.table)
        case HiveTableRelation(tableMeta, _, _, _, _) =>
          queryRelations.add(tableMeta.identifier.table)
        case _ =>
      }
      val numRows = queryRelations.map(tableSizes.getOrElse(_, 0L)).sum
      val benchmark = new Benchmark(s"TPCDS Snappy", numRows, 2, output = output)
      benchmark.addCase(s"$name$nameSuffix") { _ =>
        spark.sql(queryString).noop()
      }
      benchmark.run()
    }
  }

  private def filterQueries(
                             origQueries: Seq[String],
                             queryFilter: Set[String],
                             nameSuffix: String = ""): Seq[String] = {
    if (queryFilter.nonEmpty) {
      if (nameSuffix.nonEmpty) {
        origQueries.filter { name => queryFilter.contains(s"$name$nameSuffix") }
      } else {
        origQueries.filter(queryFilter.contains)
      }
    } else {
      origQueries
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val benchmarkArgs = new TPCDSQueryBenchmarkArguments(mainArgs)

    if (benchmarkArgs.iceberg) {
      spark.conf.set("spark.sql.catalog.hadoop_catalog.warehouse", benchmarkArgs.dataLocation)
      spark.conf.set("spark.sql.defaultCatalog", "hadoop_catalog")
    }

    // set database
    spark.conf.set("spark.app.iceberg.database", benchmarkArgs.icebergDatabase)

    // List of all TPC-DS v1.4 queries
    val tpcdsQueries = Seq(
      "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10", "q11",
      "q12", "q13", "q14a", "q14b", "q15", "q16", "q17", "q18", "q19", "q20",
      "q21", "q22", "q23a", "q23b", "q24a", "q24b", "q25", "q26", "q27", "q28", "q29", "q30",
      "q31", "q32", "q33", "q34", "q35", "q36", "q37", "q38", "q39a", "q39b", "q40",
      "q41", "q42", "q43", "q44", "q45", "q46", "q47", "q48", "q49", "q50",
      "q51", "q52", "q53", "q54", "q55", "q56", "q57", "q58", "q59", "q60",
      "q61", "q62", "q63", "q64", "q65", "q66", "q67", "q68", "q69", "q70",
      "q71", "q72", "q73", "q74", "q75", "q76", "q77", "q78", "q79", "q80",
      "q81", "q82", "q83", "q84", "q85", "q86", "q87", "q88", "q89", "q90",
      "q91", "q92", "q93", "q94", "q95", "q96", "q97", "q98", "q99")

    // This list only includes TPC-DS v2.7 queries that are different from v1.4 ones
    val nameSuffixForQueriesV2_7 = "-v2.7"
    val tpcdsQueriesV2_7 = Seq(
      "q5a", "q6", "q10a", "q11", "q12", "q14", "q14a", "q18a",
      "q20", "q22", "q22a", "q24", "q27a", "q34", "q35", "q35a", "q36a", "q47", "q49",
      "q51a", "q57", "q64", "q67a", "q70a", "q72", "q74", "q75", "q77a", "q78",
      "q80a", "q86a", "q98")

    // If `--query-filter` defined, filters the queries that this option selects
    val queriesV1_4ToRun = filterQueries(tpcdsQueries, benchmarkArgs.queryFilter)
    val queriesV2_7ToRun = filterQueries(tpcdsQueriesV2_7, benchmarkArgs.queryFilter,
      nameSuffix = nameSuffixForQueriesV2_7)

    if ((queriesV1_4ToRun ++ queriesV2_7ToRun).isEmpty) {
      throw new RuntimeException(
        s"Empty queries to run. Bad query name filter: ${benchmarkArgs.queryFilter}")
    }

    val tableSizes = setupTables(benchmarkArgs.dataLocation, benchmarkArgs.iceberg,
      createTempView = !benchmarkArgs.cboEnabled)
    if (benchmarkArgs.cboEnabled) {
      spark.sql(s"SET ${SQLConf.CBO_ENABLED.key}=true")
      spark.sql(s"SET ${SQLConf.PLAN_STATS_ENABLED.key}=true")
      spark.sql(s"SET ${SQLConf.JOIN_REORDER_ENABLED.key}=true")
      spark.sql(s"SET ${SQLConf.HISTOGRAM_ENABLED.key}=true")

      // Analyze all the tables before running TPCDS queries
      val startTime = System.nanoTime()
      tables.foreach { tableName =>
        spark.sql(s"ANALYZE TABLE $tableName COMPUTE STATISTICS FOR ALL COLUMNS")
      }
      logInfo("The elapsed time to analyze all the tables is " +
        s"${(System.nanoTime() - startTime) / NANOS_PER_SECOND.toDouble} seconds")
    } else {
      spark.sql(s"SET ${SQLConf.CBO_ENABLED.key}=false")
    }

    runTpcdsQueries(queryLocation = "tpcds", queries = queriesV1_4ToRun, tableSizes)
    runTpcdsQueries(queryLocation = "tpcds-v2.7.0", queries = queriesV2_7ToRun, tableSizes,
      nameSuffix = nameSuffixForQueriesV2_7)
  }
}
