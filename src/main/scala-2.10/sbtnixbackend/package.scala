import sbt._
import sbt.Keys._
package object sbtnixbackend {
  val isMetaBuild = settingKey[Boolean]("Whether this is the project's meta build")
  val sLog = settingKey[Logger]("Build logger")
  implicit class LegacyReferenceScope(val ref: Reference) {
    def /[A](key: TaskKey[A]): TaskKey[A] = key.in(ref)
    def /[A](key: SettingKey[A]): SettingKey[A] = key.in(ref)
    def /[A](key: InputKey[A]): InputKey[A] = key.in(ref)
    def /(config: Configuration): LegacyRefConfig = new LegacyRefConfig(ref, config)
  }
  class LegacyRefConfig(ref: Reference, config: Configuration) {
    def /[A](key: TaskKey[A]): TaskKey[A] = key.in(ref, config)
    def /[A](key: SettingKey[A]): SettingKey[A] = key.in(ref, config)
  }
  implicit class LegacyConfigScope(val config: Configuration) {
    def /[A](key: TaskKey[A]): TaskKey[A] = key.in(config)
    def /[A](key: SettingKey[A]): SettingKey[A] = key.in(config)
  }
  implicit class LegacyTaskScope[T](val task: TaskKey[T]) {
    def /[A](key: TaskKey[A]): TaskKey[A] = key.in(task)
    def /[A](key: SettingKey[A]): SettingKey[A] = key.in(task)
  }
  implicit class LegacyModule(val m: ModuleID) {
    def withExtraAttributes(a: Map[String, String]): ModuleID = m.copy(extraAttributes = a)
  }
  implicit class LegacyFileRepo(val r: FileRepository) {
    def withPatterns(p: Patterns): FileRepository = FileRepository(r.name, r.configuration, p)
  }
  implicit class LegacyMavenRepo(val r: MavenRepository) {
    def withAllowInsecureProtocol(b: Boolean): MavenRepository = r
  }
}
