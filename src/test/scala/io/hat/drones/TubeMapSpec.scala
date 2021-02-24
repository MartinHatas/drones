package io.hat.drones

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

class TubeMapSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "TubeMap" must {

    "responds with none tube stations in radius" in {}

    "responds with single tube stations in radius" in {}

    "responds with multiple tube stations in radius" in {}

    "respond with empty stations list if on requests with invalid latitude" in {}

    "respond with empty stations list if on requests with invalid longtitude" in {}

    "respond with empty stations list if on requests with invalid radius" in {}

  }

}
