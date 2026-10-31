package example

object Main {
  /** The first non-blank line of the bundled license. */
  def licenseTitle: String = {
    val in = getClass.getResourceAsStream("/license.txt")
    try scala.io.Source.fromInputStream(in, "UTF-8").getLines().map(_.trim).find(_.nonEmpty).getOrElse("")
    finally in.close()
  }

  def message: String = Greeting.json(example.javalib.Shout.shout("nix world"))

  def main(args: Array[String]): Unit = {
    println(message)
    println(licenseTitle)
    println("version " + BuildInfo.version)
  }
}
