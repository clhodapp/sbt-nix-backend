package sbtnixbackend

import java.io.File

import sbt.*
import sbt.Keys.*

/** The sbt 2 side of the sbt APIs that differ between sbt 1 and sbt 2;
  * src/main/scala-2.12 holds the sbt 1 side.
  */
object Compat {

  /** The artifacts `publish` would publish, as files: sbt 2 packages them
    * as virtual file references, which the file converter resolves.
    */
  def publishedArtifacts: Def.Initialize[Task[Vector[(Artifact, File)]]] = Def.task {
    val converter = fileConverter.value
    (publish / packagedArtifacts).value.toVector.map { case (a, ref) => a -> converter.toPath(ref).toFile }
  }

  /** The value of a task result, or its failure. */
  def toEither[A](result: Result[A]): Either[Incomplete, A] = result match {
    case Result.Value(a)   => Right(a)
    case Result.Inc(cause) => Left(cause)
  }

  /** Runs the tests of `ref`; `test` is an input task in sbt 2. */
  def runTests(extracted: Extracted, ref: ProjectRef, state: State): State =
    extracted.runInputTask(ref / Test / test, "", state)._1

  /** Whether this project resolves its dependencies with Apache Ivy; sbt 2
    * resolves with coursier only.
    */
  def usesIvyResolution: Def.Initialize[Boolean] = Def.setting(false)

  /** sbt 2 has no Ivy configuration to rewrite. */
  def ivyMirrorSettings(mirror: Def.Initialize[Task[Option[String]]]): Seq[Setting[?]] = Nil

  /** sbt 2 caches task results; a task with effects outside its result
    * (a lockfile written, a Nix build run) opts out.
    */
  inline def uncached[A](inline a: A): A = Def.uncached(a)
}
