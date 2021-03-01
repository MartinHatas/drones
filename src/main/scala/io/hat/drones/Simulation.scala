package io.hat.drones

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.stream.alpakka.csv.scaladsl.CsvParsing
import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.ByteString
import io.hat.drones.Dispatcher.TrafficConditions.conditions
import io.hat.drones.Dispatcher.{DispatcherProtocol, Report}
import io.hat.drones.Simulation.Start
import io.hat.drones.TrafficDrone.{DroneProtocol, GoToPosition}
import io.hat.drones.TubeMap.{EarthRadiusMeters, LatMax, LonMax, Station}

import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Random, Success, Try}

object Dispatcher {

  trait DispatcherProtocol
  case class Report(station: Station, droneId: String, time: LocalDateTime, speed: Double, trafficConditions: TrafficConditions) extends DispatcherProtocol

  sealed trait TrafficConditions
  case object Heavy extends TrafficConditions
  case object Moderate extends TrafficConditions
  case object Light extends TrafficConditions

  object TrafficConditions {
    val conditions: Array[TrafficConditions] = Array(Heavy, Moderate, Light)
  }

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
      drones(event.droneId) ! GoToPosition(event.lat, event.lon, event.time, context.self)
    }

    Behaviors.receive { (context, message) =>
      message match {
        case r: Report => context.log.info(s"Received traffic report [$r]")
      }

      Behaviors.same
    }
  }

}

object TrafficDrone {

  val ScanRadiusMeters = 350

  trait DroneProtocol
  case class GoToPosition(lat: Double, lon: Double, time: LocalDateTime, replyTo: ActorRef[DispatcherProtocol]) extends DroneProtocol
  case class DroneState(lat: Double, lon: Double, time: LocalDateTime)

  def apply(droneId: String, tubeMap: TubeMap): Behavior[DroneProtocol] = {

    var state: Option[DroneState] = None

    Behaviors.receive { (context, message) =>

      def randomTrafficCondition = conditions(Random.nextInt(conditions.length))

      message match {
        case GoToPosition(newLat, newLon, newTime, dispatcher) =>
          val speedKmH = state match {
            case Some(DroneState(previousLat, previousLon, previousTime)) =>
              val distanceKm = tubeMap.haversineDistance(previousLat, previousLon, newLat, newLon) / 1000
              val timeHours = previousTime.until(newTime, ChronoUnit.SECONDS) / (60d * 60d)
              distanceKm / timeHours
            case None => 0d
          }

          tubeMap
            .getStationsInRadius(newLat, newLon, ScanRadiusMeters)
            .map(station => Report(station, droneId, newTime, speedKmH, randomTrafficCondition))
            .foreach(dispatcher ! _)

          state = Some(DroneState(newLat, newLon, newTime))

      }

      Behaviors.same
    }
  }

}


class TubeMap(stations: Set[Station]) {

  def getStationsInRadius(lat: Double, lon: Double, radiusMeters: Int): Set[Station] = {
    if(positive(radiusMeters) && validLat(lat) && validLon(lon))
      stations.filterNot(station => radiusMeters <= haversineDistance(lat, lon, station.lat, station.lon))
    else
      Set()
  }

  private def validLat(lat: Double) = -LatMax <= lat && lat <= LatMax
  private def validLon(lon: Double) = -LonMax <= lon && lon <= LonMax
  private def positive(radius: Int) = radius >= 0

  def haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = {
    val deltaLat = math.toRadians(lat2 - lat1)
    val deltaLon = math.toRadians(lon2 - lon1)
    val droneLatRad = math.toRadians(lat1)
    val stationLatRad = math.toRadians(lat2)

    val a = math.pow(math.sin(deltaLat / 2), 2) +
      math.pow(math.sin(deltaLon / 2), 2) * math.cos(droneLatRad) * math.cos(stationLatRad)
    val c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    val distanceMeters = EarthRadiusMeters * c
    distanceMeters
  }
}

object TubeMap {

  val EarthRadiusMeters = 6378137d
  val LatMax = 90
  val LonMax = 180

  case class Station(name: String, lat: Double, lon: Double)
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
        val tubeMap = new TubeMap(stations.toSet)

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