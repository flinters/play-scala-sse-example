val commonSettings = Seq(
  organization := "jp.co.septeni_original",
  licenses := Seq("MIT" -> url("http://opensource.org/licenses/MIT")),
  scalaVersion := "2.11.11",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint"
  )
)

lazy val root = (project in file("."))
  .settings(commonSettings :_*)
  .settings(
    name := "play-scala-sse-example"
  )
  .enablePlugins(PlayScala)
