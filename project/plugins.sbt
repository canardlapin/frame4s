addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.1")
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % "0.8.7")
// 2.6.x requires sbt 1.12.9+; 2.5.6 is the newest line compatible with the
// ratified sbt 1.10.5 court.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.3.0")
// 1.9.x publishes through the retired legacy OSSRH endpoint. 1.11.x uses the
// Central Portal when the release job runs on sbt 1.11 or newer.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
