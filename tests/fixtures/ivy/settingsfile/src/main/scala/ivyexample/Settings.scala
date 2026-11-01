package ivyexample

object Settings {
  def main(args: Array[String]): Unit = {
    println(upickle.default.write(Map("resolvedWith" -> "ivysettings.xml")))
    println("interface " + classOf[xsbti.Logger].getName)
  }
}
