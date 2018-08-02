package example

import scala.concurrent.duration._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{TestKit, TestProbe}

class AkkaQuickstartSpec(_system: ActorSystem)
  extends TestKit(_system)
  with Matchers
  with FlatSpecLike
  with BeforeAndAfterAll {

  def this() = this(ActorSystem("AkkaQuickstartSpec"))

  override def afterAll: Unit = {
    shutdown(system)
  }

  "DeviceActor when to temperature is known" should "reply with empty reading" in {
    val probe = TestProbe()
    val deviceActor = system.actorOf(Device.props("group", "device"))

    deviceActor.tell(Device.ReadTemperature(id = 42), probe.ref)

    val responce = probe.expectMsgType[Device.RespondTemperature]

    responce.id should === (42)
    responce.value should === (None)
  }

  "DeviceActor with known temperature" should "reply with latest temperature reading" in {
    val probe = TestProbe()
    val deviceActor = system.actorOf(Device.props("group", "device"))

    deviceActor.tell(Device.RecordTemperature(id=1, 29.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id=1))

    deviceActor.tell(Device.ReadTemperature(id=2), probe.ref)
    val responce1 = probe.expectMsgType[Device.RespondTemperature]
    responce1.id should === (2)
    responce1.value should === (Some(29.0))

    deviceActor.tell(Device.RecordTemperature(id=3, 30.1), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id=3))


    deviceActor.tell(Device.ReadTemperature(id=4), probe.ref)
    val responce2 = probe.expectMsgType[Device.RespondTemperature]
    responce2.id should === (4)
    responce2.value should === (Some(30.1))
  }

  "Device Actor on TrackRequest with correct ids" should "reply with DeviceRegistered" in {
    val probe = TestProbe()
    val deviceActor = system.actorOf(Device.props("group-1", "device-1"))

    deviceActor.tell(DeviceManager.RequestTrackDevice("group-1", "device-1"), probe.ref)

    probe.expectMsg(DeviceManager.DeviceRegistered)

    probe.lastSender should === (deviceActor)
  }

  "Device Actor on TrackRequest with incorrect ids" should "ignore request" in {
    val probe = TestProbe()
    val deviceActor = system.actorOf(Device.props("group-1", "device-1"))

    deviceActor.tell(DeviceManager.RequestTrackDevice("wrong-group", "device-1"), probe.ref)
    probe.expectNoMessage(500.milliseconds)

    deviceActor.tell(DeviceManager.RequestTrackDevice("group-1", "wrong-device"), probe.ref)
    probe.expectNoMessage(500.milliseconds)
  }

  "DeviceGroup Actor on TrackRequest with incorrect ids" should "ignore request" in {
    val probe = TestProbe()
    val deviceActor = system.actorOf(DeviceGroup.props("group-id"))

    deviceActor.tell(DeviceManager.RequestTrackDevice("wrong-group", "device-1"), probe.ref)
    probe.expectNoMessage(500.milliseconds)
  }

  "DeviceGroup Actor on TrackRequest with correct ids" should "register Device Actor preserving requester as last sender for Device" in {
    val probe = TestProbe()
    val groupActor = system.actorOf(DeviceGroup.props("group-id"))

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val deviceActor1 = probe.lastSender

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-2"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val deviceActor2 = probe.lastSender

    deviceActor1 should !== (deviceActor2)

    // Check that the device actors are working
    deviceActor1.tell(Device.RecordTemperature(id = 0, 1.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id = 0))
    deviceActor2.tell(Device.RecordTemperature(id = 1, 2.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id = 1))
  }

  "DeviceGroup Actor on TrackRequest with correct duplicate ids" should "return forward to existing DeviceActor" in {
    val probe = TestProbe()
    val groupActor = system.actorOf(DeviceGroup.props("group-id"))

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val deviceActor1 = probe.lastSender

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val deviceActor2 = probe.lastSender

    deviceActor1 should === (deviceActor2)
  }

  "DeviceGroup" should "be able to list devices" in {
    val probe = TestProbe()
    val groupActor = system.actorOf(DeviceGroup.props("group-id"))

    //add devices:
    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-2"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)

    groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 1), probe.ref)
    probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 1, Set("device-1", "device-2")))
  }

  "DeviceGroup" should "be able to list devices after one has stopped" in {
    val probe = TestProbe()
    val groupActor = system.actorOf(DeviceGroup.props("group-id"))

    //add devices:
    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val toShutDown = probe.lastSender

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-2"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)

    groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 1), probe.ref)
    probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 1, Set("device-1", "device-2")))

    probe.watch(toShutDown)
    toShutDown ! PoisonPill
    probe.expectTerminated(toShutDown)

    probe.awaitAssert {
      groupActor.tell(DeviceGroup.RequestDeviceList(requestId = 1), probe.ref)
      probe.expectMsg(DeviceGroup.ReplyDeviceList(requestId = 1, Set("device-2")))
    }
  }

  "DeviceManager on TrackDevice request" should "register Device Actor preserving requester as last sender for Device" in {
    val probe = TestProbe()
    val groupActor = system.actorOf(DeviceManager.props())

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val deviceActor1 = probe.lastSender

    groupActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-2"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    val deviceActor2 = probe.lastSender

    deviceActor1 should !== (deviceActor2)

    // Check that the device actors are working
    deviceActor1.tell(Device.RecordTemperature(id = 0, 1.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id = 0))
    deviceActor2.tell(Device.RecordTemperature(id = 1, 2.0), probe.ref)
    probe.expectMsg(Device.TemperatureRecorded(id = 1))
  }

  "DeviceManager" should "be able to list devices" in {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())

    //add devices:
    managerActor.tell(DeviceManager.RequestTrackDevice("group-1", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)
    managerActor.tell(DeviceManager.RequestTrackDevice("group-2", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)

    managerActor.tell(DeviceManager.RequestGroupActorsList(requestId = 1), probe.ref)

    val reply = probe.expectMsgType[DeviceManager.ReplyGroupActorsList]
    assert(reply.groups.size === 2)
  }

  "DeviceManager" should "be able to list devices after one has stopped" in {
    val probe = TestProbe()
    val managerActor = system.actorOf(DeviceManager.props())

    //add devices:
    managerActor.tell(DeviceManager.RequestTrackDevice("group-1", "device-1"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)

    managerActor.tell(DeviceManager.RequestTrackDevice("group-id", "device-2"), probe.ref)
    probe.expectMsg(DeviceManager.DeviceRegistered)

    managerActor.tell(DeviceManager.RequestGroupActorsList(requestId = 1), probe.ref)
    val reply = probe.expectMsgType[DeviceManager.ReplyGroupActorsList]
    val toShutDown = reply.groups.head

    probe.watch(toShutDown)
    toShutDown ! PoisonPill
    probe.expectTerminated(toShutDown)

    probe.awaitAssert {
      managerActor.tell(DeviceManager.RequestGroupActorsList(requestId = 1), probe.ref)
      val reply = probe.expectMsgType[DeviceManager.ReplyGroupActorsList]

      assert(reply.groups.size === 1)
    }
  }

  "DeviceGroupQuery" should "report responces it receives" in {
    val probe = TestProbe()

    val device1 = TestProbe()
    val device2 = TestProbe()

    val queryActor = system.actorOf(DeviceGroupQuery.props(
      requestId = 1,
      requester = probe.ref,
      deviceRefToId = Map(device1.ref -> "device-1" , device2.ref -> "device-2"),
      timeout = 2.seconds
    ))

    queryActor.tell(DeviceGroup.RequestAllTemperatures(1), probe.ref)

    device1.expectMsg(Device.ReadTemperature(0))
    device2.expectMsg(Device.ReadTemperature(0))

    queryActor.tell(Device.RespondTemperature(0, Some(20.1)), device1.ref)
    queryActor.tell(Device.RespondTemperature(0, Some(29.2)), device2.ref)

    probe.expectMsg(
      DeviceGroup.ReplyAllTemperatures(
        1,
        Map(
          "device-1" -> DeviceGroup.Temperature(20.1),
          "device-2" -> DeviceGroup.Temperature(29.2)
        )
      )
    )
  }

  /*
  other tests for GroupQuery are:
  - a device has to tempearture reading
  - a device is stopped before responding
  - a device responded and then has been stopped
  - collection timeout before all readings
   */
}
