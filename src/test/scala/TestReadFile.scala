
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

object TestReadFile {
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
    conf.set("spark.files", "file:///data/oap/oap-new.jar")
//      val sc = new SparkContext(conf)
//      conf.set("spark.files", "file:///data/oap/oap-0.3.0-SNAPSHOT.jar")
//        .set("spark.executor.extraClassPath", "./oap-0.3.0-SNAPSHOT.jar")
//        .set("spark.driver.extraClassPath", "/data/IDEA-Projects/OAP-master/target/oap-0.3.0-SNAPSHOT.jar")
        .set("spark.memory.offHeap.enabled", "true")
        .set("spark.memory.offHeap.size", "200m")

    val spark = SparkSession.builder().config(conf).getOrCreate()
//    val spark = SparkSession.builder().enableHiveSupport().config(conf).getOrCreate()
//      spark.sqlContext.conf.setConfString("SQLConf.OAP_EXECUTOR_INDEX_SELECTION.key", "false")
      // For implicit conversions like converting RDDs to DataFrames

      spark.sql(s"""CREATE TEMPORARY VIEW temp (c_netnum int,
                   |c_ip                	bigint,
                   |c_event_id          	bigint,
                   |c_priority          	int,
                   |c_time              	string,
                   |c_flowid            	string,
                   |c_src_ipv4          	bigint,
                   |c_src_ipv6          	string,
                   |c_src_port          	int,
                   |c_s_tunnel_ip       	bigint,
                   |c_s_tunnel_port     	int,
                   |c_dest_ipv4         	bigint,
                   |c_dest_ipv6         	string,
                   |c_dest_port         	int,
                   |c_d_tunnel_ip       	bigint,
                   |c_d_tunnel_port     	int,
                   |c_proto_type        	int,
                   |c_return_info       	string,
                   |c_s_boundary        	bigint,
                   |c_s_region          	bigint,
                   |c_s_city            	bigint,
                   |c_s_district        	bigint,
                   |c_s_operators       	bigint,
                   |c_s_owner           	string,
                   |c_d_boundary        	bigint,
                   |c_d_region          	bigint,
                   |c_d_city            	bigint,
                   |c_d_district        	bigint,
                   |c_d_operators       	bigint,
                   |c_d_owner           	string,
                   |c_ret_file_type     	int,
                   |c_ret_filename      	string,
                   |c_ret_file          	string,
                   |c_url               	string,
                   |c_cookie            	string,
                   |c_time_part string)
                   | USING parquet
                   | OPTIONS (path "hdfs://master:9000/data/oap/")"""
        .stripMargin)

    var start = System.currentTimeMillis()
    val s1 =
      """
        |SELECT count(*) FROM temp where c_ip>440431626 and c_src_ipv4<540431626
      """.stripMargin

    val s2 = """SELECT count(*) FROM temp where c_event_id>30000 and c_event_id<40000"""
    val s3 = "select count(*) from temp"
    spark.sql("drop oindex index1 on temp")
    time(spark, s2, false)
    spark.sql("create oindex index1 on temp (c_event_id)")
    time(spark, s2, true)

  }
}
