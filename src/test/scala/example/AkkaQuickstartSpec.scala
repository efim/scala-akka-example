package example

import org.scalatest.{ BeforeAndAfterAll, FlatSpecLike, Matchers }

import akka.actor.{ ActorSystem }
import akka.testkit.{ TestKit, TestProbe }

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

}
