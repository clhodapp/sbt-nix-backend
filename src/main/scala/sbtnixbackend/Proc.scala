package sbtnixbackend

import java.io.{File, InputStream}
import java.nio.charset.StandardCharsets

/** Runs external commands (nix, git, the forked sbt) without sbt's process
  * library, whose API differs between sbt 1 and sbt 2.
  */
object Proc {

  final class ProcessFailed(val command: Seq[String], val exitCode: Int, val stderr: String)
      extends RuntimeException(
        s"command failed with exit code $exitCode: ${command.mkString(" ")}" +
          (if (stderr.trim.isEmpty) "" else "\n" + stderr.trim)
      )

  /** Runs `command` and returns its standard output. Standard error goes to
    * `onStderr` line by line and is also kept for the failure message.
    */
  def output(
      command: Seq[String],
      cwd: Option[File] = None,
      env: Map[String, String] = Map.empty,
      onStderr: String => Unit = _ => ()
  ): String = {
    val pb = new ProcessBuilder(command: _*)
    cwd.foreach(pb.directory)
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
    val p = pb.start()
    val err = new StringBuilder
    val errThread = pump(p.getErrorStream, { line =>
      err.synchronized { err.append(line).append('\n') }
      onStderr(line)
    })
    val out = readAll(p.getInputStream)
    val code = p.waitFor()
    errThread.join()
    if (code != 0) throw new ProcessFailed(command, code, err.toString)
    out
  }

  /** Runs `command` with both output streams sent to `onLine`, and returns
    * the exit code. `env` entries mapped to `None` are removed from the
    * inherited environment.
    */
  def stream(
      command: Seq[String],
      cwd: File,
      env: Map[String, Option[String]],
      onLine: String => Unit
  ): Int = {
    val pb = new ProcessBuilder(command: _*)
    pb.directory(cwd)
    env.foreach {
      case (k, Some(v)) => pb.environment().put(k, v)
      case (k, None)    => pb.environment().remove(k)
    }
    pb.redirectErrorStream(true)
    pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
    val p = pb.start()
    val t = pump(p.getInputStream, onLine)
    val code = p.waitFor()
    t.join()
    code
  }

  private def pump(in: InputStream, onLine: String => Unit): Thread = {
    val t = new Thread(new Runnable {
      def run(): Unit = {
        val r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
        var line = r.readLine()
        while (line != null) { onLine(line); line = r.readLine() }
      }
    })
    t.setDaemon(true)
    t.start()
    t
  }

  private def readAll(in: InputStream): String = {
    val bytes = new java.io.ByteArrayOutputStream
    val buf = new Array[Byte](1 << 14)
    var n = in.read(buf)
    while (n >= 0) { bytes.write(buf, 0, n); n = in.read(buf) }
    new String(bytes.toByteArray, StandardCharsets.UTF_8)
  }
}
