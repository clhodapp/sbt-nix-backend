package example

class MainSuite extends munit.FunSuite {
  test("the message combines the Scala and Java libraries") {
    assertEquals(Main.message, """{"hello":"Nix World"}""")
  }
  test("the license fetched outside coursier is bundled") {
    assertEquals(Main.licenseTitle, "Apache License")
  }
}
