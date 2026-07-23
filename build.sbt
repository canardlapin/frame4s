import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.CrossPlugin.autoImport.*
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage := Some(url("https://github.com/canardlapin/frame4s"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/canardlapin/frame4s"),
    "scm:git:git@github.com:canardlapin/frame4s.git"
  )
)
ThisBuild / licenses := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / developers := List(
  Developer(
    "canardlapin",
    "canardlapin",
    "307091466+canardlapin@users.noreply.github.com",
    url("https://github.com/canardlapin")
  )
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xmax-inlines:64"),
  Test / fork := false,
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.1" % Test
)

lazy val core =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/core"))
    .settings(commonSettings)
    .settings(name := "frame4s-core")
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
    )

lazy val fs2 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/fs2"))
    .dependsOn(core)
    .settings(commonSettings)
    .settings(
      name := "frame4s-fs2",
      libraryDependencies ++= Seq(
        "org.typelevel" %%% "cats-effect" % "3.7.0",
        "co.fs2" %%% "fs2-core" % "3.13.0"
      )
    )
    .jvmSettings(
      libraryDependencies ++= Seq(
        "org.apache.arrow" % "arrow-vector" % "19.0.0",
        "org.apache.arrow" % "arrow-memory-unsafe" % "19.0.0"
      ),
      Test / fork := true,
      Test / javaOptions += "--add-opens=java.base/java.nio=ALL-UNNAMED"
    )
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
    )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val fs2JVM = fs2.jvm
lazy val fs2JS = fs2.js

lazy val root =
  project
    .in(file("."))
    .aggregate(coreJVM, coreJS, fs2JVM, fs2JS)
    .settings(
      name := "frame4s",
      publish / skip := true
    )

addCommandAlias("compileAll", ";coreJVM/compile;coreJS/compile;fs2JVM/compile;fs2JS/compile")
addCommandAlias("testAll", ";coreJVM/test;coreJS/test;fs2JVM/test;fs2JS/test")
