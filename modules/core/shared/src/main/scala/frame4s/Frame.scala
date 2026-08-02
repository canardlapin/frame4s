package frame4s

import scala.NamedTuple

enum BindingIssue:
  case FieldCount(expected: Int, actual: Int)
  case FieldName(index: Int, expected: String, actual: String)
  case FieldType(index: Int, expected: DataType, actual: DataType)
  case FieldNullability(index: Int, expected: Boolean, actual: Boolean)

  def message: String = this match
    case FieldCount(expected, actual)       => s"expected $expected fields but found $actual"
    case FieldName(index, expected, actual) =>
      s"field $index is named '$actual'; expected '$expected'"
    case FieldType(index, expected, actual) =>
      s"field $index has type $actual; expected $expected"
    case FieldNullability(index, expected, actual) =>
      s"field $index nullable=$actual; expected nullable=$expected"

/** A structured query-construction or typed-binding failure.
  *
  * Typed combinators reject invalid column names and schema arithmetic at compile time where the
  * information is static. Dynamic construction and checks involving runtime schemas return this ADT
  * instead of throwing.
  */
enum FrameError:
  case NullSourceId
  case NullSourceName
  case InvalidSourceId(id: String)
  case InvalidSourceName(name: String)
  case InvalidSchema(error: SchemaError)
  case SchemaMismatch(issues: Vector[BindingIssue])
  case ColumnNotFound(name: String)
  case ColumnCollision(name: String)
  case DuplicateOutputNames(names: Vector[String])
  case DuplicateColumnRequests(names: Vector[String])
  case InvalidColumnReference(name: String, index: Int)
  case ExpressionType(expected: DataType, actual: DataType)
  case NullablePredicate(id: ExprId)
  case InvalidExpressionScope(id: ExprId)
  case InvalidLimit(count: Int)
  case NotValuesSource(id: SourceId, kind: SourceKind)
  case EmptySort
  case EmptyJoinKeys
  case DuplicateJoinKey(name: String)
  case JoinKeyType(name: String, left: DataType, right: DataType)

  def message: String = this match
    case NullSourceId            => "source id is null"
    case NullSourceName          => "source name is null"
    case InvalidSourceId(id)     => s"source id '$id' is empty"
    case InvalidSourceName(name) => s"source name '$name' is empty"
    case InvalidSchema(error)    => error.message
    case SchemaMismatch(issues)  => issues.map(_.message).mkString("schema mismatch: ", "; ", "")
    case ColumnNotFound(name)    => s"column '$name' does not exist"
    case ColumnCollision(name)   => s"column '$name' occurs on both join sides"
    case DuplicateOutputNames(names) =>
      names.mkString("output column names are duplicated: ", ", ", "")
    case DuplicateColumnRequests(names) =>
      names.mkString("column requests are duplicated: ", ", ", "")
    case InvalidColumnReference(name, index) =>
      s"column reference '$name' at index $index is not valid in this scope"
    case ExpressionType(expected, actual) =>
      s"expression has type $actual; expected $expected"
    case NullablePredicate(id) =>
      s"predicate ${id.value} is nullable; make it total before filtering"
    case InvalidExpressionScope(id) => s"expression ${id.value} references a different input scope"
    case InvalidLimit(count)        => s"limit must be non-negative, found $count"
    case NotValuesSource(id, kind)  =>
      s"source '${id.value}' has kind $kind; expected a values source"
    case EmptySort                      => "sort requires at least one expression"
    case EmptyJoinKeys                  => "using join requires at least one key"
    case DuplicateJoinKey(name)         => s"using join key '$name' occurs more than once"
    case JoinKeyType(name, left, right) =>
      s"using join key '$name' has incompatible types $left and $right"

private object OutputNameValidation:
  def duplicates(names: Vector[String]): Vector[String] =
    val counts = names.groupMapReduce(identity)(_ => 1)(_ + _)
    names.distinct.filter(name => counts(name) > 1)

private object SchemaCompatibility:
  def issues(expected: Schema, actual: Schema): Vector[BindingIssue] =
    val countIssues =
      if expected.size == actual.size then Vector.empty
      else Vector(BindingIssue.FieldCount(expected.size, actual.size))
    val fieldIssues = expected.fields
      .zip(actual.fields)
      .zipWithIndex
      .flatMap:
        case ((expectedField, actualField), index) =>
          Vector(
            Option.when(expectedField.name != actualField.name):
              BindingIssue.FieldName(index, expectedField.name, actualField.name)
            ,
            Option.when(expectedField.dataType != actualField.dataType):
              BindingIssue.FieldType(index, expectedField.dataType, actualField.dataType)
            ,
            Option.when(expectedField.nullable != actualField.nullable):
              BindingIssue.FieldNullability(index, expectedField.nullable, actualField.nullable)
          ).flatten
    countIssues ++ fieldIssues

private object ExpressionValidation:
  private def validateColumn(
      input: InputRef,
      scope: ExprScopeId,
      id: ColumnId,
      name: String,
      index: Int,
      expectedInput: InputRef,
      expectedScope: ExprScopeId,
      schema: Schema
  ): Either[FrameError, Unit] =
    if input != expectedInput || scope != expectedScope then
      Left(FrameError.InvalidExpressionScope(ExprId.derived(name)))
    else
      schema.fields.lift(index) match
        case Some(field) if field.id == id && field.name == name => Right(())
        case _ => Left(FrameError.InvalidColumnReference(name, index))

  private def loop(
      expression: ResolvedExpr,
      expectedInput: InputRef,
      expectedScope: ExprScopeId,
      schema: Schema
  ): Either[FrameError, Unit] = expression.node match
    case ExprNode.Column(input, scope, id, name, index) =>
      validateColumn(input, scope, id, name, index, expectedInput, expectedScope, schema)
    case ExprNode.Literal(_)             => Right(())
    case ExprNode.Unary(_, input)        => loop(input, expectedInput, expectedScope, schema)
    case ExprNode.Binary(_, left, right) =>
      loop(left, expectedInput, expectedScope, schema)
        .flatMap(_ => loop(right, expectedInput, expectedScope, schema))

  def current(
      expression: ResolvedExpr,
      token: FrameScopeToken,
      schema: Schema
  ): Either[FrameError, Unit] =
    loop(
      expression,
      InputRef.Current,
      ExprScopeId.forInput(token, InputRef.Current),
      schema
    )

  def join(
      expression: ResolvedExpr,
      leftToken: FrameScopeToken,
      left: Schema,
      rightToken: FrameScopeToken,
      right: Schema
  ): Either[FrameError, Unit] =
    def joined(current: ResolvedExpr): Either[FrameError, Unit] = current.node match
      case ExprNode.Column(InputRef.Left, scope, id, name, index) =>
        validateColumn(
          InputRef.Left,
          scope,
          id,
          name,
          index,
          InputRef.Left,
          ExprScopeId.forInput(leftToken, InputRef.Left),
          left
        )
      case ExprNode.Column(InputRef.Right, scope, id, name, index) =>
        validateColumn(
          InputRef.Right,
          scope,
          id,
          name,
          index,
          InputRef.Right,
          ExprScopeId.forInput(rightToken, InputRef.Right),
          right
        )
      case ExprNode.Column(_, _, _, _, _) => Left(FrameError.InvalidExpressionScope(current.id))
      case ExprNode.Literal(_)            => Right(())
      case ExprNode.Unary(_, input)       => joined(input)
      case ExprNode.Binary(_, lhs, rhs)   => joined(lhs).flatMap(_ => joined(rhs))
    joined(expression)

/** A runtime-typed logical expression.
  *
  * Use this only when the schema is not known as a named-tuple type. Operations validate physical
  * types and return [[FrameError]]; successful expressions still carry frame-scope provenance and
  * cannot be smuggled into another plan.
  */
final class DynamicExpr private[frame4s] (private[frame4s] val resolved: ResolvedExpr):
  def id: ExprId = resolved.id
  def dataType: DataType = resolved.dataType
  def nullable: Boolean = resolved.nullable

  def ===(other: DynamicExpr): Either[FrameError, DynamicExpr] =
    if dataType != other.dataType then Left(FrameError.ExpressionType(dataType, other.dataType))
    else
      val combined = ResolvedExpr(
        ExprId.derived(s"Equal(${id.value},${other.id.value})"),
        DataType.Bool,
        nullable || other.nullable,
        ExprNode.Binary(BinaryOperator.Equal, resolved, other.resolved)
      )
      Right(new DynamicExpr(combined))

  def isTrue: Either[FrameError, DynamicExpr] =
    if dataType != DataType.Bool then Left(FrameError.ExpressionType(DataType.Bool, dataType))
    else
      Right:
        new DynamicExpr(
          ResolvedExpr(
            ExprId.derived(s"IsTrue(${id.value})"),
            DataType.Bool,
            nullable = false,
            ExprNode.Unary(UnaryOperator.IsTrue, resolved)
          )
        )

  def sqrt: Either[FrameError, DynamicExpr] =
    dataType match
      case DataType.Float32 | DataType.Float64 =>
        Right:
          new DynamicExpr(
            ResolvedExpr(
              ExprId.derived(s"Sqrt(${id.value})"),
              dataType,
              nullable,
              ExprNode.Unary(UnaryOperator.Sqrt, resolved)
            )
          )
      case other => Left(FrameError.ExpressionType(DataType.Float64, other))

object DynamicExpr:
  def literal(value: LiteralValue): DynamicExpr =
    val (dataType, nullable) = value match
      case LiteralValue.Null(dataType)     => (dataType, true)
      case LiteralValue.Bool(_)            => (DataType.Bool, false)
      case LiteralValue.Int32(_)           => (DataType.Int32, false)
      case LiteralValue.Int64(_)           => (DataType.Int64, false)
      case LiteralValue.Float32(_)         => (DataType.Float32, false)
      case LiteralValue.Float64(_)         => (DataType.Float64, false)
      case LiteralValue.Utf8(_)            => (DataType.Utf8, false)
      case LiteralValue.Timestamp(_, unit) => (DataType.Timestamp(unit), false)
    new DynamicExpr(
      ResolvedExpr(
        ExprId.derived(s"literal:$value"),
        dataType,
        nullable,
        ExprNode.Literal(value)
      )
    )

/** A runtime-typed aggregate expression constructed through [[DynamicAggregate]]. */
final class DynamicAggregate private[frame4s] (
    private[frame4s] val resolved: ResolvedAggregate
)

object DynamicAggregate:
  val count: DynamicAggregate =
    new DynamicAggregate(
      ResolvedAggregate(DataType.Int64, nullable = false, AggregateNode.Count)
    )

  def sum(expression: DynamicExpr): Either[FrameError, DynamicAggregate] =
    numeric(expression).map: _ =>
      new DynamicAggregate(
        ResolvedAggregate(
          expression.dataType,
          expression.nullable,
          AggregateNode.Sum(expression.resolved)
        )
      )

  def mean(expression: DynamicExpr): Either[FrameError, DynamicAggregate] =
    numeric(expression).map: _ =>
      new DynamicAggregate(
        ResolvedAggregate(
          DataType.Float64,
          expression.nullable,
          AggregateNode.Mean(expression.resolved)
        )
      )

  def variancePop(expression: DynamicExpr): Either[FrameError, DynamicAggregate] =
    numeric(expression).map: _ =>
      new DynamicAggregate(
        ResolvedAggregate(
          DataType.Float64,
          expression.nullable,
          AggregateNode.VariancePop(expression.resolved)
        )
      )

  def stddevPop(expression: DynamicExpr): Either[FrameError, DynamicAggregate] =
    numeric(expression).map: _ =>
      new DynamicAggregate(
        ResolvedAggregate(
          DataType.Float64,
          expression.nullable,
          AggregateNode.StddevPop(expression.resolved)
        )
      )

  def min(expression: DynamicExpr): DynamicAggregate =
    new DynamicAggregate(
      ResolvedAggregate(
        expression.dataType,
        expression.nullable,
        AggregateNode.Min(expression.resolved)
      )
    )

  def max(expression: DynamicExpr): DynamicAggregate =
    new DynamicAggregate(
      ResolvedAggregate(
        expression.dataType,
        expression.nullable,
        AggregateNode.Max(expression.resolved)
      )
    )

  private def numeric(expression: DynamicExpr): Either[FrameError, Unit] =
    expression.dataType match
      case DataType.Int32 | DataType.Int64 | DataType.Float32 | DataType.Float64 =>
        Right(())
      case other => Left(FrameError.ExpressionType(DataType.Float64, other))

final class DynamicScope private[frame4s] (
    schema: Schema,
    input: InputRef,
    scopeId: ExprScopeId
):
  def col(name: String): Either[FrameError, DynamicExpr] =
    val index = schema.fields.indexWhere(_.name == name)
    if index < 0 then Left(FrameError.ColumnNotFound(name))
    else
      val field = schema.fields(index)
      Right:
        new DynamicExpr(
          ResolvedExpr(
            ExprId.derived(s"column:${input.qualifier}:${field.id.value}"),
            field.dataType,
            field.nullable,
            ExprNode.Column(input, scopeId, field.id, field.name, index)
          )
        )

/** An immutable logical query with a runtime [[Schema]].
  *
  * Dynamic operations return structured errors for missing columns, incompatible types, and output
  * collisions. Call `typed[S]` only when the inspected schema should exactly match the named-tuple
  * schema `S`; the check includes field order and nullability.
  *
  * A `DynamicFrame` performs no I/O. Execute it only after an exact typed binding or through a
  * backend API that explicitly accepts dynamic plans.
  */
final class DynamicFrame private[frame4s] (
    val plan: LogicalPlan,
    val schema: Schema,
    private[frame4s] val scopeToken: FrameScopeToken = FrameScopeToken.fresh()
):
  def col(name: String): Either[FrameError, DynamicExpr] =
    new DynamicScope(
      schema,
      InputRef.Current,
      ExprScopeId.forInput(scopeToken, InputRef.Current)
    ).col(name)

  private def currentExpressions: Vector[NamedExpression] =
    val scopeId = ExprScopeId.forInput(scopeToken, InputRef.Current)
    schema.fields.zipWithIndex.map: (field, index) =>
      NamedExpression(
        field.name,
        ResolvedExpr(
          ExprId.derived(s"column:current:${field.id.value}"),
          field.dataType,
          field.nullable,
          ExprNode.Column(InputRef.Current, scopeId, field.id, field.name, index)
        )
      )

  def rename(from: String, to: String): Either[FrameError, DynamicFrame] =
    val index = schema.fields.indexWhere(_.name == from)
    if index < 0 then Left(FrameError.ColumnNotFound(from))
    else if schema.field(to).nonEmpty then Left(FrameError.ColumnCollision(to))
    else
      val expressions = currentExpressions.updated(
        index,
        currentExpressions(index).copy(name = to)
      )
      Schema(
        schema.fields.updated(
          index,
          schema.fields(index).copy(id = ColumnId.derived(to), name = to)
        )
      ).left
        .map(FrameError.InvalidSchema.apply)
        .map: output =>
          new DynamicFrame(LogicalPlan.Project(plan, expressions, output), output)

  def renameAll(
      first: (String, String),
      rest: (String, String)*
  ): Either[FrameError, DynamicFrame] =
    val requests = (first +: rest).toVector
    val duplicateSources = OutputNameValidation.duplicates(requests.map(_._1))
    if duplicateSources.nonEmpty then Left(FrameError.DuplicateColumnRequests(duplicateSources))
    else
      requests.collectFirst {
        case (from, _) if schema.field(from).isEmpty => FrameError.ColumnNotFound(from)
      } match
        case Some(error) => Left(error)
        case None        =>
          val renames = requests.toMap
          val outputNames =
            schema.fields.map(field => renames.getOrElse(field.name, field.name))
          val duplicates = OutputNameValidation.duplicates(outputNames)
          if duplicates.nonEmpty then Left(FrameError.DuplicateOutputNames(duplicates))
          else
            val expressions = currentExpressions.map: expression =>
              expression.copy(name = renames.getOrElse(expression.name, expression.name))
            val fields = schema.fields.map: field =>
              val name = renames.getOrElse(field.name, field.name)
              field.copy(id = ColumnId.derived(name), name = name)
            Schema(fields).left
              .map(FrameError.InvalidSchema.apply)
              .map(output =>
                new DynamicFrame(LogicalPlan.Project(plan, expressions, output), output)
              )

  def drop(name: String): Either[FrameError, DynamicFrame] =
    val index = schema.fields.indexWhere(_.name == name)
    if index < 0 then Left(FrameError.ColumnNotFound(name))
    else
      Schema(schema.fields.patch(index, Nil, 1)).left
        .map(FrameError.InvalidSchema.apply)
        .map: output =>
          new DynamicFrame(
            LogicalPlan.Project(
              plan,
              currentExpressions.patch(index, Nil, 1),
              output
            ),
            output
          )

  def dropAll(first: String, rest: String*): Either[FrameError, DynamicFrame] =
    val requested = (first +: rest).toVector
    val duplicates = OutputNameValidation.duplicates(requested)
    if duplicates.nonEmpty then Left(FrameError.DuplicateColumnRequests(duplicates))
    else
      requested.find(name => schema.field(name).isEmpty) match
        case Some(name) => Left(FrameError.ColumnNotFound(name))
        case None       =>
          val dropped = requested.toSet
          val expressions =
            currentExpressions.filterNot(expression => dropped(expression.name))
          val fields = schema.fields.filterNot(field => dropped(field.name))
          Schema(fields).left
            .map(FrameError.InvalidSchema.apply)
            .map(output => new DynamicFrame(LogicalPlan.Project(plan, expressions, output), output))

  def replace(
      name: String,
      expression: DynamicExpr
  ): Either[FrameError, DynamicFrame] =
    val index = schema.fields.indexWhere(_.name == name)
    if index < 0 then Left(FrameError.ColumnNotFound(name))
    else
      ExpressionValidation
        .current(expression.resolved, scopeToken, schema)
        .flatMap: _ =>
          Schema(
            schema.fields.updated(
              index,
              Field(
                ColumnId.derived(name),
                name,
                expression.dataType,
                expression.nullable
              )
            )
          ).left
            .map(FrameError.InvalidSchema.apply)
            .map: output =>
              new DynamicFrame(
                LogicalPlan.Project(
                  plan,
                  currentExpressions.updated(
                    index,
                    NamedExpression(name, expression.resolved)
                  ),
                  output
                ),
                output
              )

  def replaceAll(
      first: (String, DynamicExpr),
      rest: (String, DynamicExpr)*
  ): Either[FrameError, DynamicFrame] =
    val requested = (first +: rest).toVector
    val duplicates = OutputNameValidation.duplicates(requested.map(_._1))
    if duplicates.nonEmpty then Left(FrameError.DuplicateColumnRequests(duplicates))
    else
      requested.find((name, _) => schema.field(name).isEmpty) match
        case Some((name, _)) => Left(FrameError.ColumnNotFound(name))
        case None            =>
          requested
            .foldLeft[Either[FrameError, Unit]](Right(())):
              case (validated, (_, expression)) =>
                validated.flatMap(_ =>
                  ExpressionValidation.current(expression.resolved, scopeToken, schema)
                )
            .flatMap: _ =>
              val replacements = requested.toMap
              val expressions = currentExpressions.map: expression =>
                replacements
                  .get(expression.name)
                  .fold(expression)(replacement =>
                    NamedExpression(expression.name, replacement.resolved)
                  )
              val fields = schema.fields.map: field =>
                replacements.get(field.name) match
                  case None              => field
                  case Some(replacement) =>
                    field.copy(
                      dataType = replacement.dataType,
                      nullable = replacement.nullable
                    )
              Schema(fields).left
                .map(FrameError.InvalidSchema.apply)
                .map(output =>
                  new DynamicFrame(LogicalPlan.Project(plan, expressions, output), output)
                )

  def distinct: DynamicFrame =
    new DynamicFrame(
      LogicalPlan.Aggregate(
        plan,
        currentExpressions,
        Vector.empty,
        schema
      ),
      schema
    )

  def unionAll(right: DynamicFrame): Either[FrameError, DynamicFrame] =
    SchemaCompatibility.issues(schema, right.schema) match
      case issues if issues.nonEmpty => Left(FrameError.SchemaMismatch(issues))
      case _                         =>
        Right(
          new DynamicFrame(
            LogicalPlan.UnionAll(plan, right.plan, schema),
            schema
          )
        )

  def filter(predicate: DynamicExpr): Either[FrameError, DynamicFrame] =
    if predicate.dataType != DataType.Bool then
      Left(FrameError.ExpressionType(DataType.Bool, predicate.dataType))
    else if predicate.nullable then Left(FrameError.NullablePredicate(predicate.id))
    else
      ExpressionValidation
        .current(predicate.resolved, scopeToken, schema)
        .map: _ =>
          new DynamicFrame(LogicalPlan.Filter(plan, predicate.resolved, schema), schema)

  def select(expressions: (String, DynamicExpr)*): Either[FrameError, DynamicFrame] =
    val names = expressions.map(_._1).toVector
    OutputNameValidation.duplicates(names) match
      case duplicates if duplicates.nonEmpty => Left(FrameError.DuplicateOutputNames(duplicates))
      case _                                 =>
        val validated = expressions.foldLeft[Either[FrameError, Unit]](Right(())):
          case (result, (_, expression)) =>
            result.flatMap: _ =>
              ExpressionValidation.current(expression.resolved, scopeToken, schema)
        validated.flatMap: _ =>
          Schema(
            expressions.toVector.map: (name, expression) =>
              Field(ColumnId.derived(name), name, expression.dataType, expression.nullable)
          ).left
            .map(FrameError.InvalidSchema.apply)
            .map: output =>
              val selected = expressions.toVector
                .map((name, expression) => NamedExpression(name, expression.resolved))
              new DynamicFrame(LogicalPlan.Project(plan, selected, output), output)

  def withColumn(name: String, expression: DynamicExpr): Either[FrameError, DynamicFrame] =
    if schema.field(name).nonEmpty then Left(FrameError.ColumnCollision(name))
    else
      ExpressionValidation
        .current(expression.resolved, scopeToken, schema)
        .flatMap: _ =>
          val scopeId = ExprScopeId.forInput(scopeToken, InputRef.Current)
          val retained = schema.fields.zipWithIndex.map: (field, index) =>
            NamedExpression(
              field.name,
              ResolvedExpr(
                ExprId.derived(s"column:current:${field.id.value}"),
                field.dataType,
                field.nullable,
                ExprNode.Column(InputRef.Current, scopeId, field.id, field.name, index)
              )
            )
          Schema(
            schema.fields :+ Field(
              ColumnId.derived(name),
              name,
              expression.dataType,
              expression.nullable
            )
          ).left
            .map(FrameError.InvalidSchema.apply)
            .map: output =>
              new DynamicFrame(
                LogicalPlan.Project(
                  plan,
                  retained :+ NamedExpression(name, expression.resolved),
                  output
                ),
                output
              )

  def groupBy(expressions: (String, DynamicExpr)*): Either[FrameError, DynamicGroupedFrame] =
    val names = expressions.map(_._1).toVector
    OutputNameValidation.duplicates(names) match
      case duplicates if duplicates.nonEmpty => Left(FrameError.DuplicateOutputNames(duplicates))
      case _                                 =>
        val validated = expressions.foldLeft[Either[FrameError, Unit]](Right(())):
          case (result, (_, expression)) =>
            result.flatMap: _ =>
              ExpressionValidation.current(expression.resolved, scopeToken, schema)
        validated.map: _ =>
          new DynamicGroupedFrame(
            this,
            expressions.toVector.map: (name, expression) =>
              NamedExpression(name, expression.resolved)
          )

  def innerJoin(right: DynamicFrame)(
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    join(right, JoinKind.Inner, condition)

  def leftJoin(right: DynamicFrame)(
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    join(right, JoinKind.LeftOuter, condition)

  def rightJoin(right: DynamicFrame)(
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    JoinPlanning.dynamicRight(this, right, condition)

  def semiJoin(right: DynamicFrame)(
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    existenceJoin(right, JoinKind.LeftSemi, condition)

  def antiJoin(right: DynamicFrame)(
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    existenceJoin(right, JoinKind.LeftAnti, condition)

  def innerJoinUsing(
      right: DynamicFrame,
      first: String,
      rest: String*
  ): Either[FrameError, DynamicFrame] =
    JoinPlanning.using(this, right, JoinKind.Inner, (first +: rest).toVector)

  def leftJoinUsing(
      right: DynamicFrame,
      first: String,
      rest: String*
  ): Either[FrameError, DynamicFrame] =
    JoinPlanning.using(this, right, JoinKind.LeftOuter, (first +: rest).toVector)

  private def join(
      right: DynamicFrame,
      kind: JoinKind,
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    schema.fields.map(_.name).find(name => right.schema.field(name).nonEmpty) match
      case Some(name) => Left(FrameError.ColumnCollision(name))
      case None       =>
        condition(
          new DynamicScope(
            schema,
            InputRef.Left,
            ExprScopeId.forInput(scopeToken, InputRef.Left)
          ),
          new DynamicScope(
            right.schema,
            InputRef.Right,
            ExprScopeId.forInput(right.scopeToken, InputRef.Right)
          )
        ).flatMap: expression =>
          if expression.dataType != DataType.Bool then
            Left(FrameError.ExpressionType(DataType.Bool, expression.dataType))
          else if expression.nullable then Left(FrameError.NullablePredicate(expression.id))
          else
            ExpressionValidation
              .join(
                expression.resolved,
                scopeToken,
                schema,
                right.scopeToken,
                right.schema
              )
              .flatMap: _ =>
                val rightFields = right.schema.fields.map: field =>
                  if kind == JoinKind.LeftOuter then field.copy(nullable = true) else field
                Schema(schema.fields ++ rightFields).left
                  .map(FrameError.InvalidSchema.apply)
                  .map: output =>
                    new DynamicFrame(
                      LogicalPlan.Join(
                        plan,
                        right.plan,
                        kind,
                        expression.resolved,
                        JoinColumn.all(schema.size, right.schema.size),
                        output
                      ),
                      output
                    )

  private def existenceJoin(
      right: DynamicFrame,
      kind: JoinKind,
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    condition(
      new DynamicScope(
        schema,
        InputRef.Left,
        ExprScopeId.forInput(scopeToken, InputRef.Left)
      ),
      new DynamicScope(
        right.schema,
        InputRef.Right,
        ExprScopeId.forInput(right.scopeToken, InputRef.Right)
      )
    ).flatMap: expression =>
      if expression.dataType != DataType.Bool then
        Left(FrameError.ExpressionType(DataType.Bool, expression.dataType))
      else if expression.nullable then Left(FrameError.NullablePredicate(expression.id))
      else
        ExpressionValidation
          .join(
            expression.resolved,
            scopeToken,
            schema,
            right.scopeToken,
            right.schema
          )
          .map: _ =>
            new DynamicFrame(
              LogicalPlan.Join(
                plan,
                right.plan,
                kind,
                expression.resolved,
                Vector.tabulate(schema.size)(JoinColumn.Left.apply),
                schema
              ),
              schema
            )

  def sortBy(
      expression: DynamicExpr,
      direction: SortDirection = SortDirection.Ascending,
      nulls: NullPlacement = NullPlacement.Last
  ): Either[FrameError, DynamicFrame] =
    ExpressionValidation
      .current(expression.resolved, scopeToken, schema)
      .map: _ =>
        new DynamicFrame(
          LogicalPlan.Sort(
            plan,
            Vector(SortExpression(expression.resolved, direction, nulls)),
            schema
          ),
          schema
        )

  def limit(count: Int): Either[FrameError, DynamicFrame] =
    if count < 0 then Left(FrameError.InvalidLimit(count))
    else Right(new DynamicFrame(LogicalPlan.Limit(plan, count, schema), schema))

  def typed[S <: NamedTuple.AnyNamedTuple](using
      descriptor: SchemaDescriptor[S]
  ): Either[FrameError, Frame[S]] =
    val expected = descriptor.schema
    val issues = SchemaCompatibility.issues(expected, schema)
    if issues.isEmpty then Right(new Frame(plan, schema, descriptor, scopeToken))
    else Left(FrameError.SchemaMismatch(issues))

  def explain: String = LogicalPlan.explain(plan)

final class DynamicGroupedFrame private[frame4s] (
    input: DynamicFrame,
    keys: Vector[NamedExpression]
):
  def aggregate(
      expressions: (String, DynamicAggregate)*
  ): Either[FrameError, DynamicFrame] =
    val names = (keys.map(_.name) ++ expressions.map(_._1)).toVector
    OutputNameValidation.duplicates(names) match
      case duplicates if duplicates.nonEmpty =>
        Left(FrameError.DuplicateOutputNames(duplicates))
      case _ =>
        val fields =
          keys.map: key =>
            Field(
              ColumnId.derived(key.name),
              key.name,
              key.expression.dataType,
              key.expression.nullable
            )
          ++ expressions.toVector.map: (name, aggregate) =>
            Field(
              ColumnId.derived(name),
              name,
              aggregate.resolved.dataType,
              aggregate.resolved.nullable
            )
        Schema(fields).left
          .map(FrameError.InvalidSchema.apply)
          .map: output =>
            new DynamicFrame(
              LogicalPlan.Aggregate(
                input.plan,
                keys,
                expressions.toVector.map: (name, aggregate) =>
                  NamedAggregateExpression(name, aggregate.resolved),
                output
              ),
              output
            )

object DynamicFrame:
  def scan(reference: SourceRef, fields: Vector[Field]): Either[FrameError, DynamicFrame] =
    Schema(fields).left
      .map(FrameError.InvalidSchema.apply)
      .map: schema =>
        new DynamicFrame(LogicalPlan.Source(reference, schema), schema)

  def source(name: String, fields: Vector[Field]): Either[FrameError, DynamicFrame] =
    SourceRef.scan(name, name).flatMap(scan(_, fields))

  def field(name: String, dataType: DataType, nullable: Boolean = false): Field =
    Field(ColumnId.derived(name), name, dataType, nullable)

/** An immutable logical dataframe query whose schema is the named-tuple type `S`.
  *
  * Build column expressions inside each operation's callback. The callback gives them a hidden
  * frame origin, so the compiler rejects expressions captured from another frame even when both
  * frames have the same schema. Projection, joins, grouping, rename, drop, and replacement compute
  * their output schemas in the type system.
  *
  * Constructing or composing a `Frame` performs no I/O and allocates no materialized table. Execute
  * it through `frame4s.fs2.FrameRuntime` with explicit source bindings. `explain` inspects the
  * logical plan without acquiring a source.
  *
  * `Option[A]` is the only nullable column representation. Filters require a non-nullable Boolean;
  * use `isTrue` to collapse a nullable predicate according to the documented SQL semantics.
  *
  * @tparam S
  *   ordered named-tuple schema, for example `(id: Int, label: String, score: Option[Double])`
  */
final class Frame[S <: NamedTuple.AnyNamedTuple] private[frame4s] (
    val plan: LogicalPlan,
    val schema: Schema,
    private[frame4s] val descriptor: SchemaDescriptor[S],
    private[frame4s] val scopeToken: FrameScopeToken = FrameScopeToken.fresh()
):
  type Origin

  private def currentExpressions: Vector[NamedExpression] =
    val scopeId = ExprScopeId.forInput(scopeToken, InputRef.Current)
    schema.fields.zipWithIndex.map: (field, index) =>
      NamedExpression(
        field.name,
        ResolvedExpr(
          ExprId.derived(s"column:current:${field.id.value}"),
          field.dataType,
          field.nullable,
          ExprNode.Column(InputRef.Current, scopeId, field.id, field.name, index)
        )
      )

  def select[
      Expressions <: Tuple,
      Names <: Tuple,
      Values <: Tuple
  ](
      expressions: Scope[S, this.Origin] => Expressions
  )(using
      selection: ExpressionSelection[Expressions, Names, Values],
      inScope: ExpressionsInScope[Expressions, this.Origin],
      unique: UniqueNames[Names],
      output: SchemaDescriptor[NamedTuple.NamedTuple[Names, Values]]
  ): Frame[NamedTuple.NamedTuple[Names, Values]] =
    val selected =
      selection.expressions(
        expressions(Scope.current[S, this.Origin](scopeToken, schema))
      )
    new Frame[NamedTuple.NamedTuple[Names, Values]](
      LogicalPlan.Project(plan, selected, output.schema),
      output.schema,
      output
    )

  def withColumn[Name <: String & Singleton, Value, ExprOrigin](
      name: Name
  )(
      expression: Scope[S, this.Origin] => ExprOf[Value, ExprOrigin]
  )(using
      absent: ColumnAbsent[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      inScope: OriginInScope[ExprOrigin, this.Origin],
      output: SchemaDescriptor[AppendedSchema[S, Name, Value]]
  ): Frame[AppendedSchema[S, Name, Value]] =
    val scope = Scope.current[S, this.Origin](scopeToken, schema)
    val scopeId = ExprScopeId.forInput(scopeToken, InputRef.Current)
    val retained = schema.fields.zipWithIndex.map: (field, index) =>
      NamedExpression(
        field.name,
        ResolvedExpr(
          ExprId.derived(s"column:current:${field.id.value}"),
          field.dataType,
          field.nullable,
          ExprNode.Column(InputRef.Current, scopeId, field.id, field.name, index)
        )
      )
    val appended = retained :+ NamedExpression(name, expression(scope).resolved)
    new Frame[AppendedSchema[S, Name, Value]](
      LogicalPlan.Project(plan, appended, output.schema),
      output.schema,
      output
    )

  def rename[
      From <: String & Singleton,
      To <: String & Singleton
  ](
      from: From,
      to: To
  )(using
      at: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], From],
      absent: ColumnAbsent[NamedTuple.Names[S], NamedTuple.DropNames[S], To],
      output: SchemaDescriptor[RenamedSchema[S, From, To]]
  ): Frame[RenamedSchema[S, From, To]] =
    val expressions = currentExpressions.updated(
      at.index,
      currentExpressions(at.index).copy(name = to)
    )
    new Frame(
      LogicalPlan.Project(plan, expressions, output.schema),
      output.schema,
      output
    )

  def renameAll[
      From1 <: String & Singleton,
      To1 <: String & Singleton,
      From2 <: String & Singleton,
      To2 <: String & Singleton
  ](
      first: (From1, To1),
      second: (From2, To2)
  )(using
      firstAt: ColumnLookup[
        NamedTuple.Names[S],
        NamedTuple.DropNames[S],
        From1
      ],
      secondAt: ColumnLookup[
        NamedTuple.Names[S],
        NamedTuple.DropNames[S],
        From2
      ],
      uniqueRequests: UniqueNames[From1 *: From2 *: EmptyTuple],
      uniqueOutput: UniqueNames[
        RenameTwoNames[NamedTuple.Names[S], From1, To1, From2, To2]
      ],
      output: SchemaDescriptor[
        RenamedTwoSchema[S, From1, To1, From2, To2]
      ]
  ): Frame[RenamedTwoSchema[S, From1, To1, From2, To2]] =
    val renames = Map(first, second)
    val expressions = currentExpressions.map: expression =>
      expression.copy(name = renames.getOrElse(expression.name, expression.name))
    new Frame(
      LogicalPlan.Project(plan, expressions, output.schema),
      output.schema,
      output
    )

  def drop[Name <: String & Singleton](
      name: Name
  )(using
      at: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      output: SchemaDescriptor[DroppedSchema[S, Name]]
  ): Frame[DroppedSchema[S, Name]] =
    new Frame(
      LogicalPlan.Project(
        plan,
        currentExpressions.patch(at.index, Nil, 1),
        output.schema
      ),
      output.schema,
      output
    )

  def dropAll[
      Name1 <: String & Singleton,
      Name2 <: String & Singleton
  ](
      first: Name1,
      second: Name2
  )(using
      firstAt: ColumnLookup[
        NamedTuple.Names[S],
        NamedTuple.DropNames[S],
        Name1
      ],
      secondAt: ColumnLookup[
        NamedTuple.Names[S],
        NamedTuple.DropNames[S],
        Name2
      ],
      unique: UniqueNames[Name1 *: Name2 *: EmptyTuple],
      output: SchemaDescriptor[
        DropManySchema[S, Name1 *: Name2 *: EmptyTuple]
      ]
  ): Frame[DropManySchema[S, Name1 *: Name2 *: EmptyTuple]] =
    val dropped = Set(first, second)
    new Frame(
      LogicalPlan.Project(
        plan,
        currentExpressions.filterNot(expression => dropped(expression.name)),
        output.schema
      ),
      output.schema,
      output
    )

  def replace[
      Name <: String & Singleton,
      Value,
      ExprOrigin
  ](
      name: Name
  )(
      expression: Scope[S, this.Origin] => ExprOf[Value, ExprOrigin]
  )(using
      at: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      inScope: OriginInScope[ExprOrigin, this.Origin],
      output: SchemaDescriptor[ReplacedSchema[S, Name, Value]]
  ): Frame[ReplacedSchema[S, Name, Value]] =
    val replacement =
      expression(Scope.current[S, this.Origin](scopeToken, schema))
    val expressions = currentExpressions.updated(
      at.index,
      NamedExpression(name, replacement.resolved)
    )
    new Frame(
      LogicalPlan.Project(plan, expressions, output.schema),
      output.schema,
      output
    )

  def distinct: Frame[S] =
    new Frame(
      LogicalPlan.Aggregate(plan, currentExpressions, Vector.empty, schema),
      schema,
      descriptor
    )

  def unionAll(right: Frame[S]): Frame[S] =
    new Frame(
      LogicalPlan.UnionAll(plan, right.plan, schema),
      schema,
      descriptor
    )

  def filter[ExprOrigin](
      predicate: Scope[S, this.Origin] => ExprOf[Boolean, ExprOrigin]
  )(using inScope: OriginInScope[ExprOrigin, this.Origin]): Frame[S] =
    val resolved =
      predicate(Scope.current[S, this.Origin](scopeToken, schema)).resolved
    new Frame(LogicalPlan.Filter(plan, resolved, schema), schema, descriptor)

  def innerJoin[Right <: NamedTuple.AnyNamedTuple, ExprOrigin](right: Frame[Right])(
      condition: (
          Scope[S, this.Origin],
          Scope[Right, right.Origin]
      ) => ExprOf[Boolean, ExprOrigin]
  )(using
      disjoint: DisjointNames[
        NamedTuple.Names[S],
        NamedTuple.Names[Right],
        NamedTuple.DropNames[Right]
      ],
      inScope: OriginInScope[ExprOrigin, this.Origin | right.Origin],
      output: SchemaDescriptor[ConcatSchema[S, Right]]
  ): Frame[ConcatSchema[S, Right]] =
    val on = condition(
      Scope.left[S, this.Origin](scopeToken, schema),
      Scope.right[Right, right.Origin](right.scopeToken, right.schema)
    )
    new Frame[ConcatSchema[S, Right]](
      LogicalPlan.Join(
        plan,
        right.plan,
        JoinKind.Inner,
        on.resolved,
        JoinColumn.all(schema.size, right.schema.size),
        output.schema
      ),
      output.schema,
      output
    )

  def leftJoin[Right <: NamedTuple.AnyNamedTuple, ExprOrigin](right: Frame[Right])(
      condition: (
          Scope[S, this.Origin],
          Scope[Right, right.Origin]
      ) => ExprOf[Boolean, ExprOrigin]
  )(using
      disjoint: DisjointNames[
        NamedTuple.Names[S],
        NamedTuple.Names[Right],
        NamedTuple.DropNames[Right]
      ],
      inScope: OriginInScope[ExprOrigin, this.Origin | right.Origin],
      output: SchemaDescriptor[LeftJoinSchema[S, Right]]
  ): Frame[LeftJoinSchema[S, Right]] =
    val on = condition(
      Scope.left[S, this.Origin](scopeToken, schema),
      Scope.right[Right, right.Origin](right.scopeToken, right.schema)
    )
    new Frame[LeftJoinSchema[S, Right]](
      LogicalPlan.Join(
        plan,
        right.plan,
        JoinKind.LeftOuter,
        on.resolved,
        JoinColumn.all(schema.size, right.schema.size),
        output.schema
      ),
      output.schema,
      output
    )

  def rightJoin[
      Right <: NamedTuple.AnyNamedTuple,
      ExprOrigin
  ](right: Frame[Right])(
      condition: (
          Scope[S, this.Origin],
          Scope[Right, right.Origin]
      ) => ExprOf[Boolean, ExprOrigin]
  )(using
      disjoint: DisjointNames[
        NamedTuple.Names[S],
        NamedTuple.Names[Right],
        NamedTuple.DropNames[Right]
      ],
      inScope: OriginInScope[ExprOrigin, this.Origin | right.Origin],
      swapped: SchemaDescriptor[LeftJoinSchema[Right, S]],
      output: SchemaDescriptor[RightJoinSchema[S, Right]]
  ): Frame[RightJoinSchema[S, Right]] =
    val on = condition(
      Scope.right[S, this.Origin](scopeToken, schema),
      Scope.left[Right, right.Origin](right.scopeToken, right.schema)
    )
    val joined = LogicalPlan.Join(
      right.plan,
      plan,
      JoinKind.LeftOuter,
      on.resolved,
      JoinColumn.all(right.schema.size, schema.size),
      swapped.schema
    )
    val projected = JoinPlanning.rightProjection(
      swapped.schema,
      output.schema,
      right.schema.size,
      schema.size
    )
    new Frame(
      LogicalPlan.Project(joined, projected, output.schema),
      output.schema,
      output
    )

  def semiJoin[
      Right <: NamedTuple.AnyNamedTuple,
      ExprOrigin
  ](right: Frame[Right])(
      condition: (
          Scope[S, this.Origin],
          Scope[Right, right.Origin]
      ) => ExprOf[Boolean, ExprOrigin]
  )(using
      inScope: OriginInScope[ExprOrigin, this.Origin | right.Origin]
  ): Frame[S] =
    val on = condition(
      Scope.left[S, this.Origin](scopeToken, schema),
      Scope.right[Right, right.Origin](right.scopeToken, right.schema)
    )
    new Frame(
      LogicalPlan.Join(
        plan,
        right.plan,
        JoinKind.LeftSemi,
        on.resolved,
        Vector.tabulate(schema.size)(JoinColumn.Left.apply),
        schema
      ),
      schema,
      descriptor
    )

  def antiJoin[
      Right <: NamedTuple.AnyNamedTuple,
      ExprOrigin
  ](right: Frame[Right])(
      condition: (
          Scope[S, this.Origin],
          Scope[Right, right.Origin]
      ) => ExprOf[Boolean, ExprOrigin]
  )(using
      inScope: OriginInScope[ExprOrigin, this.Origin | right.Origin]
  ): Frame[S] =
    val on = condition(
      Scope.left[S, this.Origin](scopeToken, schema),
      Scope.right[Right, right.Origin](right.scopeToken, right.schema)
    )
    new Frame(
      LogicalPlan.Join(
        plan,
        right.plan,
        JoinKind.LeftAnti,
        on.resolved,
        Vector.tabulate(schema.size)(JoinColumn.Left.apply),
        schema
      ),
      schema,
      descriptor
    )

  def innerJoinUsing[
      Right <: NamedTuple.AnyNamedTuple,
      Name <: String & Singleton
  ](right: Frame[Right], name: Name)(using
      leftAt: ColumnAt[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      rightAt: ColumnAt[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name],
      same: SchemaFieldType[S, Name] =:= SchemaFieldType[Right, Name],
      disjoint: DisjointNames[
        NamedTuple.Names[S],
        RemoveFieldNames[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name],
        RemoveFieldValues[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name]
      ],
      output: SchemaDescriptor[UsingJoinSchema[S, Right, Name]]
  ): Frame[UsingJoinSchema[S, Right, Name]] =
    val dynamic = JoinPlanning.typedUsing(
      this.dynamic,
      right.dynamic,
      JoinKind.Inner,
      name,
      leftAt.index,
      rightAt.index,
      output.schema
    )
    new Frame(dynamic.plan, output.schema, output)

  def leftJoinUsing[
      Right <: NamedTuple.AnyNamedTuple,
      Name <: String & Singleton
  ](right: Frame[Right], name: Name)(using
      leftAt: ColumnAt[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      rightAt: ColumnAt[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name],
      same: SchemaFieldType[S, Name] =:= SchemaFieldType[Right, Name],
      disjoint: DisjointNames[
        NamedTuple.Names[S],
        RemoveFieldNames[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name],
        RemoveFieldValues[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name]
      ],
      output: SchemaDescriptor[LeftUsingJoinSchema[S, Right, Name]]
  ): Frame[LeftUsingJoinSchema[S, Right, Name]] =
    val dynamic = JoinPlanning.typedUsing(
      this.dynamic,
      right.dynamic,
      JoinKind.LeftOuter,
      name,
      leftAt.index,
      rightAt.index,
      output.schema
    )
    new Frame(dynamic.plan, output.schema, output)

  def groupBy[
      Keys <: Tuple,
      KeyNames <: Tuple,
      KeyValues <: Tuple
  ](
      keys: Scope[S, this.Origin] => Keys
  )(using
      selection: ExpressionSelection[Keys, KeyNames, KeyValues],
      inScope: ExpressionsInScope[Keys, this.Origin],
      unique: UniqueNames[KeyNames]
  ): GroupedFrame[S, Keys, this.Origin] =
    val selected = keys(Scope.current[S, this.Origin](scopeToken, schema))
    new GroupedFrame(this, selection.expressions(selected))

  def sortBy(
      first: Scope[S, this.Origin] => ExprOf[?, this.Origin],
      rest: (Scope[S, this.Origin] => ExprOf[?, this.Origin])*
  ): Frame[S] =
    sortBy(SortDirection.Ascending, NullPlacement.Last)(first, rest*)

  def sortBy(
      direction: SortDirection,
      nulls: NullPlacement
  )(
      first: Scope[S, this.Origin] => ExprOf[?, this.Origin],
      rest: (Scope[S, this.Origin] => ExprOf[?, this.Origin])*
  ): Frame[S] =
    val scope = Scope.current[S, this.Origin](scopeToken, schema)
    val expressions = (first +: rest)
      .map(build => SortExpression(build(scope).resolved, direction, nulls))
      .toVector
    new Frame(LogicalPlan.Sort(plan, expressions, schema), schema, descriptor)

  def limit(count: Int): Either[FrameError, Frame[S]] =
    if count < 0 then Left(FrameError.InvalidLimit(count))
    else Right(new Frame(LogicalPlan.Limit(plan, count, schema), schema, descriptor))

  def dynamic: DynamicFrame = new DynamicFrame(plan, schema, scopeToken)

  def normalized: (Frame[S], NormalizationReceipt) =
    val result = PlanNormalizer.normalize(plan)
    (new Frame(result.plan, schema, descriptor), result.receipt)

  def explain: String = LogicalPlan.explain(plan)

object Frame:
  /** Construct a pure scan plan for a typed source reference.
    *
    * This method does not open or validate the external source. The execution boundary checks the
    * acquired source schema against the derived schema for `S`.
    */
  def scan[S <: NamedTuple.AnyNamedTuple](reference: SourceRef)(using
      descriptor: SchemaDescriptor[S]
  ): Frame[S] =
    val schema = descriptor.schema
    new Frame(LogicalPlan.Source(reference, schema), schema, descriptor)

  def values[S <: NamedTuple.AnyNamedTuple](reference: SourceRef)(using
      descriptor: SchemaDescriptor[S]
  ): Either[FrameError, Frame[S]] =
    if reference.kind != SourceKind.Values then
      Left(FrameError.NotValuesSource(reference.id, reference.kind))
    else Right(scan(reference))

  def source[S <: NamedTuple.AnyNamedTuple](name: String)(using
      descriptor: SchemaDescriptor[S]
  ): Either[FrameError, Frame[S]] =
    SourceRef.scan(name, name).map(scan(_))

private object JoinPlanning:
  private def column(
      token: FrameScopeToken,
      input: InputRef,
      field: Field,
      index: Int
  ): ResolvedExpr =
    val scopeId = ExprScopeId.forInput(token, input)
    ResolvedExpr(
      ExprId.derived(s"column:${input.qualifier}:${field.id.value}"),
      field.dataType,
      field.nullable,
      ExprNode.Column(input, scopeId, field.id, field.name, index)
    )

  private def equality(left: ResolvedExpr, right: ResolvedExpr): ResolvedExpr =
    val equal = ResolvedExpr(
      ExprId.derived(s"Equal(${left.id.value},${right.id.value})"),
      DataType.Bool,
      left.nullable || right.nullable,
      ExprNode.Binary(BinaryOperator.Equal, left, right)
    )
    ResolvedExpr(
      ExprId.derived(s"IsTrue(${equal.id.value})"),
      DataType.Bool,
      nullable = false,
      ExprNode.Unary(UnaryOperator.IsTrue, equal)
    )

  private def allEqual(
      comparisons: Vector[ResolvedExpr]
  ): ResolvedExpr =
    comparisons.tail.foldLeft(comparisons.head): (acc, next) =>
      ResolvedExpr(
        ExprId.derived(s"And(${acc.id.value},${next.id.value})"),
        DataType.Bool,
        nullable = false,
        ExprNode.Binary(BinaryOperator.And, acc, next)
      )

  def rightProjection(
      joined: Schema,
      output: Schema,
      rightSize: Int,
      leftSize: Int
  ): Vector[NamedExpression] =
    val token = FrameScopeToken.fresh()
    val scopeId = ExprScopeId.forInput(token, InputRef.Current)
    val indexes =
      Vector.tabulate(leftSize)(index => rightSize + index) ++
        Vector.tabulate(rightSize)(identity)
    indexes
      .zip(output.fields)
      .map: (sourceIndex, outputField) =>
        val source = joined.fields(sourceIndex)
        NamedExpression(
          outputField.name,
          ResolvedExpr(
            ExprId.derived(s"column:current:${source.id.value}"),
            source.dataType,
            source.nullable,
            ExprNode.Column(
              InputRef.Current,
              scopeId,
              source.id,
              source.name,
              sourceIndex
            )
          )
        )

  def dynamicRight(
      left: DynamicFrame,
      right: DynamicFrame,
      condition: (DynamicScope, DynamicScope) => Either[FrameError, DynamicExpr]
  ): Either[FrameError, DynamicFrame] =
    left.schema.fields.map(_.name).find(name => right.schema.field(name).nonEmpty) match
      case Some(name) => Left(FrameError.ColumnCollision(name))
      case None       =>
        condition(
          new DynamicScope(
            left.schema,
            InputRef.Right,
            ExprScopeId.forInput(left.scopeToken, InputRef.Right)
          ),
          new DynamicScope(
            right.schema,
            InputRef.Left,
            ExprScopeId.forInput(right.scopeToken, InputRef.Left)
          )
        ).flatMap: expression =>
          if expression.dataType != DataType.Bool then
            Left(FrameError.ExpressionType(DataType.Bool, expression.dataType))
          else if expression.nullable then Left(FrameError.NullablePredicate(expression.id))
          else
            ExpressionValidation
              .join(
                expression.resolved,
                right.scopeToken,
                right.schema,
                left.scopeToken,
                left.schema
              )
              .flatMap: _ =>
                val joinedFields =
                  right.schema.fields ++
                    left.schema.fields.map(_.copy(nullable = true))
                val outputFields =
                  left.schema.fields.map(_.copy(nullable = true)) ++
                    right.schema.fields
                Schema(joinedFields).left
                  .map(FrameError.InvalidSchema.apply)
                  .flatMap: joinedSchema =>
                    Schema(outputFields).left
                      .map(FrameError.InvalidSchema.apply)
                      .map: output =>
                        val joined = LogicalPlan.Join(
                          right.plan,
                          left.plan,
                          JoinKind.LeftOuter,
                          expression.resolved,
                          JoinColumn.all(right.schema.size, left.schema.size),
                          joinedSchema
                        )
                        new DynamicFrame(
                          LogicalPlan.Project(
                            joined,
                            rightProjection(
                              joinedSchema,
                              output,
                              right.schema.size,
                              left.schema.size
                            ),
                            output
                          ),
                          output
                        )

  def typedUsing(
      left: DynamicFrame,
      right: DynamicFrame,
      kind: JoinKind,
      key: String,
      leftIndex: Int,
      rightIndex: Int,
      output: Schema
  ): DynamicFrame =
    val leftField = left.schema.fields(leftIndex)
    val rightField = right.schema.fields(rightIndex)
    val condition = equality(
      column(left.scopeToken, InputRef.Left, leftField, leftIndex),
      column(right.scopeToken, InputRef.Right, rightField, rightIndex)
    )
    val columns =
      Vector.tabulate(left.schema.size)(JoinColumn.Left.apply) ++
        right.schema.fields.indices
          .filter(_ != rightIndex)
          .map(JoinColumn.Right.apply)
          .toVector
    new DynamicFrame(
      LogicalPlan.Join(
        left.plan,
        right.plan,
        kind,
        condition,
        columns,
        output
      ),
      output
    )

  def using(
      left: DynamicFrame,
      right: DynamicFrame,
      kind: JoinKind,
      keys: Vector[String]
  ): Either[FrameError, DynamicFrame] =
    if keys.isEmpty then Left(FrameError.EmptyJoinKeys)
    else
      keys
        .groupMapReduce(identity)(_ => 1)(_ + _)
        .collectFirst:
          case (name, count) if count > 1 => name
      match
        case Some(name) => Left(FrameError.DuplicateJoinKey(name))
        case None       =>
          val keyFields =
            keys.foldLeft[
              Either[FrameError, Vector[(Int, Field, Int, Field)]]
            ](Right(Vector.empty)):
              case (result, name) =>
                result.flatMap: fields =>
                  val leftIndex = left.schema.fields.indexWhere(_.name == name)
                  val rightIndex = right.schema.fields.indexWhere(_.name == name)
                  (left.schema.fields.lift(leftIndex), right.schema.fields.lift(rightIndex)) match
                    case (None, _) => Left(FrameError.ColumnNotFound(name))
                    case (_, None) => Left(FrameError.ColumnNotFound(name))
                    case (Some(lhs), Some(rhs)) if lhs.dataType != rhs.dataType =>
                      Left(FrameError.JoinKeyType(name, lhs.dataType, rhs.dataType))
                    case (Some(lhs), Some(rhs)) =>
                      Right(fields :+ (leftIndex, lhs, rightIndex, rhs))
          keyFields.flatMap: resolvedKeys =>
            left.schema.fields
              .map(_.name)
              .find(name => !keys.contains(name) && right.schema.field(name).nonEmpty) match
              case Some(name) => Left(FrameError.ColumnCollision(name))
              case None       =>
                val retainedRight =
                  right.schema.fields.zipWithIndex.filter: (field, _) =>
                    !keys.contains(field.name)
                val outputFields =
                  left.schema.fields ++ retainedRight.map: (field, _) =>
                    if kind == JoinKind.LeftOuter then field.copy(nullable = true)
                    else field
                Schema(outputFields).left
                  .map(FrameError.InvalidSchema.apply)
                  .map: output =>
                    val comparisons = resolvedKeys.map:
                      case (leftIndex, lhs, rightIndex, rhs) =>
                        equality(
                          column(left.scopeToken, InputRef.Left, lhs, leftIndex),
                          column(
                            right.scopeToken,
                            InputRef.Right,
                            rhs,
                            rightIndex
                          )
                        )
                    val columns =
                      Vector.tabulate(left.schema.size)(JoinColumn.Left.apply) ++
                        retainedRight.map: (_, index) =>
                          JoinColumn.Right(index)
                    new DynamicFrame(
                      LogicalPlan.Join(
                        left.plan,
                        right.plan,
                        kind,
                        allEqual(comparisons),
                        columns,
                        output
                      ),
                      output
                    )

/** Intermediate typed grouping that can only be completed with `aggregate`.
  *
  * Group keys retain their names and types. Aggregate output names must be disjoint from the keys,
  * and the resulting schema is computed at compile time.
  */
final class GroupedFrame[
    S <: NamedTuple.AnyNamedTuple,
    Keys <: Tuple,
    ScopeOrigin
] private[frame4s] (
    input: Frame[S],
    keys: Vector[NamedExpression]
):
  def aggregate[Aggregates <: Tuple](
      expressions: Scope[S, ScopeOrigin] => Aggregates
  )(using
      inScope: AggregatesInScope[Aggregates, ScopeOrigin],
      selection: AggregateSelection[Aggregates],
      output: SchemaDescriptor[
        ConcatSchema[SelectedSchema[Keys], AggregateSchema[Aggregates]]
      ],
      unique: UniqueNames[
        Tuple.Concat[SelectionNames[Keys], AggregateNames[Aggregates]]
      ]
  ): Frame[ConcatSchema[SelectedSchema[Keys], AggregateSchema[Aggregates]]] =
    val aggregates = selection.expressions(
      expressions(Scope.current[S, ScopeOrigin](input.scopeToken, input.schema))
    )
    new Frame[
      ConcatSchema[SelectedSchema[Keys], AggregateSchema[Aggregates]]
    ](
      LogicalPlan.Aggregate(input.plan, keys, aggregates, output.schema),
      output.schema,
      output
    )
