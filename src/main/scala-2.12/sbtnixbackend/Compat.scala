package sbtnixbackend

import java.io.File

import sbt._
import sbt.Keys._
import sbt.librarymanagement.ivy.{ExternalIvyConfiguration, InlineIvyConfiguration, IvyConfiguration, IvyPaths}

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
    * mirror of their URLs, with an Ivy home of its own (see IvyMirror). The
    * rewrite applies to the Ivy configuration alone: coursier, which the
    * plugin's own repository step still uses, keeps the real URLs, so its
    * downloads lock by them.
    */
  def ivyMirrorSettings(mirror: Def.Initialize[Task[Option[IvyMirror.Setup]]]): Seq[Setting[_]] = Seq(
    ivyConfiguration := withIvyMirror(ivyConfiguration.value, mirror.value, baseDirectory.value),
    // The compiler bridge of Scala 2.12 and older is compiled from sources
    // that a configuration of its own resolves.
    scalaCompilerBridgeScope / ivyConfiguration :=
      withIvyMirror((scalaCompilerBridgeScope / ivyConfiguration).value, mirror.value, baseDirectory.value)
  )

  private def withIvyMirror(configuration: IvyConfiguration, mirror: Option[IvyMirror.Setup], base: File): IvyConfiguration =
    (mirror, configuration) match {
      case (Some(setup), inline: InlineIvyConfiguration) =>
        val userHome = inline.paths
          .flatMap(_.ivyHome)
          .orElse(sys.props.get("sbt.ivy.home").map(new File(_)))
          .getOrElse(new File(sys.props("user.home"), ".ivy2"))
        val resolvers = inline.resolvers.map { r =>
          val pinned = IvyMirror.pinIvyHome(r, userHome)
          setup.base.fold(pinned)(IvyMirror.rewrite(pinned, _))
        }
        val others = inline.otherResolvers.map(IvyMirror.pinIvyHome(_, userHome))
        val home = IvyMirror.home(setup, resolvers ++ others)
        inline
          .withResolvers(resolvers)
          .withOtherResolvers(others)
          .withPaths(inline.paths.getOrElse(IvyPaths(base, None)).withIvyHome(home))
      case (Some(setup), _: ExternalIvyConfiguration) if setup.base.isDefined =>
        sys.error(
          "sbt-nix-backend: an Ivy settings file (externalIvySettings) names resolvers the plugin cannot read; " +
            "declare them as sbt resolvers instead"
        )
      case _ => configuration
    }

  /** Publishes this project into the Nix output `dir` with `config`,
    * through sbt's publisher, in either style. Ivy's publisher delivers an
    * Ivy-style module's ivy.xml from Ivy's cache; for a project that
    * resolves with coursier, sbt writes it there before it computes
    * `makeIvyXmlConfiguration` (sbt.internal.librarymanagement.IvyXml).
    */
  def publishToOutput(
      config: TaskKey[PublishConfiguration],
      skipKey: TaskKey[Boolean],
      dir: Def.Initialize[File]
  ): Def.Initialize[Task[Unit]] =
    Classpaths.publishOrSkip(config, skipKey).dependsOn(makeIvyXmlConfiguration)

  /** sbt 1 does not cache task results, so every task runs. */
  def uncached[A](a: A): A = a
}
