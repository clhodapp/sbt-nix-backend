package sbtnixbackend

class JsonSuite extends munit.FunSuite {
  import Json._

  test("a value survives printing and parsing") {
    val value = obj(
      "s" -> str("quote \" backslash \\ newline \n tab \t control \u0001"),
      "n" -> num(42),
      "b" -> Bool(true),
      "null" -> Null,
      "a" -> arr(num(1), arr(), obj()),
      "o" -> obj("nested" -> str("x"))
    )
    assertEquals(parse(value.render), value)
  }

  test("printing keeps field order") {
    assertEquals(obj("b" -> num(1), "a" -> num(2)).render, "{\n  \"b\": 1,\n  \"a\": 2\n}")
  }

  test("parsing reads escapes, numbers and whitespace") {
    assertEquals(
      parse(""" { "a" : [ 1 , -2.5e3 , "é\/" ] } """),
      obj("a" -> arr(num(1), Num(BigDecimal("-2.5e3")), str("é/")))
    )
  }

  test("parsing rejects malformed input") {
    Seq("{", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "1 2", "{\"a\":1,}").foreach { bad =>
      intercept[JsonError](parse(bad))
    }
  }

  test("accessors name what they expected") {
    val e = intercept[JsonError](obj("x" -> num(1)).apply("x").asString)
    assert(e.getMessage.contains("expected a string"), e.getMessage)
    intercept[JsonError](obj().apply("missing"))
  }
}
