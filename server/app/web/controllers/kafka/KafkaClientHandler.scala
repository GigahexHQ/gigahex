package web.controllers.kafka

import java.time.Duration
import java.util
import java.util.Properties
import java.util.concurrent.CompletableFuture

import web.models.cluster.{PartitionDetails, TopicConfiguration, TopicDetails, TopicMessage}

import scala.jdk.FutureConverters._
import org.apache.kafka.clients.admin.{Admin, AdminClientConfig, ReplicaInfo}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.{KafkaFuture, TopicPartition}

import scala.jdk.CollectionConverters._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait KafkaClientHandler {

  def withConsumer[T](server: String, port: Int)(handler: KafkaConsumer[String, String] => T): T = {
    val properties = new Properties()
    import org.apache.kafka.clients.consumer.ConsumerConfig
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer])
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer])
    properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, s"${server}:${port}")
    val kafkaConsumer = new KafkaConsumer[String, String](properties)
    handler(kafkaConsumer)
  }

  def withAdmin(server: String, port: Int)(handler: Admin => Future[Result]): Future[Result] = {
    val properties = new Properties()
    properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, s"${server}:${port}")
    properties.put(AdminClientConfig.RETRIES_CONFIG, 2)

    try {
      val admin = Admin.create(properties)
      handler(admin)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        Future.successful(Results.InternalServerError)
    }

  }

  def getBrokers(admin: Admin)(implicit ec: ExecutionContext): Future[util.Collection[Integer]] = {
    admin
      .describeCluster()
      .nodes()
      .toCompletableFuture
      .asScala
      .map(_.asScala.map(broker => Integer.valueOf(broker.id())).toSeq.asJavaCollection)
  }

  def getReplicasStats(admin: Admin, brokers: util.Collection[Integer])(implicit ec: ExecutionContext): Future[Map[Int, Map[TopicPartition, ReplicaInfo]]] = {
    println(brokers.asScala.foreach(println))
    admin.describeLogDirs(brokers)
      .allDescriptions()
      .toCompletableFuture
      .asScala
      .map(_.asScala.toMap.flatMap {
        case (brokerId, logsMap) => logsMap.asScala.flatMap {
          case (_, description) =>
            Map(brokerId.toInt -> description.replicaInfos().asScala.toMap )
        }
      })
  }

  def getTopicPartitions(admin: Admin, topic: String, host: String, port: Int)(implicit ec: ExecutionContext): Future[Seq[PartitionDetails]] = {
    
     admin.describeTopics(Seq(topic).asJavaCollection)
      .all()
      .toCompletableFuture
      .asScala
      .map { topics =>
        val partitionInfo = topics.get(topic).partitions().asScala.toSeq
        withConsumer(host,port){ consumer =>
          val beginnings = consumer
            .beginningOffsets(partitionInfo.map(pi => new TopicPartition(topic, pi.partition())).asJavaCollection)

          val endings = consumer
            .endOffsets(partitionInfo.map(pi => new TopicPartition(topic, pi.partition())).asJavaCollection)

          consumer.close()
          partitionInfo.map { info =>
            val tp = new TopicPartition(topic, info.partition())
            PartitionDetails(info.partition(),
              startingOffset = beginnings.get(tp),
              endingOffset = endings.get(tp),
              messages = endings.get(tp) - beginnings.get(tp),
              replicas = info.replicas().asScala.toSeq.map(_.id())
            )
          }
        }
      }
  }

  def getTopicConfig(admin: Admin, topic: String)(implicit ec: ExecutionContext): Future[Seq[TopicConfiguration]] = {
    val configResource = new ConfigResource(ConfigResource.Type.TOPIC, topic)
    admin.describeConfigs(Seq(configResource).asJavaCollection)
      .all().toCompletableFuture.asScala.map { configs =>
       val c = configs.get(configResource)
      c.entries().asScala.toSeq.map(entry => TopicConfiguration(entry.name(), entry.value(), entry.`type`().toString, entry.source().toString))
    }
  }

  def getTopicMessages(admin: Admin, topic: String, host: String, port: Int)(implicit ec: ExecutionContext): Future[Seq[TopicMessage]] = {
    getTopicPartitions(admin, topic, host, port)
      .map { partitions =>
        withConsumer(host, port){ consumer =>
        val topicPartitions = partitions.map(pd => new TopicPartition(topic, pd.id)).asJava
            consumer.assign(topicPartitions)
            consumer.seekToBeginning(topicPartitions)
            val records = consumer.poll(Duration.ofMillis(500))
            consumer.close()
            if(records.count() > 0){
              records.iterator().asScala.toSeq.map { r =>

                TopicMessage(r.key(), r.value(), r.offset(), r.timestamp(), r.partition())}
            } else Seq.empty[TopicMessage]

        }
      }
  }

  def getTopicSummary(admin: Admin, host: String, port: Int)(implicit ec: ExecutionContext): Future[Seq[TopicDetails]] = {
    val topicDetails = for {
      brokers <- getBrokers(admin)
      topicPartitions <- getReplicasStats(admin, brokers)
    } yield {

      admin.listTopics().listings().toCompletableFuture.asScala.onComplete {
        case Failure(exception) =>
        case Success(value) => value.asScala.foreach(tl => println(tl.name()))
      }
      //Aggregate the topic details by iterating through all the replicas in each broker
      topicPartitions.foldLeft(Seq.empty[TopicDetails]) {
        case (topics, (brokerId, replicas)) =>
          val topicWithStats = replicas.map {
            case (partition, info) =>
              TopicDetails(partition.topic(), Seq(partition.partition()), Seq(brokerId), 0, info.size())
          }.groupBy(_.name)
            .map {
              case (topicName, details) => TopicDetails(topicName, details.flatMap(_.partitions).toSeq.distinct,
                details.flatMap(_.replications).toSeq.distinct, 0, details.map(_.size).sum)
            }.toSeq

          val newTopicSet = topics ++ topicWithStats

          newTopicSet.map { t =>
            topicWithStats.find(_.name.equalsIgnoreCase(t.name)) match {
              case None => t
              case Some(oldVal) => t.copy(partitions = (oldVal.partitions ++ t.partitions).distinct,
                replications = (oldVal.replications ++ t.replications).distinct,
                messages = t.messages, size = oldVal.size + t.size)
            }
          }
          newTopicSet.distinct
      }
    }

    topicDetails.map { tds =>
      val topicPartitions = tds.flatMap { td =>
        td.partitions.map(p => new TopicPartition(td.name, p))
      }

      withConsumer(host, port) { consumer =>
          val beginning = consumer.beginningOffsets(topicPartitions.asJavaCollection)
          val ending = consumer.endOffsets(topicPartitions.asJavaCollection)
          consumer.close()

          tds.map { topicD =>
            val topicBeginning = beginning.asScala.filter(_._1.topic().equalsIgnoreCase(topicD.name))
            val topicEnding = ending.asScala.filter(_._1.topic().equalsIgnoreCase(topicD.name))
            val totalMessages = topicEnding.foldLeft(0L) {
                      case (x, y) =>
                        val (partition, offset) = y
                        val beginningOffset = topicBeginning(partition)
                        val diff = offset - beginningOffset
                        x + diff
                    }
            topicD.copy(messages = totalMessages)
          }
      }
    }
  }

  implicit class KafkaFutureToCompletableFuture[T](kafkaFuture: KafkaFuture[T]) {
    def toCompletableFuture: CompletableFuture[T] = {
      val wrappingFuture = new CompletableFuture[T]
      kafkaFuture.whenComplete((value, throwable) => {
        if (throwable != null) {
          wrappingFuture.completeExceptionally(throwable)
        } else {
          wrappingFuture.complete(value)
        }
      })
      wrappingFuture
    }
  }

}
