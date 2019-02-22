package com.shilin.dataloader

import java.net.InetAddress

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import com.shilin.scala.model._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import com.shilin.java.model.Constant._
import org.elasticsearch.transport.client.PreBuiltTransportClient



object DataLoader {

  //  val MONGODB_MOVIE_COLLECTION = "Movie"
  //  val MONGODB_RATING_COLLECTION = "Rating"
  //  val MONGODB_TAG_COLLECTION = "Tag"

  val ES_MOVIE_INDEX = "Movie"

  //程序的入口
  def main(args: Array[String]): Unit = {

    if (args.length != 7) {
      System.err.println("Usage: java -jar dataloader.jar <mongo_server> <es_http_server> <es_trans_server> <es_cluster_name> <movie_data_path> <rating_data_path> <tag_data_path>\n"
        + "   <mongo_server> \n"
        + "   <es_http_server> \n"
        + "   <es_trans_server> \n"
        + "   <to> \n\n")
      System.exit(1)


    }
    val mongo_server = args(0)
    val es_http_server = args(1)
    val es_trans_server = args(2)
    val es_cluster_name = args(3)

    val movie_data_path = args(4)
    val rating_data_path = args(5)
    val tag_data_path = args(6)



    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> ("mongodb://bigdata:27017/" + MONGO_DATABASE),
      "mongo.db" -> "recommender",
      "es.httpHosts" -> "bigdata:9200",
      "es.transportHosts" -> "bigdata:9300",
      "es.index" -> ES_INDEX,
      "es.cluster.name" -> "es-cluster"

    )

    val sparkConf = new SparkConf().setAppName("DataLoader").setMaster(config.get("spark.cores").get)


    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    import spark.implicits._
    val movieRDD = spark.sparkContext.textFile(movie_data_path)
    val movieDF = movieRDD.map(item => {
      val attr = item.split("\\^")
      Movie(attr(0).toInt, attr(1).trim, attr(2).trim, attr(3).trim, attr(4).trim, attr(5).trim, attr(6).trim, attr(7).trim, attr(8).trim, attr(9).trim)
    }).toDF()
    val ratingRDD = spark.sparkContext.textFile(rating_data_path)
    val ratingDF = ratingRDD.map(item => {
      val attr = item.split(",")
      MovieRating(attr(0).toInt, attr(1).toInt, attr(2).toDouble, attr(3).toInt)
    }).toDF()

    val tagRDD = spark.sparkContext.textFile(tag_data_path)
    val tagDF = tagRDD.map(item => {
      val attr = item.split(",")
      Tag(attr(0).toInt, attr(1).toInt, attr(2).trim, attr(3).toInt)
    }).toDF()

    implicit val mongoConfig = MongoConfig(config.get("mongo.uri").get, config.get("mongo.db").get)

    // 需要将数据保存到MongoDB中
    //storeDataInMongoDB(movieDF, ratingDF, tagDF)


    /**
      * Movie数据集，数据集字段通过分割
      *
      * 151^                          电影的ID
      * Rob Roy (1995)^               电影的名称
      * In the highlands ....^        电影的描述
      * 139 minutes^                  电影的时长
      * August 26, 1997^              电影的发行日期
      * 1995^                         电影的拍摄日期
      * English ^                     电影的语言
      * Action|Drama|Romance|War ^    电影的类型
      * Liam Neeson|Jessica Lange...  电影的演员
      * Michael Caton-Jones           电影的导演
      *
      * tag1|tag2|tag3|....           电影的Tag
      **/

    // 首先需要将Tag数据集进行处理，  处理后的形式为  MID ， tag1|tag2|tag3     tag1   tag2  tag3

    import org.apache.spark.sql.functions._

    /**
      * MID , Tags
      * 1     tag1|tag2|tag3|tag4....
      */
    val newTag = tagDF.groupBy($"mid").agg(concat_ws("|", collect_set($"tag")).as("tags")).select("mid", "tags")

    // 需要将处理后的Tag数据，和Moive数据融合，产生新的Movie数据，
    val movieWithTagsDF = movieDF.join(newTag, Seq("mid", "mid"), "left")

    // 声明了一个ES配置的隐式参数
    implicit val esConfig = ESConfig(config.get("es.httpHosts").get, config.get("es.transportHosts").get, config.get("es.index").get, config.get("es.cluster.name").get)

    // 需要将新的Movie数据保存到ES中
    storeDataInES(movieWithTagsDF)

    // 关闭Spark
    spark.stop()

  }

  def storeDataInMongoDB(movieDF: DataFrame, ratingDF: DataFrame, tagDF: DataFrame)(implicit mongoConfig: MongoConfig): Unit = {

    //新建一个到MongoDB的连接
    val mongoClient = MongoClient(MongoClientURI(mongoConfig.uri))

    //如果MongoDB中有对应的数据库，那么应该删除
    mongoClient(mongoConfig.db)(MONGO_MOVIE_COLLECTION).dropCollection()
    mongoClient(mongoConfig.db)(MONGO_RATING_COLLECTION).dropCollection()
    mongoClient(mongoConfig.db)(MONGO_TAG_COLLECTION).dropCollection()

    //将当前数据写入到MongoDB
    movieDF
      .write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGO_MOVIE_COLLECTION)
      .mode("overwrite")
      .format(MONGO_DRIVER_CLASS)
      .save()

    ratingDF
      .write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGO_RATING_COLLECTION)
      .mode("overwrite")
      .format(MONGO_DRIVER_CLASS)
      .save()

    tagDF
      .write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGO_TAG_COLLECTION)
      .mode("overwrite")
      .format(MONGO_DRIVER_CLASS)
      .save()

    //对数据表建索引
    mongoClient(mongoConfig.db)(MONGO_MOVIE_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConfig.db)(MONGO_RATING_COLLECTION).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConfig.db)(MONGO_RATING_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConfig.db)(MONGO_TAG_COLLECTION).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConfig.db)(MONGO_TAG_COLLECTION).createIndex(MongoDBObject("mid" -> 1))

    //关闭MongoDB的连接
    mongoClient.close()
  }

  // 将数据保存到ES中的方法
  def storeDataInES(movieDF: DataFrame)(implicit eSConfig: ESConfig): Unit = {

    //新建一个配置
    val settings: Settings = Settings.builder().put("cluster.name", eSConfig.clustername).build()

    //新建一个ES的客户端
    val esClient = new PreBuiltTransportClient(settings)

    //需要将TransportHosts添加到esClient中
    val REGEX_HOST_PORT = "(.+):(\\d+)".r
    eSConfig.transportHosts.split(",").foreach {
      case REGEX_HOST_PORT(host: String, port: String) => {
        esClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port.toInt))
      }
    }

    //需要清除掉ES中遗留的数据
    if (esClient.admin().indices().exists(new IndicesExistsRequest(eSConfig.index)).actionGet().isExists) {
      esClient.admin().indices().delete(new DeleteIndexRequest(eSConfig.index))
    }
    esClient.admin().indices().create(new CreateIndexRequest(eSConfig.index))

    //将数据写入到ES中
    movieDF
      .write
      .option("es.nodes", eSConfig.httpHosts)
      .option("es.http.timeout", "100m")
      .option("es.mapping.id", "mid")
      .mode("overwrite")
      .format(ES_DRIVER_CLASS)
      .save(eSConfig.index + "/" + ES_MOVIE_INDEX)

  }

}
