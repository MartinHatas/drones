package io.hat.drones

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.alpakka.csv.scaladsl.CsvParsing
import akka.stream.scaladsl.FileIO
import akka.stream.typed.scaladsl.ActorSink
import io.hat.drones.Simulation.Start
import io.hat.drones.TrafficDrone.DroneProtocol
import io.hat.drones.TubeMap.{Station, StationsReadComplete, StationsReadFailed, TubeMapProtocol}

import java.nio.file.Paths

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
  case class Station(name: String, lat: Double, lon: Double) extends TubeMapProtocol
  case object StationsReadComplete extends TubeMapProtocol
  case class StationsReadFailed(t: Throwable) extends TubeMapProtocol

  def apply(): Behavior[TubeMapProtocol] = {

    Behaviors.receive { (context, msg) =>
      context.log.info(msg.toString)
      Behaviors.same
    }
  }

}

object Simulation {

  case class Start(mapFileName: String, dronesIds: Set[String])

  def apply(): Behavior[Start] =
    Behaviors.setup { context =>

      Behaviors.receive { (context, startMessage)  =>

        implicit val system = context.system
        context.log.info(s"Starting simulation $startMessage")

        val tubeMap = context.spawn(TubeMap(), "tube-map")

        FileIO.fromPath(Paths.get(startMessage.mapFileName))
          .via(CsvParsing.lineScanner())
          .map(csvRecord =>
            Station(
              name = csvRecord(0).utf8String,
              lat = csvRecord(1).utf8String.toDouble,
              lon = csvRecord(2).utf8String.toDouble)
            )
          .runWith(ActorSink.actorRef[TubeMapProtocol](tubeMap, StationsReadComplete, StationsReadFailed))

        val drones = startMessage.dronesIds
          .map(droneId => (droneId -> context.spawn(TrafficDrone(droneId, tubeMap), s"drone-$droneId")))
          .toMap

        val dispatcher = context.spawn(Dispatcher(drones), "dispatcher")

        Behaviors.same
      }
    }
}

object DronesApp extends App {

  implicit val simulation: ActorSystem[Simulation.Start] = ActorSystem(Simulation(), "traffic-drones-system")

  simulation ! Start("data/tube.csv", Set("5937", "6043"))
}
