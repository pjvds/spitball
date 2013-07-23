package services

import org.slf4j.LoggerFactory
import com.heroku.logfmt.Logfmt
import scala.collection.JavaConverters._
import scala.annotation.tailrec
import java.text.SimpleDateFormat
import spray.json._
import models._
import controllers.Formatters._
import spray.httpx.SprayJsonSupport._
import scala.util.parsing.json.JSONObject

object Spitball {

  private lazy val logger = LoggerFactory.getLogger(Spitball.getClass)
  private lazy val redisService = RedisService()
  private val datep = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ")

  def get(requestId: String) = fromRedis(requestId)


  def getV1(requestId:String):Map[String,String]= {
    fromRedis(requestId).foldRight(Map[String,String]()) {(measure, agg)=>
      agg + (measure.name -> measure.value)
    }
  }

  def drain(logs: String) {
    parse(logs).foreach { line =>
      val parsedLine = Logfmt.parse(line.line.toCharArray).asScala.toMap.mapValues(new String(_))
      forRequestId(parsedLine).map { entry =>
        val requestId = entry._1
        val pairs = entry._2.filterKeys(_.startsWith("measure."))
        val jsonPairs =  pairs.map { kv =>
          val key = kv._1
          val value = kv._2
          (key, LogValue(value,line.time))
        }
        toRedis(requestId, jsonPairs)
      }
    }
  }

  private def parse(in: String): Iterator[LogLine] = {
    @tailrec
    def loop(unparsed: Iterator[Char], parsed: Iterator[LogLine]): Iterator[LogLine] = {
      if (unparsed.isEmpty) parsed
      else {
        val (head, tail) = unparsed.span(_ != ' ')
        val length = head.mkString.toInt
        val chunk = tail.slice(1, 1 + length).mkString
        val time = datep.parse(chunk.split(' ')(1))
        loop(tail, parsed ++ Iterator(LogLine(chunk, time)))
      }
    }
    loop(in.toIterator, Iterator.empty)
  }

  private def forRequestId(data: Map[String, String]): Option[(String, Map[String, String])] = {
    data.get("request_id").map(requestId => (requestId, data))
  }

  private def key(requestId: String): String = {
    "REQUEST_ID:" + requestId
  }

  private  def fromRedis(requestId: String): Seq[Measure]= {
    redisService.withRedis { redis =>
      val data  = redis.lrange(requestId,0,-1)
      val measures= data.asScala.map{ value =>
        value.asJson.convertTo[Measure]
      }
      return measures
    }
  }
  private def toRedis(requestId: String, pairs: Map[String, LogValue]) {
    if (!pairs.isEmpty) {
      redisService.withRedis { redis =>
        val strs = pairs.map { kv =>
          (kv._1, (kv._2.value, kv._2.time.toString)).toJson.toString()
            }

        redis.rpush(key(requestId), strs.toSeq:_*)
        redis.expire(key(requestId), sys.env.get("REQUESTS_EXPIRE_SEC").map(_.toInt).getOrElse(10 * 60))
        logger.info(s"redis.save saved.request_id=$requestId saved.pairs=${pairs.keys.size}")
      }
    }
  }
}

