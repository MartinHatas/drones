package io.hat.drones

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import io.hat.drones.Simulation.Start
import io.hat.drones.TrafficDrone.DroneProtocol
import io.hat.drones.TubeMap.TubeMapProtocol

object Dispatcher {

  trait DispatcherProtocol

  def apply(drones: Map[String, ActorRef[DroneProtocol]]): Behavior[DispatcherProtocol] = Behaviors.receive { (context, message) =>
    Behaviors.same
  }
}

object TrafficDrone {

  trait DroneProtocol

  def apply(droneId: String, map: ActorRef[TubeMapProtocol]): Behavior[DroneProtocol] = {
    Behaviors.receive { (context, message) =>
      Behaviors.same
    }
  }
}


object TubeMap {

  trait TubeMapProtocol
  case class ScanStationsRequest(lat: Double, lon: Double, radius: Int, replyTo: ActorRef[_]) extends TubeMapProtocol
  case class StationsAroundResponse(stations: Set[Station])
  case class Station(name: String, lat: Double, lon: Double)

  def apply(mapFileName: String): Behavior[TubeMapProtocol] = {

    val map = ??? // load  map from a file

    Behaviors.receive { (context, stationsRequest) =>

      Behaviors.same
    }
  }

}

object Simulation {

  case class Start(mapFileName: String, dronesIds: Set[String])

  def apply(): Behavior[Start] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage { startMessage =>

        val tubeMap = context.spawn(TubeMap(startMessage.mapFileName), "tube-map")
        val drones = startMessage.dronesIds
          .map(droneId => (droneId -> context.spawn(TrafficDrone(droneId, tubeMap), s"drone-$droneId")))
          .toMap

        val dispatcher = context.spawn(Dispatcher(drones), "dispatcher")

        Behaviors.same
      }
    }
}

object DronesApp extends App {

  val simulation: ActorSystem[Simulation.Start] = ActorSystem(Simulation(), "traffic-drones-system")

  simulation ! Start("tube.csv", Set("5937", "6043"))
}
