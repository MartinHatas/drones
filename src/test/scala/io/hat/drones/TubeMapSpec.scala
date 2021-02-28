package io.hat.drones

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import io.hat.drones.TrafficDrone.DroneProtocol
import io.hat.drones.TubeMap.Station
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

    val tubeMap = new TubeMap(stations)

    "responds with none tube stations in radius" in {
      val (lat, lon) = (52.503071, -0.280303)
      val probe = testKit.createTestProbe[DroneProtocol]()

      val stations = tubeMap.getStationsInRadius(lat, lon, radiusMeters = 300)

      assert(stations === Set())
    }

    "responds with single tube stations in radius" in {
      val (lat, lon) = (51.503071, -0.280303)
      val probe = testKit.createTestProbe[DroneProtocol]()

      val stations = tubeMap.getStationsInRadius(lat, lon, radiusMeters = 300)

      assert(stations === Set(Station("Acton Town", 51.503071, -0.280303)))

    }

    "responds with multiple tube stations in radius" in {
      val (lat, lon) = (51.503071, -0.280303)
      val probe = testKit.createTestProbe[DroneProtocol]()

      val stations = tubeMap.getStationsInRadius(lat, lon, radiusMeters = 15_000)

      assert(stations ===
        Set(
          Station("Acton Town", 51.503071,-0.280303),
          Station("Aldgate East", 51.51503,-0.073162),
          Station("Alperton", 51.541209,-0.299516),
          Station("Aldgate", 51.514342,-0.075627)
        )
      )
    }

    "respond with empty stations list if on requests with invalid latitude" in {
      val (invalidLat, lon) = (100, -0.280303)
      val probe = testKit.createTestProbe[DroneProtocol]()

      val stations = tubeMap.getStationsInRadius(invalidLat, lon, radiusMeters = 300)

      assert(stations === Set() )
    }

    "respond with empty stations list if on requests with invalid longtitude" in {
      val (lat, invalidLon) = (1.503071, -200)
      val probe = testKit.createTestProbe[DroneProtocol]()

      val stations = tubeMap.getStationsInRadius(lat, invalidLon, radiusMeters = 300)

      assert(stations === Set() )
    }

    "respond with empty stations list if on requests with negative radius" in {
      val (lat, lon) = (52.503071, -0.280303)
      val probe = testKit.createTestProbe[DroneProtocol]()

      val stations = tubeMap.getStationsInRadius(lat, lon, radiusMeters = -300)

      assert(stations === Set() )
    }

  }

}
