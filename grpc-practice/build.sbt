ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.10"


lazy val root = (project in file("."))
  .settings(
    name := "grpc-practice"
  )

Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)

// (optional) If you need scalapb/scalapb.proto or anything from
// google/protobuf/*.proto
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.9" % Test,
  "org.scalamock" %% "scalamock" % "5.1.0" % Test,
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
  "io.grpc" % "grpc-stub" % scalapb.compiler.Version.grpcJavaVersion
)