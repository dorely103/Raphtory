package com.raphtory.core.actors.PartitionManager

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.cluster.pubsub.DistributedPubSubMediator.SubscribeAck
import com.raphtory.core.actors.PartitionManager.Workers.ReaderWorker
import com.raphtory.core.actors.RaphtoryActor
import com.raphtory.core.model.EntityStorage
import com.raphtory.core.model.communication._

import scala.collection.parallel.mutable.ParTrieMap
import scala.util.Try

class Reader(
    id: Int,
    test: Boolean,
    managerCountVal: Int,
    storage: ParTrieMap[Int, EntityStorage],
) extends RaphtoryActor {

  implicit var managerCount: Int = managerCountVal

  // Id which refers to the partitions position in the graph manager map
  val managerId: Int = id

  val mediator: ActorRef = DistributedPubSub(context.system).mediator

  mediator ! DistributedPubSubMediator.Put(self)

  var readers: ParTrieMap[Int, ActorRef] = new ParTrieMap[Int, ActorRef]()

  for (i <- 0 until totalWorkers) {
    log.debug("Initialising [{}] worker children for Reader [{}}.", totalWorkers, managerId)

    // create threads for writing
    val child = context.system.actorOf(
            Props(new ReaderWorker(managerCount, managerId, i, storage(i))).withDispatcher("reader-dispatcher"),
            s"Manager_${id}_reader_$i"
    )

    context.watch(child)
    readers.put(i, child)
  }

  override def preStart(): Unit =
    log.debug("Reader [{}] is being started.", managerId)

  override def receive: Receive = {
    case ReaderWorkersOnline()     => sender ! ReaderWorkersACK()
    case req: AnalyserPresentCheck => processAnalyserPresentCheckRequest(req)
    case req: UpdatedCounter       => processUpdatedCounterRequest(req)
    case SubscribeAck              =>
    case Terminated(child) =>
      log.warning(s"ReaderWorker with path [{}] belonging to Reader [{}] has died.", child.path, managerId)
    case x => log.warning(s"Reader [{}] received unknown [{}] message.", managerId, x)
  }

  def processAnalyserPresentCheckRequest(req: AnalyserPresentCheck): Unit = {
    log.debug(s"Reader [{}] received [{}] request.", managerId, req)

    val className   = req.className
    val classExists = Try(Class.forName(className))

    classExists.toEither.fold(
            { _: Throwable =>
              log.debug("Class [{}] was not found within this image.", className)

              sender ! ClassMissing()
            }, { _: Class[_] =>
              log.debug(s"Class [{}] exists. Proceeding.", className)

              sender ! AnalyserPresent()
            }
    )
  }





  def processUpdatedCounterRequest(req: UpdatedCounter): Unit = {
    log.debug("Reader [{}] received [{}] request.", managerId, req)

    managerCount = req.newValue
    readers.foreach(x => x._2 ! UpdatedCounter(req.newValue))
  }
}
