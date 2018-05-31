
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object TestIndex {
  def time(spark: SparkSession, sqltext: String, withIndex: Boolean): Unit = {
    val start = System.currentTimeMillis()
    // scalastyle:off println
    spark.sql(sqltext).collect().foreach(println)
    val end = System.currentTimeMillis()

    println(s"with index:$withIndex,use time:" + (end - start) + "ms")
    // scalastyle:on println
  }

  def main(args: Array[String]): Unit = {

    //      val spark = SparkSession.builder().master("local").getOrCreate()
    val conf = new SparkConf().setAppName("WordCount").setMaster("local")
    //      val sc = new SparkContext(conf)
//    conf.set("spark.files", "file:///data/oap/oap-0.3.0-SNAPSHOT.jar")
//      //        .set("spark.executor.extraClassPath", "./oap-0.3.0-SNAPSHOT.jar")
//      //        .set("spark.driver.extraClassPath", "/data/IDEA-Projects/OAP-master/target/oap-0.3.0-SNAPSHOT.jar")
//      .set("spark.memory.offHeap.enabled", "true")
//      .set("spark.memory.offHeap.size", "200m")

    val spark = SparkSession.builder().config(conf).getOrCreate()
    import spark.implicits._
    spark.sql(s"""CREATE TEMPORARY TABLE parquet_test (a INT, b STRING)
                 | USING parquet
                 | OPTIONS (path 'hdfs://master:9000/data/temp/')""".stripMargin)
    val data = (1 to 300).map { i => (i, s"this is test $i") }.toDF()
      .createOrReplaceTempView("t")
    spark.sql("insert overwrite table parquet_test select * from t")
    spark.sql("create oindex index1 on parquet_test (a)")
    spark.sql("show oindex from parquet_test").show()
    spark.sql("SELECT * FROM parquet_test where a>70").show()
  }


}
