package frame4s

import scala.NamedTuple
import scala.annotation.implicitNotFound
import scala.collection.mutable
import scala.compiletime.error

private[frame4s] enum InputRef:
  case Current
  case Left
  case Right

  def qualifier: String = this match
    case Current => "current"
    case Left    => "left"
    case Right   => "right"

/** Unary operations represented in a logical expression tree.
  *
  * Users normally select these through methods such as [[ExprOf.isNull]], [[ExprOf.sqrt]], and
  * `isTrue`; backend implementations pattern-match on this ADT.
  */
enum UnaryOperator:
  case IsNull
  case IsNotNull
  case IsTrue
  case IsFalse
  case Negate
  case Sqrt

  def symbol: String = this match
    case IsNull    => "isNull"
    case IsNotNull => "isNotNull"
    case IsTrue    => "isTrue"
    case IsFalse   => "isFalse"
    case Negate    => "unary -"
    case Sqrt      => "sqrt"

/** Binary operations represented in a logical expression tree.
  *
  * Integral arithmetic is checked during execution. Boolean operators preserve the SQL three-valued
  * semantics carried by nullable expressions.
  */
enum BinaryOperator:
  case Equal
  case NullSafeEqual
  case NotEqual
  case LessThan
  case LessThanOrEqual
  case GreaterThan
  case GreaterThanOrEqual
  case Add
  case Subtract
  case Multiply
  case Divide
  case And
  case Or

  def symbol: String = this match
    case Equal              => "==="
    case NullSafeEqual      => "nullSafeEq"
    case NotEqual           => "=!="
    case LessThan           => "<"
    case LessThanOrEqual    => "<="
    case GreaterThan        => ">"
    case GreaterThanOrEqual => ">="
    case Add                => "+"
    case Subtract           => "-"
    case Multiply           => "*"
    case Divide             => "/"
    case And                => "&&"
    case Or                 => "||"

/** Backend-neutral literal values stored in logical plans.
  *
  * Typed user code usually constructs literals with [[Expr.literal]]. `Null` always records the
  * non-null physical type because untyped null is not a valid column representation.
  */
enum LiteralValue:
  case Null(dataType: DataType)
  case Bool(value: Boolean)
  case Int32(value: Int)
  case Int64(value: Long)
  case Float32(value: Float)
  case Float64(value: Double)
  case Utf8(value: Utf8Value)
  case Timestamp(value: Long, unit: TimeUnit)

  override def toString: String = this match
    case Null(dataType)     => s"Null($dataType)"
    case Bool(_)            => "Bool(<redacted>)"
    case Int32(_)           => "Int32(<redacted>)"
    case Int64(_)           => "Int64(<redacted>)"
    case Float32(_)         => "Float32(<redacted>)"
    case Float64(_)         => "Float64(<redacted>)"
    case Utf8(_)            => "Utf8(<redacted>)"
    case Timestamp(_, unit) => s"Timestamp(<redacted>,$unit)"

object LiteralValue:
  /** Construct a UTF-8 literal without admitting raw null. */
  def utf8(value: String): Either[ValueError, LiteralValue] =
    Utf8Value.from(value).map(Utf8.apply)

  private[frame4s] def checkedUtf8(value: String): LiteralValue =
    Utf8(Utf8Value.checked(value))

  private[frame4s] def validate(value: LiteralValue): Either[ValueError, LiteralValue] =
    if value == null then Left(ValueError.NullLiteral)
    else
      value match
        case Null(dataType) if dataType == null => Left(ValueError.NullDataType)
        case Timestamp(_, unit) if unit == null => Left(ValueError.NullTimeUnit)
        case _                                  => Right(value)

final private[frame4s] case class ExprFingerprint(first: Long, second: Long)

private[frame4s] enum ExprIdentityNode:
  case Derived(description: String)
  case Column(
      input: InputRef,
      column: ColumnId,
      dataType: DataType,
      nullable: Boolean
  )
  case Literal(value: LiteralValue)
  case Unary(
      operator: UnaryOperator,
      input: ExprId,
      dataType: DataType,
      nullable: Boolean
  )
  case Binary(
      operator: BinaryOperator,
      left: ExprId,
      right: ExprId,
      dataType: DataType,
      nullable: Boolean
  )

/** Internal structural identity for a resolved expression.
  *
  * Its fingerprint has fixed size. A compact structural witness disambiguates the deliberately rare
  * case in which two structures have the same fingerprint. Public diagnostics never render either
  * representation.
  */
final class ExprId private[frame4s] (
    private[frame4s] val fingerprint: ExprFingerprint,
    private[frame4s] val witness: ExprIdentityNode
):
  override def equals(other: Any): Boolean = other match
    case that: ExprId =>
      (this eq that) ||
      (fingerprint == that.fingerprint && ExprId.structurallyEqual(this, that))
    case _ => false

  override def hashCode(): Int =
    val foldedFirst = fingerprint.first ^ (fingerprint.first >>> 32)
    val foldedSecond = fingerprint.second ^ (fingerprint.second >>> 32)
    31 * foldedFirst.toInt + foldedSecond.toInt

  override def toString: String = "ExprId(<redacted>)"

object ExprId:
  private val FirstOffset = -3750763034362895579L
  private val FirstPrime = 1099511628211L
  private val SecondOffset = 7640891576956012809L
  private val SecondPrime = -4417276706812531889L

  final private class IdentityPair(val left: ExprId, val right: ExprId):
    override def equals(other: Any): Boolean = other match
      case that: IdentityPair => (left eq that.left) && (right eq that.right)
      case _                  => false

    override def hashCode(): Int =
      31 * System.identityHashCode(left) + System.identityHashCode(right)

  private def mixFirst(state: Long, value: Long): Long =
    (state ^ value) * FirstPrime

  private def mixSecond(state: Long, value: Long): Long =
    (state ^ java.lang.Long.rotateLeft(value, 23)) * SecondPrime

  private def combine(tag: Int, values: Long*): ExprFingerprint =
    var first = mixFirst(FirstOffset, tag.toLong)
    var second = mixSecond(SecondOffset, tag.toLong)
    var index = 0
    while index < values.length do
      first = mixFirst(first, values(index))
      second = mixSecond(second, values(index))
      index += 1
    ExprFingerprint(first ^ (first >>> 32), second ^ (second >>> 29))

  private def stringFingerprint(tag: Int, value: String): ExprFingerprint =
    if value == null then combine(tag, -1L)
    else
      var first = mixFirst(FirstOffset, tag.toLong)
      var second = mixSecond(SecondOffset, tag.toLong)
      var index = 0
      while index < value.length do
        val character = value.charAt(index).toLong
        first = mixFirst(first, character)
        second = mixSecond(second, character)
        index += 1
      combine(tag, first, second, value.length.toLong)

  private def dataTypeCode(dataType: DataType): Long = dataType match
    case DataType.Bool            => 1L
    case DataType.Int32           => 2L
    case DataType.Int64           => 3L
    case DataType.Float32         => 4L
    case DataType.Float64         => 5L
    case DataType.Utf8            => 6L
    case DataType.Timestamp(unit) => 16L + unit.ordinal.toLong

  private def literalFingerprint(value: LiteralValue): ExprFingerprint = value match
    case LiteralValue.Null(dataType)  => combine(20, dataTypeCode(dataType))
    case LiteralValue.Bool(actual)    => combine(21, if actual then 1L else 0L)
    case LiteralValue.Int32(actual)   => combine(22, actual.toLong)
    case LiteralValue.Int64(actual)   => combine(23, actual)
    case LiteralValue.Float32(actual) =>
      combine(24, java.lang.Float.floatToRawIntBits(actual).toLong)
    case LiteralValue.Float64(actual) =>
      combine(25, java.lang.Double.doubleToRawLongBits(actual))
    case LiteralValue.Utf8(actual)            => stringFingerprint(26, actual.value)
    case LiteralValue.Timestamp(actual, unit) => combine(27, actual, unit.ordinal.toLong)

  private def sameLiteral(left: LiteralValue, right: LiteralValue): Boolean =
    (left, right) match
      case (LiteralValue.Null(a), LiteralValue.Null(b))       => a == b
      case (LiteralValue.Bool(a), LiteralValue.Bool(b))       => a == b
      case (LiteralValue.Int32(a), LiteralValue.Int32(b))     => a == b
      case (LiteralValue.Int64(a), LiteralValue.Int64(b))     => a == b
      case (LiteralValue.Float32(a), LiteralValue.Float32(b)) =>
        java.lang.Float.floatToRawIntBits(a) == java.lang.Float.floatToRawIntBits(b)
      case (LiteralValue.Float64(a), LiteralValue.Float64(b)) =>
        java.lang.Double.doubleToRawLongBits(a) == java.lang.Double.doubleToRawLongBits(b)
      case (LiteralValue.Utf8(a), LiteralValue.Utf8(b))                   => a.value == b.value
      case (LiteralValue.Timestamp(a, au), LiteralValue.Timestamp(b, bu)) =>
        a == b && au == bu
      case _ => false

  private def structurallyEqual(left: ExprId, right: ExprId): Boolean =
    val pending = mutable.ArrayDeque((left, right))
    val visited = mutable.HashSet.empty[IdentityPair]
    var equal = true
    while pending.nonEmpty && equal do
      val (currentLeft, currentRight) = pending.removeLast()
      if !(currentLeft eq currentRight) then
        if currentLeft.fingerprint != currentRight.fingerprint then equal = false
        else
          val pair = new IdentityPair(currentLeft, currentRight)
          if visited.add(pair) then
            (currentLeft.witness, currentRight.witness) match
              case (ExprIdentityNode.Derived(a), ExprIdentityNode.Derived(b)) =>
                equal = a == b
              case (
                    ExprIdentityNode.Column(ai, ac, at, an),
                    ExprIdentityNode.Column(bi, bc, bt, bn)
                  ) =>
                equal = ai == bi && ac == bc && at == bt && an == bn
              case (ExprIdentityNode.Literal(a), ExprIdentityNode.Literal(b)) =>
                equal = sameLiteral(a, b)
              case (
                    ExprIdentityNode.Unary(ao, ai, at, an),
                    ExprIdentityNode.Unary(bo, bi, bt, bn)
                  ) =>
                equal = ao == bo && at == bt && an == bn
                if equal then pending.append((ai, bi))
              case (
                    ExprIdentityNode.Binary(ao, al, ar, at, an),
                    ExprIdentityNode.Binary(bo, bl, br, bt, bn)
                  ) =>
                equal = ao == bo && at == bt && an == bn
                if equal then
                  pending.append((al, bl))
                  pending.append((ar, br))
              case _ => equal = false
    equal

  private[frame4s] def derived(description: String): ExprId =
    new ExprId(stringFingerprint(1, description), ExprIdentityNode.Derived(description))

  private[frame4s] def column(
      input: InputRef,
      column: ColumnId,
      dataType: DataType,
      nullable: Boolean
  ): ExprId =
    val name = stringFingerprint(2, column.value)
    val fingerprint = combine(
      3,
      input.ordinal.toLong,
      dataTypeCode(dataType),
      if nullable then 1L else 0L,
      name.first,
      name.second
    )
    new ExprId(
      fingerprint,
      ExprIdentityNode.Column(input, column, dataType, nullable)
    )

  private[frame4s] def literal(value: LiteralValue): ExprId =
    new ExprId(literalFingerprint(value), ExprIdentityNode.Literal(value))

  private[frame4s] def unary(
      operator: UnaryOperator,
      input: ExprId,
      dataType: DataType,
      nullable: Boolean
  ): ExprId =
    val fingerprint = combine(
      4,
      operator.ordinal.toLong,
      dataTypeCode(dataType),
      if nullable then 1L else 0L,
      input.fingerprint.first,
      input.fingerprint.second
    )
    new ExprId(
      fingerprint,
      ExprIdentityNode.Unary(operator, input, dataType, nullable)
    )

  private[frame4s] def binary(
      operator: BinaryOperator,
      left: ExprId,
      right: ExprId,
      dataType: DataType,
      nullable: Boolean
  ): ExprId =
    val fingerprint = combine(
      5,
      operator.ordinal.toLong,
      dataTypeCode(dataType),
      if nullable then 1L else 0L,
      left.fingerprint.first,
      left.fingerprint.second,
      right.fingerprint.first,
      right.fingerprint.second
    )
    new ExprId(
      fingerprint,
      ExprIdentityNode.Binary(operator, left, right, dataType, nullable)
    )

  private[frame4s] def diagnostic(id: ExprId): String = "<redacted>"

  /** Test-only collision injection: retain a fingerprint while changing its structural witness. */
  private[frame4s] def collisionProbe(id: ExprId, description: String): ExprId =
    new ExprId(id.fingerprint, ExprIdentityNode.Derived(description))

final private[frame4s] class FrameScopeToken private ()

private[frame4s] object FrameScopeToken:
  def fresh(): FrameScopeToken = new FrameScopeToken()

final private[frame4s] case class ExprScopeId(
    token: FrameScopeToken,
    input: InputRef
)

private[frame4s] object ExprScopeId:
  def forInput(token: FrameScopeToken, input: InputRef): ExprScopeId =
    ExprScopeId(token, input)

private[frame4s] enum ExprNode:
  case Column(
      input: InputRef,
      scope: ExprScopeId,
      id: ColumnId,
      name: String,
      index: Int
  )
  case Literal(value: LiteralValue)
  case Unary(operator: UnaryOperator, input: ResolvedExpr)
  case Binary(operator: BinaryOperator, left: ResolvedExpr, right: ResolvedExpr)

final private[frame4s] case class ResolvedExpr(
    id: ExprId,
    dataType: DataType,
    nullable: Boolean,
    node: ExprNode
)

@implicitNotFound(
  "This expression was created for a different frame scope. Build it from the callback's scope or use a literal."
)
sealed private[frame4s] trait OriginInScope[Origin, Allowed]

private[frame4s] object OriginInScope:
  given [Origin, Allowed](using Origin <:< Allowed): OriginInScope[Origin, Allowed] with {}

@implicitNotFound(
  "Scalar operand has type ${Found}; expected ${Left} or its corresponding required/nullable form. Convert the value explicitly before using it in this expression"
)
sealed private[frame4s] trait ExactLiteralOperand[Left, Found, Right]:
  def widen(value: Found): Right

private[frame4s] object ExactLiteralOperand:
  given exact[Left, Found](using subtype: Found <:< Left): ExactLiteralOperand[Left, Found, Left]
  with
    def widen(value: Found): Left = subtype(value)

  given requiredForOptional[A, Found](using
      subtype: Found <:< A
  ): ExactLiteralOperand[Option[A], Found, A] with
    def widen(value: Found): A = subtype(value)

  given optionalForRequired[A, Found](using
      subtype: Found <:< Option[A]
  ): ExactLiteralOperand[A, Found, Option[A]] with
    def widen(value: Found): Option[A] = subtype(value)

/** Closed evidence for equality against an exact-width scalar literal. */
@implicitNotFound(
  "Operators ===, =!=, and nullSafeEq are not supported for ${Left} and scalar ${Found}. The scalar must have the same supported physical type; either side may be Option."
)
opaque type LiteralEqualityType[Left, Found, Result] = LiteralEqualityBuilder[Left, Found, Result]

private[frame4s] trait LiteralEqualityBuilder[Left, Found, Result]:
  def comparison[Origin](
      operator: BinaryOperator,
      left: ExprOf[Left, Origin],
      value: Found
  ): ExprOf[Result, Origin]
  def nullSafe[Origin](left: ExprOf[Left, Origin], value: Found): ExprOf[Boolean, Origin]

private[frame4s] object LiteralEqualityType:
  extension [Left, Found, Result](capability: LiteralEqualityType[Left, Found, Result])
    private[frame4s] def comparison[Origin](
        operator: BinaryOperator,
        left: ExprOf[Left, Origin],
        value: Found
    ): ExprOf[Result, Origin] = capability.comparison(operator, left, value)

    private[frame4s] def nullSafe[Origin](
        left: ExprOf[Left, Origin],
        value: Found
    ): ExprOf[Boolean, Origin] = capability.nullSafe(left, value)

  given [Left, Found, Right, Result](using
      exact: ExactLiteralOperand[Left, Found, Right],
      columnType: ColumnType[Right],
      equality: EqualityType[Left, Right, Result]
  ): LiteralEqualityType[Left, Found, Result] =
    new LiteralEqualityBuilder[Left, Found, Result]:
      def comparison[Origin](
          operator: BinaryOperator,
          left: ExprOf[Left, Origin],
          value: Found
      ): ExprOf[Result, Origin] =
        Expr.comparison(operator, left, Expr.literal(exact.widen(value)))

      def nullSafe[Origin](
          left: ExprOf[Left, Origin],
          value: Found
      ): ExprOf[Boolean, Origin] =
        Expr.nullSafeComparison(left, Expr.literal(exact.widen(value)))

/** Closed evidence for ordered comparison against an exact-width scalar literal. */
@implicitNotFound(
  "Operators <, <=, >, and >= are not supported for ${Left} and scalar ${Found}. The scalar must have the same supported physical type; either side may be Option."
)
opaque type LiteralOrderedType[Left, Found, Result] = LiteralOrderedBuilder[Left, Found, Result]

private[frame4s] trait LiteralOrderedBuilder[Left, Found, Result]:
  def apply[Origin](
      operator: BinaryOperator,
      left: ExprOf[Left, Origin],
      value: Found
  ): ExprOf[Result, Origin]

private[frame4s] object LiteralOrderedType:
  extension [Left, Found, Result](capability: LiteralOrderedType[Left, Found, Result])
    private[frame4s] def build[Origin](
        operator: BinaryOperator,
        left: ExprOf[Left, Origin],
        value: Found
    ): ExprOf[Result, Origin] = capability.apply(operator, left, value)

  given [Left, Found, Right, Result](using
      exact: ExactLiteralOperand[Left, Found, Right],
      columnType: ColumnType[Right],
      ordered: OrderedType[Left, Right, Result]
  ): LiteralOrderedType[Left, Found, Result] =
    new LiteralOrderedBuilder[Left, Found, Result]:
      def apply[Origin](
          operator: BinaryOperator,
          left: ExprOf[Left, Origin],
          value: Found
      ): ExprOf[Result, Origin] =
        Expr.comparison(operator, left, Expr.literal(exact.widen(value)))

/** Closed evidence for width-preserving arithmetic against a scalar literal. */
@implicitNotFound(
  "Operators +, -, and * are not supported for ${Left} and scalar ${Found}. Both operands must have the same numeric width; either may be Option."
)
opaque type LiteralArithmeticType[Left, Found, Result] =
  LiteralArithmeticBuilder[Left, Found, Result]

private[frame4s] trait LiteralArithmeticBuilder[Left, Found, Result]:
  def apply[Origin](
      operator: BinaryOperator,
      left: ExprOf[Left, Origin],
      value: Found
  ): ExprOf[Result, Origin]

private[frame4s] object LiteralArithmeticType:
  extension [Left, Found, Result](capability: LiteralArithmeticType[Left, Found, Result])
    private[frame4s] def build[Origin](
        operator: BinaryOperator,
        left: ExprOf[Left, Origin],
        value: Found
    ): ExprOf[Result, Origin] = capability.apply(operator, left, value)

  given [Left, Found, Right, Result](using
      exact: ExactLiteralOperand[Left, Found, Right],
      columnType: ColumnType[Right],
      arithmetic: ArithmeticType[Left, Right, Result]
  ): LiteralArithmeticType[Left, Found, Result] =
    new LiteralArithmeticBuilder[Left, Found, Result]:
      def apply[Origin](
          operator: BinaryOperator,
          left: ExprOf[Left, Origin],
          value: Found
      ): ExprOf[Result, Origin] =
        Expr.samePhysical(operator, left, Expr.literal(exact.widen(value)))

/** Closed evidence for width-preserving division by a scalar literal. */
@implicitNotFound(
  "Operator / is not supported for ${Left} and scalar ${Found}. Both operands must have the same numeric width; either may be Option."
)
opaque type LiteralDivisibleType[Left, Found, Result] = LiteralDivisibleBuilder[Left, Found, Result]

private[frame4s] trait LiteralDivisibleBuilder[Left, Found, Result]:
  def apply[Origin](
      left: ExprOf[Left, Origin],
      value: Found
  ): ExprOf[Result, Origin]

private[frame4s] object LiteralDivisibleType:
  extension [Left, Found, Result](capability: LiteralDivisibleType[Left, Found, Result])
    private[frame4s] def build[Origin](
        left: ExprOf[Left, Origin],
        value: Found
    ): ExprOf[Result, Origin] = capability.apply(left, value)

  given [Left, Found, Right, Result](using
      exact: ExactLiteralOperand[Left, Found, Right],
      columnType: ColumnType[Right],
      divisible: DivisibleType[Left, Right, Result]
  ): LiteralDivisibleType[Left, Found, Result] =
    new LiteralDivisibleBuilder[Left, Found, Result]:
      def apply[Origin](
          left: ExprOf[Left, Origin],
          value: Found
      ): ExprOf[Result, Origin] =
        Expr.samePhysical(BinaryOperator.Divide, left, Expr.literal(exact.widen(value)))

/** A typed logical expression whose originating frame scope is intentionally hidden. */
type Expr[A] = ExprOf[A, ?]
type ScopedExpr[A, Origin] = ExprOf[A, Origin]

/** A typed, immutable expression in a frame query.
  *
  * Construct column expressions inside a [[Frame]] callback. Comparison and arithmetic methods
  * accept an exact Scala value directly; use [[Expr.literal]] when the same literal expression is
  * reused. These methods do not perform numeric widening or implicit conversion, and an un-ascribed
  * raw `null` is rejected at compile time. The hidden `Origin` prevents a column expression from
  * being reused with another frame. An operation on `Option[A]` remains nullable unless the
  * operation, such as `isNull`, is total by definition.
  *
  * Constructing an expression performs no I/O and reads no table data.
  *
  * @tparam A
  *   the Scala column value type; `Option[A]` is the only nullable form
  * @tparam Origin
  *   the compiler-tracked frame scope that created this expression
  */
sealed class ExprOf[A, Origin] private[frame4s] (
    private[frame4s] val resolved: ResolvedExpr
):
  def id: ExprId = resolved.id
  def dataType: DataType = resolved.dataType
  def nullable: Boolean = resolved.nullable

  def as[Name <: String & Singleton](name: Name): NamedExprOf[Name, A, Origin] =
    NamedExpr(name, this)

  def ===[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using EqualityType[A, B, Result]): ExprOf[Result, Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.Equal, this, other)

  def ===[B, Result](other: LiteralExpr[B])(using
      EqualityType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.comparison(BinaryOperator.Equal, this, other)

  def ===[Value, Result](value: Value)(using
      equality: LiteralEqualityType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    equality.comparison(BinaryOperator.Equal, this, value)

  transparent inline def ===(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def =!=[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using EqualityType[A, B, Result]): ExprOf[Result, Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.NotEqual, this, other)

  def =!=[B, Result](other: LiteralExpr[B])(using
      EqualityType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.comparison(BinaryOperator.NotEqual, this, other)

  def =!=[Value, Result](value: Value)(using
      equality: LiteralEqualityType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    equality.comparison(BinaryOperator.NotEqual, this, value)

  transparent inline def =!=(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def nullSafeEq[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using EqualityType[A, B, Result]): ExprOf[Boolean, Origin | OtherOrigin] =
    Expr.nullSafeComparison(this, other)

  def nullSafeEq[B, Result](other: LiteralExpr[B])(using
      EqualityType[A, B, Result]
  ): ExprOf[Boolean, Origin] =
    Expr.nullSafeComparison(this, other)

  def nullSafeEq[Value, Result](value: Value)(using
      equality: LiteralEqualityType[A, Value, Result]
  ): ExprOf[Boolean, Origin] =
    equality.nullSafe(this, value)

  transparent inline def nullSafeEq(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def <[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using OrderedType[A, B, Result]): ExprOf[Result, Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.LessThan, this, other)

  def <[B, Result](other: LiteralExpr[B])(using
      OrderedType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.comparison(BinaryOperator.LessThan, this, other)

  def <[Value, Result](value: Value)(using
      ordered: LiteralOrderedType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    ordered.build(BinaryOperator.LessThan, this, value)

  transparent inline def <(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def <=[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using OrderedType[A, B, Result]): ExprOf[Result, Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.LessThanOrEqual, this, other)

  def <=[B, Result](other: LiteralExpr[B])(using
      OrderedType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.comparison(BinaryOperator.LessThanOrEqual, this, other)

  def <=[Value, Result](value: Value)(using
      ordered: LiteralOrderedType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    ordered.build(BinaryOperator.LessThanOrEqual, this, value)

  transparent inline def <=(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def >[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using OrderedType[A, B, Result]): ExprOf[Result, Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.GreaterThan, this, other)

  def >[B, Result](other: LiteralExpr[B])(using
      OrderedType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.comparison(BinaryOperator.GreaterThan, this, other)

  def >[Value, Result](value: Value)(using
      ordered: LiteralOrderedType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    ordered.build(BinaryOperator.GreaterThan, this, value)

  transparent inline def >(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def >=[B, OtherOrigin, Result](
      other: ExprOf[B, OtherOrigin]
  )(using OrderedType[A, B, Result]): ExprOf[Result, Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.GreaterThanOrEqual, this, other)

  def >=[B, Result](other: LiteralExpr[B])(using
      OrderedType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.comparison(BinaryOperator.GreaterThanOrEqual, this, other)

  def >=[Value, Result](value: Value)(using
      ordered: LiteralOrderedType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    ordered.build(BinaryOperator.GreaterThanOrEqual, this, value)

  transparent inline def >=(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def +[B, OtherOrigin, Result](other: ExprOf[B, OtherOrigin])(using
      ArithmeticType[A, B, Result]
  ): ExprOf[Result, Origin | OtherOrigin] =
    Expr.samePhysical(BinaryOperator.Add, this, other)

  def +[B, Result](other: LiteralExpr[B])(using
      ArithmeticType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.samePhysical(BinaryOperator.Add, this, other)

  def +[Value, Result](value: Value)(using
      arithmetic: LiteralArithmeticType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    arithmetic.build(BinaryOperator.Add, this, value)

  transparent inline def +(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def -[B, OtherOrigin, Result](other: ExprOf[B, OtherOrigin])(using
      ArithmeticType[A, B, Result]
  ): ExprOf[Result, Origin | OtherOrigin] =
    Expr.samePhysical(BinaryOperator.Subtract, this, other)

  def -[B, Result](other: LiteralExpr[B])(using
      ArithmeticType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.samePhysical(BinaryOperator.Subtract, this, other)

  def -[Value, Result](value: Value)(using
      arithmetic: LiteralArithmeticType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    arithmetic.build(BinaryOperator.Subtract, this, value)

  transparent inline def -(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def *[B, OtherOrigin, Result](other: ExprOf[B, OtherOrigin])(using
      ArithmeticType[A, B, Result]
  ): ExprOf[Result, Origin | OtherOrigin] =
    Expr.samePhysical(BinaryOperator.Multiply, this, other)

  def *[B, Result](other: LiteralExpr[B])(using
      ArithmeticType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.samePhysical(BinaryOperator.Multiply, this, other)

  def *[Value, Result](value: Value)(using
      arithmetic: LiteralArithmeticType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    arithmetic.build(BinaryOperator.Multiply, this, value)

  transparent inline def *(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def /[B, OtherOrigin, Result](other: ExprOf[B, OtherOrigin])(using
      DivisibleType[A, B, Result]
  ): ExprOf[Result, Origin | OtherOrigin] =
    Expr.samePhysical(BinaryOperator.Divide, this, other)

  def /[B, Result](other: LiteralExpr[B])(using
      DivisibleType[A, B, Result]
  ): ExprOf[Result, Origin] =
    Expr.samePhysical(BinaryOperator.Divide, this, other)

  def /[Value, Result](value: Value)(using
      divisible: LiteralDivisibleType[A, Value, Result]
  ): ExprOf[Result, Origin] =
    divisible.build(this, value)

  transparent inline def /(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  def isNull: ExprOf[Boolean, Origin] =
    Expr.unary(UnaryOperator.IsNull, this, DataType.Bool, nullable = false)

  def isNotNull: ExprOf[Boolean, Origin] =
    Expr.unary(UnaryOperator.IsNotNull, this, DataType.Bool, nullable = false)

  def &&[B, OtherOrigin, Result](other: ExprOf[B, OtherOrigin])(using
      BooleanType[A, B, Result]
  ): ExprOf[Result, Origin | OtherOrigin] =
    Expr.booleanBinary(BinaryOperator.And, this, other)

  def ||[B, OtherOrigin, Result](other: ExprOf[B, OtherOrigin])(using
      BooleanType[A, B, Result]
  ): ExprOf[Result, Origin | OtherOrigin] =
    Expr.booleanBinary(BinaryOperator.Or, this, other)

  def isTrue(using BooleanValue[A]): ExprOf[Boolean, Origin] =
    Expr.unary(UnaryOperator.IsTrue, this, DataType.Bool, nullable = false)

  def isFalse(using BooleanValue[A]): ExprOf[Boolean, Origin] =
    Expr.unary(UnaryOperator.IsFalse, this, DataType.Bool, nullable = false)

  def unary_-(using NumericType[A]): ExprOf[A, Origin] =
    Expr.unary(UnaryOperator.Negate, this, dataType, nullable)

  def sqrt(using FloatingType[A]): ExprOf[A, Origin] =
    Expr.unary(UnaryOperator.Sqrt, this, dataType, nullable)

/** A scope-independent literal expression that may be combined with any compatible column. */
final class LiteralExpr[A] private[frame4s] (
    resolved: ResolvedExpr
) extends ExprOf[A, Nothing](resolved)

/** Constructors for scope-independent typed expressions. */
object Expr:
  /** Reject an un-ascribed raw null before it can resolve to a reference-typed overload. */
  transparent inline def literal(inline value: Null): Nothing =
    error("Raw null is not a typed column value. Use None for a nullable column.")

  /** Construct a literal whose physical type and nullability come from [[ColumnType]].
    *
    * `None` is represented as a typed null. A raw `null` is not accepted as a typed column value.
    */
  def literal[A](value: A)(using columnType: ColumnType[A]): LiteralExpr[A] =
    literalChecked(value).fold(error => throw InvalidValueFailure(error), identity)

  /** Construct a literal through a total boundary.
    *
    * Use this form when a string-bearing value came from Java or code compiled without explicit
    * nulls. `None` remains the only typed representation of absence.
    */
  def literalChecked[A](
      value: A
  )(using columnType: ColumnType[A]): Either[ValueError, LiteralExpr[A]] =
    columnType
      .literal(value)
      .map: literal =>
        new LiteralExpr(
          ResolvedExpr(
            ExprId.literal(literal),
            columnType.dataType,
            columnType.nullable,
            ExprNode.Literal(literal)
          )
        )

  private[frame4s] def resolvedUnary(
      operator: UnaryOperator,
      input: ResolvedExpr,
      dataType: DataType,
      nullable: Boolean
  ): ResolvedExpr =
    ResolvedExpr(
      ExprId.unary(operator, input.id, dataType, nullable),
      dataType,
      nullable,
      ExprNode.Unary(operator, input)
    )

  private[frame4s] def resolvedBinary(
      operator: BinaryOperator,
      left: ResolvedExpr,
      right: ResolvedExpr,
      dataType: DataType,
      nullable: Boolean
  ): ResolvedExpr =
    ResolvedExpr(
      ExprId.binary(operator, left.id, right.id, dataType, nullable),
      dataType,
      nullable,
      ExprNode.Binary(operator, left, right)
    )

  private[frame4s] def unary[A, B, Origin](
      operator: UnaryOperator,
      input: ExprOf[A, Origin],
      dataType: DataType,
      nullable: Boolean
  ): ExprOf[B, Origin] =
    new ExprOf(resolvedUnary(operator, input.resolved, dataType, nullable))

  private[frame4s] def comparison[Left, Right, Result, LeftOrigin, RightOrigin](
      operator: BinaryOperator,
      left: ExprOf[Left, LeftOrigin],
      right: ExprOf[Right, RightOrigin]
  ): ExprOf[Result, LeftOrigin | RightOrigin] =
    new ExprOf(
      resolvedBinary(
        operator,
        left.resolved,
        right.resolved,
        DataType.Bool,
        left.nullable || right.nullable
      )
    )

  private[frame4s] def samePhysical[Left, Right, Result, LeftOrigin, RightOrigin](
      operator: BinaryOperator,
      left: ExprOf[Left, LeftOrigin],
      right: ExprOf[Right, RightOrigin]
  ): ExprOf[Result, LeftOrigin | RightOrigin] =
    new ExprOf(
      resolvedBinary(
        operator,
        left.resolved,
        right.resolved,
        left.dataType,
        left.nullable || right.nullable
      )
    )

  private[frame4s] def nullSafeComparison[Left, Right, LeftOrigin, RightOrigin](
      left: ExprOf[Left, LeftOrigin],
      right: ExprOf[Right, RightOrigin]
  ): ExprOf[Boolean, LeftOrigin | RightOrigin] =
    new ExprOf(
      resolvedBinary(
        BinaryOperator.NullSafeEqual,
        left.resolved,
        right.resolved,
        DataType.Bool,
        nullable = false
      )
    )

  private[frame4s] def booleanBinary[Left, Right, Result, LeftOrigin, RightOrigin](
      operator: BinaryOperator,
      left: ExprOf[Left, LeftOrigin],
      right: ExprOf[Right, RightOrigin]
  ): ExprOf[Result, LeftOrigin | RightOrigin] =
    new ExprOf(
      resolvedBinary(
        operator,
        left.resolved,
        right.resolved,
        DataType.Bool,
        left.nullable || right.nullable
      )
    )

/** A typed expression paired with its singleton output column name. */
sealed trait NamedExpr[Name <: String & Singleton, A]:
  def name: Name
  def expression: Expr[A]

final case class NamedExprOf[
    Name <: String & Singleton,
    A,
    Origin
](
    name: Name,
    expression: ExprOf[A, Origin]
) extends NamedExpr[Name, A]

/** A checked source-column expression that retains its existing singleton field name.
  *
  * [[Scope.col]] is the only constructor. Projection and grouping may therefore reuse `name`
  * without an alias while preserving the same frame-origin proof as [[ExprOf]]. Computed
  * expressions return `ExprOf` and still require [[ExprOf.as]] before they become output fields.
  */
final class ColumnExprOf[
    Name <: String & Singleton,
    A,
    Origin
] private[frame4s] (
    val name: Name,
    resolved: ResolvedExpr
) extends ExprOf[A, Origin](resolved)
    with NamedExpr[Name, A]:
  def expression: ExprOf[A, Origin] = this

object NamedExpr:
  def apply[Name <: String & Singleton, A, Origin](
      name: Name,
      expression: ExprOf[A, Origin]
  ): NamedExprOf[Name, A, Origin] =
    NamedExprOf(name, expression)

final private[frame4s] case class NamedExpression(name: String, expression: ResolvedExpr)

@implicitNotFound(
  "A projection or grouping may contain raw columns or explicitly named computed expressions. Add .as(\"name\") to each computed expression."
)
private[frame4s] trait ExpressionSelection[
    Expressions <: Tuple,
    Names <: Tuple,
    Values <: Tuple
]:
  def expressions(value: Expressions): Vector[NamedExpression]

private[frame4s] object ExpressionSelection:
  given ExpressionSelection[EmptyTuple, EmptyTuple, EmptyTuple] with
    def expressions(value: EmptyTuple): Vector[NamedExpression] = Vector.empty

  given namedSelection[
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple,
      TailNames <: Tuple,
      TailValues <: Tuple
  ](using
      tail: ExpressionSelection[Tail, TailNames, TailValues]
  ): ExpressionSelection[
    NamedExprOf[Name, Value, Origin] *: Tail,
    Name *: TailNames,
    Value *: TailValues
  ] with
    def expressions(
        value: NamedExprOf[Name, Value, Origin] *: Tail
    ): Vector[NamedExpression] =
      NamedExpression(value.head.name, value.head.expression.resolved) +: tail.expressions(
        value.tail
      )

  given columnSelection[
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple,
      TailNames <: Tuple,
      TailValues <: Tuple
  ](using
      tail: ExpressionSelection[Tail, TailNames, TailValues]
  ): ExpressionSelection[
    ColumnExprOf[Name, Value, Origin] *: Tail,
    Name *: TailNames,
    Value *: TailValues
  ] with
    def expressions(
        value: ColumnExprOf[Name, Value, Origin] *: Tail
    ): Vector[NamedExpression] =
      NamedExpression(value.head.name, value.head.resolved) +: tail.expressions(value.tail)

@implicitNotFound(
  "One or more expressions were created for a different frame scope. Build every column expression from this callback's scope."
)
sealed private[frame4s] trait ExpressionsInScope[Expressions <: Tuple, Allowed]

private[frame4s] object ExpressionsInScope:
  given [Allowed]: ExpressionsInScope[EmptyTuple, Allowed] with {}

  given namedInScope[
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple,
      Allowed
  ](using
      head: OriginInScope[Origin, Allowed],
      tail: ExpressionsInScope[Tail, Allowed]
  ): ExpressionsInScope[NamedExprOf[Name, Value, Origin] *: Tail, Allowed] with {}

  given columnInScope[
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple,
      Allowed
  ](using
      head: OriginInScope[Origin, Allowed],
      tail: ExpressionsInScope[Tail, Allowed]
  ): ExpressionsInScope[ColumnExprOf[Name, Value, Origin] *: Tail, Allowed] with {}

final class Scope[S <: NamedTuple.AnyNamedTuple, Origin] private[frame4s] (
    input: InputRef,
    schema: Schema,
    scopeId: ExprScopeId
):
  /** Select one existing field by its singleton name.
    *
    * The result retains both the checked field name and this callback's frame provenance. Raw
    * columns can therefore be projected or grouped without repeating `.as`; a computed expression
    * still requires an explicit output name.
    */
  def col[Name <: String & Singleton](name: Name)(using
      at: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], Name]
  ): ColumnExprOf[Name, SchemaFieldType[S, Name], Origin] =
    val field = schema.fields(at.index)
    val resolved = ResolvedExpr(
      ExprId.column(input, field.id, field.dataType, field.nullable),
      field.dataType,
      field.nullable,
      ExprNode.Column(input, scopeId, field.id, field.name, at.index)
    )
    new ColumnExprOf(name, resolved)

object Scope:
  private[frame4s] def current[S <: NamedTuple.AnyNamedTuple, Origin](
      token: FrameScopeToken,
      schema: Schema
  ): Scope[S, Origin] =
    new Scope(InputRef.Current, schema, ExprScopeId.forInput(token, InputRef.Current))

  private[frame4s] def left[S <: NamedTuple.AnyNamedTuple, Origin](
      token: FrameScopeToken,
      schema: Schema
  ): Scope[S, Origin] =
    new Scope(InputRef.Left, schema, ExprScopeId.forInput(token, InputRef.Left))

  private[frame4s] def right[S <: NamedTuple.AnyNamedTuple, Origin](
      token: FrameScopeToken,
      schema: Schema
  ): Scope[S, Origin] =
    new Scope(InputRef.Right, schema, ExprScopeId.forInput(token, InputRef.Right))

private[frame4s] enum AggregateNode:
  case Count
  case Sum(input: ResolvedExpr)
  case Mean(input: ResolvedExpr)
  case VariancePop(input: ResolvedExpr)
  case StddevPop(input: ResolvedExpr)
  case Min(input: ResolvedExpr)
  case Max(input: ResolvedExpr)

final private[frame4s] case class ResolvedAggregate(
    dataType: DataType,
    nullable: Boolean,
    node: AggregateNode
)

type AggregateExpr[A] = AggregateExprOf[A, ?]

final class AggregateExprOf[A, Origin] private[frame4s] (
    private[frame4s] val resolved: ResolvedAggregate
):
  def as[Name <: String & Singleton](
      name: Name
  ): NamedAggregateOf[Name, A, Origin] =
    NamedAggregate(name, this)

sealed trait NamedAggregate[Name <: String & Singleton, A]:
  def name: Name
  def expression: AggregateExpr[A]

final case class NamedAggregateOf[
    Name <: String & Singleton,
    A,
    Origin
](
    name: Name,
    expression: AggregateExprOf[A, Origin]
) extends NamedAggregate[Name, A]

object NamedAggregate:
  def apply[Name <: String & Singleton, A, Origin](
      name: Name,
      expression: AggregateExprOf[A, Origin]
  ): NamedAggregateOf[Name, A, Origin] =
    NamedAggregateOf(name, expression)

object Aggregate:
  val count: AggregateExprOf[Long, Nothing] =
    new AggregateExprOf(
      ResolvedAggregate(DataType.Int64, nullable = false, AggregateNode.Count)
    )

  def sum[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericType[A]): AggregateExprOf[ReductionResult[A], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        expression.dataType,
        nullable = true,
        AggregateNode.Sum(expression.resolved)
      )
    )

  def mean[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericType[A]): AggregateExprOf[Option[Double], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        DataType.Float64,
        nullable = true,
        AggregateNode.Mean(expression.resolved)
      )
    )

  def variancePop[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericType[A]): AggregateExprOf[Option[Double], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        DataType.Float64,
        nullable = true,
        AggregateNode.VariancePop(expression.resolved)
      )
    )

  def stddevPop[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericType[A]): AggregateExprOf[Option[Double], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        DataType.Float64,
        nullable = true,
        AggregateNode.StddevPop(expression.resolved)
      )
    )

  def min[A, Origin](
      expression: ExprOf[A, Origin]
  )(using OrderedValue[A]): AggregateExprOf[ReductionResult[A], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        expression.dataType,
        nullable = true,
        AggregateNode.Min(expression.resolved)
      )
    )

  def max[A, Origin](
      expression: ExprOf[A, Origin]
  )(using OrderedValue[A]): AggregateExprOf[ReductionResult[A], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        expression.dataType,
        nullable = true,
        AggregateNode.Max(expression.resolved)
      )
    )

/** Closed evidence for width-preserving `+`, `-`, and `*` expressions. */
@implicitNotFound(
  "Operators +, -, and * are not supported for ${Left} and ${Right}. Both operands must have the same numeric width; either may be Option."
)
opaque type ArithmeticType[Left, Right, Result] = Unit

private[frame4s] object ArithmeticType:
  given required[A <: (Int | Long | Float | Double)]: ArithmeticType[A, A, A] = ()
  given optionalLeft[A <: (Int | Long | Float | Double)]: ArithmeticType[Option[A], A, Option[A]] =
    ()
  given optionalRight[A <: (Int | Long | Float | Double)]: ArithmeticType[A, Option[A], Option[A]] =
    ()
  given optionalBoth[A <: (Int | Long | Float | Double)]
      : ArithmeticType[Option[A], Option[A], Option[A]] = ()

/** Closed evidence for width-preserving checked integral or IEEE floating-point division. */
@implicitNotFound(
  "Operator / is not supported for ${Left} and ${Right}. Both operands must have the same numeric width; either may be Option."
)
opaque type DivisibleType[Left, Right, Result] = Unit

private[frame4s] object DivisibleType:
  given required[A <: (Int | Long | Float | Double)]: DivisibleType[A, A, A] = ()
  given optionalLeft[A <: (Int | Long | Float | Double)]: DivisibleType[Option[A], A, Option[A]] =
    ()
  given optionalRight[A <: (Int | Long | Float | Double)]: DivisibleType[A, Option[A], Option[A]] =
    ()
  given optionalBoth[A <: (Int | Long | Float | Double)]
      : DivisibleType[Option[A], Option[A], Option[A]] = ()

/** Closed evidence for equality over one physical scalar type, with nullable lifting. */
@implicitNotFound(
  "Operators ===, =!=, and nullSafeEq are not supported for ${Left} and ${Right}. Operands must have the same supported physical type; either may be Option."
)
opaque type EqualityType[Left, Right, Result] = Unit

private[frame4s] object EqualityType:
  given required[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : EqualityType[A, A, Boolean] = ()
  given optionalLeft[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : EqualityType[Option[A], A, Option[Boolean]] = ()
  given optionalRight[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : EqualityType[A, Option[A], Option[Boolean]] = ()
  given optionalBoth[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : EqualityType[Option[A], Option[A], Option[Boolean]] = ()

/** Closed evidence for ordered comparisons over one physical scalar type. */
@implicitNotFound(
  "Operators <, <=, >, and >= are not supported for ${Left} and ${Right}. Operands must have the same supported physical type; either may be Option."
)
opaque type OrderedType[Left, Right, Result] = Unit

private[frame4s] object OrderedType:
  given required[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : OrderedType[A, A, Boolean] = ()
  given optionalLeft[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : OrderedType[Option[A], A, Option[Boolean]] = ()
  given optionalRight[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : OrderedType[A, Option[A], Option[Boolean]] = ()
  given optionalBoth[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : OrderedType[Option[A], Option[A], Option[Boolean]] = ()

/** Closed evidence for SQL three-valued `&&` and `||`. */
@implicitNotFound(
  "Operators && and || are not supported for ${Left} and ${Right}. Both operands must be Boolean or Option[Boolean]."
)
opaque type BooleanType[Left, Right, Result] = Unit

private[frame4s] object BooleanType:
  given BooleanType[Boolean, Boolean, Boolean] = ()
  given BooleanType[Option[Boolean], Boolean, Option[Boolean]] = ()
  given BooleanType[Boolean, Option[Boolean], Option[Boolean]] = ()
  given BooleanType[Option[Boolean], Option[Boolean], Option[Boolean]] = ()

/** Closed evidence for unary numeric negation. */
@implicitNotFound(
  "Unary - requires Int, Long, Float, Double, or Option of one of those types; found ${A}."
)
opaque type NumericType[A] = Unit

private[frame4s] object NumericType:
  given required[A <: (Int | Long | Float | Double)]: NumericType[A] = ()
  given optional[A <: (Int | Long | Float | Double)]: NumericType[Option[A]] = ()

/** Closed evidence for Boolean truth predicates. */
@implicitNotFound("isTrue and isFalse require Boolean or Option[Boolean]; found ${A}.")
opaque type BooleanValue[A] = Unit

private[frame4s] object BooleanValue:
  given BooleanValue[Boolean] = ()
  given BooleanValue[Option[Boolean]] = ()

/** Closed evidence for ordered reductions. */
@implicitNotFound(
  "min and max require Boolean, Int, Long, Float, Double, String, TimestampMicros, or Option of one of those types; found ${A}."
)
opaque type OrderedValue[A] = Unit

private[frame4s] object OrderedValue:
  given required[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : OrderedValue[A] = ()
  given optional[A <: (Boolean | Int | Long | Float | Double | String | TimestampMicros)]
      : OrderedValue[Option[A]] = ()

/** Closed evidence for square-root expressions. */
@implicitNotFound(
  "sqrt requires Float, Double, Option[Float], or Option[Double]; found ${A}."
)
opaque type FloatingType[A] = Unit

private[frame4s] object FloatingType:
  given required[A <: (Float | Double)]: FloatingType[A] = ()
  given optional[A <: (Float | Double)]: FloatingType[Option[A]] = ()

type ReductionResult[A] = Nullable[A]

final private[frame4s] case class NamedAggregateExpression(
    name: String,
    expression: ResolvedAggregate
)

private[frame4s] trait AggregateSelection[Expressions <: Tuple]:
  def expressions(value: Expressions): Vector[NamedAggregateExpression]

private[frame4s] object AggregateSelection:
  given AggregateSelection[EmptyTuple] with
    def expressions(value: EmptyTuple): Vector[NamedAggregateExpression] = Vector.empty

  given [
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple
  ](using
      tail: AggregateSelection[Tail]
  ): AggregateSelection[NamedAggregateOf[Name, Value, Origin] *: Tail] with
    def expressions(
        value: NamedAggregateOf[Name, Value, Origin] *: Tail
    ): Vector[NamedAggregateExpression] =
      NamedAggregateExpression(value.head.name, value.head.expression.resolved) +: tail.expressions(
        value.tail
      )

@implicitNotFound(
  "One or more aggregate expressions were created for a different frame scope. Build every aggregate input from this callback's scope."
)
sealed private[frame4s] trait AggregatesInScope[Expressions <: Tuple, Allowed]

private[frame4s] object AggregatesInScope:
  given [Allowed]: AggregatesInScope[EmptyTuple, Allowed] with {}

  given [
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple,
      Allowed
  ](using
      head: OriginInScope[Origin, Allowed],
      tail: AggregatesInScope[Tail, Allowed]
  ): AggregatesInScope[
    NamedAggregateOf[Name, Value, Origin] *: Tail,
    Allowed
  ] with {}

type AggregateNames[Expressions <: Tuple] <: Tuple = Expressions match
  case EmptyTuple                          => EmptyTuple
  case NamedAggregate[name, value] *: tail => name *: AggregateNames[tail]

type AggregateValues[Expressions <: Tuple] <: Tuple = Expressions match
  case EmptyTuple                          => EmptyTuple
  case NamedAggregate[name, value] *: tail => value *: AggregateValues[tail]

type AggregateSchema[Expressions <: Tuple] = NamedTuple.NamedTuple[
  AggregateNames[Expressions],
  AggregateValues[Expressions]
]
