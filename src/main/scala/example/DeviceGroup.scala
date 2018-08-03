package example

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import scala.concurrent.duration._

object DeviceGroup {
  def props(groupId: String): Props = Props(new DeviceGroup(groupId))

  final case class RequestDeviceList(requestId: Long)
  final case class ReplyDeviceList(requestId: Long, ids: Set[String])

  final case class RequestAllTemperatures(requestId: Long)
  final case class ReplyAllTemperatures(requestId: Long, readings: Map[String, TemperatureReading])

  sealed trait TemperatureReading
  final case class Temperature(value: Double) extends TemperatureReading
  final object TemperatureNotAvailable extends TemperatureReading
  final object Timeout extends TemperatureReading
  final object DeviceNotAvailable extends TemperatureReading
}

class DeviceGroup(groupId: String) extends Actor with ActorLogging {
  import DeviceGroup._

  var deviceIdToActor = Map.empty[String, ActorRef]
  var actorToDeviceId = Map.empty[ActorRef, String]

  override def preStart(): Unit = log.info("DeviceGroup {} started", groupId)
  override def postStop(): Unit = log.info("DeviceGroup {} stopped", groupId)

  override def receive: Receive = {
    case RequestAllTemperatures(requestId) =>
      context.actorOf(DeviceGroupQuery.props(
        requestId = requestId,
        requester = sender(),
        deviceRefToId = actorToDeviceId,
        timeout = 5.seconds
      ))

    case trackMsg @ DeviceManager.RequestTrackDevice(`groupId`, _) =>
      deviceIdToActor.get(trackMsg.deviceId) match {
        case Some(deviceActor) => deviceActor forward trackMsg
        case None =>
          log.info("Creating device actor for {}", trackMsg.deviceId)
          val deviceActor = context.actorOf(Device.props(groupId, trackMsg.deviceId), s"device-${trackMsg.deviceId}")
          deviceIdToActor += trackMsg.deviceId -> deviceActor
          actorToDeviceId += deviceActor -> trackMsg.deviceId
          context.watch(deviceActor)
          deviceActor forward trackMsg
      }

    case DeviceManager.RequestTrackDevice(groupId, deviceId) =>
      log.warning("Ignoring TrackDevice Request for {}:{}. This actor is responsible for {}",
        groupId, deviceId, this.groupId)

    case RequestDeviceList(requestId) =>
      sender() ! ReplyDeviceList(requestId, deviceIdToActor.keySet)

    case Terminated(deviceActor) =>
      val deviceId = actorToDeviceId(deviceActor)
      log.info("Device actor for {} has been terminated", deviceId)
      deviceIdToActor -= deviceId
      actorToDeviceId -= deviceActor
  }

}
