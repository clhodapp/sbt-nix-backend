package sbt
package object librarymanagement {
  type UpdateReport = sbt.UpdateReport
  type ModuleID = sbt.ModuleID
  type Artifact = sbt.Artifact
  val Artifact = sbt.Artifact
  type ConfigRef = sbt.Configuration
  def ConfigRef(name: String): sbt.Configuration = sbt.Configuration(name, "", false, Nil, true)
  type CrossVersion = sbt.CrossVersion
  val CrossVersion = sbt.CrossVersion
  type Resolver = sbt.Resolver
  val Resolver = sbt.Resolver
  type Patterns = sbt.Patterns
  val Patterns = sbt.Patterns
  type FileRepository = sbt.FileRepository
  val FileRepository = sbt.FileRepository
  type MavenRepository = sbt.MavenRepository
  val MavenRepository = sbt.MavenRepository
  type URLRepository = sbt.URLRepository
  object URLRepository {
    def apply(name: String, patterns: sbt.Patterns, insecure: Boolean): sbt.URLRepository =
      sbt.URLRepository(name, patterns)
  }
}
package object util { type Logger = sbt.Logger }
package object internal { type BuildStructure = sbt.BuildStructure }
