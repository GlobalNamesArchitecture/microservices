import Dependencies._
import Settings._
import com.github.tototoshi.sbt.slick.CodegenPlugin
import scoverage.ScoverageKeys._

lazy val common = (project in file("common"))
    .settings(noPublishingSettings: _*)
    .settings(Settings.settings: _*)
    .settings(Settings.commonSettings: _*)
    .settings(libraryDependencies ++= commonDependencies)

lazy val matcher = (project in file("matcher"))
    .dependsOn(common)
    .settings(publishingSettings: _*)
    .settings(Settings.settings: _*)
    .settings(Settings.wartremoverSettings: _*)
    .settings(Settings.matcherSettings: _*)
    .settings(libraryDependencies ++= matcherDependencies)

lazy val nameResolver = (project in file("nameResolver"))
    .enablePlugins(CodegenPlugin)
    .dependsOn(common, matcher)
    .settings(publishingSettings: _*)
    .settings(Settings.settings: _*)
    .settings(Settings.wartremoverSettings: _*)
    .settings(Settings.nameResolverSettings: _*)
    .settings(
      libraryDependencies ++= nameResolverDependencies,
      wartremoverExcluded ++= Seq(
        (scalaSource in Compile).value / "org" / "globalnames" / "index" / "nameresolver" / "dao" / "Tables.scala",
        (sourceManaged in Compile).value / "org" / "globalnames" / "index" / "nameresolver" / "dao" / "Tables.scala"
      )
    )

lazy val api = (project in file("api"))
    .enablePlugins(BuildInfoPlugin)
    .dependsOn(common, nameResolver, matcher)
    .settings(publishingSettings: _*)
    .settings(Settings.settings: _*)
    .settings(Settings.wartremoverSettings: _*)
    .settings(Settings.apiSettings: _*)
    .settings(libraryDependencies ++= apiDependencies)

lazy val `gnindex-root` = project.in(file("."))
    .aggregate(common, nameResolver, matcher, api)
    .settings(noPublishingSettings: _*)
    .settings(
      crossScalaVersions := Seq("2.11.8"),
      coverageEnabled := true
    )
