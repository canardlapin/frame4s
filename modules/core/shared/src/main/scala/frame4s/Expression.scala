package frame4s

import scala.NamedTuple
import scala.annotation.implicitNotFound

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
  case IsTrue
  case Negate
  case Sqrt

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
  case Utf8(value: String)
  case Timestamp(value: Long, unit: TimeUnit)

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

type ComparisonResult[A] = A match
  case Option[value] => Option[Boolean]
  case _             => Boolean

@implicitNotFound(
  "This expression was created for a different frame scope. Build it from the callback's scope or use a literal."
)
sealed private[frame4s] trait OriginInScope[Origin, Allowed]

private[frame4s] object OriginInScope:
  given [Origin, Allowed](using Origin <:< Allowed): OriginInScope[Origin, Allowed] with {}

/** A typed logical expression whose originating frame scope is intentionally hidden. */
type Expr[A] = ExprOf[A, ?]
type ScopedExpr[A, Origin] = ExprOf[A, Origin]

/** A typed, immutable expression in a frame query.
  *
  * Construct column expressions inside a [[Frame]] callback and constants with [[Expr.literal]].
  * The hidden `Origin` prevents a column expression from being reused with another frame. An
  * operation on `Option[A]` remains nullable unless the operation, such as `isNull`, is total by
  * definition.
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

  def ===[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  ): ExprOf[ComparisonResult[A], Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.Equal, this, other)

  def ===(other: LiteralExpr[A]): ExprOf[ComparisonResult[A], Origin] =
    Expr.comparison(BinaryOperator.Equal, this, other)

  def =!=[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  ): ExprOf[ComparisonResult[A], Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.NotEqual, this, other)

  def =!=(other: LiteralExpr[A]): ExprOf[ComparisonResult[A], Origin] =
    Expr.comparison(BinaryOperator.NotEqual, this, other)

  def nullSafeEq[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  ): ExprOf[Boolean, Origin | OtherOrigin] =
    Expr.nullSafeComparison(this, other)

  def nullSafeEq(other: LiteralExpr[A]): ExprOf[Boolean, Origin] =
    Expr.nullSafeComparison(this, other)

  def <[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  )(using Ordering[A]): ExprOf[ComparisonResult[A], Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.LessThan, this, other)

  def <(other: LiteralExpr[A])(using
      Ordering[A]
  ): ExprOf[ComparisonResult[A], Origin] =
    Expr.comparison(BinaryOperator.LessThan, this, other)

  def <=[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  )(using Ordering[A]): ExprOf[ComparisonResult[A], Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.LessThanOrEqual, this, other)

  def <=(other: LiteralExpr[A])(using
      Ordering[A]
  ): ExprOf[ComparisonResult[A], Origin] =
    Expr.comparison(BinaryOperator.LessThanOrEqual, this, other)

  def >[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  )(using Ordering[A]): ExprOf[ComparisonResult[A], Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.GreaterThan, this, other)

  def >(other: LiteralExpr[A])(using
      Ordering[A]
  ): ExprOf[ComparisonResult[A], Origin] =
    Expr.comparison(BinaryOperator.GreaterThan, this, other)

  def >=[OtherOrigin](
      other: ExprOf[A, OtherOrigin]
  )(using Ordering[A]): ExprOf[ComparisonResult[A], Origin | OtherOrigin] =
    Expr.comparison(BinaryOperator.GreaterThanOrEqual, this, other)

  def >=(other: LiteralExpr[A])(using
      Ordering[A]
  ): ExprOf[ComparisonResult[A], Origin] =
    Expr.comparison(BinaryOperator.GreaterThanOrEqual, this, other)

  def +[OtherOrigin](other: ExprOf[A, OtherOrigin])(using
      Numeric[A]
  ): ExprOf[A, Origin | OtherOrigin] =
    Expr.sameType(BinaryOperator.Add, this, other)

  def +(other: LiteralExpr[A])(using Numeric[A]): ExprOf[A, Origin] =
    Expr.sameType(BinaryOperator.Add, this, other)

  def -[OtherOrigin](other: ExprOf[A, OtherOrigin])(using
      Numeric[A]
  ): ExprOf[A, Origin | OtherOrigin] =
    Expr.sameType(BinaryOperator.Subtract, this, other)

  def -(other: LiteralExpr[A])(using Numeric[A]): ExprOf[A, Origin] =
    Expr.sameType(BinaryOperator.Subtract, this, other)

  def *[OtherOrigin](other: ExprOf[A, OtherOrigin])(using
      Numeric[A]
  ): ExprOf[A, Origin | OtherOrigin] =
    Expr.sameType(BinaryOperator.Multiply, this, other)

  def *(other: LiteralExpr[A])(using Numeric[A]): ExprOf[A, Origin] =
    Expr.sameType(BinaryOperator.Multiply, this, other)

  def /[OtherOrigin](other: ExprOf[A, OtherOrigin])(using
      Fractional[A]
  ): ExprOf[A, Origin | OtherOrigin] =
    Expr.sameType(BinaryOperator.Divide, this, other)

  def /(other: LiteralExpr[A])(using Fractional[A]): ExprOf[A, Origin] =
    Expr.sameType(BinaryOperator.Divide, this, other)

  def isNull: ExprOf[Boolean, Origin] =
    Expr.unary(UnaryOperator.IsNull, this, DataType.Bool, nullable = false)

  def sqrt(using FloatingColumn[A]): ExprOf[A, Origin] =
    Expr.unary(UnaryOperator.Sqrt, this, dataType, nullable)

/** A scope-independent literal expression that may be combined with any compatible column. */
final class LiteralExpr[A] private[frame4s] (
    resolved: ResolvedExpr
) extends ExprOf[A, Nothing](resolved)

/** Constructors for scope-independent typed expressions. */
object Expr:
  /** Construct a literal whose physical type and nullability come from [[ColumnType]].
    *
    * `None` is represented as a typed null. A raw `null` is not accepted as a typed column value.
    */
  def literal[A](value: A)(using columnType: ColumnType[A]): LiteralExpr[A] =
    val literal = columnType.literal(value)
    val id = ExprId.derived(s"literal:$literal")
    new LiteralExpr(
      ResolvedExpr(id, columnType.dataType, columnType.nullable, ExprNode.Literal(literal))
    )

  private def binaryId(operator: BinaryOperator, left: ResolvedExpr, right: ResolvedExpr): ExprId =
    ExprId.derived(s"$operator(${left.id.value},${right.id.value})")

  private[frame4s] def unary[A, B, Origin](
      operator: UnaryOperator,
      input: ExprOf[A, Origin],
      dataType: DataType,
      nullable: Boolean
  ): ExprOf[B, Origin] =
    val resolved = input.resolved
    new ExprOf(
      ResolvedExpr(
        ExprId.derived(s"$operator(${resolved.id.value})"),
        dataType,
        nullable,
        ExprNode.Unary(operator, resolved)
      )
    )

  private[frame4s] def comparison[A, LeftOrigin, RightOrigin](
      operator: BinaryOperator,
      left: ExprOf[A, LeftOrigin],
      right: ExprOf[A, RightOrigin]
  ): ExprOf[ComparisonResult[A], LeftOrigin | RightOrigin] =
    new ExprOf(
      ResolvedExpr(
        binaryId(operator, left.resolved, right.resolved),
        DataType.Bool,
        left.nullable || right.nullable,
        ExprNode.Binary(operator, left.resolved, right.resolved)
      )
    )

  private[frame4s] def sameType[A, LeftOrigin, RightOrigin](
      operator: BinaryOperator,
      left: ExprOf[A, LeftOrigin],
      right: ExprOf[A, RightOrigin]
  ): ExprOf[A, LeftOrigin | RightOrigin] =
    new ExprOf(
      ResolvedExpr(
        binaryId(operator, left.resolved, right.resolved),
        left.dataType,
        left.nullable || right.nullable,
        ExprNode.Binary(operator, left.resolved, right.resolved)
      )
    )

  private[frame4s] def nullSafeComparison[A, LeftOrigin, RightOrigin](
      left: ExprOf[A, LeftOrigin],
      right: ExprOf[A, RightOrigin]
  ): ExprOf[Boolean, LeftOrigin | RightOrigin] =
    new ExprOf(
      ResolvedExpr(
        binaryId(BinaryOperator.NullSafeEqual, left.resolved, right.resolved),
        DataType.Bool,
        nullable = false,
        ExprNode.Binary(BinaryOperator.NullSafeEqual, left.resolved, right.resolved)
      )
    )

  private[frame4s] def booleanBinary[LeftOrigin, RightOrigin](
      operator: BinaryOperator,
      left: ExprOf[Boolean, LeftOrigin],
      right: ExprOf[Boolean, RightOrigin]
  ): ExprOf[Boolean, LeftOrigin | RightOrigin] =
    new ExprOf(
      ResolvedExpr(
        binaryId(operator, left.resolved, right.resolved),
        DataType.Bool,
        nullable = false,
        ExprNode.Binary(operator, left.resolved, right.resolved)
      )
    )

extension [LeftOrigin](expression: ExprOf[Boolean, LeftOrigin])
  def &&[RightOrigin](
      other: ExprOf[Boolean, RightOrigin]
  ): ExprOf[Boolean, LeftOrigin | RightOrigin] =
    Expr.booleanBinary(BinaryOperator.And, expression, other)

  def ||[RightOrigin](
      other: ExprOf[Boolean, RightOrigin]
  ): ExprOf[Boolean, LeftOrigin | RightOrigin] =
    Expr.booleanBinary(BinaryOperator.Or, expression, other)

extension [Origin](expression: ExprOf[Option[Boolean], Origin])
  def isTrue: ExprOf[Boolean, Origin] =
    Expr.unary(UnaryOperator.IsTrue, expression, DataType.Bool, nullable = false)

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

object NamedExpr:
  def apply[Name <: String & Singleton, A, Origin](
      name: Name,
      expression: ExprOf[A, Origin]
  ): NamedExprOf[Name, A, Origin] =
    NamedExprOf(name, expression)

final private[frame4s] case class NamedExpression(name: String, expression: ResolvedExpr)

private[frame4s] trait ExpressionSelection[Expressions <: Tuple]:
  def expressions(value: Expressions): Vector[NamedExpression]

private[frame4s] object ExpressionSelection:
  given ExpressionSelection[EmptyTuple] with
    def expressions(value: EmptyTuple): Vector[NamedExpression] = Vector.empty

  given [
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple
  ](using
      tail: ExpressionSelection[Tail]
  ): ExpressionSelection[NamedExprOf[Name, Value, Origin] *: Tail] with
    def expressions(
        value: NamedExprOf[Name, Value, Origin] *: Tail
    ): Vector[NamedExpression] =
      NamedExpression(value.head.name, value.head.expression.resolved) +: tail.expressions(
        value.tail
      )

@implicitNotFound(
  "One or more expressions were created for a different frame scope. Build every column expression from this callback's scope."
)
sealed private[frame4s] trait ExpressionsInScope[Expressions <: Tuple, Allowed]

private[frame4s] object ExpressionsInScope:
  given [Allowed]: ExpressionsInScope[EmptyTuple, Allowed] with {}

  given [
      Name <: String & Singleton,
      Value,
      Origin,
      Tail <: Tuple,
      Allowed
  ](using
      head: OriginInScope[Origin, Allowed],
      tail: ExpressionsInScope[Tail, Allowed]
  ): ExpressionsInScope[NamedExprOf[Name, Value, Origin] *: Tail, Allowed] with {}

final class Scope[S <: NamedTuple.AnyNamedTuple, Origin] private[frame4s] (
    input: InputRef,
    schema: Schema,
    scopeId: ExprScopeId
):
  def col[Name <: String & Singleton](name: Name)(using
      at: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], Name]
  ): ExprOf[SchemaFieldType[S, Name], Origin] =
    val field = schema.fields(at.index)
    val resolved = ResolvedExpr(
      ExprId.derived(s"column:${input.qualifier}:${field.id.value}"),
      field.dataType,
      field.nullable,
      ExprNode.Column(input, scopeId, field.id, field.name, at.index)
    )
    new ExprOf(resolved)

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
  )(using NumericColumn[A]): AggregateExprOf[A, Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        expression.dataType,
        expression.nullable,
        AggregateNode.Sum(expression.resolved)
      )
    )

  def mean[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericColumn[A]): AggregateExprOf[MeanResult[A], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        DataType.Float64,
        expression.nullable,
        AggregateNode.Mean(expression.resolved)
      )
    )

  def variancePop[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericColumn[A]): AggregateExprOf[MeanResult[A], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        DataType.Float64,
        expression.nullable,
        AggregateNode.VariancePop(expression.resolved)
      )
    )

  def stddevPop[A, Origin](
      expression: ExprOf[A, Origin]
  )(using NumericColumn[A]): AggregateExprOf[MeanResult[A], Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        DataType.Float64,
        expression.nullable,
        AggregateNode.StddevPop(expression.resolved)
      )
    )

  def min[A, Origin](
      expression: ExprOf[A, Origin]
  )(using Ordering[A]): AggregateExprOf[A, Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        expression.dataType,
        expression.nullable,
        AggregateNode.Min(expression.resolved)
      )
    )

  def max[A, Origin](
      expression: ExprOf[A, Origin]
  )(using Ordering[A]): AggregateExprOf[A, Origin] =
    new AggregateExprOf(
      ResolvedAggregate(
        expression.dataType,
        expression.nullable,
        AggregateNode.Max(expression.resolved)
      )
    )

trait NumericColumn[A]

object NumericColumn:
  given NumericColumn[Int] with {}
  given NumericColumn[Long] with {}
  given NumericColumn[Float] with {}
  given NumericColumn[Double] with {}
  given [A](using NumericColumn[A]): NumericColumn[Option[A]] with {}

@implicitNotFound(
  "sqrt requires a Float, Double, Option[Float], or Option[Double] expression."
)
sealed trait FloatingColumn[A]

object FloatingColumn:
  given FloatingColumn[Float] with {}
  given FloatingColumn[Double] with {}
  given [A](using FloatingColumn[A]): FloatingColumn[Option[A]] with {}

type MeanResult[A] = A match
  case Option[value] => Option[Double]
  case _             => Double

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
