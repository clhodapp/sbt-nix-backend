// SPDX-License-Identifier: MIT
package example

object Main {
  def greeting(name: String): String =
    "Hello, " + org.apache.commons.lang3.StringUtils.capitalize(name) + "!"

  def main(args: Array[String]): Unit = println(greeting(args.headOption.getOrElse("nix")))
}
