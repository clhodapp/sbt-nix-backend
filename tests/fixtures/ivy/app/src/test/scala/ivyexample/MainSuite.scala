package ivyexample

class MainSuite extends munit.FunSuite {
  test("the message comes from the library") {
    assertEquals(Message.json, """{"resolvedWith":"ivy"}""")
  }
}
