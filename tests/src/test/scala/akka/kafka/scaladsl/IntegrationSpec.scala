/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.scaladsl

import java.util.concurrent.ConcurrentLinkedQueue

import akka.Done
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka._
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.Timeout
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.apache.kafka.clients.producer.{ProducerConfig, ProducerRecord}
import org.apache.kafka.common.{Metric, MetricName, TopicPartition}
import org.scalatest._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Success

class IntegrationSpec extends SpecBase(kafkaPort = KafkaPorts.IntegrationSpec) with Inside {

  implicit val patience = PatienceConfig(15.seconds, 500.millis)

  def createKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig(kafkaPort,
                        zooKeeperPort,
                        Map(
                          "offsets.topic.replication.factor" -> "1"
                        ))

  "Kafka connector" must {
    "produce to plainSink and consume from plainSource" in {
      assertAllStagesStopped {
        val topic1 = createTopicName(1)
        val group1 = createGroupId(1)

        givenInitializedTopic(topic1)

        Await.result(produce(topic1, 1 to 100), remainingOrDefault)

        val (control, probe) = createProbe(consumerDefaults.withGroupId(group1), topic1)

        probe
          .request(100)
          .expectNextN((1 to 100).map(_.toString))

        probe.cancel()
      }
    }

    "resume consumer from committed offset" in assertAllStagesStopped {
      val topic1 = createTopicName(1)
      val group1 = createGroupId(1)
      val group2 = createGroupId(2)

      givenInitializedTopic(topic1)

      // NOTE: If no partition is specified but a key is present a partition will be chosen
      // using a hash of the key. If neither key nor partition is present a partition
      // will be assigned in a round-robin fashion.

      Source(1 to 100)
        .map(n => new ProducerRecord(topic1, partition0, DefaultKey, n.toString))
        .runWith(Producer.plainSink(producerDefaults))

      val committedElements = new ConcurrentLinkedQueue[Int]()

      val consumerSettings = consumerDefaults.withGroupId(group1)

      val (control, probe1) = Consumer
        .committableSource(consumerSettings, Subscriptions.topics(topic1))
        .filterNot(_.record.value == InitialMsg)
        .mapAsync(10) { elem =>
          elem.committableOffset.commitScaladsl().map { _ =>
            committedElements.add(elem.record.value.toInt)
            Done
          }
        }
        .toMat(TestSink.probe)(Keep.both)
        .run()

      probe1
        .request(25)
        .expectNextN(25)
        .toSet should be(Set(Done))

      probe1.cancel()
      Await.result(control.isShutdown, remainingOrDefault)

      val probe2 = Consumer
        .committableSource(consumerSettings, Subscriptions.topics(topic1))
        .map(_.record.value)
        .runWith(TestSink.probe)

      // Note that due to buffers and mapAsync(10) the committed offset is more
      // than 26, and that is not wrong

      // some concurrent publish
      Source(101 to 200)
        .map(n => new ProducerRecord(topic1, partition0, DefaultKey, n.toString))
        .runWith(Producer.plainSink(producerDefaults))

      probe2
        .request(100)
        .expectNextN(((committedElements.asScala.max + 1) to 100).map(_.toString))

      probe2.cancel()

      // another consumer should see all
      val probe3 = Consumer
        .committableSource(consumerSettings.withGroupId(group2), Subscriptions.topics(topic1))
        .filterNot(_.record.value == InitialMsg)
        .map(_.record.value)
        .runWith(TestSink.probe)

      probe3
        .request(100)
        .expectNextN((1 to 100).map(_.toString))

      probe3.cancel()
    }

    "be able to set rebalance listener" in assertAllStagesStopped {
      val topic1 = createTopicName(1)
      val group1 = createGroupId(1)

      val consumerSettings = consumerDefaults.withGroupId(group1)

      val listener = TestProbe()

      val sub = Subscriptions.topics(topic1).withRebalanceListener(listener.ref)
      val (control, probe1) = Consumer
        .committableSource(consumerSettings, sub)
        .filterNot(_.record.value == InitialMsg)
        .mapAsync(10) { elem =>
          elem.committableOffset.commitScaladsl()
        }
        .toMat(TestSink.probe)(Keep.both)
        .run()

      probe1.request(25)

      val revoked = listener.expectMsgType[TopicPartitionsRevoked]
      info("revoked: " + revoked)
      revoked.sub shouldEqual sub
      revoked.topicPartitions.size shouldEqual 0

      val assigned = listener.expectMsgType[TopicPartitionsAssigned]
      info("assigned: " + assigned)
      assigned.sub shouldEqual sub
      assigned.topicPartitions.size shouldEqual 1

      probe1.cancel()
      Await.result(control.isShutdown, remainingOrDefault)
    }

    "handle commit without demand" in assertAllStagesStopped {
      val topic1 = createTopicName(1)
      val group1 = createGroupId(1)

      givenInitializedTopic(topic1)

      // important to use more messages than the internal buffer sizes
      // to trigger the intended scenario
      Await.result(produce(topic1, 1 to 100), remainingOrDefault)

      val (control, probe1) = Consumer
        .committableSource(consumerDefaults.withGroupId(group1), Subscriptions.topics(topic1))
        .filterNot(_.record.value == InitialMsg)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      // request one, only
      probe1.request(1)

      val committableOffset = probe1.expectNext().committableOffset

      // enqueue some more
      Await.result(produce(topic1, 101 to 110), remainingOrDefault)

      probe1.expectNoMessage(200.millis)

      // then commit, which triggers a new poll while we haven't drained
      // previous buffer
      val done1 = committableOffset.commitScaladsl()

      Await.result(done1, remainingOrDefault)

      probe1.request(1)
      val done2 = probe1.expectNext().committableOffset.commitScaladsl()
      Await.result(done2, remainingOrDefault)

      probe1.cancel()
      Await.result(control.isShutdown, remainingOrDefault)
    }

    "consume and commit in batches" in assertAllStagesStopped {
      val topic1 = createTopicName(1)
      val group1 = createGroupId(1)

      givenInitializedTopic(topic1)

      Await.result(produce(topic1, 1 to 100), remainingOrDefault)
      val consumerSettings = consumerDefaults.withGroupId(group1)

      def consumeAndBatchCommit(topic: String) =
        Consumer
          .committableSource(
            consumerSettings,
            Subscriptions.topics(topic)
          )
          .map { msg =>
            msg.committableOffset
          }
          .batch(max = 10, first => CommittableOffsetBatch(first)) { (batch, elem) =>
            batch.updated(elem)
          }
          .mapAsync(1)(_.commitScaladsl())
          .toMat(TestSink.probe)(Keep.both)
          .run()

      val (control, probe) = consumeAndBatchCommit(topic1)

      // Request one batch
      probe.request(1).expectNextN(1)

      probe.cancel()
      Await.result(control.isShutdown, remainingOrDefault)

      // Resume consumption
      val (control2, probe2) = createProbe(consumerSettings, topic1)

      val element = probe2.request(1).expectNext(60.seconds)

      Assertions.assert(element.toInt > 1, "Should start after first element")
      probe2.cancel()
    }

    "connect consumer to producer and commit in batches" in {
      assertAllStagesStopped {
        val topic1 = createTopic(1)
        val topic2 = createTopic(2)
        val group1 = createGroupId(1)

        awaitProduce(produce(topic1, 1 to 10))

        val source = Consumer
          .committableSource(consumerDefaults.withGroupId(group1), Subscriptions.topics(topic1))
          .map(msg => {
            ProducerMessage.Message(
              // Produce to topic2
              new ProducerRecord[String, String](topic2, msg.record.value),
              msg.committableOffset
            )
          })
          .via(Producer.flexiFlow(producerDefaults))
          .map(_.passThrough)
          .batch(max = 10, CommittableOffsetBatch.apply)(_.updated(_))
          .mapAsync(producerDefaults.parallelism)(_.commitScaladsl())

        val probe = source.runWith(TestSink.probe)

        probe.request(1).expectNext()

        probe.cancel()
      }
    }

    "not produce any records after send-failure if stage is stopped" in {
      assertAllStagesStopped {
        val topic1 = createTopicName(1)
        val group1 = createGroupId(1)
        // we use a 'max.block.ms' setting that will cause the metadata-retrieval to fail
        // effectively failing the production of the first messages
        val failFirstMessagesProducerSettings = producerDefaults.withProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1")

        givenInitializedTopic(topic1)

        Await.ready(produce(topic1, 1 to 100, failFirstMessagesProducerSettings), remainingOrDefault)

        val (control, probe) = createProbe(consumerDefaults.withGroupId(group1), topic1)

        probe
          .request(100)
          .expectNoMessage(1.second)

        probe.cancel()
      }
    }

    "stop and shut down KafkaConsumerActor for committableSource used with take" in assertAllStagesStopped {
      val topic1 = createTopicName(1)
      val group1 = createGroupId(1)

      Await.ready(produce(topic1, 1 to 10), remainingOrDefault)

      val (control, res) =
        Consumer
          .committableSource(consumerDefaults.withGroupId(group1), Subscriptions.topics(topic1))
          .mapAsync(1)(msg => msg.committableOffset.commitScaladsl().map(_ => msg.record.value))
          .take(5)
          .toMat(Sink.seq)(Keep.both)
          .run()

      Await.result(control.isShutdown, remainingOrDefault) should be(Done)
      res.futureValue should be((1 to 5).map(_.toString))
    }

    "stop and shut down KafkaConsumerActor for atMostOnceSource used with take" in assertAllStagesStopped {
      val topic1 = createTopicName(1)
      val group1 = createGroupId(1)

      Await.ready(produce(topic1, 1 to 10), remainingOrDefault)

      val (control, res) =
        Consumer
          .atMostOnceSource(consumerDefaults.withGroupId(group1), Subscriptions.topics(topic1))
          .map(_.value)
          .take(5)
          .toMat(Sink.seq)(Keep.both)
          .run()

      Await.result(control.isShutdown, remainingOrDefault) should be(Done)
      res.futureValue should be((1 to 5).map(_.toString))
    }

    "begin consuming from the beginning of the topic" in {
      assertAllStagesStopped {
        val topic = createTopicName(1)
        val group = createGroupId(1)

        givenInitializedTopic(topic)

        Await.result(produce(topic, 1 to 100), remainingOrDefault)

        val probe = Consumer
          .plainPartitionedManualOffsetSource(consumerDefaults.withGroupId(group),
                                              Subscriptions.topics(topic),
                                              _ => Future.successful(Map.empty))
          .flatMapMerge(1, _._2)
          .filterNot(_.value == InitialMsg)
          .map(_.value())
          .runWith(TestSink.probe)

        probe
          .request(100)
          .expectNextN((1 to 100).map(_.toString))

        probe.cancel()
      }
    }

    "begin consuming from the middle of the topic" in {
      assertAllStagesStopped {
        val topic = createTopicName(1)
        val group = createGroupId(1)

        givenInitializedTopic(topic)

        Await.result(produce(topic, 1 to 100), remainingOrDefault)

        val probe = Consumer
          .plainPartitionedManualOffsetSource(consumerDefaults.withGroupId(group),
                                              Subscriptions.topics(topic),
                                              tp => Future.successful(tp.map(_ -> 51L).toMap))
          .flatMapMerge(1, _._2)
          .filterNot(_.value == InitialMsg)
          .map(_.value())
          .runWith(TestSink.probe)

        probe
          .request(50)
          .expectNextN((51 to 100).map(_.toString))

        probe.cancel()
      }
    }

    "call the onRevoked hook" in {
      assertAllStagesStopped {
        val topic = createTopic()
        val group = createGroupId(1)

        awaitProduce(produce(topic, 1 to 100))

        var revoked = false

        val source = Consumer
          .plainPartitionedManualOffsetSource(consumerDefaults.withGroupId(group),
                                              Subscriptions.topics(topic),
                                              _ => Future.successful(Map.empty),
                                              _ => revoked = true)
          .flatMapMerge(1, _._2)
          .map(_.value())

        val (control1, probe1) = source.toMat(TestSink.probe)(Keep.both).run()

        probe1.request(50)

        sleep(consumerDefaults.waitClosePartition)

        val probe2 = source.runWith(TestSink.probe)

        eventually {
          assert(revoked, "revoked hook should have been called")
        }

        probe1.cancel()
        probe2.cancel()
        control1.isShutdown.futureValue should be(Done)
      }
    }

    "support metadata fetching on ConsumerActor" in {
      assertAllStagesStopped {
        val topic = createTopicName(1)
        val group = createGroupId(1)

        givenInitializedTopic(topic)

        Await.result(produce(topic, 1 to 100), remainingOrDefault)

        // Create ConsumerActor manually
        // https://doc.akka.io/docs/akka-stream-kafka/0.20/consumer.html#sharing-kafkaconsumer
        val consumer = system.actorOf(KafkaConsumerActor.props(consumerDefaults.withGroupId(group)))

        // Timeout for metadata fetching requests
        implicit val timeout: Timeout = Timeout(5.seconds)

        import Metadata._

        // ListTopics
        inside(Await.result(consumer ? ListTopics, remainingOrDefault)) {
          case Topics(Success(topics)) =>
            val pi = topics(topic).head
            assert(pi.topic == topic, "Topic name in retrieved PartitionInfo does not match?!")
        }

        // GetPartitionsFor
        inside(Await.result(consumer ? GetPartitionsFor(topic), remainingOrDefault)) {
          case PartitionsFor(Success(partitions)) =>
            assert(partitions.size == 1, "Topic must have one partition GetPartitionsFor")
            val pi = partitions.head
            assert(pi.topic == topic, "Topic name mismatch in GetPartitionsFor")
            assert(pi.partition == 0, "Partition number mismatch in GetPartitionsFor")
        }

        val partition0 = new TopicPartition(topic, 0)

        // GetBeginningOffsets
        inside(Await.result(consumer ? GetBeginningOffsets(Set(partition0)), remainingOrDefault)) {
          case BeginningOffsets(Success(offsets)) =>
            assert(offsets == Map(partition0 -> 0), "Wrong BeginningOffsets for topic")
        }

        // GetEndOffsets
        inside(Await.result(consumer ? GetEndOffsets(Set(partition0)), remainingOrDefault)) {
          case EndOffsets(Success(offsets)) =>
            assert(offsets == Map(partition0 -> 101), "Wrong EndOffsets for topic")
        }

        val tsYesterday = System.currentTimeMillis() - 86400000

        // GetOffsetsForTimes - beginning
        inside(Await.result(consumer ? GetOffsetsForTimes(Map(partition0 -> tsYesterday)), remainingOrDefault)) {
          case OffsetsForTimes(Success(offsets)) =>
            val offsetAndTs = offsets(partition0)
            assert(offsetAndTs.offset() == 0, "Wrong offset in OffsetsForTimes (beginning)")
        }

        // GetCommittedOffset
        inside(Await.result(consumer ? GetCommittedOffset(partition0), 10.seconds)) {
          case CommittedOffset(Success(offsetMeta)) =>
            assert(offsetMeta == null, "Wrong offset in GetCommittedOffset")
        }

        // verify that consumption still works
        val probe = Consumer
          .plainExternalSource[Array[Byte], String](consumer, Subscriptions.assignment(partition0))
          .filterNot(_.value == InitialMsg)
          .map(_.value())
          .runWith(TestSink.probe)

        probe
          .request(100)
          .expectNextN((1 to 100).map(_.toString))

        probe.cancel()
      }
    }

  }

  "Consumer control" must {

    "complete source when stopped" in
    assertAllStagesStopped {
      val topic = createTopicName(1)
      val group = createGroupId(1)

      givenInitializedTopic(topic)

      Await.result(produce(topic, 1 to 100), remainingOrDefault)

      val (control, probe) = Consumer
        .plainSource(consumerDefaults.withGroupId(group), Subscriptions.topics(topic))
        .filterNot(_.value == InitialMsg)
        .map(_.value())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      probe
        .request(100)
        .expectNextN(100)

      val stopped = control.stop()
      probe.expectComplete()

      Await.result(stopped, remainingOrDefault)

      control.shutdown()
      probe.cancel()
    }

    "complete partition sources when stopped" in
    assertAllStagesStopped {
      val topic = createTopicName(1)
      val group = createGroupId(1)

      givenInitializedTopic(topic)

      Await.result(produce(topic, 1 to 100), remainingOrDefault)

      val (control, probe) = Consumer
        .plainPartitionedSource(consumerDefaults.withGroupId(group), Subscriptions.topics(topic))
        .flatMapMerge(1, _._2)
        .filterNot(_.value == InitialMsg)
        .map(_.value())
        .toMat(TestSink.probe)(Keep.both)
        .run()

      probe
        .request(100)
        .expectNextN(100)

      val stopped = control.stop()
      probe.expectComplete()

      Await.result(stopped, remainingOrDefault)

      control.shutdown()
      probe.cancel()
    }

    "access metrics" in assertAllStagesStopped {
      val topic = createTopic(number = 1, partitions = 1, replication = 1)
      val group = createGroupId(1)

      val control = Consumer
        .plainSource(consumerDefaults.withGroupId(group), Subscriptions.topics(topic))
        .map(_.value())
        .to(TestSink.probe)
        .run()

      // Wait a tiny bit to avoid a race on "not yet initialized: only setHandler is allowed in GraphStageLogic constructor"
      sleep(1.milli)
      val metrics: Future[Map[MetricName, Metric]] = control.metrics
      metrics.futureValue should not be 'empty

      Await.result(control.shutdown(), remainingOrDefault)
    }

  }
}
