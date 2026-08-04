package frame4s

import scala.collection.mutable

enum JoinKind:
  case Inner
  case LeftOuter
  case LeftSemi
  case LeftAnti

private[frame4s] enum JoinColumn:
  case Left(index: Int)
  case Right(index: Int)

private[frame4s] object JoinColumn:
  def all(leftSize: Int, rightSize: Int): Vector[JoinColumn] =
    Vector.tabulate(leftSize)(JoinColumn.Left.apply) ++
      Vector.tabulate(rightSize)(JoinColumn.Right.apply)

enum SortDirection:
  case Ascending
  case Descending

enum NullPlacement:
  case First
  case Last

/** One typed sort expression with its own direction and null placement.
  *
  * Start with `SortKey(expression)`, then use [[ascending]], [[descending]], [[nullsFirst]], or
  * [[nullsLast]]. Every modifier returns a new key; constructing one never changes a frame.
  */
final class SortKey[Origin] private[frame4s] (
    private[frame4s] val expression: ResolvedExpr,
    private[frame4s] val direction: SortDirection,
    private[frame4s] val nulls: NullPlacement
):
  def ascending: SortKey[Origin] =
    new SortKey(expression, SortDirection.Ascending, nulls)

  def descending: SortKey[Origin] =
    new SortKey(expression, SortDirection.Descending, nulls)

  def nullsFirst: SortKey[Origin] =
    new SortKey(expression, direction, NullPlacement.First)

  def nullsLast: SortKey[Origin] =
    new SortKey(expression, direction, NullPlacement.Last)

private object SortKeyDefaults:
  val direction = SortDirection.Ascending
  val nulls = NullPlacement.Last

object SortKey:
  /** Build an ascending, nulls-last key. Chain immutable modifiers for another policy. */
  def apply[A, Origin](expression: ExprOf[A, Origin]): SortKey[Origin] =
    new SortKey(expression.resolved, SortKeyDefaults.direction, SortKeyDefaults.nulls)

enum SourceKind:
  case Scan
  case Values

enum OrderGuarantee:
  case Unspecified
  case Stable
  case Sorted(keys: Vector[ExprId])

final case class SourceRef private (
    id: SourceId,
    displayName: String,
    kind: SourceKind,
    order: OrderGuarantee
)

object SourceRef:
  private[frame4s] val singleSourceValues: SourceRef =
    SourceRef(
      SourceId.unsafe("frame4s.single-source.values"),
      "single in-memory source",
      SourceKind.Values,
      OrderGuarantee.Stable
    )

  private[frame4s] val singleSourceScan: SourceRef =
    SourceRef(
      SourceId.unsafe("frame4s.single-source.scan"),
      "single scan source",
      SourceKind.Scan,
      OrderGuarantee.Unspecified
    )

  def scan(
      id: String,
      displayName: String,
      order: OrderGuarantee = OrderGuarantee.Unspecified
  ): Either[FrameError, SourceRef] =
    create(id, displayName, SourceKind.Scan, order)

  def values(id: String, displayName: String): Either[FrameError, SourceRef] =
    create(id, displayName, SourceKind.Values, OrderGuarantee.Stable)

  private def create(
      id: String,
      displayName: String,
      kind: SourceKind,
      order: OrderGuarantee
  ): Either[FrameError, SourceRef] =
    if id == null then Left(FrameError.NullSourceId)
    else if displayName == null then Left(FrameError.NullSourceName)
    else if order == null then Left(FrameError.NullSourceOrder)
    else if order match
        case OrderGuarantee.Sorted(keys) => keys == null || keys.exists(_ == null)
        case _                           => false
    then Left(FrameError.InvalidSourceOrder)
    else if id.trim.isEmpty then Left(FrameError.InvalidSourceId(id))
    else if displayName.trim.isEmpty then Left(FrameError.InvalidSourceName(displayName))
    else Right(SourceRef(SourceId.unsafe(id), displayName, kind, order))

final private[frame4s] case class SortExpression(
    expression: ResolvedExpr,
    direction: SortDirection,
    nulls: NullPlacement
)

sealed trait LogicalPlan:
  def output: Schema
  def children: Vector[LogicalPlan]
  def nodeName: String
  def order: OrderGuarantee

object LogicalPlan:
  final private[frame4s] case class Source(
      reference: SourceRef,
      output: Schema
  ) extends LogicalPlan:
    val children = Vector.empty
    val nodeName = reference.kind.toString
    val order = reference.order

  final private[frame4s] case class Project(
      input: LogicalPlan,
      expressions: Vector[NamedExpression],
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(input)
    val nodeName = "Project"
    val order = input.order

  final private[frame4s] case class Filter(
      input: LogicalPlan,
      predicate: ResolvedExpr,
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(input)
    val nodeName = "Filter"
    val order = input.order

  final private[frame4s] case class Join(
      left: LogicalPlan,
      right: LogicalPlan,
      kind: JoinKind,
      condition: ResolvedExpr,
      columns: Vector[JoinColumn],
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(left, right)
    val nodeName = "Join"
    val order = kind match
      case JoinKind.LeftSemi | JoinKind.LeftAnti => left.order
      case JoinKind.Inner | JoinKind.LeftOuter   => OrderGuarantee.Unspecified

  final private[frame4s] case class UnionAll(
      left: LogicalPlan,
      right: LogicalPlan,
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(left, right)
    val nodeName = "UnionAll"
    val order = (left.order, right.order) match
      case (OrderGuarantee.Stable, OrderGuarantee.Stable) => OrderGuarantee.Stable
      case _                                              => OrderGuarantee.Unspecified

  final private[frame4s] case class Aggregate(
      input: LogicalPlan,
      keys: Vector[NamedExpression],
      aggregates: Vector[NamedAggregateExpression],
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(input)
    val nodeName = "Aggregate"
    val order = OrderGuarantee.Unspecified

  final private[frame4s] case class Sort(
      input: LogicalPlan,
      sortExpressions: Vector[SortExpression],
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(input)
    val nodeName = "Sort"
    val order = OrderGuarantee.Sorted(sortExpressions.map(_.expression.id))

  final private[frame4s] case class Limit(
      input: LogicalPlan,
      count: Int,
      output: Schema
  ) extends LogicalPlan:
    val children = Vector(input)
    val nodeName = "Limit"
    val order = input.order

  def explain(plan: LogicalPlan): String =
    final class ExpressionLabels:
      private val byId = mutable.HashMap.empty[ExprId, String]
      private val ordered = mutable.ArrayBuffer.empty[(String, ResolvedExpr)]

      private def register(root: ResolvedExpr): Unit =
        val pending = mutable.ArrayDeque(root)
        while pending.nonEmpty do
          val expression = pending.removeLast()
          if !byId.contains(expression.id) then
            val next = s"e${ordered.size + 1}"
            byId.update(expression.id, next)
            ordered += ((next, expression))
            expression.node match
              case ExprNode.Unary(_, input)        => pending.append(input)
              case ExprNode.Binary(_, left, right) =>
                pending.append(right)
                pending.append(left)
              case ExprNode.Column(_, _, _, _, _) | ExprNode.Literal(_) => ()

      def label(expression: ResolvedExpr): String =
        register(expression)
        byId(expression.id)

      private def literalType(value: LiteralValue): String = value match
        case LiteralValue.Null(dataType)     => s"null($dataType)"
        case LiteralValue.Bool(_)            => DataType.Bool.toString
        case LiteralValue.Int32(_)           => DataType.Int32.toString
        case LiteralValue.Int64(_)           => DataType.Int64.toString
        case LiteralValue.Float32(_)         => DataType.Float32.toString
        case LiteralValue.Float64(_)         => DataType.Float64.toString
        case LiteralValue.Utf8(_)            => DataType.Utf8.toString
        case LiteralValue.Timestamp(_, unit) => DataType.Timestamp(unit).toString

      private def definition(expression: ResolvedExpr): String = expression.node match
        case ExprNode.Column(input, _, _, _, index) =>
          s"column(${input.qualifier},$index)"
        case ExprNode.Literal(value)         => s"literal(${literalType(value)})"
        case ExprNode.Unary(operator, input) =>
          s"$operator(${byId(input.id)})"
        case ExprNode.Binary(operator, left, right) =>
          s"$operator(${byId(left.id)},${byId(right.id)})"

      def appendDefinitions(builder: StringBuilder): Unit =
        if ordered.nonEmpty then
          builder.append("Expressions\n")
          ordered.foreach: (label, expression) =>
            builder.append(s"  $label = ${definition(expression)}\n")

    val labels = new ExpressionLabels

    def loop(current: LogicalPlan, depth: Int, builder: StringBuilder): Unit =
      val indent = "  " * depth
      current match
        case Source(reference, schema) =>
          builder.append(
            s"${indent}${reference.kind}[${reference.displayName}; id=${reference.id.value}] ${schema.toString}\n"
          )
        case Project(input, expressions, schema) =>
          val rendered =
            expressions
              .map(expression => s"${expression.name}=${labels.label(expression.expression)}")
              .mkString(", ")
          builder.append(
            s"${indent}Project[$rendered] ${schema.toString}\n"
          )
          loop(input, depth + 1, builder)
        case Filter(input, predicate, schema) =>
          builder.append(s"${indent}Filter[${labels.label(predicate)}] ${schema.toString}\n")
          loop(input, depth + 1, builder)
        case Join(left, right, kind, condition, _, schema) =>
          builder.append(s"${indent}Join[$kind; ${labels.label(condition)}] ${schema.toString}\n")
          loop(left, depth + 1, builder)
          loop(right, depth + 1, builder)
        case UnionAll(left, right, schema) =>
          builder.append(s"${indent}UnionAll[order=${current.order}] ${schema.toString}\n")
          loop(left, depth + 1, builder)
          loop(right, depth + 1, builder)
        case Aggregate(input, keys, aggregates, schema) =>
          val names = (keys.map(_.name) ++ aggregates.map(_.name)).mkString(", ")
          builder.append(s"${indent}Aggregate[$names] ${schema.toString}\n")
          loop(input, depth + 1, builder)
        case Sort(input, order, schema) =>
          val ids = order
            .map(item => s"${labels.label(item.expression)}:${item.direction}:${item.nulls}")
            .mkString(", ")
          builder.append(s"${indent}Sort[$ids] ${schema.toString}\n")
          loop(input, depth + 1, builder)
        case Limit(input, count, schema) =>
          builder.append(s"${indent}Limit[$count] ${schema.toString}\n")
          loop(input, depth + 1, builder)

    val builder = new StringBuilder
    loop(plan, 0, builder)
    labels.appendDefinitions(builder)
    builder.result().stripSuffix("\n")
