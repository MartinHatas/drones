package io.hat.drones

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.Attributes
import akka.stream.alpakka.csv.scaladsl.CsvParsing
import akka.stream.scaladsl.{FileIO, Sink}
import io.hat.drones.Simulation.Start
import io.hat.drones.TrafficDrone.DroneProtocol
import io.hat.drones.TubeMap.{Station, TubeMapProtocol}

import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

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
  case class Station(name: String, lat: Double, lon: Double)
  case class StationsAroundResponse(stations: Set[Station])

  def apply(stations: Seq[Station]): Behavior[TubeMapProtocol] = {
    Behaviors.receive[TubeMapProtocol] { (context, msg) =>
      msg match {
        case ScanStationsRequest(lat, lon, radius, replyTo) =>
      }
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

        val stationsLoading = FileIO.fromPath(Paths.get(startMessage.mapFileName))
          .via(CsvParsing.lineScanner())
          .map(csvRecord =>
            Station(
              name = csvRecord(0).utf8String,
              lat = csvRecord(1).utf8String.toDouble,
              lon = csvRecord(2).utf8String.toDouble)
            )
          .log("Tube station registered").addAttributes(Attributes.logLevels(onElement = Attributes.LogLevels.Info))
          .runWith(Sink.seq)

        val stations = Await.result(stationsLoading, 5 seconds)
        val tubeMap = context.spawn(TubeMap(stations), "tube-map")

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
