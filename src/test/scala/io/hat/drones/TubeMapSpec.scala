package io.hat.drones

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import io.hat.drones.TubeMap.{ScanStationsRequest, Station, StationsInRadiusResponse}
import org.scalatest.wordspec.AnyWordSpecLike

class TubeMapSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "TubeMap" must {

    val stations = Set(
      Station("Acton Town", 51.503071, -0.280303),
      Station("Aldgate", 51.514342, -0.075627),
      Station("Aldgate East", 51.51503, -0.073162),
      Station("All Saints (DLR)", 51.510477, -0.012625),
      Station("Alperton", 51.541209, -0.299516)
    )

    val tubeMap = testKit.spawn(TubeMap(stations), "tube-map-under-test")

    "responds with none tube stations in radius" in {
      val (lat, lon) = (52.503071, -0.280303)
      val probe = testKit.createTestProbe[StationsInRadiusResponse]()

      tubeMap ! ScanStationsRequest(lat, lon, radiusMeters = 300, replyTo = probe.ref)

      probe.expectMessage(StationsInRadiusResponse( Set() ))
    }

    "responds with single tube stations in radius" in {
      val (lat, lon) = (51.503071, -0.280303)
      val probe = testKit.createTestProbe[StationsInRadiusResponse]()

      tubeMap ! ScanStationsRequest(lat, lon, radiusMeters = 300, replyTo = probe.ref)

      probe.expectMessage(
        StationsInRadiusResponse( Set(Station("Acton Town", 51.503071, -0.280303)))
      )
    }

    "responds with multiple tube stations in radius" in {
      val (lat, lon) = (51.503071, -0.280303)
      val probe = testKit.createTestProbe[StationsInRadiusResponse]()

      tubeMap ! ScanStationsRequest(lat, lon, radiusMeters = 15_000, replyTo = probe.ref)

      probe.expectMessage(
        StationsInRadiusResponse( Set(
          Station("Acton Town", 51.503071,-0.280303),
          Station("Aldgate East", 51.51503,-0.073162),
          Station("Alperton", 51.541209,-0.299516),
          Station("Aldgate", 51.514342,-0.075627)
        ))
      )
    }

    "respond with empty stations list if on requests with invalid latitude" in {
      val (invalidLat, lon) = (100, -0.280303)
      val probe = testKit.createTestProbe[StationsInRadiusResponse]()

      tubeMap ! ScanStationsRequest(invalidLat, lon, radiusMeters = 300, replyTo = probe.ref)

      probe.expectMessage(StationsInRadiusResponse( Set() ))
    }

    "respond with empty stations list if on requests with invalid longtitude" in {
      val (lat, invalidLon) = (1.503071, -200)
      val probe = testKit.createTestProbe[StationsInRadiusResponse]()

      tubeMap ! ScanStationsRequest(lat, invalidLon, radiusMeters = 300, replyTo = probe.ref)

      probe.expectMessage(StationsInRadiusResponse( Set() ))
    }

    "respond with empty stations list if on requests with negative radius" in {
      val (lat, lon) = (52.503071, -0.280303)
      val probe = testKit.createTestProbe[StationsInRadiusResponse]()

      tubeMap ! ScanStationsRequest(lat, lon, radiusMeters = -300, replyTo = probe.ref)

      probe.expectMessage(StationsInRadiusResponse( Set() ))
    }

  }

}
