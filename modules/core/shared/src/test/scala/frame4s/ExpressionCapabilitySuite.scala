package frameconsumer

import scala.compiletime.testing.typeCheckErrors

class ExpressionCapabilitySuite extends munit.FunSuite:
  private def firstDiagnostic(errors: List[scala.compiletime.testing.Error]): String =
    errors.headOption.fold(fail("expected a compile-time diagnostic"))(_.message)

  test("every numeric width admits all required and nullable operand pairings"):
    val errors = compileErrors("""
      import frame4s.*

      def ints[Origin](required: ExprOf[Int, Origin], nullable: ExprOf[Option[Int], Origin]) =
        val addRequired: Expr[Int] = required + required
        val addLeft: Expr[Option[Int]] = nullable + required
        val addRight: Expr[Option[Int]] = required + nullable
        val addNullable: Expr[Option[Int]] = nullable + nullable
        val divideRequired: Expr[Int] = required / required
        val divideLeft: Expr[Option[Int]] = nullable / required
        val divideRight: Expr[Option[Int]] = required / nullable
        val divideNullable: Expr[Option[Int]] = nullable / nullable
        (
          addRequired,
          addLeft,
          addRight,
          addNullable,
          divideRequired,
          divideLeft,
          divideRight,
          divideNullable,
          required - required,
          nullable - required,
          required - nullable,
          nullable - nullable,
          required * required,
          nullable * required,
          required * nullable,
          nullable * nullable
        )

      def longs[Origin](required: ExprOf[Long, Origin], nullable: ExprOf[Option[Long], Origin]) =
        val addRequired: Expr[Long] = required + required
        val addLeft: Expr[Option[Long]] = nullable + required
        val addRight: Expr[Option[Long]] = required + nullable
        val addNullable: Expr[Option[Long]] = nullable + nullable
        val divideRequired: Expr[Long] = required / required
        val divideLeft: Expr[Option[Long]] = nullable / required
        val divideRight: Expr[Option[Long]] = required / nullable
        val divideNullable: Expr[Option[Long]] = nullable / nullable
        (
          addRequired,
          addLeft,
          addRight,
          addNullable,
          divideRequired,
          divideLeft,
          divideRight,
          divideNullable,
          required - required,
          nullable - required,
          required - nullable,
          nullable - nullable,
          required * required,
          nullable * required,
          required * nullable,
          nullable * nullable
        )

      def floats[Origin](required: ExprOf[Float, Origin], nullable: ExprOf[Option[Float], Origin]) =
        val addRequired: Expr[Float] = required + required
        val addLeft: Expr[Option[Float]] = nullable + required
        val addRight: Expr[Option[Float]] = required + nullable
        val addNullable: Expr[Option[Float]] = nullable + nullable
        val divideRequired: Expr[Float] = required / required
        val divideLeft: Expr[Option[Float]] = nullable / required
        val divideRight: Expr[Option[Float]] = required / nullable
        val divideNullable: Expr[Option[Float]] = nullable / nullable
        (
          addRequired,
          addLeft,
          addRight,
          addNullable,
          divideRequired,
          divideLeft,
          divideRight,
          divideNullable,
          required - required,
          nullable - required,
          required - nullable,
          nullable - nullable,
          required * required,
          nullable * required,
          required * nullable,
          nullable * nullable
        )

      def doubles[Origin](
          required: ExprOf[Double, Origin],
          nullable: ExprOf[Option[Double], Origin]
      ) =
        val addRequired: Expr[Double] = required + required
        val addLeft: Expr[Option[Double]] = nullable + required
        val addRight: Expr[Option[Double]] = required + nullable
        val addNullable: Expr[Option[Double]] = nullable + nullable
        val divideRequired: Expr[Double] = required / required
        val divideLeft: Expr[Option[Double]] = nullable / required
        val divideRight: Expr[Option[Double]] = required / nullable
        val divideNullable: Expr[Option[Double]] = nullable / nullable
        (
          addRequired,
          addLeft,
          addRight,
          addNullable,
          divideRequired,
          divideLeft,
          divideRight,
          divideNullable,
          required - required,
          nullable - required,
          required - nullable,
          nullable - nullable,
          required * required,
          nullable * required,
          required * nullable,
          nullable * nullable
        )

      def exactLiterals[Origin](
          required: ExprOf[Int, Origin],
          nullable: ExprOf[Option[Int], Origin]
      ) =
        val liftedRight: Expr[Option[Int]] = nullable + 1
        val liftedLeft: Expr[Option[Int]] = required + Option(1)
        val divided: Expr[Option[Int]] = nullable / 2
        (liftedRight, liftedLeft, divided)
    """)

    assertEquals(errors, "")

  test("comparison, Boolean, unary, and null conveniences preserve exact result types"):
    val errors = compileErrors("""
      import frame4s.*

      def comparisons[Origin](
          required: ExprOf[Int, Origin],
          nullable: ExprOf[Option[Int], Origin]
      ) =
        val equalLeft: Expr[Option[Boolean]] = nullable === required
        val equalRight: Expr[Option[Boolean]] = required === nullable
        val unequal: Expr[Option[Boolean]] = nullable =!= required
        val less: Expr[Option[Boolean]] = nullable < required
        val greater: Expr[Option[Boolean]] = required > nullable
        val total: Expr[Boolean] = nullable.nullSafeEq(required)
        val present: Expr[Boolean] = nullable.isNotNull
        val missing: Expr[Boolean] = required.isNull
        (equalLeft, equalRight, unequal, less, greater, total, present, missing)

      def orderedPhysicalScalars[Origin](
          text: ExprOf[String, Origin],
          optionalText: ExprOf[Option[String], Origin],
          timestamp: ExprOf[TimestampMicros, Origin],
          optionalTimestamp: ExprOf[Option[TimestampMicros], Origin],
          flag: ExprOf[Boolean, Origin],
          optionalFlag: ExprOf[Option[Boolean], Origin]
      ) =
        val textOrder: Expr[Option[Boolean]] = optionalText < text
        val timestampOrder: Expr[Option[Boolean]] = timestamp >= optionalTimestamp
        val booleanOrder: Expr[Option[Boolean]] = optionalFlag <= flag
        val textEquality: Expr[Option[Boolean]] = text === optionalText
        val timestampEquality: Expr[Option[Boolean]] = optionalTimestamp === timestamp
        val booleanEquality: Expr[Option[Boolean]] = flag === optionalFlag
        val timestampLiteralEquality: Expr[Boolean] = timestamp === TimestampMicros(1L)
        val timestampLiteralOrder: Expr[Boolean] = timestamp >= TimestampMicros(0L)
        (
          textOrder,
          timestampOrder,
          booleanOrder,
          textEquality,
          timestampEquality,
          booleanEquality,
          timestampLiteralEquality,
          timestampLiteralOrder
        )

      def booleans[Origin](
          required: ExprOf[Boolean, Origin],
          nullable: ExprOf[Option[Boolean], Origin]
      ) =
        val andRequired: Expr[Boolean] = required && required
        val andLeft: Expr[Option[Boolean]] = nullable && required
        val andRight: Expr[Option[Boolean]] = required && nullable
        val andNullable: Expr[Option[Boolean]] = nullable && nullable
        val orRequired: Expr[Boolean] = required || required
        val orLeft: Expr[Option[Boolean]] = nullable || required
        val orRight: Expr[Option[Boolean]] = required || nullable
        val orNullable: Expr[Option[Boolean]] = nullable || nullable
        val requiredTrue: Expr[Boolean] = required.isTrue
        val nullableTrue: Expr[Boolean] = nullable.isTrue
        val requiredFalse: Expr[Boolean] = required.isFalse
        val nullableFalse: Expr[Boolean] = nullable.isFalse
        (
          andRequired,
          andLeft,
          andRight,
          andNullable,
          orRequired,
          orLeft,
          orRight,
          orNullable,
          requiredTrue,
          nullableTrue,
          requiredFalse,
          nullableFalse
        )

      def unary[Origin](
          i: ExprOf[Int, Origin],
          oi: ExprOf[Option[Int], Origin],
          l: ExprOf[Long, Origin],
          ol: ExprOf[Option[Long], Origin],
          f: ExprOf[Float, Origin],
          of: ExprOf[Option[Float], Origin],
          d: ExprOf[Double, Origin],
          od: ExprOf[Option[Double], Origin]
      ) =
        val ni: Expr[Int] = -i
        val noi: Expr[Option[Int]] = -oi
        val nl: Expr[Long] = -l
        val nol: Expr[Option[Long]] = -ol
        val nf: Expr[Float] = -f
        val nof: Expr[Option[Float]] = -of
        val nd: Expr[Double] = -d
        val nod: Expr[Option[Double]] = -od
        val sf: Expr[Float] = f.sqrt
        val sof: Expr[Option[Float]] = of.sqrt
        val sd: Expr[Double] = d.sqrt
        val sod: Expr[Option[Double]] = od.sqrt
        (ni, noi, nl, nol, nf, nof, nd, nod, sf, sof, sd, sod)
    """)

    assertEquals(errors, "")

  test("the dynamic surface exposes the same admitted expression algebra"):
    val errors = compileErrors("""
      import frame4s.*

      def program(
          required: DynamicExpr,
          nullable: DynamicExpr,
          floating: DynamicExpr,
          requiredBool: DynamicExpr,
          nullableBool: DynamicExpr
      ) =
        for
          added <- nullable + required
          subtracted <- nullable - required
          multiplied <- nullable * required
          divided <- nullable / required
          equal <- nullable === required
          unequal <- nullable =!= required
          totalEqual <- nullable.nullSafeEq(required)
          less <- nullable < required
          ordered <- nullable <= required
          greater <- nullable > required
          greaterEqual <- nullable >= required
          and <- nullableBool && requiredBool
          or <- requiredBool || nullableBool
          negated <- nullable.negate
          root <- floating.sqrt
          truth <- nullableBool.isTrue
          falsehood <- nullableBool.isFalse
        yield Vector(
          added,
          subtracted,
          multiplied,
          divided,
          equal,
          unequal,
          totalEqual,
          less,
          ordered,
          greater,
          greaterEqual,
          and,
          or,
          negated,
          root,
          truth,
          falsehood,
          nullable.isNull,
          nullable.isNotNull
        )
    """)

    assertEquals(errors, "")

  test("open or fabricated Scala evidence cannot authorize an unsupported plan"):
    val numeric = typeCheckErrors("""
      import frame4s.*
      import scala.math.Numeric

      given Numeric[String] = null

      def invalid[Origin](value: ExprOf[String, Origin]) = value + value
    """)
    val fractional = typeCheckErrors("""
      import frame4s.*
      import scala.math.Fractional

      given Fractional[String] = null

      def invalid[Origin](value: ExprOf[String, Origin]) = value / value
    """)
    val ordering = typeCheckErrors("""
      import frame4s.*

      final case class Token(value: String)
      given Ordering[Token] = Ordering.by(_.value)

      def invalid[Origin](value: ExprOf[Token, Origin]) = value < value
    """)
    val oldWitness = typeCheckErrors("""
      import frame4s.*

      given NumericColumn[String] with {}

      def invalid[Origin](value: ExprOf[String, Origin]) = value + value
    """)
    val newWitness = typeCheckErrors("""
      import frame4s.*

      given ArithmeticType[String, String, String] with {}
    """)
    val nullWitness = typeCheckErrors("""
      import frame4s.*

      given ArithmeticType[String, String, String] = null

      def invalid[Origin](value: ExprOf[String, Origin]) = value + value
    """)
    val unitWitness = typeCheckErrors("""
      import frame4s.*

      given ArithmeticType[String, String, String] = ()

      def invalid[Origin](value: ExprOf[String, Origin]) = value + value
    """)
    val literalNullWitness = typeCheckErrors("""
      import frame4s.*

      given LiteralArithmeticType[String, String, String] = null

      def invalid[Origin](value: ExprOf[String, Origin]) = value + "suffix"
    """)
    val encodedCustomType = typeCheckErrors("""
      import frame4s.*

      final case class Token(value: String)

      given ColumnType[Token] with
        val dataType = DataType.Utf8
        val nullable = false
        def literal(value: Token) = LiteralValue.Utf8(value.value)

      given Ordering[Token] = Ordering.by(_.value)

      val token = Expr.literal(Token("value"))
      val ordered = token < token
    """)

    assert(firstDiagnostic(numeric).contains("+"))
    assert(firstDiagnostic(fractional).contains("/"))
    assert(firstDiagnostic(ordering).contains("<"))
    assert(oldWitness.nonEmpty)
    assert(newWitness.nonEmpty)
    assert(nullWitness.nonEmpty)
    assert(unitWitness.nonEmpty)
    assert(literalNullWitness.nonEmpty)
    assert(encodedCustomType.nonEmpty)

  test("unsupported and mismatched operations lead with the operator and logical types"):
    val arithmetic = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      def invalid[Origin](value: ExprOf[String, Origin]) = value + value
    """))
    val division = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      def invalid[Origin](value: ExprOf[String, Origin]) = value / value
    """))
    val mismatch = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      def invalid[Origin](left: ExprOf[Int, Origin], right: ExprOf[Long, Origin]) = left + right
    """))
    val negation = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      def invalid[Origin](value: ExprOf[String, Origin]) = -value
    """))

    assert(arithmetic.contains("+"), arithmetic)
    assert(arithmetic.contains("String"), arithmetic)
    assert(division.contains("/"), division)
    assert(division.contains("String"), division)
    assert(mismatch.contains("Int"), mismatch)
    assert(mismatch.contains("Long"), mismatch)
    assert(negation.contains("Unary -"), negation)
    Vector(arithmetic, division, mismatch, negation).foreach: message =>
      assert(!message.contains("cannot reduce match type"), message)
