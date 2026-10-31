package sbtnixbackend

/** A minimal JSON value type with a parser and a deterministic printer.
  *
  * The plugin writes the lock file, reads the store-path manifest that Nix
  * prints, and reads `nix flake metadata --json`. sbt 1 and sbt 2 ship
  * different JSON libraries, so the plugin carries its own, written in the
  * subset of Scala that compiles on both 2.12 and 3.
  */
sealed trait Json {
  def render: String = Json.render(this, 0)
}

object Json {
  final case class Str(value: String) extends Json
  final case class Num(value: BigDecimal) extends Json
  final case class Bool(value: Boolean) extends Json
  case object Null extends Json
  final case class Arr(items: Vector[Json]) extends Json
  /** Fields keep their insertion order, so printing is deterministic. */
  final case class Obj(fields: Vector[(String, Json)]) extends Json {
    def get(key: String): Option[Json] = fields.collectFirst { case (k, v) if k == key => v }
    def apply(key: String): Json =
      get(key).getOrElse(throw new JsonError(s"missing field '$key'"))
  }

  final class JsonError(message: String) extends RuntimeException(message)

  def obj(fields: (String, Json)*): Obj = Obj(fields.toVector)
  def arr(items: Json*): Arr = Arr(items.toVector)
  def str(value: String): Str = Str(value)
  def num(value: Int): Num = Num(BigDecimal(value))

  implicit final class JsonOps(private val json: Json) extends AnyVal {
    def asString: String = json match {
      case Str(s) => s
      case other  => throw new JsonError(s"expected a string, got ${other.render}")
    }
    def asInt: Int = json match {
      case Num(n) if n.isValidInt => n.toInt
      case other                  => throw new JsonError(s"expected an integer, got ${other.render}")
    }
    def asBoolean: Boolean = json match {
      case Bool(b) => b
      case other   => throw new JsonError(s"expected a boolean, got ${other.render}")
    }
    def asArray: Vector[Json] = json match {
      case Arr(items) => items
      case other      => throw new JsonError(s"expected an array, got ${other.render}")
    }
    def asObject: Obj = json match {
      case o: Obj => o
      case other  => throw new JsonError(s"expected an object, got ${other.render}")
    }
  }

  // Printing: two-space indentation, one array element or object field per
  // line, so a lock diff shows one artifact per hunk.
  private def render(json: Json, indent: Int): String = json match {
    case Str(s)  => quote(s)
    case Num(n)  => n.bigDecimal.toPlainString
    case Bool(b) => b.toString
    case Null    => "null"
    case Arr(items) if items.isEmpty => "[]"
    case Arr(items) =>
      val pad = "  " * (indent + 1)
      items.map(i => pad + render(i, indent + 1)).mkString("[\n", ",\n", "\n" + "  " * indent + "]")
    case Obj(fields) if fields.isEmpty => "{}"
    case Obj(fields) =>
      val pad = "  " * (indent + 1)
      fields
        .map { case (k, v) => pad + quote(k) + ": " + render(v, indent + 1) }
        .mkString("{\n", ",\n", "\n" + "  " * indent + "}")
  }

  def quote(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case '\b' => sb.append("\\b")
      case '\f' => sb.append("\\f")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c    => sb.append(c)
    }
    sb.append('"').toString
  }

  def parse(input: String): Json = {
    val p = new Parser(input)
    val value = p.value()
    p.skipWhitespace()
    if (!p.atEnd) p.fail("trailing characters after the JSON value")
    value
  }

  private final class Parser(in: String) {
    private var pos = 0

    def atEnd: Boolean = pos >= in.length

    def fail(message: String): Nothing =
      throw new JsonError(s"$message at offset $pos")

    def skipWhitespace(): Unit =
      while (!atEnd && " \t\r\n".indexOf(in.charAt(pos)) >= 0) pos += 1

    private def peek: Char = {
      if (atEnd) fail("unexpected end of input")
      in.charAt(pos)
    }

    private def expect(c: Char): Unit = {
      if (peek != c) fail(s"expected '$c'")
      pos += 1
    }

    private def literal(word: String, result: Json): Json = {
      if (!in.startsWith(word, pos)) fail(s"expected '$word'")
      pos += word.length
      result
    }

    def value(): Json = {
      skipWhitespace()
      peek match {
        case '{' => obj()
        case '[' => arr()
        case '"' => Str(string())
        case 't' => literal("true", Bool(true))
        case 'f' => literal("false", Bool(false))
        case 'n' => literal("null", Null)
        case c if c == '-' || c.isDigit => number()
        case c => fail(s"unexpected character '$c'")
      }
    }

    private def obj(): Json = {
      expect('{')
      val fields = Vector.newBuilder[(String, Json)]
      skipWhitespace()
      if (peek == '}') { pos += 1; return Obj(fields.result()) }
      var more = true
      while (more) {
        skipWhitespace()
        val key = string()
        skipWhitespace()
        expect(':')
        fields += key -> value()
        skipWhitespace()
        peek match {
          case ',' => pos += 1
          case '}' => pos += 1; more = false
          case _   => fail("expected ',' or '}'")
        }
      }
      Obj(fields.result())
    }

    private def arr(): Json = {
      expect('[')
      val items = Vector.newBuilder[Json]
      skipWhitespace()
      if (peek == ']') { pos += 1; return Arr(items.result()) }
      var more = true
      while (more) {
        items += value()
        skipWhitespace()
        peek match {
          case ',' => pos += 1
          case ']' => pos += 1; more = false
          case _   => fail("expected ',' or ']'")
        }
      }
      Arr(items.result())
    }

    private def string(): String = {
      expect('"')
      val sb = new StringBuilder
      var done = false
      while (!done) {
        val c = peek
        pos += 1
        c match {
          case '"' => done = true
          case '\\' =>
            val e = peek
            pos += 1
            e match {
              case '"'  => sb.append('"')
              case '\\' => sb.append('\\')
              case '/'  => sb.append('/')
              case 'b'  => sb.append('\b')
              case 'f'  => sb.append('\f')
              case 'n'  => sb.append('\n')
              case 'r'  => sb.append('\r')
              case 't'  => sb.append('\t')
              case 'u' =>
                if (pos + 4 > in.length) fail("truncated unicode escape")
                val hex = in.substring(pos, pos + 4)
                try sb.append(Integer.parseInt(hex, 16).toChar)
                catch { case _: NumberFormatException => fail(s"bad unicode escape '$hex'") }
                pos += 4
              case other => fail(s"bad escape '\\$other'")
            }
          case ch if ch < 0x20 => fail("control character in string")
          case ch              => sb.append(ch)
        }
      }
      sb.toString
    }

    private def number(): Json = {
      val start = pos
      if (peek == '-') pos += 1
      while (!atEnd && "0123456789.eE+-".indexOf(in.charAt(pos)) >= 0) pos += 1
      val text = in.substring(start, pos)
      try Num(BigDecimal(text))
      catch { case _: NumberFormatException => fail(s"bad number '$text'") }
    }
  }
}
