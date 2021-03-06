import sbt._

object Version {
  val akka      = "2.4.1"
  val logback   = "1.1.3"
  val scala     = "2.11.7"
  val scalaTest = "2.2.5"
  val spray     = "1.3.3"
  val sprayJson = "1.3.2"
  val mockito   = "1.10.19"
}

object Library {
  val akkaActor       = "com.typesafe.akka" %% "akka-actor"                    % Version.akka
  val akkaSlf4j       = "com.typesafe.akka" %% "akka-slf4j"                    % Version.akka
  val akkaTestkit     = "com.typesafe.akka" %% "akka-testkit"                  % Version.akka
  val logbackClassic  = "ch.qos.logback"    %  "logback-classic"               % Version.logback
  val scalaTest       = "org.scalatest"     %% "scalatest"                     % Version.scalaTest
  val sprayCan        = "io.spray"          %% "spray-can"                     % Version.spray
  val sprayJson       = "io.spray"          %% "spray-json"                    % Version.sprayJson
  val sprayRouting    = "io.spray"          %% "spray-routing"                 % Version.spray
  val mockito         = "org.mockito"       %  "mockito-core"                  % Version.mockito
}

object Dependencies {

  import Library._

  val AkkaFtp = List(
    akkaActor,
    akkaSlf4j,
    logbackClassic,
    sprayCan,
    sprayJson,
    sprayRouting,
    akkaTestkit % "test",
    scalaTest   % "test,it",
    mockito     % "test"
  )
}
