package example

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}

object DeviceManager {
  final case class RequestTrackDevice(groupId: String, deviceId: String)
  case object DeviceRegistered

  final case class RequestGroupActorsList(requestId: Long)
  final case class ReplyGroupActorsList(requestId: Long, groups: Set[ActorRef])

  def props(): Props = Props(new DeviceManager())
}

class DeviceManager extends Actor with ActorLogging {
  import DeviceManager._

  var groupIdToActor = Map.empty[String, ActorRef]
  var actorRefToGroupId = Map.empty[ActorRef, String]

  override def preStart(): Unit = log.info("Starting DeviceManager")
  override def postStop(): Unit = log.info("Stopping DeviceManager")

  override def receive: Receive = {
    case RequestGroupActorsList(requestId) =>
      sender ! ReplyGroupActorsList(requestId, actorRefToGroupId.keySet)

    case message @ RequestTrackDevice(groupId, deviceId) =>
      groupIdToActor.get(groupId) match {
        case Some(groupActor) =>
          groupActor.forward(message)

        case None =>
          val groupActor = context.actorOf(DeviceGroup.props(groupId))
          context.watch(groupActor)

          groupIdToActor += groupId -> groupActor
          actorRefToGroupId += groupActor -> groupId

          groupActor.forward(message)
      }

    case Terminated(actorRef) =>
      val groupId = actorRefToGroupId(actorRef)
      log.info("GroupActor for {} has been terminated", groupId)
      groupIdToActor -= groupId
      actorRefToGroupId -= actorRef

  }
}
