package sgrv.be.debug

import zio.{Runtime, UIO, Unsafe}
import zio.http.Status

class DebugSuite extends munit.FunSuite:

  private def run[A](effect: UIO[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("collects every requested system-signature section"):
    val signature = run(Debug.collect(Seq("9000", "line\nbreak", "slash\\value", "carriage\rreturn", "tab\tvalue")))

    Seq(
      "=== Operating system ===",
      "=== Disk space ===",
      "=== Memory ===",
      "=== Environment variables ===",
      "=== Backend command-line arguments ===",
      "=== Java runtime ===",
      "=== nginx reverse proxy ==="
    ).foreach(section => assert(signature.contains(section), clues(section)))

    assert(signature.contains("[0]=9000"))
    assert(signature.contains("[1]=line\\nbreak"))
    assert(signature.contains("[2]=slash\\\\value"))
    assert(signature.contains("[3]=carriage\\rreturn"))
    assert(signature.contains("[4]=tab\\tvalue"))
    assert(signature.contains(s"Java version: ${java.lang.System.getProperty("java.version")}"))

  test("serves the signature response"):
    val response = run(Debug.response)
    val content  = run(response.body.asString.orDie)

    assertEquals(response.status, Status.Ok)
    assert(content.contains("=== Operating system ==="))
    assert(content.contains("Backend command-line arguments ===\n(none)"))
