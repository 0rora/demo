name := "luxe"
organization := "io.github.luxe-app"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  guice,
//  "org.webjars" % "bootstrap" % "4.1.3",

//  "org.webjars.npm" % "material-components-web" % "0.39.1",
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "io.github.lux-app.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "io.github.lux-app.binders._"
