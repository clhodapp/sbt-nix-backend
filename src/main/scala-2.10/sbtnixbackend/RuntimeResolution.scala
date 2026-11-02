package sbtnixbackend

import java.io.File
import java.nio.file.Files
import scala.collection.JavaConverters._
import sbt._

/** A fresh Ivy consumer resolution, with origins read from Ivy's own cache records. Descriptor origins include parent
  * POMs and imported descriptors.
  */
object RuntimeResolution {
  def complete(
      repository: File,
      coordinates: RuntimeRepository.Coordinates,
      configuration: IvyConfiguration,
      mirror: Option[IvyMirror.Setup],
      log: Logger
  ): Vector[String] = {
    val home = Files.createTempDirectory("sbt-purifier-runtime").toFile
    try {
      val others = configuration match {
        case c: InlineIvyConfiguration =>
          c.resolvers.map(r => Compat.archivedResolver(mirror.flatMap(_.base).fold(r)(IvyMirror.unrewrite(r, _))))
        case c: ExternalIvyConfiguration => IvySettingsFile.resolvers(c.uri, None)
      }
      val own = IvyLayout.resolvers("sbt-purifier-runtime", repository)
      val mirrored = others.map(r => mirror.flatMap(_.base).fold(r)(IvyMirror.rewrite(r, _)))
      val conf = new InlineIvyConfiguration(
        new IvyPaths(repository, Some(home)),
        own ++ mirrored,
        Nil,
        Nil,
        false,
        None,
        Nil,
        log
      )
      val ivy = new IvySbt(conf)
      val dependency = ModuleID(coordinates.organization, coordinates.name, coordinates.version).copy(
        extraAttributes = coordinates.attributes,
        configurations = Some("compile->default(compile);runtime->runtime(default)")
      )
      val name = "consumer-of-" + coordinates.name
      val module = new ivy.Module(
        InlineConfiguration(
          ModuleID("sbt-purifier", name, "0"),
          ModuleInfo(name),
          Seq(dependency),
          configurations = Seq(Compile, Runtime),
          defaultConfiguration = Some(Compile)
        )
      )
      val report = IvyActions.update(module, new UpdateConfiguration(None, false, UpdateLogging.Quiet), log)
      val origins = (home ** "ivydata-*.properties").get.toVector
        .flatMap { file =>
          val properties = new java.util.Properties
          val in = Files.newInputStream(file.toPath)
          try properties.load(in)
          finally in.close()
          properties.stringPropertyNames.asScala.toVector.filter(_.endsWith(".location")).map(properties.getProperty)
        }
        .map { value =>
          val file = new File(value)
          val url = if (file.isAbsolute) file.toURI.toString else value
          mirror.flatMap(_.base).fold(url)(IvyMirror.unrewritePrefix(url, _))
        }
        .distinct
        .sorted
      RuntimeRepository.copyUrls(
        repository,
        coordinates,
        others,
        NixBackendPlugin.Mode.coursierCacheFromEnv,
        origins,
        report,
        log
      )
    } finally IO.delete(home)
  }
}
