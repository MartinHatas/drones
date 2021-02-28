package io.hat.drones

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.alpakka.csv.scaladsl.CsvParsing
import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.{ByteString, Timeout}
import io.hat.drones.Simulation.Start
import io.hat.drones.TrafficDrone.{DroneProtocol, GoToPosition, StationsInRadiusResponse}
import io.hat.drones.TubeMap.{Station, TubeMapProtocol}

import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Success, Try}

object Dispatcher {

  trait DispatcherProtocol
  case class Report(droneId: String, time: LocalDateTime, speed: Double, trafficConditions: TrafficConditions)

  sealed trait TrafficConditions
  case object Heavy extends TrafficConditions
  case object Moderate extends TrafficConditions
  case object Light extends TrafficConditions

  case class SimulationEvent(time: LocalDateTime, droneId: String, lat: Double, lon: Double)
  object SimulationEvent {

    val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val mapper: List[ByteString] => SimulationEvent = {
      line => SimulationEvent(
        droneId = line(0).utf8String,
        lat = line(1).utf8String.toDouble,
        lon = line(2).utf8String.toDouble,
        time = LocalDateTime.parse(line(3).utf8String, dateFormat)
      )
    }
  }

  def apply(drones: Map[String, ActorRef[DroneProtocol]]): Behavior[DispatcherProtocol] = Behaviors.setup { context =>

    implicit val ac = context.system
    val simulationEvents = drones.keySet.toSeq
      .map(droneId => s"data/$droneId.csv")
      .map(filePath => CsvLoader.loadData(filePath, SimulationEvent.mapper))
      .flatMap(dronePlanLoading => Await.result(dronePlanLoading, 1 second))
      .sortWith { case (e1, e2) => e1.time.compareTo(e2.time) < 0 }

    context.log.info(s"Loaded [${simulationEvents.size}] instructions for drones [${drones.keySet.mkString(",")}].")

    simulationEvents.foreach { event =>
      drones(event.droneId) ! GoToPosition(event.lat, event.lon, event.time)
    }

    Behaviors.receive { (context, message) =>

      Behaviors.same
    }
  }

}

object TrafficDrone {

  val ScanRadiusMeters = 250

  trait DroneProtocol
  case class GoToPosition(lat: Double, lon: Double, time: LocalDateTime) extends DroneProtocol
  case class StationsInRadiusResponse(stations: Set[Station]) extends DroneProtocol

  case class DroneState(lat: Double, lon: Double, time: LocalDateTime)

  def apply(droneId: String, map: ActorRef[TubeMapProtocol]): Behavior[DroneProtocol] = {

    var state: Option[DroneState] = None

    Behaviors.receive { (context, message) =>

      implicit val ac = context.system.scheduler
      implicit val timeout: Timeout = 3 seconds

      message match {
        case GoToPosition(lat, lon, time) =>

//          map ? ScanStationsRequest(lat, lon , ScanRadiusMeters, context.self)

          state = Some(DroneState(lat, lon, time))

      }

      Behaviors.same
    }
  }
}

object TubeMap {

  val EarthRadiusMeters = 6378137d
  val LatMax = 90
  val LonMax = 180

  trait TubeMapProtocol
  case class ScanStationsRequest(lat: Double, lon: Double, radiusMeters: Int, replyTo: ActorRef[DroneProtocol]) extends TubeMapProtocol
  case class Station(name: String, lat: Double, lon: Double)

  def apply(stations: Set[Station]): Behavior[TubeMapProtocol] = {
    Behaviors.receiveMessagePartial[TubeMapProtocol] {
      case ScanStationsRequest(lat, lon, radius, replyTo) if positive(radius) && validLat(lat) && validLon(lon) =>
        val stationsInRadius = stations.filterNot(station => radius <= haversineDistance(lat, lon, station))
        replyTo ! StationsInRadiusResponse(stationsInRadius)
        Behaviors.same
      case ScanStationsRequest(_, _, _, replyTo) => replyTo ! StationsInRadiusResponse(Set())
        Behaviors.same
    }
  }

  def validLat(lat: Double) = -LatMax <= lat && lat <= LatMax
  def validLon(lon: Double) = -LonMax <= lon && lon <= LonMax
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

  val stationMapper: List[ByteString] => Station = {
    line => Station(name = line(0).utf8String, lat = line(1).utf8String.toDouble, lon = line(2).utf8String.toDouble)
  }

  def apply(): Behavior[Start] =
    Behaviors.setup { context =>

      Behaviors.receive { (context, startMessage)  =>

        implicit val system = context.system
        context.log.info(s"Starting simulation $startMessage")

        val stationsLoading = CsvLoader.loadData(startMessage.mapFileName, stationMapper)
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


object CsvLoader {

  import akka.util.ByteString

  def loadData[T](fileName: String, mapper: List[ByteString] => T)(implicit ac: ActorSystem[_]): Future[Seq[T]] = {
    FileIO.fromPath(Paths.get(fileName))
      .via(CsvParsing.lineScanner())
      .map(csvRecord => Try(mapper(csvRecord)))
      .collect{ case Success(element) => element }
      .runWith(Sink.seq)
  }

}