package example2

object Main {
  def message: String = Message.json(2)

  def main(args: Array[String]): Unit = {
    println(message)
    println("version " + BuildInfo.version)
  }
}
