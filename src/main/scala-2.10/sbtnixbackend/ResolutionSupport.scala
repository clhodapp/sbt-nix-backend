package sbtnixbackend
import java.io.File
import sbt._
import sbt.Keys._
object ResolutionSupport {
  val buildSettings: Seq[Setting[_]] = Seq(sLog := ConsoleLogger())
  val projectSettings: Seq[Setting[_]] = Seq(
    resolvers ~= (_.map(Compat.archivedResolver)),
    externalResolvers ~= (_.map(Compat.archivedResolver)),
    fullResolvers ~= (_.map(Compat.archivedResolver)),
    isMetaBuild := baseDirectory.value.getCanonicalFile.toPath
      .startsWith(new File(sys.props("user.dir"), "project").getCanonicalFile.toPath)
  )
  def runtimeCompletion(
      mirror: Def.Initialize[Task[Option[IvyMirror.Setup]]]
  ): Def.Initialize[Task[RuntimeCompletion]] = Def.task {
    val configuration = ivyConfiguration.value
    val setup = mirror.value
    new RuntimeCompletion {
      def apply(repository: File, coordinates: RuntimeRepository.Coordinates, log: Logger): Vector[String] =
        RuntimeResolution.complete(repository, coordinates, configuration, setup, log)
    }
  }
}
