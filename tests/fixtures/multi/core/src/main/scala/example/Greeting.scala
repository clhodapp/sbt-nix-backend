package example

object Greeting {
  def json(name: String): String = upickle.default.write(Map("hello" -> name))
}
