package ivyexample

object Message {
  def json: String = upickle.default.write(Map("resolvedWith" -> "ivy"))
}
