import sbt.Global

/*****************************************************************
 Global Settings & Configs
  *****************************************************************/
Global / name := "yadl"
Global / version := sys.env.getOrElse("BUILD_NUMBER", "LOCAL")
Global / organization := "epifab.io"
Global / scalaVersion := "2.13.0"
Global / exportJars := true
Global / parallelExecution := false
Global / scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Xfatal-warnings",
  "-language:existentials",
  "-language:higherKinds",
  // "-Xlint:_,-missing-interpolator",
)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "2.0.0-M4",
  "com.chuusai" %% "shapeless" % "2.3.3",
  "org.postgresql" % "postgresql" % "42.2.2",
  "org.scalatest" %% "scalatest" % "3.0.8" % Test
)

val circeVersion = "0.12.3"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-simple" % "1.7.22",
  "org.slf4j" % "slf4j-api" % "1.7.22",
  // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
  "ch.qos.logback" % "logback-core" % "1.1.7",
  "ch.qos.logback" % "logback-classic" % "1.1.7"
)
