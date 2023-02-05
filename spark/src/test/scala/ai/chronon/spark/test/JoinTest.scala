package ai.chronon.spark.test

import ai.chronon.aggregator.test.Column
import ai.chronon.api
import ai.chronon.api.{Accuracy, Builders, Constants, LongType, Operation, StringType, TimeUnit, Window}
import ai.chronon.api.Extensions._
import ai.chronon.spark.Extensions._
import ai.chronon.spark.GroupBy.renderDataSourceQuery
import ai.chronon.spark._
import ai.chronon.spark.stats.SummaryJob
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructType, StringType => SparkStringType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.junit.Assert._
import org.junit.Test

import scala.collection.JavaConverters._

class JoinTest {

  val spark: SparkSession = SparkSessionBuilder.build("JoinTest", local = true)

  private val today = Constants.Partition.at(System.currentTimeMillis())
  private val monthAgo = Constants.Partition.minus(today, new Window(30, TimeUnit.DAYS))
  private val yearAgo = Constants.Partition.minus(today, new Window(365, TimeUnit.DAYS))
  private val dayAndMonthBefore = Constants.Partition.before(monthAgo)

  private val namespace = "test_namespace_jointest"
  spark.sql(s"CREATE DATABASE IF NOT EXISTS $namespace")

  private val tableUtils = TableUtils(spark)

  @Test
  def testEventsEntitiesSnapshot(): Unit = {
    val dollarTransactions = List(
      Column("user", StringType, 100),
      Column("ts", LongType, 200),
      Column("amount_dollars", LongType, 1000)
    )

    val rupeeTransactions = List(
      Column("user", StringType, 100),
      Column("ts", LongType, 200),
      Column("amount_rupees", LongType, 70000)
    )

    val dollarTable = s"$namespace.dollar_transactions"
    val rupeeTable = s"$namespace.rupee_transactions"
    spark.sql(s"DROP TABLE IF EXISTS $dollarTable")
    spark.sql(s"DROP TABLE IF EXISTS $rupeeTable")
    DataFrameGen.entities(spark, dollarTransactions, 30000, partitions = 200).save(dollarTable, Map("tblProp1" -> "1"))
    DataFrameGen.entities(spark, rupeeTransactions, 4500, partitions = 30).save(rupeeTable)

    val dollarSource = Builders.Source.entities(
      query = Builders.Query(
        selects = Builders.Selects("ts", "amount_dollars"),
        startPartition = yearAgo,
        endPartition = dayAndMonthBefore,
        setups =
          Seq("create temporary function temp_replace_right_a as 'org.apache.hadoop.hive.ql.udf.UDFRegExpReplace'")
      ),
      snapshotTable = dollarTable
    )

    //println("Rupee Source start partition $month")
    val rupeeSource =
      Builders.Source.entities(
        query = Builders.Query(
          selects = Map("ts" -> "ts", "amount_dollars" -> "CAST(amount_rupees/70 as long)"),
          startPartition = monthAgo,
          setups = Seq(
            "create temporary function temp_replace_right_b as 'org.apache.hadoop.hive.ql.udf.UDFRegExpReplace'",
            "create temporary function temp_replace_right_c as 'org.apache.hadoop.hive.ql.udf.UDFRegExpReplace'",
            "create temporary function temp_replace_right_c as 'org.apache.hadoop.hive.ql.udf.UDFRegExpReplace'"
          )
        ),
        snapshotTable = rupeeTable
      )

    val groupBy = Builders.GroupBy(
      sources = Seq(dollarSource, rupeeSource),
      keyColumns = Seq("user"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.SUM,
                             inputColumn = "amount_dollars",
                             windows = Seq(new Window(30, TimeUnit.DAYS)))),
      metaData = Builders.MetaData(name = "unit_test.user_transactions", namespace = namespace, team = "chronon")
    )
    val queriesSchema = List(
      Column("user", api.StringType, 100)
    )

    val queryTable = s"$namespace.queries"
    DataFrameGen
      .events(spark, queriesSchema, 3000, partitions = 180)
      .withColumnRenamed("user", "user_name") // to test chronon renaming logic
      .save(queryTable)

    val start = Constants.Partition.minus(today, new Window(60, TimeUnit.DAYS))
    val end = Constants.Partition.minus(today, new Window(30, TimeUnit.DAYS))
    val joinConf = Builders.Join(
      left = Builders.Source.events(
        query = Builders.Query(
          startPartition = start,
          setups = Seq(
            "create temporary function temp_replace_left as 'org.apache.hadoop.hive.ql.udf.UDFRegExpReplace'",
            "create temporary function temp_replace_right_c as 'org.apache.hadoop.hive.ql.udf.UDFRegExpReplace'"
          )
        ),
        table = queryTable
      ),
      joinParts = Seq(Builders.JoinPart(groupBy = groupBy, keyMapping = Map("user_name" -> "user"))),
      metaData = Builders.MetaData(name = "test.user_transaction_features", namespace = namespace, team = "chronon")
    )

    val runner1 = new Join(joinConf, Constants.Partition.minus(today, new Window(40, TimeUnit.DAYS)), tableUtils)
    runner1.computeJoin()
    val dropStart = Constants.Partition.minus(today, new Window(55, TimeUnit.DAYS))
    val dropEnd = Constants.Partition.minus(today, new Window(45, TimeUnit.DAYS))
    tableUtils.dropPartitionRange(
      s"$namespace.test_user_transaction_features",
      dropStart,
      dropEnd
    )
    println(tableUtils.partitions(s"$namespace.test_user_transaction_features"))

    joinConf.joinParts.asScala
      .map(jp => joinConf.partOutputTable(jp))
      .foreach(tableUtils.dropPartitionRange(_, dropStart, dropEnd))

    def resetUDFs(): Unit = {
      Seq("temp_replace_left", "temp_replace_right_a", "temp_replace_right_b", "temp_replace_right_c")
        .foreach(function => tableUtils.sql(s"DROP TEMPORARY FUNCTION IF EXISTS $function"))
    }

    resetUDFs()
    val runner2 = new Join(joinConf, end, tableUtils)
    val computed = runner2.computeJoin(Some(3))
    println(s"join start = $start")

    val expectedQuery = s"""
        |WITH
        |   queries AS (
        |     SELECT user_name,
        |         ts,
        |         ds
        |     from $queryTable
        |     where user_name IS NOT null
        |         AND ts IS NOT NULL
        |         AND ds IS NOT NULL
        |         AND ds >= '$start'
        |         and ds <= '$end'),
        |   grouped_transactions AS (
        |      SELECT user,
        |             ds,
        |             SUM(IF(transactions.ts  >= (unix_timestamp(transactions.ds, 'yyyy-MM-dd') - (86400*(30-1))) * 1000, amount_dollars, null)) AS unit_test_user_transactions_amount_dollars_sum_30d,
        |             SUM(amount_dollars) AS amount_dollars_sum
        |      FROM
        |         (SELECT user, ts, ds, CAST(amount_rupees/70 as long) as amount_dollars from $rupeeTable
        |          WHERE ds >= '$monthAgo'
        |          UNION
        |          SELECT user, ts, ds, amount_dollars from $dollarTable
        |          WHERE ds >= '$yearAgo' and ds <= '$dayAndMonthBefore') as transactions
        |      WHERE unix_timestamp(ds, 'yyyy-MM-dd')*1000 + 86400*1000> ts
        |        AND user IS NOT NULL
        |        AND ds IS NOT NULL
        |      GROUP BY user, ds)
        | SELECT queries.user_name,
        |        queries.ts,
        |        queries.ds,
        |        grouped_transactions.unit_test_user_transactions_amount_dollars_sum_30d
        | FROM queries left outer join grouped_transactions
        | ON queries.user_name = grouped_transactions.user
        | AND from_unixtime(queries.ts/1000, 'yyyy-MM-dd') = date_add(grouped_transactions.ds, 1)
        | WHERE queries.user_name IS NOT NULL
        |""".stripMargin
    val expected = spark.sql(expectedQuery)
    val queries = tableUtils.sql(
      s"SELECT user_name, ts, ds from $queryTable where user_name IS NOT null AND ts IS NOT NULL AND ds IS NOT NULL AND ds >= '$start'")
    val diff = Comparison.sideBySide(computed, expected, List("user_name", "ts", "ds"))

    if (diff.count() > 0) {
      println(s"Actual count: ${computed.count()}")
      println(s"Expected count: ${expected.count()}")
      println(s"Diff count: ${diff.count()}")
      println(s"Queries count: ${queries.count()}")
      println(s"diff result rows")
      diff.show()
    }
    assertEquals(0, diff.count())

    // test join part table hole detection:
    // in events<>entities<>snapshot case, join part table partitions and left partitions are offset by 1
    //
    // drop only end-1 from join output table, and only end-2 from join part table. trying to trick the job into
    // thinking that end-1 is already computed for the join part, since end-1 already exists in join part table
    val endMinus1 = Constants.Partition.minus(end, new Window(1, TimeUnit.DAYS))
    val endMinus2 = Constants.Partition.minus(end, new Window(2, TimeUnit.DAYS))

    tableUtils.dropPartitionRange(s"$namespace.test_user_transaction_features", endMinus1, endMinus1)
    println(tableUtils.partitions(s"$namespace.test_user_transaction_features"))

    joinConf.joinParts.asScala
      .map(jp => joinConf.partOutputTable(jp))
      .foreach(tableUtils.dropPartitionRange(_, endMinus2, endMinus2))

    resetUDFs()
    val runner3 = new Join(joinConf, end, tableUtils)

    val expected2 = spark.sql(expectedQuery)
    val computed2 = runner3.computeJoin(Some(3))
    val diff2 = Comparison.sideBySide(computed2, expected2, List("user_name", "ts", "ds"))

    if (diff2.count() > 0) {
      println(s"Actual count: ${computed2.count()}")
      println(s"Expected count: ${expected2.count()}")
      println(s"Diff count: ${diff2.count()}")
      println(s"Queries count: ${queries.count()}")
      println(s"diff result rows")
      diff2.show()
    }
    assertEquals(0, diff2.count())
  }

  @Test
  def testEntitiesEntities(): Unit = {
    // untimed/unwindowed entities on right
    // right side
    val weightSchema = List(
      Column("user", api.StringType, 1000),
      Column("country", api.StringType, 100),
      Column("weight", api.DoubleType, 500)
    )
    val weightTable = s"$namespace.weights"
    DataFrameGen.entities(spark, weightSchema, 1000, partitions = 400).save(weightTable)

    val weightSource = Builders.Source.entities(
      query = Builders.Query(selects = Builders.Selects("weight"),
                             startPartition = yearAgo,
                             endPartition = dayAndMonthBefore),
      snapshotTable = weightTable
    )

    val weightGroupBy = Builders.GroupBy(
      sources = Seq(weightSource),
      keyColumns = Seq("country"),
      aggregations = Seq(Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "weight")),
      metaData = Builders.MetaData(name = "unit_test.country_weights", namespace = namespace)
    )

    val heightSchema = List(
      Column("user", api.StringType, 1000),
      Column("country", api.StringType, 100),
      Column("height", api.LongType, 200)
    )
    val heightTable = s"$namespace.heights"
    DataFrameGen.entities(spark, heightSchema, 1000, partitions = 400).save(heightTable)
    val heightSource = Builders.Source.entities(
      query = Builders.Query(selects = Builders.Selects("height"), startPartition = monthAgo),
      snapshotTable = heightTable
    )

    val heightGroupBy = Builders.GroupBy(
      sources = Seq(heightSource),
      keyColumns = Seq("country"),
      aggregations = Seq(Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "height")),
      metaData = Builders.MetaData(name = "unit_test.country_heights", namespace = namespace)
    )

    // left side
    val countrySchema = List(Column("country", api.StringType, 100))
    val countryTable = s"$namespace.countries"
    DataFrameGen.entities(spark, countrySchema, 1000, partitions = 400).save(countryTable)

    val start = Constants.Partition.minus(today, new Window(60, TimeUnit.DAYS))
    val end = Constants.Partition.minus(today, new Window(15, TimeUnit.DAYS))
    val joinConf = Builders.Join(
      left = Builders.Source.entities(Builders.Query(startPartition = start), snapshotTable = countryTable),
      joinParts = Seq(Builders.JoinPart(groupBy = weightGroupBy), Builders.JoinPart(groupBy = heightGroupBy)),
      metaData = Builders.MetaData(name = "test.country_features", namespace = namespace, team = "chronon")
    )

    val runner = new Join(joinConf, end, tableUtils)
    val computed = runner.computeJoin(Some(7))
    val expected = tableUtils.sql(s"""
    |WITH
    |   countries AS (SELECT country, ds from $countryTable where ds >= '$start' and ds <= '$end'),
    |   grouped_weights AS (
    |      SELECT country,
    |             ds,
    |             avg(weight) as unit_test_country_weights_weight_average
    |      FROM $weightTable
    |      WHERE ds >= '$yearAgo' and ds <= '$dayAndMonthBefore'
    |      GROUP BY country, ds),
    |   grouped_heights AS (
    |      SELECT country,
    |             ds,
    |             avg(height) as unit_test_country_heights_height_average
    |      FROM $heightTable
    |      WHERE ds >= '$monthAgo'
    |      GROUP BY country, ds)
    |   SELECT countries.country,
    |        countries.ds,
    |        grouped_weights.unit_test_country_weights_weight_average,
    |        grouped_heights.unit_test_country_heights_height_average
    | FROM countries left outer join grouped_weights
    | ON countries.country = grouped_weights.country
    | AND countries.ds = grouped_weights.ds
    | left outer join grouped_heights
    | ON countries.ds = grouped_heights.ds
    | AND countries.country = grouped_heights.country
    """.stripMargin)

    println("showing join result")
    computed.show()
    println("showing query result")
    expected.show()
    println(
      s"Left side count: ${spark.sql(s"SELECT country, ds from $countryTable where ds >= '$start' and ds <= '$end'").count()}")
    println(s"Actual count: ${computed.count()}")
    println(s"Expected count: ${expected.count()}")
    val diff = Comparison.sideBySide(computed, expected, List("country", "ds"))
    if (diff.count() > 0) {
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff.show()
    }
    assertEquals(diff.count(), 0)
    /* the below testing case to cover the scenario when input table and output table
     * have same partitions, in other words, the frontfill is done, the join job
     * should not trigger a backfill and exit the program properly
     */

    // use console to redirect println message to Java IO
    val stream = new java.io.ByteArrayOutputStream()
    Console.withOut(stream) {
      // rerun the same join job
      runner.computeJoin(Some(7))
    }
    val stdOutMsg = stream.toString()
    println(s"std out message =\n $stdOutMsg")
    // make sure that the program exits with target print statements
    assertTrue(stdOutMsg.contains(s"There is no data to compute based on end partition of $end."))
  }

  @Test
  def testEventsEventsSnapshot(): Unit = {
    val viewsSchema = List(
      Column("user", api.StringType, 10000),
      Column("item", api.StringType, 100),
      Column("time_spent_ms", api.LongType, 5000)
    )

    val viewsTable = s"$namespace.view_events"
    DataFrameGen.events(spark, viewsSchema, count = 1000, partitions = 200).drop("ts").save(viewsTable)

    val viewsSource = Builders.Source.events(
      query = Builders.Query(selects = Builders.Selects("time_spent_ms"), startPartition = yearAgo),
      table = viewsTable
    )

    val viewsGroupBy = Builders.GroupBy(
      sources = Seq(viewsSource),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "time_spent_ms")
      ),
      metaData = Builders.MetaData(name = "unit_test.item_views", namespace = namespace),
      accuracy = Accuracy.SNAPSHOT
    )

    // left side
    val itemQueries = List(Column("item", api.StringType, 100))
    val itemQueriesTable = s"$namespace.item_queries"
    DataFrameGen
      .events(spark, itemQueries, 1000, partitions = 100)
      .save(itemQueriesTable)

    val start = Constants.Partition.minus(today, new Window(100, TimeUnit.DAYS))

    val joinConf = Builders.Join(
      left = Builders.Source.events(Builders.Query(startPartition = start), table = itemQueriesTable),
      joinParts = Seq(Builders.JoinPart(groupBy = viewsGroupBy, prefix = "user")),
      metaData = Builders.MetaData(name = "test.item_snapshot_features_2", namespace = namespace, team = "chronon")
    )

    (new Analyzer(tableUtils, joinConf, monthAgo, today)).run()
    val join = new Join(joinConf = joinConf, endPartition = monthAgo, tableUtils)
    val computed = join.computeJoin()
    computed.show()

    val expected = tableUtils.sql(s"""
                                |WITH
                                |   queries AS (SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$monthAgo')
                                | SELECT queries.item,
                                |        queries.ts,
                                |        queries.ds,
                                |        AVG(IF(queries.ds > $viewsTable.ds, time_spent_ms, null)) as user_unit_test_item_views_time_spent_ms_average
                                | FROM queries left outer join $viewsTable
                                |  ON queries.item = $viewsTable.item
                                | WHERE ($viewsTable.item IS NOT NULL) AND $viewsTable.ds >= '$yearAgo' AND $viewsTable.ds <= '$dayAndMonthBefore'
                                | GROUP BY queries.item, queries.ts, queries.ds, from_unixtime(queries.ts/1000, 'yyyy-MM-dd')
                                |""".stripMargin)
    expected.show()

    val diff = Comparison.sideBySide(computed, expected, List("item", "ts", "ds"))

    if (diff.count() > 0) {
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff.show()
    }
    assertEquals(diff.count(), 0)
  }
  @Test
  def testJoinAnalyzerSchema(): Unit = {
    val viewsSource = genTestEventSource

    val viewsGroupBy = Builders.GroupBy(
      sources = Seq(viewsSource),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "time_spent_ms")
      ),
      metaData = Builders.MetaData(name = "join_analyzer_test.item_views", namespace = namespace),
      accuracy = Accuracy.SNAPSHOT
    )
    val anotherViewsGroupBy = Builders.GroupBy(
      sources = Seq(viewsSource),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.SUM, inputColumn = "time_spent_ms")
      ),
      metaData = Builders.MetaData(name = "join_analyzer_test.new_item_views", namespace = namespace),
      accuracy = Accuracy.SNAPSHOT
    )

    // left side
    val itemQueries = List(Column("item", api.StringType, 100))
    val itemQueriesTable = s"$namespace.item_queries"
    DataFrameGen
      .events(spark, itemQueries, 1000, partitions = 100)
      .save(itemQueriesTable)

    val start = Constants.Partition.minus(today, new Window(100, TimeUnit.DAYS))

    val joinConf = Builders.Join(
      left = Builders.Source.events(Builders.Query(startPartition = start), table = itemQueriesTable),
      joinParts = Seq(
        Builders.JoinPart(groupBy = viewsGroupBy, prefix = "prefix_one"),
        Builders.JoinPart(groupBy = anotherViewsGroupBy, prefix = "prefix_two")
      ),
      metaData =
        Builders.MetaData(name = "test_join_analyzer.item_snapshot_features", namespace = namespace, team = "chronon")
    )

    //run analyzer and validate output schema
    val analyzer = new Analyzer(tableUtils, joinConf, monthAgo, today, enableHitter = true)
    val analyzerSchema = analyzer.analyzeJoin(joinConf)._1.map(_.trim).sorted
    val join = new Join(joinConf = joinConf, endPartition = monthAgo, tableUtils)
    val computed = join.computeJoin()
    val expectedSchema = computed.schema.fields.map(field => s"${field.name} => ${field.dataType}").sorted
    println("=== expected schema =====")
    println(expectedSchema.mkString("\n"))

    assertTrue(expectedSchema sameElements analyzerSchema)
  }
  @Test
  def testEventsEventsTemporal(): Unit = {

    val joinConf = getEventsEventsTemporal("temporal")
    val viewsSchema = List(
      Column("user", api.StringType, 10000),
      Column("item", api.StringType, 100),
      Column("time_spent_ms", api.LongType, 5000)
    )

    val viewsTable = s"$namespace.view_temporal"
    DataFrameGen.events(spark, viewsSchema, count = 10000, partitions = 200).save(viewsTable, Map("tblProp1" -> "1"))

    val viewsSource = Builders.Source.events(
      table = viewsTable,
      query = Builders.Query(selects = Builders.Selects("time_spent_ms"), startPartition = yearAgo)
    )
    val viewsGroupBy = Builders.GroupBy(
      sources = Seq(viewsSource),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "time_spent_ms"),
        Builders.Aggregation(operation = Operation.MIN, inputColumn = "ts"),
        Builders.Aggregation(operation = Operation.MAX, inputColumn = "ts")
        // Builders.Aggregation(operation = Operation.APPROX_UNIQUE_COUNT, inputColumn = "ts")
        // sql - APPROX_COUNT_DISTINCT(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_ts_approx_unique_count
      ),
      metaData = Builders.MetaData(name = "unit_test.item_views", namespace = namespace)
    )

    // left side
    val itemQueries = List(Column("item", api.StringType, 100))
    val itemQueriesTable = s"$namespace.item_queries"
    val itemQueriesDf = DataFrameGen
      .events(spark, itemQueries, 10000, partitions = 100)
    // duplicate the events
    itemQueriesDf.union(itemQueriesDf).save(itemQueriesTable) //.union(itemQueriesDf)

    val start = Constants.Partition.minus(today, new Window(100, TimeUnit.DAYS))
    (new Analyzer(tableUtils, joinConf, monthAgo, today)).run()
    val join = new Join(joinConf = joinConf, endPartition = dayAndMonthBefore, tableUtils)
    val computed = join.computeJoin(Some(100))
    computed.show()

    val expected = tableUtils.sql(s"""
                                     |WITH
                                     |   queries AS (SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$dayAndMonthBefore')
                                     | SELECT queries.item, queries.ts, queries.ds, part.user_unit_test_item_views_ts_min, part.user_unit_test_item_views_ts_max, part.user_unit_test_item_views_time_spent_ms_average
                                     | FROM (SELECT queries.item,
                                     |        queries.ts,
                                     |        queries.ds,
                                     |        MIN(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as user_unit_test_item_views_ts_min,
                                     |        MAX(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as user_unit_test_item_views_ts_max,
                                     |        AVG(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_unit_test_item_views_time_spent_ms_average
                                     |     FROM queries left outer join $viewsTable
                                     |     ON queries.item = $viewsTable.item
                                     |     WHERE $viewsTable.item IS NOT NULL AND $viewsTable.ds >= '$yearAgo' AND $viewsTable.ds <= '$dayAndMonthBefore'
                                     |     GROUP BY queries.item, queries.ts, queries.ds) as part
                                     | JOIN queries
                                     | ON queries.item <=> part.item AND queries.ts <=> part.ts AND queries.ds <=> part.ds
                                     |""".stripMargin)
    expected.show()

    val diff = Comparison.sideBySide(computed, expected, List("item", "ts", "ds"))
    val queriesBare =
      tableUtils.sql(s"SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$dayAndMonthBefore'")
    assertEquals(queriesBare.count(), computed.count())
    if (diff.count() > 0) {
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff
        .replaceWithReadableTime(Seq("ts", "a_user_unit_test_item_views_ts_max", "b_user_unit_test_item_views_ts_max"),
                                 dropOriginal = true)
        .show()
    }
    assertEquals(diff.count(), 0)
  }

  @Test
  def testEventsEventsCumulative(): Unit = {
    // Create a cumulative source GroupBy
    val viewsTable = s"$namespace.view_cumulative"
    val viewsGroupBy = getViewsGroupBy(suffix = "cumulative", makeCumulative = true)
    // Copy and modify existing events/events case to use cumulative GroupBy
    val joinConf = getEventsEventsTemporal("cumulative")
    joinConf.setJoinParts(Seq(Builders.JoinPart(groupBy = viewsGroupBy)).asJava)

    // Run job
    val itemQueriesTable = s"$namespace.item_queries"
    println("Item Queries DF: ")
    val q =
      s"""
        |SELECT
        |  `ts`,
        |  `ds`,
        |  `item`,
        |  time_spent_ms as `time_spent_ms`
        |FROM $viewsTable
        |WHERE
        |  ds >= '2021-06-03' AND ds <= '2021-06-03'""".stripMargin
    spark.sql(q).show()
    val start = Constants.Partition.minus(today, new Window(100, TimeUnit.DAYS))
    val join = new Join(joinConf = joinConf, endPartition = dayAndMonthBefore, tableUtils)
    val computed = join.computeJoin(Some(100))
    computed.show()

    val expected = tableUtils.sql(s"""
                                     |WITH
                                     |   queries AS (SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$dayAndMonthBefore')
                                     | SELECT queries.item, queries.ts, queries.ds, part.unit_test_item_views_ts_min, part.unit_test_item_views_ts_max, part.unit_test_item_views_time_spent_ms_average
                                     | FROM (SELECT queries.item,
                                     |        queries.ts,
                                     |        queries.ds,
                                     |        MIN(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as unit_test_item_views_ts_min,
                                     |        MAX(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as unit_test_item_views_ts_max,
                                     |        AVG(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as unit_test_item_views_time_spent_ms_average
                                     |     FROM queries left outer join $viewsTable
                                     |     ON queries.item = $viewsTable.item
                                     |     WHERE $viewsTable.item IS NOT NULL AND $viewsTable.ds = '$today'
                                     |     GROUP BY queries.item, queries.ts, queries.ds) as part
                                     | JOIN queries
                                     | ON queries.item <=> part.item AND queries.ts <=> part.ts AND queries.ds <=> part.ds
                                     |""".stripMargin)
    expected.show()

    val diff = Comparison.sideBySide(computed, expected, List("item", "ts", "ds"))
    val queriesBare =
      tableUtils.sql(s"SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$dayAndMonthBefore'")
    assertEquals(queriesBare.count(), computed.count())
    if (diff.count() > 0) {
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff.show()
    }
    assertEquals(diff.count(), 0)
  }

  def getGroupByForIncrementalSourceTest() = {
    val messageData = Seq(
      Row("Hello", "a", "2021-01-01"),
      Row("World", "a", "2021-01-02"),
      Row("Hello!", "b", "2021-01-03")
    )

    val messageSchema: StructType = new StructType()
      .add("message", SparkStringType)
      .add("id", SparkStringType)
      .add("ds", SparkStringType)

    val rdd: RDD[Row] = spark.sparkContext.parallelize(messageData)
    val df: DataFrame = spark.createDataFrame(rdd, messageSchema)

    val table = s"$namespace.messages"

    spark.sql(s"DROP TABLE IF EXISTS $table")

    df.write.partitionBy("ds").saveAsTable(table)

    val source = Builders.Source.events(
      table = table,
      query = Builders.Query(selects = Builders.Selects("message"), startPartition = "2021-01-01")
    )

    Builders.GroupBy(
      sources = Seq(source),
      keyColumns = Seq("id"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.MAX, inputColumn = "message")
        // Builders.Aggregation(operation = Operation.APPROX_UNIQUE_COUNT, inputColumn = "ts")
        // sql - APPROX_COUNT_DISTINCT(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_ts_approx_unique_count
      ),
      metaData = Builders.MetaData(name = "unit_test.messages", namespace = namespace, team = "item_team"),
      accuracy = Accuracy.TEMPORAL
    )

  }

  @Test
  def testSourceQueryRender(): Unit = {
    // Test cumulative
    val viewsGroupByCumulative = getViewsGroupBy(suffix = "render", makeCumulative = true)
    val renderedCumulative = renderDataSourceQuery(
      viewsGroupByCumulative.sources.asScala.head,
      Seq("item"),
      PartitionRange("2021-02-23", "2021-05-03"),
      tableUtils,
      None,
      viewsGroupByCumulative.inferredAccuracy
    )
    // Only checking that the date logic is correct in the query
    assert(renderedCumulative.contains(s"ds >= '${today}' AND ds <= '${today}'"))

    // Test incremental
    val viewsGroupByIncremental = getGroupByForIncrementalSourceTest()
    val renderedIncremental = renderDataSourceQuery(
      viewsGroupByIncremental.sources.asScala.head,
      Seq("item"),
      PartitionRange("2021-01-01", "2021-01-03"),
      tableUtils,
      None,
      viewsGroupByCumulative.inferredAccuracy
    )
    println(renderedIncremental)
    assert(renderedIncremental.contains(s"ds >= '2021-01-01' AND ds <= '2021-01-03'"))
  }

  @Test
  def testNoAgg(): Unit = {
    // Left side entities, right side entities no agg
    // Also testing specific select statement (rather than select *)
    val namesSchema = List(
      Column("user", api.StringType, 1000),
      Column("name", api.StringType, 500)
    )
    val namesTable = s"$namespace.names"
    DataFrameGen.entities(spark, namesSchema, 1000, partitions = 400).save(namesTable)

    val namesSource = Builders.Source.entities(
      query =
        Builders.Query(selects = Builders.Selects("name"), startPartition = yearAgo, endPartition = dayAndMonthBefore),
      snapshotTable = namesTable
    )

    val namesGroupBy = Builders.GroupBy(
      sources = Seq(namesSource),
      keyColumns = Seq("user"),
      aggregations = null,
      metaData = Builders.MetaData(name = "unit_test.user_names", team = "chronon")
    )

    DataFrameGen
      .entities(spark, namesSchema, 1000, partitions = 400)
      .groupBy("user", "ds")
      .agg(Map("name" -> "max"))
      .save(namesTable)

    // left side
    val userSchema = List(Column("user", api.StringType, 100))
    val usersTable = s"$namespace.users"
    DataFrameGen.entities(spark, userSchema, 1000, partitions = 400).dropDuplicates().save(usersTable)

    val start = Constants.Partition.minus(today, new Window(60, TimeUnit.DAYS))
    val end = Constants.Partition.minus(today, new Window(15, TimeUnit.DAYS))
    val joinConf = Builders.Join(
      left = Builders.Source.entities(Builders.Query(selects = Map("user" -> "user"), startPartition = start),
                                      snapshotTable = usersTable),
      joinParts = Seq(Builders.JoinPart(groupBy = namesGroupBy)),
      metaData = Builders.MetaData(name = "test.user_features", namespace = namespace, team = "chronon")
    )

    val runner = new Join(joinConf, end, tableUtils)
    val computed = runner.computeJoin(Some(7))
    println(s"join start = $start")
    val expected = tableUtils.sql(s"""
                                     |WITH
                                     |   users AS (SELECT user, ds from $usersTable where ds >= '$start' and ds <= '$end'),
                                     |   grouped_names AS (
                                     |      SELECT user,
                                     |             name as unit_test_user_names_name,
                                     |             ds
                                     |      FROM $namesTable
                                     |      WHERE ds >= '$yearAgo' and ds <= '$dayAndMonthBefore')
                                     |   SELECT users.user,
                                     |        grouped_names.unit_test_user_names_name,
                                     |        users.ds
                                     | FROM users left outer join grouped_names
                                     | ON users.user = grouped_names.user
                                     | AND users.ds = grouped_names.ds
    """.stripMargin)

    println("showing join result")
    computed.show()
    println("showing query result")
    expected.show()
    println(
      s"Left side count: ${spark.sql(s"SELECT user, ds from $namesTable where ds >= '$start' and ds <= '$end'").count()}")
    println(s"Actual count: ${computed.count()}")
    println(s"Expected count: ${expected.count()}")
    val diff = Comparison.sideBySide(computed, expected, List("user", "ds"))
    if (diff.count() > 0) {
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff.show()
    }
    assertEquals(diff.count(), 0)
  }

  @Test
  def testVersioning(): Unit = {
    val joinConf = getEventsEventsTemporal("versioning")

    // Run the old join to ensure that tables exist
    val oldJoin = new Join(joinConf = joinConf, endPartition = dayAndMonthBefore, tableUtils)
    oldJoin.computeJoin(Some(100))

    // Make sure that there is no versioning-detected changes at this phase
    val joinPartsToRecomputeNoChange = oldJoin.tablesToRecompute()
    assertEquals(joinPartsToRecomputeNoChange.size, 0)

    // First test changing the left side table - this should trigger a full recompute
    val leftChangeJoinConf = joinConf.deepCopy()
    leftChangeJoinConf.getLeft.getEvents.setTable("some_other_table_name")
    val leftChangeJoin = new Join(joinConf = leftChangeJoinConf, endPartition = dayAndMonthBefore, tableUtils)
    val leftChangeRecompute = leftChangeJoin.tablesToRecompute()
    assertEquals(leftChangeRecompute.size, 3)
    val partTable = s"${leftChangeJoinConf.metaData.outputTable}_user_unit_test_item_views"
    assertEquals(leftChangeRecompute,
                 Seq(partTable, leftChangeJoinConf.metaData.bootstrapTable, leftChangeJoinConf.metaData.outputTable))

    // Test adding a joinPart
    val addPartJoinConf = joinConf.deepCopy()
    val existingJoinPart = addPartJoinConf.getJoinParts.get(0)
    val newJoinPart = Builders.JoinPart(groupBy = getViewsGroupBy(suffix = "versioning"), prefix = "user_2")
    addPartJoinConf.setJoinParts(Seq(existingJoinPart, newJoinPart).asJava)
    val addPartJoin = new Join(joinConf = addPartJoinConf, endPartition = dayAndMonthBefore, tableUtils)
    val addPartRecompute = addPartJoin.tablesToRecompute()
    assertEquals(addPartRecompute.size, 1)
    assertEquals(addPartRecompute, Seq(addPartJoinConf.metaData.outputTable))
    // Compute to ensure that it works and to set the stage for the next assertion
    addPartJoin.computeJoin(Some(100))

    // Test modifying only one of two joinParts
    val rightModJoinConf = addPartJoinConf.deepCopy()
    rightModJoinConf.getJoinParts.get(1).setPrefix("user_3")
    val rightModJoin = new Join(joinConf = rightModJoinConf, endPartition = dayAndMonthBefore, tableUtils)
    val rightModRecompute = rightModJoin.tablesToRecompute()
    assertEquals(rightModRecompute.size, 2)
    val rightModPartTable = s"${addPartJoinConf.metaData.outputTable}_user_2_unit_test_item_views"
    assertEquals(rightModRecompute, Seq(rightModPartTable, addPartJoinConf.metaData.outputTable))
    // Modify both
    rightModJoinConf.getJoinParts.get(0).setPrefix("user_4")
    val rightModBothJoin = new Join(joinConf = rightModJoinConf, endPartition = dayAndMonthBefore, tableUtils)
    // Compute to ensure that it works
    val computed = rightModBothJoin.computeJoin(Some(100))

    // Now assert that the actual output is correct after all these runs
    computed.show()
    val itemQueriesTable = joinConf.getLeft.getEvents.table
    val start = joinConf.getLeft.getEvents.getQuery.getStartPartition
    val viewsTable = s"$namespace.view_versioning"

    val expected = tableUtils.sql(s"""
                                     |WITH
                                     |   queries AS (SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$dayAndMonthBefore')
                                     | SELECT queries.item, queries.ts, queries.ds, part.user_4_unit_test_item_views_ts_min, part.user_4_unit_test_item_views_ts_max, part.user_4_unit_test_item_views_time_spent_ms_average, part.user_3_unit_test_item_views_ts_min, part.user_3_unit_test_item_views_ts_max, part.user_3_unit_test_item_views_time_spent_ms_average
                                     | FROM (SELECT queries.item,
                                     |        queries.ts,
                                     |        queries.ds,
                                     |        MIN(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as user_4_unit_test_item_views_ts_min,
                                     |        MAX(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as user_4_unit_test_item_views_ts_max,
                                     |        AVG(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_4_unit_test_item_views_time_spent_ms_average,
                                     |        MIN(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as user_3_unit_test_item_views_ts_min,
                                     |        MAX(IF(queries.ts > $viewsTable.ts, $viewsTable.ts, null)) as user_3_unit_test_item_views_ts_max,
                                     |        AVG(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_3_unit_test_item_views_time_spent_ms_average
                                     |     FROM queries left outer join $viewsTable
                                     |     ON queries.item = $viewsTable.item
                                     |     WHERE $viewsTable.ds >= '$yearAgo' AND $viewsTable.ds <= '$dayAndMonthBefore'
                                     |     GROUP BY queries.item, queries.ts, queries.ds) as part
                                     | JOIN queries
                                     | ON queries.item <=> part.item AND queries.ts <=> part.ts AND queries.ds <=> part.ds
                                     |""".stripMargin)
    expected.show()

    val diff = Comparison.sideBySide(expected, computed, List("item", "ts", "ds"))
    val queriesBare =
      tableUtils.sql(s"SELECT item, ts, ds from $itemQueriesTable where ds >= '$start' and ds <= '$dayAndMonthBefore'")
    assertEquals(queriesBare.count(), computed.count())
    if (diff.count() > 0) {
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff
        .replaceWithReadableTime(
          Seq("ts", "a_user_3_unit_test_item_views_ts_max", "b_user_3_unit_test_item_views_ts_max"),
          dropOriginal = true)
        .show()
    }
    assertEquals(0, diff.count())
  }

  private def getViewsGroupBy(suffix: String, makeCumulative: Boolean = false) = {
    val viewsSchema = List(
      Column("user", api.StringType, 10000),
      Column("item", api.StringType, 100),
      Column("time_spent_ms", api.LongType, 5000)
    )

    val viewsTable = s"$namespace.view_$suffix"
    val df = DataFrameGen.events(spark, viewsSchema, count = 10000, partitions = 200)

    val viewsSource = Builders.Source.events(
      table = viewsTable,
      query = Builders.Query(selects = Builders.Selects("time_spent_ms"), startPartition = yearAgo),
      isCumulative = makeCumulative
    )

    val dfToWrite = if (makeCumulative) {
      // Move all events into latest partition and set isCumulative on thrift object
      df.drop("ds").withColumn("ds", lit(today))
    } else { df }

    spark.sql(s"DROP TABLE IF EXISTS $viewsTable")
    dfToWrite.save(viewsTable, Map("tblProp1" -> "1"))

    Builders.GroupBy(
      sources = Seq(viewsSource),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "time_spent_ms"),
        Builders.Aggregation(operation = Operation.MIN, inputColumn = "ts"),
        Builders.Aggregation(operation = Operation.MAX, inputColumn = "ts")
        // Builders.Aggregation(operation = Operation.APPROX_UNIQUE_COUNT, inputColumn = "ts")
        // sql - APPROX_COUNT_DISTINCT(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_ts_approx_unique_count
      ),
      metaData = Builders.MetaData(name = "unit_test.item_views", namespace = namespace, team = "item_team"),
      accuracy = Accuracy.TEMPORAL
    )
  }

  private def getEventsEventsTemporal(nameSuffix: String = "") = {
    // left side
    val itemQueries = List(Column("item", api.StringType, 100))
    val itemQueriesTable = s"$namespace.item_queries"
    val itemQueriesDf = DataFrameGen
      .events(spark, itemQueries, 10000, partitions = 100)
    // duplicate the events
    itemQueriesDf.union(itemQueriesDf).save(itemQueriesTable) //.union(itemQueriesDf)

    val start = Constants.Partition.minus(today, new Window(100, TimeUnit.DAYS))
    val suffix = if (nameSuffix.isEmpty) "" else s"_$nameSuffix"
    Builders.Join(
      left = Builders.Source.events(Builders.Query(startPartition = start), table = itemQueriesTable),
      joinParts = Seq(Builders.JoinPart(groupBy = getViewsGroupBy(nameSuffix), prefix = "user")),
      metaData =
        Builders.MetaData(name = s"test.item_temporal_features${suffix}", namespace = namespace, team = "item_team")
    )

  }

  @Test
  def testEndPartitionJoin(): Unit = {
    val join = getEventsEventsTemporal("end_partition_test")
    val start = join.getLeft.query.startPartition
    val end = Constants.Partition.after(start)
    val limitedJoin = Builders.Join(
      left =
        Builders.Source.events(Builders.Query(startPartition = start, endPartition = end), table = join.getLeft.table),
      joinParts = join.getJoinParts.asScala,
      metaData = join.metaData
    )
    assertTrue(end < today)
    val toCompute = new Join(limitedJoin, today, tableUtils)
    toCompute.computeJoin()
    val ds = tableUtils.sql(s"SELECT MAX(ds) FROM ${limitedJoin.metaData.outputTable}")
    assertTrue(ds.first().getString(0) < today)
  }

  @Test
  def testStructJoin(): Unit = {
    val nameSuffix = "_struct_test"
    val itemQueries = List(Column("item", api.StringType, 100))
    val itemQueriesTable = s"$namespace.item_queries_$nameSuffix"
    val itemQueriesDf = DataFrameGen
      .events(spark, itemQueries, 10000, partitions = 100)

    itemQueriesDf.save(s"${itemQueriesTable}_tmp")
    val structLeftDf = tableUtils.sql(
      s"SELECT item, NAMED_STRUCT('item_repeat', item) as item_struct, ts, ds FROM ${itemQueriesTable}_tmp")
    structLeftDf.save(itemQueriesTable)
    val start = Constants.Partition.minus(today, new Window(100, TimeUnit.DAYS))

    val viewsSchema = List(
      Column("user", api.StringType, 10000),
      Column("item", api.StringType, 100),
      Column("time_spent_ms", api.LongType, 5000)
    )

    val viewsTable = s"$namespace.view_$nameSuffix"
    val df = DataFrameGen.events(spark, viewsSchema, count = 10000, partitions = 200)

    val viewsSource = Builders.Source.events(
      table = viewsTable,
      query = Builders.Query(selects = Builders.Selects("time_spent_ms", "item_struct"), startPartition = yearAgo)
    )
    spark.sql(s"DROP TABLE IF EXISTS $viewsTable")
    df.save(s"${viewsTable}_tmp", Map("tblProp1" -> "1"))
    val structSource =
      tableUtils.sql(s"SELECT *, NAMED_STRUCT('item_repeat', item) as item_struct FROM ${viewsTable}_tmp")
    structSource.save(viewsTable)
    val gb = Builders.GroupBy(
      sources = Seq(viewsSource),
      keyColumns = Seq("item"),
      aggregations = Seq(
        Builders.Aggregation(operation = Operation.AVERAGE, inputColumn = "time_spent_ms"),
        Builders.Aggregation(operation = Operation.MIN, inputColumn = "ts"),
        Builders.Aggregation(operation = Operation.LAST_K, argMap = Map("k" -> "1"), inputColumn = "item_struct")
        // Builders.Aggregation(operation = Operation.APPROX_UNIQUE_COUNT, inputColumn = "ts")
        // sql - APPROX_COUNT_DISTINCT(IF(queries.ts > $viewsTable.ts, time_spent_ms, null)) as user_ts_approx_unique_count
      ),
      metaData =
        Builders.MetaData(name = s"unit_test.item_views_$nameSuffix", namespace = namespace, team = "item_team"),
      accuracy = Accuracy.TEMPORAL
    )

    val join = Builders.Join(
      left = Builders.Source.events(Builders.Query(startPartition = start), table = itemQueriesTable),
      joinParts = Seq(Builders.JoinPart(groupBy = gb, prefix = "user")),
      metaData =
        Builders.MetaData(name = s"test.item_temporal_features$nameSuffix", namespace = namespace, team = "item_team")
    )
    val toCompute = new Join(join, today, tableUtils)
    toCompute.computeJoin()
    // Add stats
    new SummaryJob(spark, join, today).dailyRun(stepDays = Some(30))
  }

  def genTestEventSource(): api.Source = {
    val viewsSchema = List(
      Column("user", api.StringType, 10000),
      Column("item", api.StringType, 100),
      Column("time_spent_ms", api.LongType, 5000)
    )

    val viewsTable = s"$namespace.view_events"
    DataFrameGen.events(spark, viewsSchema, count = 1000, partitions = 200).drop("ts").save(viewsTable)

    Builders.Source.events(
      query = Builders.Query(selects = Builders.Selects("time_spent_ms"), startPartition = yearAgo),
      table = viewsTable
    )
  }
}
