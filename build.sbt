import sbt._
import Keys._

lazy val buildSettings = Seq(
  name := "trader4s",
  version := "0.1",
  description := "Reliable execution and fast market data for IBKR",
  scalaVersion := "2.13.6",
  javacOptions := Seq("-source", "11", "-target", "11"),
  resolvers += Resolver.sbtPluginRepo("releases"),
  scalacOptions ++= Seq("-Xfatal-warnings", "-feature", "-deprecation", "-Ywarn-value-discard"),
  Test / fork := true,
//  Test   / javaOptions += s"-Dlogback.configurationFile=$logbackXmlPath",
  Test / javaOptions += s"-Dfile.ending=UTF8",
//  Test   / javaOptions += s"-Djavax.net.debug=all",
  Test   / scalacOptions ++= Seq("-Yrangepos"),
  Global / onChangedBuildSource := ReloadOnSourceChanges,
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.0").cross(CrossVersion.full)),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
  // Test / javaOptions += "-Dlogback.statusListenerClass=ch.qos.logback.core.status.OnConsoleStatusListener"
)

lazy val sbtAssemblySettings = Seq(
  )

lazy val formatting = Seq(
  )

lazy val allSettings = buildSettings ++ sbtAssemblySettings ++ formatting

lazy val processMapperAnnotations      = taskKey[Unit]("Process Object Mapper annotations in compiled Scala classes")
lazy val compileMapperGeneratedSources = taskKey[Unit]("Compile the sources that were generated by the Object Mapper")

def runCommand(command: String, message: => String, log: Logger): Unit = {
  import scala.sys.process._

  val result = command !

  if (result != 0) {
    log.error(message)
    sys.error("Failed running command: " + command)
  }
}

lazy val ibCassandraModel = project
  .settings(moduleName := "cat-trader-cassandra-dao")
  .settings(allSettings)
  .settings(
    Compile / unmanagedJars += file("/Users/pavel.voropaev/IdeaProjects/mltrader/thirdPartyJars/ibkr/ApiDemo.jar"),
    Compile / unmanagedJars += file("/Users/pavel.voropaev/IdeaProjects/mltrader/thirdPartyJars/ibkr/TwsApi_debug.jar")
  )
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.catsEffect,
      Dependencies.Libraries.fs2rx,
      Dependencies.Libraries.javaDriverMapperRuntime,
      Dependencies.Libraries.javaDriverMapperProcessor % "provided"
    ),
    processMapperAnnotations := {
      val log = streams.value.log

      log.info("Processing Object Mapper annotations in compiled Scala classes...")

      val classpath       = ((Compile / products).value ++ (Compile / dependencyClasspath).value.files).mkString(":")
      val sourceDirectory = (Compile / classDirectory).value
      val classesToProcess = (sourceDirectory ** ("*.class" -- ("*Builder.class" || "*MapperGenerated.class")))
        .getPaths()
        .map(_.stripPrefix(sourceDirectory.getAbsolutePath + "/").stripSuffix(".class").replaceAll("/", "."))
        .mkString(" ")
      val destinationDirectory = (Compile / sourceManaged).value / "mapper"
      destinationDirectory.mkdirs()

      val processor = "com.datastax.oss.driver.internal.mapper.processor.MapperProcessor"

      val command =
        s"""javac
           | -classpath $classpath
           | -proc:only -processor $processor
           | -d $destinationDirectory
           | $classesToProcess""".stripMargin
      runCommand(command, "Failed to run Object Mapper processor", log)

      log.info("Done processing Object Mapper annotate ons in compiled Scala classes")
    },
    compileMapperGeneratedSources := {
      val log = streams.value.log

      log.info("Compiling Object Mapper generated sources...")

      val classpath       = ((Compile / products).value ++ (Compile / dependencyClasspath).value.files).mkString(":")
      val sourceDirectory = (Compile / sourceManaged).value / "mapper"
      val javaSources     = (sourceDirectory ** "*.java").getPaths().mkString(" ")

      val command =
        s"""javac
           | -classpath $classpath
           | -d ${(Compile / classDirectory).value}
           | $javaSources""".stripMargin
      runCommand(command, "Failed to compile mapper-generated sources", log)

      log.info("Done compiling Object Mapper generated sources")
    },
    Compile / compileMapperGeneratedSources := (Compile / compileMapperGeneratedSources)
      .dependsOn(Compile / processMapperAnnotations)
      .value,
    Compile / packageBin := (Compile / packageBin).dependsOn(Compile / compileMapperGeneratedSources).value
  )

lazy val ibFeed = project
  .settings(moduleName := "cat-trader-core")
  .settings(allSettings)
  .settings(
    Compile / compile := (Compile / compile).dependsOn(ibCassandraModel / Compile / compileMapperGeneratedSources).value
  )
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.cassandraMigrator,
      Dependencies.Libraries.cassandraCore,
      Dependencies.Libraries.catsCore,
      Dependencies.Libraries.pureConfig,
      Dependencies.Libraries.kittens,
      Dependencies.Libraries.catsEffect,
      Dependencies.Libraries.scalaLogging,
      Dependencies.Libraries.log4cats,
      Dependencies.Libraries.logback,
      Dependencies.Libraries.fs2io,
      Dependencies.Libraries.mapref,
      Dependencies.Libraries.fs2core,
      Dependencies.Libraries.scalatest,
      Dependencies.Libraries.ceTestKit,
      Dependencies.Libraries.ceTesting,
      Dependencies.Libraries.scalactic
    )
  )
  .dependsOn(ibCassandraModel)

lazy val trader4s = project.in(file(".")).aggregate(ibCassandraModel, ibFeed)
