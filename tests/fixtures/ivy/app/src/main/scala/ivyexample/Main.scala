package ivyexample

object Main {
  def main(args: Array[String]): Unit = {
    println(Message.json)
    println("version " + BuildInfo.version)
  }
}
