package sbtnixbackend

import java.io.File

import sbt._
import sbt.Keys._

/** The sbt 1 side of the sbt APIs that differ between sbt 1 and sbt 2;
  * src/main/scala-3 holds the sbt 2 side.
  */
object Compat {

  /** The artifacts `publish` would publish, as files. */
  def publishedArtifacts: Def.Initialize[Task[Vector[(Artifact, File)]]] = Def.task {
    (publish / packagedArtifacts).value.toVector
  }

  /** The value of a task result, or its failure. */
  def toEither[A](result: Result[A]): Either[Incomplete, A] = result match {
    case Value(a)   => Right(a)
    case Inc(cause) => Left(cause)
  }

  /** Runs the tests of `ref`. */
  def runTests(extracted: Extracted, ref: ProjectRef, state: State): State =
    extracted.runTask(ref / Test / test, state)._1

  /** sbt 1 does not cache task results, so every task runs. */
  def uncached[A](a: A): A = a
}
