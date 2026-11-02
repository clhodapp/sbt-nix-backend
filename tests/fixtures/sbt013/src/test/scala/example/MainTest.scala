// SPDX-License-Identifier: MIT
package example

class MainTest {
  @org.junit.Test def greeting(): Unit =
    org.junit.Assert.assertEquals("Hello, Nix!", Main.greeting("nix"))
}
