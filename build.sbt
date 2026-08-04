import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.CrossPlugin.autoImport.*
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*
import _root_.org.typelevel.sbt.TypelevelSitePlugin
import _root_.sbtversionpolicy.Compatibility
import _root_.sbtversionpolicy.SbtVersionPolicyPlugin

lazy val apiDocsCheck = taskKey[Unit](
  "Reject stale example pages and unresolved frame4s links in generated API documentation."
)

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.7.4"
// Generate and validate the public site in CI. Deployment remains an explicit
// release-owner action until GitHub Pages and its permissions are verified.
ThisBuild / tlSitePublishBranch := None
ThisBuild / tlSitePublishTags := false
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / versionPolicyIntention := Compatibility.BinaryAndSourceCompatible
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
ThisBuild / versionPolicyPreviousVersions := {
  val current = version.value
  if (current.startsWith("0.1.0")) Seq.empty
  else Seq("0.1.0")
}
Global / excludeLintKeys += versionPolicyPreviousVersions

def rehearsalRepository: File =
  file(
    sys.props.getOrElse(
      "frame4s.rehearsal.repo",
      "target/release-rehearsal/repository"
    )
  )

def rehearsalResolver: Resolver =
  "frame4s-release-rehearsal" at rehearsalRepository.toURI.toString

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xmax-inlines:64",
    // Fatal warnings are what make the exhaustivity guarantees in the plan/expression ADTs
    // load-bearing: a future `case _ =>` over a sealed hierarchy fails the build instead of
    // scrolling past. `-Wunused` deliberately omits explicits/implicits/params, since the typed
    // API uses unused `using` evidence from its closed operator capabilities as type-level
    // constraints.
    "-Werror",
    "-Wunused:imports,privates,locals",
    "-Wvalue-discard",
    "-Wnonunit-statement"
  ),
  Test / fork := false,
  libraryDependencies += "org.scalameta" %%% "munit" % "1.3.0" % Test
)

lazy val arrowJvmOptions = Seq("--add-opens=java.base/java.nio=ALL-UNNAMED")

lazy val core =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/core"))
    .settings(commonSettings)
    .settings(
      name := "frame4s-core",
      description := "Immutable, typed local dataframe library for Scala 3."
    )
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
    )

lazy val testkit =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/testkit"))
    .dependsOn(core)
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-testkit",
      description := "Repository-internal conformance laws and generators for frame4s.",
      publish / skip := true,
      libraryDependencies ++= Seq(
        "org.scalacheck" %%% "scalacheck" % "1.19.0",
        "org.scalameta" %%% "munit-scalacheck" % "1.3.0" % Test
      )
    )
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
    )

lazy val fs2 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/fs2"))
    .dependsOn(core)
    .dependsOn(testkit % "test->compile")
    .settings(commonSettings)
    .settings(
      name := "frame4s-fs2",
      description := "fs2 streaming integration for frame4s.",
      libraryDependencies ++= Seq(
        "org.typelevel" %%% "cats-effect" % "3.7.0",
        "co.fs2" %%% "fs2-core" % "3.13.0"
      )
    )
    .jvmSettings(
      libraryDependencies += "co.fs2" %% "fs2-io" % "3.13.0",
      Compile / doc / scalacOptions ++= Seq(
        "-external-mappings:.*modules/core/jvm/target/.*::scaladoc3::https://www.javadoc.io/doc/io.github.canardlapin/frame4s-core_3/latest/",
        "-skip-by-id:frame4s.fs2.examples"
      ),
      Compile / doc / sources :=
        (Compile / sources).value.filterNot(_.getPath.contains("/examples/")),
      Compile / run / fork := true,
      Compile / run / javaOptions ++= Seq(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
      )
    )
    .jsSettings(
      Compile / doc / scalacOptions +=
        "-external-mappings:.*modules/core/js/target/.*::scaladoc3::https://www.javadoc.io/doc/io.github.canardlapin/frame4s-core_sjs1_3/latest/",
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
    )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val testkitJVM = testkit.jvm
lazy val testkitJS = testkit.js
lazy val fs2JVM = fs2.jvm
lazy val fs2JS = fs2.js

lazy val arrow =
  project
    .in(file("modules/arrow"))
    .dependsOn(fs2JVM)
    .settings(commonSettings)
    .settings(
      name := "frame4s-arrow",
      description := "Optional Apache Arrow IPC adapter for frame4s on the JVM.",
      libraryDependencies ++= Seq(
        "org.apache.arrow" % "arrow-vector" % "19.0.0",
        "org.apache.arrow" % "arrow-memory-unsafe" % "19.0.0"
      ),
      Compile / doc / scalacOptions ++= Seq(
        "-external-mappings:.*modules/core/jvm/target/.*::scaladoc3::https://www.javadoc.io/doc/io.github.canardlapin/frame4s-core_3/latest/",
        "-external-mappings:.*modules/fs2/jvm/target/.*::scaladoc3::https://www.javadoc.io/doc/io.github.canardlapin/frame4s-fs2_3/latest/"
      ),
      Compile / run / fork := true,
      Compile / run / javaOptions ++= arrowJvmOptions,
      Test / fork := true,
      Test / javaOptions ++= arrowJvmOptions
    )

lazy val benchmarks =
  project
    .in(file("modules/benchmarks"))
    .dependsOn(coreJVM, fs2JVM)
    .enablePlugins(JmhPlugin)
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-benchmarks",
      description := "Non-published JMH measurement court for frame4s.",
      publish / skip := true,
      libraryDependencies += "io.github.pityka" %% "saddle-core" % "4.0.0-M14",
      // CourtRunner launches JMH forks. A forked sbt `run` gives those children a concrete
      // dependency classpath instead of sbt's layered in-process classloader.
      Compile / run / fork := true
    )

/** Compiled from a package outside `frame4s` so release gates exercise the same public visibility
  * and implicit-resolution boundary as a real downstream build.
  */
lazy val firstContact =
  project
    .in(file("modules/first-contact"))
    .dependsOn(fs2JVM)
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-first-contact",
      description := "Non-published downstream first-contact release specimen.",
      publish / skip := true,
      Compile / run / fork := true,
      Compile / run / javaOptions ++= Seq(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
      )
    )

lazy val docs =
  project
    .in(file("modules/docs"))
    .dependsOn(coreJVM, fs2JVM, arrow)
    .enablePlugins(TypelevelSitePlugin)
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-docs",
      description := "Executable user guides for frame4s.",
      publish / skip := true,
      mdocIn := (ThisBuild / baseDirectory).value / "docs" / "guide",
      mdocOut := target.value / "mdoc",
      scalacOptions --= Seq(
        "-Werror",
        "-Wunused:imports,privates,locals",
        "-Wvalue-discard",
        "-Wnonunit-statement"
      )
    )

lazy val stagedConsumerJVM =
  project
    .in(file("modules/staged-consumer-jvm"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-staged-consumer-jvm",
      publish / skip := true,
      resolvers ++=
        (if (sys.props.get("frame4s.rehearsal.enabled").contains("true"))
           Seq(rehearsalResolver)
         else Seq.empty),
      libraryDependencies ++= Seq(
        "io.github.canardlapin" %% "frame4s-core" % version.value,
        "io.github.canardlapin" %% "frame4s-fs2" % version.value
      ),
      Compile / run / fork := true,
      Compile / mainClass := Some("consumer.StagedConsumer")
    )

lazy val stagedConsumerJS =
  project
    .in(file("modules/staged-consumer-js"))
    .enablePlugins(ScalaJSPlugin)
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-staged-consumer-js",
      publish / skip := true,
      resolvers ++=
        (if (sys.props.get("frame4s.rehearsal.enabled").contains("true"))
           Seq(rehearsalResolver)
         else Seq.empty),
      libraryDependencies ++= Seq(
        "io.github.canardlapin" %%% "frame4s-core" % version.value,
        "io.github.canardlapin" %%% "frame4s-fs2" % version.value
      ),
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      scalaJSUseMainModuleInitializer := true,
      Compile / mainClass := Some("consumer.StagedConsumer")
    )

lazy val stagedConsumerArrow =
  project
    .in(file("modules/staged-consumer-arrow"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(
      name := "frame4s-staged-consumer-arrow",
      publish / skip := true,
      resolvers ++=
        (if (sys.props.get("frame4s.rehearsal.enabled").contains("true"))
           Seq(rehearsalResolver)
         else Seq.empty),
      libraryDependencies +=
        "io.github.canardlapin" %% "frame4s-arrow" % version.value,
      Compile / run / fork := true,
      Compile / run / javaOptions ++= arrowJvmOptions,
      Compile / mainClass := Some("consumer.StagedArrowConsumer")
    )

def stagedWideSettings(width: Int) =
  Seq(
    name := s"frame4s-staged-wide-$width",
    publish / skip := true,
    resolvers ++=
      (if (sys.props.get("frame4s.rehearsal.enabled").contains("true"))
         Seq(rehearsalResolver)
       else Seq.empty),
    libraryDependencies +=
      "io.github.canardlapin" %%% "frame4s-core" % version.value,
    scalacOptions ~= (_.filterNot(_.startsWith("-Xmax-inlines"))),
    Compile / sourceGenerators += Def.task {
      WideSchemaGenerator.generate((Compile / sourceManaged).value, width)
    }.taskValue,
    Compile / mainClass := Some(s"consumer.WideSchema$width")
  )

lazy val stagedWide32 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/staged-consumer-wide/32"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(stagedWideSettings(32))
    .jvmSettings(Compile / run / fork := true)
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      scalaJSUseMainModuleInitializer := true
    )

lazy val stagedWide48 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/staged-consumer-wide/48"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(stagedWideSettings(48))
    .jvmSettings(Compile / run / fork := true)
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      scalaJSUseMainModuleInitializer := true
    )

lazy val stagedWide128 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/staged-consumer-wide/128"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(stagedWideSettings(128))
    .jvmSettings(Compile / run / fork := true)
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      scalaJSUseMainModuleInitializer := true
    )

lazy val stagedWide256 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/staged-consumer-wide/256"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(stagedWideSettings(256))
    .jvmSettings(Compile / run / fork := true)
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      scalaJSUseMainModuleInitializer := true
    )

lazy val stagedWide512 =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/staged-consumer-wide/512"))
    .disablePlugins(SbtVersionPolicyPlugin)
    .settings(commonSettings)
    .settings(stagedWideSettings(512))
    .jvmSettings(Compile / run / fork := true)
    .jsSettings(
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
      scalaJSUseMainModuleInitializer := true
    )

lazy val stagedWide32JVM = stagedWide32.jvm
lazy val stagedWide32JS = stagedWide32.js
lazy val stagedWide48JVM = stagedWide48.jvm
lazy val stagedWide48JS = stagedWide48.js
lazy val stagedWide128JVM = stagedWide128.jvm
lazy val stagedWide128JS = stagedWide128.js
lazy val stagedWide256JVM = stagedWide256.jvm
lazy val stagedWide256JS = stagedWide256.js
lazy val stagedWide512JVM = stagedWide512.jvm
lazy val stagedWide512JS = stagedWide512.js

lazy val root =
  project
    .in(file("."))
    .disablePlugins(SbtVersionPolicyPlugin)
    .aggregate(
      coreJVM,
      coreJS,
      testkitJVM,
      testkitJS,
      fs2JVM,
      fs2JS,
      arrow,
      firstContact,
      docs
    )
    .settings(
      name := "frame4s",
      publish / skip := true,
      apiDocsCheck := {
        val coreDocs = Seq(
          (coreJVM / Compile / doc / target).value,
          (coreJS / Compile / doc / target).value
        )
        val fs2Docs = Seq(
          (fs2JVM / Compile / doc / target).value,
          (fs2JS / Compile / doc / target).value
        )
        val arrowDocs = Seq((arrow / Compile / doc / target).value)
        val unresolvedFrame4sLink =
          """data-unresolved-link=""[^>]*>(Frame|DynamicFrame|ExprOf|Table|RecordBatch|Schema|SchemaDescriptor|SourceRef|LogicalPlan|ReferenceInterpreter|FrameError|StorageError|ExecutionError|TableReadError|ColumnArray|DataType|Field|RowCodec|FrameRuntime|FrameSource|FrameSink|CsvFrameSource|TsvFrameSource|CsvPathSource|TsvPathSource|ArrowIpcFrameSource|ArrowIpcFrameSink|SourceError|SinkError|RuntimeBindingError)</span>""".r
        // Scala 3.7.4 Scaladoc emits an unlinked parent-enum span on generated
        // `Enum$$Case.html` pages. Scan every authored/top-level page on both
        // platforms while leaving that compiler-owned hierarchy artifact
        // visible in the output.
        val corePages = coreDocs
          .flatMap(root => (root ** "*.html").get)
          .filterNot(_.getName.contains("$$"))
        val fs2Pages = (fs2Docs ++ arrowDocs)
          .flatMap(root => (root ** "*.html").get)
          .filterNot(_.getName.contains("$$"))
        val unresolved = (corePages ++ fs2Pages)
          .filter(file => unresolvedFrame4sLink.findFirstIn(IO.read(file)).nonEmpty)
        val staleExamples = fs2Docs.flatMap { root =>
          Seq(
            root / "frame4s" / "fs2" / "examples.html",
            root / "frame4s" / "fs2" / "examples"
          ).filter(_.exists)
        }

        if (unresolved.nonEmpty)
          sys.error(
            unresolved
              .map(file => s"unresolved frame4s API link in ${file.getPath}")
              .mkString("\n")
          )
        if (staleExamples.nonEmpty)
          sys.error(
            staleExamples
              .map(file => s"excluded example API page is present: ${file.getPath}")
              .mkString("\n")
          )
      }
    )

addCommandAlias(
  "compileAll",
  ";coreJVM/compile;coreJS/compile;testkitJVM/compile;testkitJS/compile;fs2JVM/compile;fs2JS/compile;arrow/compile;firstContact/compile"
)
addCommandAlias(
  "testAll",
  ";coreJVM/test;coreJS/test;testkitJVM/test;testkitJS/test;fs2JVM/test;fs2JS/test;arrow/test;firstContact/test"
)
addCommandAlias("benchmarkSmoke", ";benchmarks/Jmh/compile")
addCommandAlias(
  "formatCheck",
  ";scalafmtCheckAll;benchmarks/scalafmtCheck;stagedConsumerJVM/scalafmtCheck;stagedConsumerJS/scalafmtCheck;stagedConsumerArrow/scalafmtCheck;scalafmtSbtCheck"
)
addCommandAlias("docsCheck", ";docs/tlSite")
addCommandAlias(
  "apiDocs",
  ";coreJVM/Compile/doc;coreJS/Compile/doc;fs2JVM/Compile/doc;fs2JS/Compile/doc;arrow/Compile/doc;apiDocsCheck"
)
