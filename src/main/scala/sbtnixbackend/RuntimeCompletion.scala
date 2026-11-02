package sbtnixbackend

import java.io.File

/** The resolver-specific completion of a published runtime repository. */
trait RuntimeCompletion {
  def apply(repository: File, coordinates: RuntimeRepository.Coordinates, log: sbt.util.Logger): Vector[String]
}
