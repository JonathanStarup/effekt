import sbtcrossproject.CrossProject
import scalariform.formatter.preferences.AlignSingleLineCaseStatements.MaxArrowIndent
import scalariform.formatter.preferences._

enablePlugins(ScalaJSPlugin)


lazy val effektVersion = "0.1.6"

lazy val noPublishSettings = Seq(
  publish := {},
  publishLocal := {},
)

lazy val commonSettings = Seq(
  scalaVersion := "2.13.1",
  scalacOptions ++= Seq(
    "-encoding", "utf8",
    "-deprecation",
    "-unchecked",
    // "-Xlint",
    "-Xfatal-warnings",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
  ),
  scalariformPreferences := scalariformPreferences.value
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(DanglingCloseParenthesis, Force)
    .setPreference(NewlineAtEndOfFile, true)
    .setPreference(MaxArrowIndent, 20)
)


lazy val root = project.in(file("."))
  .aggregate(effekt.js, effekt.jvm)
  .settings(noPublishSettings)
  .settings(Seq(
    Compile / run := (effekt.jvm / Compile / run).evaluated
  ))

lazy val deploy = taskKey[Unit]("Builds the jar and moves it to the bin folder")

lazy val effekt: CrossProject = crossProject(JSPlatform, JVMPlatform).in(file("."))
  .settings(
    name := "effekt",
    version := effektVersion
  )
  .settings(commonSettings)
  .jvmSettings(
    mainClass in assembly := Some("effekt.Server"),
    assemblyJarName in assembly := "effekt.jar",
    Test / parallelExecution := false,

    libraryDependencies ++= Seq(
      "org.rogach" %% "scallop" % "3.4.0",
      "org.bitbucket.inkytonik.kiama" %% "kiama" % "2.3.0",
      "org.bitbucket.inkytonik.kiama" %% "kiama-extras" % "2.3.0",
      "com.lihaoyi" %% "ammonite-terminal" % "2.0.4",
      "org.scala-sbt" %% "io" % "1.3.1" % "test",
      "org.scalatest" % "scalatest_2.13" % "3.1.1" % "test"
    ),

    // show duration of the tests
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),

    // We use the lib folder as resource directory to include it in the JAR
    Compile / unmanagedResourceDirectories += (baseDirectory in ThisBuild).value / "lib",

    Compile / unmanagedResourceDirectories += (baseDirectory in ThisBuild).value / "licenses",

    deploy := {
      import scala.sys.process.Process

      // Download licenses of third party libraries
      Process("mvn license:download-licenses license:add-third-party").!!

      // Update version in package.json and pom.xml
      Process(s"npm version ${effektVersion} --no-git-tag-version --allow-same-version").!!
      Process(s"mvn versions:set -DnewVersion=${effektVersion} -DgenerateBackupPoms=false").!!

      // prepend shebang to make jar file executable
      val binary = (baseDirectory in ThisBuild).value / "bin" / "effekt"
      val jarfile = assembly.value
      IO.append(binary, "#! /usr/bin/env java -jar\n")
      IO.append(binary, IO.readBytes(jarfile))
    },

    Test / watchTriggers += baseDirectory.value.toGlob / "lib" / "**" / "*.effekt"
  )
  .jsSettings(
    // Add JS-specific settings here
    libraryDependencies ++= Seq(
      "org.bitbucket.inkytonik.kiama" %%% "kiama-scalajs" % "2.4.0-SNAPSHOT"
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },

    // include all resource files in the virtual file system
    sourceGenerators in Compile += Def.task {

      val baseDir = (baseDirectory in ThisBuild).value / "lib"
      val resources = baseDir ** "*.*"

      val sourceDir = (sourceManaged in Compile).value
      val sourceFile = sourceDir / "Resources.scala"

      if (!sourceFile.exists() || sourceFile.lastModified() < baseDir.lastModified()) {

        val virtuals = resources.get.map { file =>
          val filename = file.relativeTo(baseDir).get
          val content = IO.read(file).replaceAllLiterally("$", "$$")
          s"""file(raw\"\"\"$filename\"\"\").write(raw\"\"\"$content\"\"\")"""
        }

        val scalaCode =
          s"""
package effekt.util
import effekt.util.JSPathUtils._

object Resources {

  def load() = {
${virtuals.mkString("\n\n")}
  }
}
"""

        IO.write(sourceFile, scalaCode)
      }

      Seq(sourceFile)
    }.taskValue,

    scalaJSUseMainModuleInitializer := true
  )
