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
import scala.util.{Success, Try}

object Dispatcher {

  trait DispatcherProtocol

  def apply(drones: Map[String, ActorRef[DroneProtocol]]): Behavior[DispatcherProtocol] = Behaviors.setup { context =>

    Behaviors.receive { (context, message) =>

      Behaviors.same
    }
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

  val EarthRadiusMeters = 6378137d

  trait TubeMapProtocol
  case class ScanStationsRequest(lat: Double, lon: Double, radiusMeters: Int, replyTo: ActorRef[StationsInRadiusResponse]) extends TubeMapProtocol
  case class Station(name: String, lat: Double, lon: Double)
  case class StationsInRadiusResponse(stations: Set[Station])

  def apply(stations: Set[Station]): Behavior[TubeMapProtocol] = {
    Behaviors.receive[TubeMapProtocol] { (context, msg) =>
      msg match {
        case ScanStationsRequest(lat, lon, radius, replyTo) if positive(radius) && validLat(lat) && validLon(lon) =>
          val stationsInRadius = stations
            .filterNot(station => radius <= haversineDistance(lat, lon, station) )
          replyTo ! StationsInRadiusResponse(stationsInRadius)
        case ScanStationsRequest(_, _, _, replyTo) => replyTo ! StationsInRadiusResponse(Set())
      }
      Behaviors.same
    }
  }

  def validLat(lat: Double) = -90 <= lat && lat <= 90

  def validLon(lat: Double) = -180 <= lat && lat <= 120

  def positive(radius: Int) = radius >= 0

  def haversineDistance(droneLat: Double, droneLon: Double, station: Station): Double = {
    val deltaLat = math.toRadians(station.lat - droneLat)
    val deltaLon = math.toRadians(station.lon - droneLon)
    val droneLatRad = math.toRadians(droneLat)
    val stationLatRad = math.toRadians(station.lat)

    val a = math.pow(math.sin(deltaLat / 2), 2) +
        math.pow(math.sin(deltaLon / 2), 2) * math.cos(droneLatRad) * math.cos(stationLatRad)
    val c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    val distanceMeters = EarthRadiusMeters * c
    distanceMeters
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
            Try(Station(
              name = csvRecord(0).utf8String,
              lat = csvRecord(1).utf8String.toDouble,
              lon = csvRecord(2).utf8String.toDouble)
            ))
          .collect{ case Success(station) => station }
          .log("Tube station registered").addAttributes(Attributes.logLevels(onElement = Attributes.LogLevels.Info))
          .runWith(Sink.seq)

        val stations = Await.result(stationsLoading, 5 seconds)
        val tubeMap = context.spawn(TubeMap(stations.toSet), "tube-map")

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
