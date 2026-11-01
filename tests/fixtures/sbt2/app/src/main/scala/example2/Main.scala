package example2

object Main {
  def message: String = Message.json(2)

  def main(args: Array[String]): Unit = {
    println(message)
    println("version " + BuildInfo.version)
    // From sbt's interface module, which only an Ivy-layout repository has.
    println("interface " + classOf[xsbti.Logger].getName)
  }
}
