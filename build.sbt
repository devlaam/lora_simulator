scalaVersion := "2.11.7"

scalacOptions += "-feature"

libraryDependencies := {
  CrossVersion.partialVersion(scalaVersion.value) match {
    // if scala 2.11+ is used, add dependency on scala-xml module
    case Some((2, scalaMajor)) if scalaMajor >= 11 =>
      libraryDependencies.value ++ Seq(
        "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
        "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3",
        "org.scala-lang.modules" %% "scala-swing" % "1.0.1")
    case _ =>
      // or just libraryDependencies.value if you don't depend on scala-swing
      libraryDependencies.value :+ "org.scala-lang" % "scala-swing" % scalaVersion.value
  }
}

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.12"

libraryDependencies += "commons-codec" % "commons-codec" % "1.6"

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.3.9"

libraryDependencies +=  "org.scalaj" %% "scalaj-http" % "1.1.5"

libraryDependencies +=  "com.messagebird" % "messagebird-api" % "1.0.1"

//libraryDependencies += "org.apache.httpcomponents" % "httpcore" % "4.4.1"

//libraryDependencies += "org.apache.httpcomponents" % "httpclient" % "4.5"
