package example2

class MainSuite extends munit.FunSuite {
  test("the message comes from the library") {
    assertEquals(Main.message, """{"sbt":2}""")
  }
}
