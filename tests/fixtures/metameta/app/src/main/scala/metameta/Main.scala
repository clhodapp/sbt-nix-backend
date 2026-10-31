package metameta

object Main {
  def message: String = upickle.default.write(Map("installed" -> "project/project"))

  def main(args: Array[String]): Unit = {
    println(message)
    println("version " + BuildInfo.version)
  }
}
