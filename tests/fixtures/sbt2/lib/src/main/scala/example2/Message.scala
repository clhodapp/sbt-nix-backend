package example2

object Message {
  def json(sbt: Int): String = upickle.default.write(Map("sbt" -> sbt))
}
