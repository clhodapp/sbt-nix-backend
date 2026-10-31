package sbtnixbackend

import java.io.File

import sbt._
import sbt.Keys._
import sbt.librarymanagement.ivy.InlineIvyConfiguration

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

  /** Whether this project resolves its dependencies with Apache Ivy. */
  def usesIvyResolution: Def.Initialize[Boolean] = Def.setting(!useCoursier.value)

  /** A project that resolves with Ivy reads the locked downloads through a
    * mirror of their URLs (see IvyMirror). The rewrite applies to the Ivy
    * configuration alone: coursier, which the plugin's own repository
    * step still uses, keeps the real URLs, so its downloads lock by them.
    */
  def ivyMirrorSettings(mirror: Def.Initialize[Task[Option[String]]]): Seq[Setting[_]] = Seq(
    ivyConfiguration := {
      val configuration = ivyConfiguration.value
      (mirror.value, configuration) match {
        case (Some(base), inline: InlineIvyConfiguration) =>
          inline.withResolvers(inline.resolvers.map(IvyMirror.rewrite(_, base)))
        case _ => configuration
      }
    }
  )

  /** sbt 1 does not cache task results, so every task runs. */
  def uncached[A](a: A): A = a
}
