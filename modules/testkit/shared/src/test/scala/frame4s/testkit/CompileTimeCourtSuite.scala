package frame4s.testkit

import frame4s.*
import scala.compiletime.testing.typeCheckErrors

/** Compile-time specimens are intentionally ordinary user code, not compiler internals. */
class CompileTimeCourtSuite extends munit.FunSuite:
  // Incremental-court specimen: changing this file recompiles the wide-schema checks.
  type Narrow = (id: Int, label: String, score: Option[Double])

  type Wide32 = (
      c01: Int,
      c02: Int,
      c03: Int,
      c04: Int,
      c05: Int,
      c06: Int,
      c07: Int,
      c08: Int,
      c09: Int,
      c10: Int,
      c11: Int,
      c12: Int,
      c13: Int,
      c14: Int,
      c15: Int,
      c16: Int,
      c17: Int,
      c18: Int,
      c19: Int,
      c20: Int,
      c21: Int,
      c22: Int,
      c23: Int,
      c24: Int,
      c25: Int,
      c26: Int,
      c27: Int,
      c28: Int,
      c29: Int,
      c30: Int,
      c31: Int,
      c32: Int
  )

  type Wide48 = (
      c01: Int,
      c02: Int,
      c03: Int,
      c04: Int,
      c05: Int,
      c06: Int,
      c07: Int,
      c08: Int,
      c09: Int,
      c10: Int,
      c11: Int,
      c12: Int,
      c13: Int,
      c14: Int,
      c15: Int,
      c16: Int,
      c17: Int,
      c18: Int,
      c19: Int,
      c20: Int,
      c21: Int,
      c22: Int,
      c23: Int,
      c24: Int,
      c25: Int,
      c26: Int,
      c27: Int,
      c28: Int,
      c29: Int,
      c30: Int,
      c31: Int,
      c32: Int,
      c33: Int,
      c34: Int,
      c35: Int,
      c36: Int,
      c37: Int,
      c38: Int,
      c39: Int,
      c40: Int,
      c41: Int,
      c42: Int,
      c43: Int,
      c44: Int,
      c45: Int,
      c46: Int,
      c47: Int,
      c48: Int
  )

  private def source[S <: scala.NamedTuple.AnyNamedTuple](name: String)(using
      SchemaDescriptor[S]
  ): Frame[S] =
    Frame.source[S](name).fold(error => fail(error.message), identity)

  private def firstDiagnostic(
      errors: List[scala.compiletime.testing.Error]
  ): String =
    errors.headOption.fold(fail("expected a compile-time diagnostic"))(_.message)

  test("narrow, 32-column, and wider practical schemas compile through the public API"):
    val narrow = source[Narrow]("narrow")
    val wide32 = source[Wide32]("wide-32")
    val wide48 = source[Wide48]("wide-48")

    val narrowProjection: Frame[(id: Int, score: Option[Double])] =
      narrow
        .filter(row => row.col("id") > 0)
        .select(row => (row.col("id"), row.col("score")))
    val projection32: Frame[(c01: Int, c16: Int, c32: Int)] =
      wide32.select: row =>
        (
          row.col("c01"),
          row.col("c16"),
          row.col("c32")
        )
    val projection48: Frame[(c01: Int, c24: Int, c48: Int)] =
      wide48.select: row =>
        (
          row.col("c01"),
          row.col("c24"),
          row.col("c48")
        )

    assertEquals(narrowProjection.schema.fields.map(_.name), Vector("id", "score"))
    assertEquals(projection32.schema.fields.map(_.name), Vector("c01", "c16", "c32"))
    assertEquals(projection48.schema.fields.map(_.name), Vector("c01", "c24", "c48"))

  test("a missing-column diagnostic names the field and gives actionable context"):
    val message = firstDiagnostic(
      typeCheckErrors("""
        import frame4s.*
        type Input = (id: Int, label: String)
        val frame = Frame.source[Input]("input").toOption.get
        frame.sortBy(row => row.col("missing"))
      """)
    )
    assert(message.contains("missing"), message)
    assert(message.contains("Check the spelling"), message)
    assert(!message.contains("match type"), message)

  test("a duplicate-output diagnostic includes the aliases and no match-type machinery"):
    val message = firstDiagnostic(
      typeCheckErrors("""
        import frame4s.*
        type Input = (id: Int, label: String)
        val frame = Frame.source[Input]("input").toOption.get
        frame.select(row => (row.col("id").as("same"), row.col("label").as("same")))
      """)
    )
    assert(message.contains("same"), message)
    assert(message.contains("is duplicated"), message)
    assert(!message.contains("match type"), message)

  test("a mistyped result diagnostic identifies the named field"):
    val message = firstDiagnostic(
      typeCheckErrors("""
        import frame4s.*
        type Input = (id: Int)
        val frame = Frame.source[Input]("input").toOption.get
        val result: Frame[(id: String)] =
          frame.select(row => Tuple1(row.col("id").as("id")))
      """)
    )
    assert(message.contains("id"), message)
    assert(message.contains("String"), message)
    assert(message.contains("Int"), message)
