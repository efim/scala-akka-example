package example

import akka.actor.ActorSystem
import scala.io.StdIn

object IotApp {

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("iot-system")

    try {
      // loop level supervisor
      val supervisor = system.actorOf(IotSupervisor.props(), "iot-supervisor")
      // terminate on keyboard input
      StdIn.readLine()
    } finally {
      system.terminate()
    }
  }
}
