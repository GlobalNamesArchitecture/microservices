import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._
import com.typesafe.sbt.SbtPgp.autoImport._
import com.twitter.scrooge.ScroogeSBT.autoImport._
import sbtbuildinfo.BuildInfoKey
import sbtbuildinfo.BuildInfoKeys._
import slick.codegen.SourceCodeGenerator
import slick.{ model => m }
import com.github.tototoshi.sbt.slick.CodegenPlugin.autoImport._
import spray.revolver.RevolverPlugin.autoImport._
import wartremover.WartRemover.autoImport._

object Settings {

  lazy val testReporter = Seq(
    testOptions := Seq(
      Tests.Argument("-h", "target/test-html"),
      Tests.Argument("-u", "target/test-xml"),
      Tests.Argument("-C", "org.globalnames.TestReporter"),
      // Configuring summaries has no effect when running with SBT
      Tests.Argument("-oD"),
      // T = full backtraces, NCXEHLOPQRM = Ignore all events that are already sent to out (SBT)
      Tests.Argument("-eTNCXEHLOPQRM"),
      Tests.Filter { testName => !testName.contains("Integration") }
    )
  )

  lazy val settings = testSettings ++ Seq(
    version := {
      val version = "0.1.0"
      val release = sys.props.isDefinedAt("release")
      if (release) version
      else version + sys.props.get("buildNumber").map { "-" + _ }.getOrElse("") + "-SNAPSHOT"
    },
    scalaVersion := "2.11.12",
    homepage := Some(new URL("http://globalnames.org/")),
    organization in ThisBuild := "org.globalnames",
    description := "Family of Global Names microservices",
    startYear := Some(2015),
    licenses := Seq("MIT" ->
      new URL("https://github.com/GlobalNamesArchitecture/gnmicroservices/blob/master/LICENSE")),
    resolvers ++= Seq(
      "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
      Resolver.sonatypeRepo("snapshots")
    ),
    javacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-source", "1.8",
      "-target", "1.8",
      "-Xlint:unchecked",
      "-Xlint:deprecation"),
    scalacOptions ++= List(
      "-encoding", "UTF-8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Xlint",
      "-language:_",
      "-target:jvm-1.8",
      "-Xlog-reflective-calls"),

    scalacOptions in Test ++= Seq("-Yrangepos"),

    scroogeThriftDependencies in Compile := Seq("finatra-thrift_2.11"),

    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.globalnames.index",

    test in assembly := {},
    target in assembly := file(baseDirectory.value + "/../bin/"),
    assemblyMergeStrategy in assembly := {
      case "logback.xml" => MergeStrategy.last
      case PathList("META-INF", xs@_*) => MergeStrategy.discard
      case n if n.startsWith("reference.conf") => MergeStrategy.concat
      case _ => MergeStrategy.first
    },

    javaOptions ++= Seq(
      "-Dlog.service.output=/dev/stderr",
      "-Dlog.access.output=/dev/stderr")
  )

  val wartremoverSettings = Seq(
    wartremoverWarnings in (Compile, compile) := Seq(
      Wart.AsInstanceOf,
      Wart.EitherProjectionPartial,
      Wart.IsInstanceOf,
      Wart.TraversableOps,
      Wart.NonUnitStatements,
      Wart.Null,
      Wart.OptionPartial,
      Wart.Return,
      Wart.StringPlusAny,
      Wart.Throw,
      Wart.TryPartial,
      Wart.Var,
      Wart.FinalCaseClass,
      Wart.ExplicitImplicitTypes
    ),

    wartremoverWarnings in (Test, compile) := Seq(
      Wart.EitherProjectionPartial,
      Wart.TraversableOps,
      Wart.Return,
      Wart.StringPlusAny,
      Wart.TryPartial,
      Wart.FinalCaseClass,
      Wart.ExplicitImplicitTypes
    )
  )

  val publishingSettings = Seq(
    publishMavenStyle := true,
    useGpg := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value) {
        Some("snapshots" at nexus + "content/repositories/snapshots")
      } else {
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
      }
    },
    pomIncludeRepository := { _ => false },
    pomExtra :=
      <scm>
        <url>git@github.com:GlobalNamesArchitecture/gnmicroservices.git</url>
        <connection>scm:git:git@github.com:GlobalNamesArchitecture/gnmicroservices.git</connection>
      </scm>
        <developers>
          <developer>
            <id>dimus</id>
            <name>Dmitry Mozzherin</name>
          </developer>
          <developer>
            <id>alexander-myltsev</id>
            <name>Alexander Myltsev</name>
            <url>http://myltsev.com</url>
          </developer>
        </developers>
  )

  val noPublishingSettings = Seq(
    publishArtifact := false,
    publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
  )

  lazy val testSettings = Seq(
    parallelExecution in Global := false,
    fork in ThisBuild in Test := false,
    parallelExecution in ThisBuild in Test := false
  )

  lazy val itSettings = Defaults.itSettings ++ Seq(
    logBuffered in IntegrationTest := false,
    fork in IntegrationTest := true
  )

  /////////////////////
  // Common settings //
  /////////////////////
  lazy val databaseUrl = {
    val host = sys.env.getOrElse("DB_HOST", "localhost")
    val port = sys.env.getOrElse("DB_PORT", "5432")
    val database = sys.env.getOrElse("DB_DATABASE", "development")
    s"jdbc:postgresql://$host:$port/$database"
  }
  lazy val databaseUser = sys.env.getOrElse("DB_USER", "postgres")
  lazy val databasePassword = sys.env.getOrElse("DB_USER_PASS", "")
  lazy val commonSettings = Seq(
    scroogeThriftDependencies in Compile := Seq("finatra-thrift_2.11"),

    slickCodegenDatabaseUrl := databaseUrl,
    slickCodegenDatabaseUser := databaseUser,
    slickCodegenDatabasePassword := databasePassword,
    slickCodegenDriver := slick.driver.PostgresDriver,
    slickCodegenJdbcDriver := "org.postgresql.Driver",
    slickCodegenOutputPackage := "org.globalnames.index.dao",
    slickCodegenExcludedTables := Seq("schema_version"),
    slickCodegenCodeGenerator := { (model: m.Model) =>
      new SourceCodeGenerator(model) {
        override def code =
          s"""import com.github.tototoshi.slick.PostgresJodaSupport._
             |import org.joda.time.DateTime
             |${super.code}""".stripMargin
        override def Table = new Table(_) {
          override def Column = new Column(_) {
            override def rawType = model.tpe match {
              case "java.sql.Timestamp" => "DateTime" // kill j.s.Timestamp
              case _ => super.rawType
            }
          }
        }
      }
    },
    sourceGenerators in Compile += slickCodegen.taskValue,

    wartremoverExcluded ++= Seq(
      (scalaSource in Compile).value / "org" / "globalnames" / "index" / "dao" / "Tables.scala",
      (sourceManaged in Compile).value / "org" / "globalnames" / "index" / "dao" / "Tables.scala"
    )
  )

  //////////////////
  // API settings //
  //////////////////
  lazy val apiSettings = Seq(
    assemblyJarName in assembly := "gnindexapi-" + version.value + ".jar",
    Revolver.enableDebugging(port = 5007, suspend = false)
  )

  ///////////////////////////
  // NameResolver settings //
  ///////////////////////////
  lazy val nameResolverSettings = testReporter ++ Seq(
    assemblyJarName in assembly := "gnnameresolver-" + version.value + ".jar",
    Revolver.enableDebugging(port = 5006, suspend = false)
  )

  /////////////////////////
  // NameFilter settings //
  /////////////////////////
  lazy val nameFilterSettings = testReporter ++ Seq(
    assemblyJarName in assembly := "gnnamefilter-" + version.value + ".jar",
    Revolver.enableDebugging(port = 5009, suspend = false)
  )

  //////////////////////////
  // nameBrowser settings //
  //////////////////////////
  lazy val nameBrowserSettings = testReporter ++ Seq(
    assemblyJarName in assembly := "gnnamebrowser-" + version.value + ".jar",
    Revolver.enableDebugging(port = 5010, suspend = false)
  )

  //////////////////////////
  // crossMapper settings //
  //////////////////////////
  lazy val crossMapperSettings = testReporter ++ Seq(
    assemblyJarName in assembly := "gncrossmapper-" + version.value + ".jar",
    Revolver.enableDebugging(port = 5011, suspend = false)
  )

  //////////////////////
  // Matcher settings //
  //////////////////////
  lazy val matcherSettings = testReporter ++ Seq(
    assemblyJarName in assembly := "gnmatcher-" + version.value + ".jar",
    Revolver.enableDebugging(port = 5008, suspend = false),
    javaOptions in reStart ++= Seq("-Xms4G", "-Xmx20G", "-Xss1M", "-XX:+CMSClassUnloadingEnabled")
  )

  ////////////////////////
  // Benchmark settings //
  ////////////////////////
  lazy val benchmarkSettings = Seq(
    assemblyJarName in assembly := "gnbenchmark-" + version.value + ".jar"
  )

}
