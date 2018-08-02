package example

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}

import scala.concurrent.duration.FiniteDuration

object DeviceGroupQuery {
  case object CollectionTimeout

  def props(
             requestId: Long,
             requester: ActorRef,
             deviceRefToId: Map[ActorRef, String],
             timeout: FiniteDuration
           ): Props = Props(new DeviceGroupQuery(requestId, requester, deviceRefToId, timeout))
}

class DeviceGroupQuery(
                      requestId: Long,
                      requester: ActorRef,
                      deviceRefToId: Map[ActorRef, String],
                      timeout: FiniteDuration
                      ) extends Actor with ActorLogging {
  import DeviceGroupQuery._
  import context.dispatcher
  val queryTimeoutTimer = context.system.scheduler.scheduleOnce(timeout, self, CollectionTimeout)

  override def preStart(): Unit =
    log.info("GroupQuery {} is started", requestId)
    deviceRefToId.keysIterator.foreach { deviceActor =>
      context.watch(deviceActor)
      deviceActor ! Device.ReadTemperature(0)
    }

  override def postStop(): Unit =
    log.info("GroupQuery {} is stopped", requestId)
    queryTimeoutTimer.cancel()

  override def receive: Receive = waitReply(Map.empty, deviceRefToId.keySet)

  def waitReply(
                 receivedReadings: Map[String, DeviceGroup.TemperatureReading],
                 waitingFor: Set[ActorRef]
               ): Receive = {
    case Device.RespondTemperature(0, valueOption) =>
      val reading = valueOption match {
        case Some(value) => DeviceGroup.Temperature(value)
        case None => DeviceGroup.TemperatureNotAvailable
      }
      receivedReading(sender(), reading, receivedReadings, waitingFor)

    case Terminated(actorRef) =>
      val deviceId = deviceRefToId(actorRef)
      log.warning(
        "GroupQuery {} received device timeout from {}",
        requestId, deviceId
      )
      receivedReading(actorRef, DeviceGroup.DeviceNotAvailable, receivedReadings, waitingFor)

    case CollectionTimeout =>
      val timedoutReplies = waitingFor.map { actorRef =>
        deviceRefToId(actorRef) -> DeviceGroup.Timeout
      }
      val allResults = receivedReadings ++ timedoutReplies
      requester ! DeviceGroup.ReplyAllTemperatures(requestId, allResults)
  }

  def receivedReading(
                     deviceRef: ActorRef,
                     reading: DeviceGroup.TemperatureReading,
                     receivedReadings: Map[String, DeviceGroup.TemperatureReading],
                     waitingFor: Set[ActorRef]
                     ) = {
    val deviceId = deviceRefToId(deviceRef)
    log.info(
      "GroupQuery {} received reading {} from {}",
      requestId, reading, deviceId
    )
    context.unwatch(deviceRef)
    val newReadings = receivedReadings + (deviceId -> reading)
    val newWaitingFor = waitingFor - deviceRef

    if(newWaitingFor.isEmpty) {
      requester ! DeviceGroup.ReplyAllTemperatures(requestId, newReadings)
      // forgot to end this request!
      context.stop(self)
    } else {
      context.become(waitReply(newReadings, newWaitingFor))
    }
  }

}
