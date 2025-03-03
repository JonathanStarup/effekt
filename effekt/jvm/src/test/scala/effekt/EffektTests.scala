package effekt

import java.io.File

import sbt.io._
import sbt.io.syntax._

import org.scalatest.funspec.AnyFunSpec

import scala.language.implicitConversions

trait EffektTests extends AnyFunSpec {

  // The sources of all testfiles are stored here:
  lazy val examplesDir = new File("examples")

  // Test files which are to be ignored (since features are missing or known bugs exist)
  lazy val ignored: List[File] = List()

  // Folders to discover and run tests in
  lazy val included: List[File] = List(examplesDir)

  def runTestFor(f: File, expectedResult: String): Unit

  def runTests() = included.foreach(runPositiveTestsIn)

  def runPositiveTestsIn(dir: File): Unit = describe(dir.getName) {
    dir.listFiles.foreach {
      case f if f.isDirectory && !ignored.contains(f) =>
        runPositiveTestsIn(f)
      case f if f.getName.endsWith(".effekt") || f.getName.endsWith(".md") =>
        val path = f.getParentFile
        val baseName = f.getName.stripSuffix(".md").stripSuffix(".effekt")

        val checkfile = path / (baseName + ".check")

        if (!checkfile.exists()) {
          sys error s"Missing checkfile for ${f.getPath}"
        }

        if (ignored.contains(f)) {
          ignore(f.getName) { () }
        } else {
          runTestFor(f, IO.read(checkfile).toString)
        }

      case _ => ()
    }
  }

  def removeAnsiColors(text: String): String = text.replaceAll("\u001B\\[[;\\d]*m", "")

  runTests()
}
