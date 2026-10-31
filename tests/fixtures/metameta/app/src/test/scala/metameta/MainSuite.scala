package metameta

class MainSuite extends munit.FunSuite {
  test("the message names where the plugin is installed") {
    assertEquals(Main.message, """{"installed":"project/project"}""")
  }
}
