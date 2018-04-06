package com.raphtory.Actors.RaphtoryActors

/**
  * Created by Mirate on 11/07/2017.
  */
import java.text.SimpleDateFormat

import akka.actor.Actor
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import com.raphtory.caseclass._
import com.raphtory.utils.Utils

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class WatchDog(managerCount:Int) extends Actor{
  val mediator = DistributedPubSub(context.system).mediator
  mediator ! DistributedPubSubMediator.Put(self)

  var pmcounter = 0
  var routercounter = 0

  var minimumRouters = 1
  var clusterUp = false

  val maxTime = 30000

  var PMKeepAlive = TrieMap[Int,Long]()
  var RouterKeepAlive = TrieMap[Int,Long]()

  var pmCounter = 0
  var roCounter = 0

  override def preStart() {
    context.system.scheduler.schedule(Duration(2, SECONDS),Duration(1, SECONDS),self,"tick")
  }

  override def receive: Receive = {
    case ClusterStatusRequest => sender() ! ClusterStatusResponse(clusterUp)
    case "tick" => keepAliveHandler()
    case PartitionUp(id:Int) => mapHandler(id,PMKeepAlive, "Partition Manager")
    case RouterUp(id:Int) =>mapHandler(id,RouterKeepAlive, "Router")
    case RequestPartitionId => {
      println("Sending Id to the requestor")
      sender() ! AssignedId(pmCounter)
      pmCounter += 1
      println("Sending new total coutner to all the subscribers")
      mediator ! DistributedPubSubMediator.Publish(Utils.partitionsTopic, PartitionsCount(pmCounter))
    }

  }

  def keepAliveHandler() = {
    checkMapTime(PMKeepAlive)
    checkMapTime(RouterKeepAlive)
    if(!clusterUp)
      if(RouterKeepAlive.size>=minimumRouters)
        if(PMKeepAlive.size==managerCount){
          clusterUp=true

          println("All Partition Managers and minimum number of routers have joined the cluster")
        }

  }

  def checkMapTime(map: TrieMap[Int,Long]) = map.foreach(pm =>
    if(pm._2 + maxTime <= System.currentTimeMillis())
      println(s"Manager ${pm._1} not responding since ${unixToTimeStamp(pm._2)}"))

  def mapHandler(id: Int, map:TrieMap[Int,Long], mapType:String) = {
    println(s"Inside map handler for $mapType $id")
    map.putIfAbsent(id,System.currentTimeMillis()) match {
      case Some(time) => map.update(id,System.currentTimeMillis())
      case _ => println(s"$mapType $id has started sending keep alive messages at ${nowTimeStamp()}")
    }
  }

  def getManager(srcId:Int):String = s"/user/Manager_${srcId % managerCount}" //simple srcID hash at the moment

  def unixToTimeStamp(unixTime:Long) = new SimpleDateFormat("dd-MM hh:mm:ss").format(unixTime)
  def nowTimeStamp()= new SimpleDateFormat("dd-MM hh:mm:ss").format(System.currentTimeMillis())

}

