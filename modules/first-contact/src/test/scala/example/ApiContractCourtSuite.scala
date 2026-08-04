package example

import frame4s.*
import scala.compiletime.testing.typeCheckErrors

class ApiContractCourtSuite extends munit.FunSuite:
  private def firstMessage(errors: List[scala.compiletime.testing.Error]): String =
    errors.headOption.fold(fail("expected a compile error"))(_.message)

  private def assertUserFacing(
      errors: List[scala.compiletime.testing.Error],
      expected: String
  ): Unit =
    val message = firstMessage(errors)
    val expectedIndex = message.indexOf(expected)
    val internalIndex = message.indexOf("match type")
    assert(expectedIndex >= 0, message)
    assert(internalIndex < 0 || expectedIndex < internalIndex, message)

  test("the public API rejects a scalar with the wrong exact type"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type People = (id: Int)
      def invalid(frame: Frame[People]) =
        frame.filter(row => row.col("id") > "1")
    """)
    assertUserFacing(errors, "not supported for Int and scalar String")

  test("the public literal factory rejects an un-ascribed raw null"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        Expr.literal(null)
      """),
      "Raw null is not a typed column value"
    )

  test("the public operand overload rejects raw null"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        type People = (id: Int)
        def invalid(frame: Frame[People]) =
          frame.filter(row => row.col("id") === null)
      """),
      "Raw null is not a typed column value"
    )

  test("checked public constructors reject ascribed null and Some(null)"):
    assertEquals(Expr.literalChecked(null: String), Left(ValueError.NullUtf8))
    assertEquals(
      Expr.literalChecked(Some(null): Option[String]),
      Left(ValueError.NullUtf8)
    )
    assertEquals(LiteralValue.utf8(null), Left(ValueError.NullUtf8))
    assertEquals(ScalarValue.utf8(null), Left(ValueError.NullUtf8))

    val raw = intercept[InvalidValueFailure](Expr.literal(null: String))
    val nested = intercept[InvalidValueFailure]:
      Expr.literal(Some(null): Option[String])
    assertEquals(raw.error, ValueError.NullUtf8)
    assertEquals(nested.error, ValueError.NullUtf8)

  test("raw strings cannot call the UTF-8 enum cases"):
    val errors = typeCheckErrors("""
      import frame4s.*
      val literal = LiteralValue.Utf8("value")
      val scalar = ScalarValue.Utf8("value")
    """)
    assert(errors.nonEmpty)
    assert(errors.exists(_.message.contains("Utf8Value")), clues(errors.map(_.message)))

  test("exact scalar operands reject numeric widening"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type People = (id: Int)
      def invalid(frame: Frame[People]) =
        frame.filter(row => row.col("id") > 0L)
    """)
    assertUserFacing(errors, "not supported for Int and scalar Long")

  test("nullable columns accept a required scalar and lift the result"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type People = (score: Option[Double])
      def valid(frame: Frame[People]) =
        frame.filter(row => (row.col("score") > 0.0).isTrue)
    """)
    assertEquals(errors, Nil)

  test("raw projected columns retain duplicate-name checking"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        type People = (id: Int, name: String)
        def invalid(frame: Frame[People]) =
          frame.select(row => (row.col("id"), row.col("id")))
      """),
      "Output column 'id' is duplicated"
    )

  test("a computed projection still requires an explicit name"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        type People = (id: Int)
        def invalid(frame: Frame[People]) =
          frame.select(row => Tuple1(row.col("id") + 1))
      """),
      "Add .as"
    )

  test("a missing projected field reports the field name first"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        type People = (id: Int)
        def invalid(frame: Frame[People]) =
          frame.select(row => Tuple1(row.col("missing")))
      """),
      "Column 'missing' does not exist"
    )

  test("name-preserving raw columns retain path-dependent provenance"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        type Left = (id: Int, label: String)
        type Right = (label: String, id: Int)
        val left = Frame.source[Left]("left").toOption.get
        val right = Frame.source[Right]("right").toOption.get
        var stolen: ColumnExprOf["id", Int, left.Origin] = null
        left.select { row =>
          stolen = row.col("id")
          Tuple1(row.col("id"))
        }
        right.select(_ => Tuple1(stolen))
      """),
      "different frame scope"
    )

  test("join output-name collisions remain compile-time errors"):
    assertUserFacing(
      typeCheckErrors("""
        import frame4s.*
        type LeftRows = (id: Int, label: String)
        type RightRows = (key: Int, label: String)
        def invalid(left: Frame[LeftRows], right: Frame[RightRows]) =
          left.innerJoin(right)((lhs, rhs) => lhs.col("id") === rhs.col("key"))
      """),
      "Join output column 'label' occurs on both sides"
    )
