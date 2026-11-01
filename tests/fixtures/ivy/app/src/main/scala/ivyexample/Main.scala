package ivyexample

object Main {
  def main(args: Array[String]): Unit = {
    println(Message.json)
    println("version " + BuildInfo.version)
    // From sbt's interface module, which only an Ivy-layout repository has.
    println("interface " + classOf[xsbti.Logger].getName)
  }
}
