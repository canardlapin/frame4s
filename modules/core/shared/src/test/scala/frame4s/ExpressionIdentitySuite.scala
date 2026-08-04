package frame4s

import scala.collection.mutable.ArrayBuffer

class ExpressionIdentitySuite extends munit.FunSuite:
  type Row = (id: Int, label: String)

  private def source: Frame[Row] =
    Frame.source[Row]("identity").fold(error => fail(error.message), identity)

  test("reused expression identity and public rendering stay bounded"):
    val base: ExprOf[Int, Nothing] = Expr.literal(1)
    val deep = (0 until 20).foldLeft(base): (current, _) =>
      current + current

    assert(deep.id.toString.length <= 64)

    val pending = ArrayBuffer(deep.id)
    val seen = ArrayBuffer.empty[ExprId]
    while pending.nonEmpty do
      val current = pending.remove(pending.length - 1)
      if !seen.exists(_ eq current) then
        seen += current
        current.witness match
          case ExprIdentityNode.Unary(_, input, _, _)        => pending += input
          case ExprIdentityNode.Binary(_, left, right, _, _) =>
            pending += left
            pending += right
          case _ => ()
    assertEquals(seen.length, 21)

  test("a fingerprint collision does not merge distinct structural witnesses"):
    val first = Expr.literal(1).resolved
    val secondBase = Expr.literal(2).resolved
    val collision = ExprId.collisionProbe(first.id, "different structure")
    val second = secondBase.copy(id = collision)

    assertEquals(first.id.hashCode, collision.hashCode)
    assertNotEquals(first.id, collision)

    val output = Schema(
      Vector(
        DynamicFrame.field("first", DataType.Int32),
        DynamicFrame.field("second", DataType.Int32)
      )
    ).fold(error => fail(error.message), identity)
    val plan = LogicalPlan.Project(
      source.plan,
      Vector(NamedExpression("first", first), NamedExpression("second", second)),
      output
    )
    assert(
      LogicalPlan.explain(plan).contains("Project[first=e1, second=e2]"),
      clues(LogicalPlan.explain(plan))
    )

  test("deep reused expression explain grows linearly"):
    val input = source
    val query = input.select: row =>
      val base: ExprOf[Int, input.Origin] = row.col("id")
      val deep = (0 until 100).foldLeft(base): (current, _) =>
        current + current
      Tuple1(deep.as("deep"))
    val explanation = query.explain

    assert(explanation.length < 10000, clues(explanation.length))
    assertEquals(explanation.linesIterator.count(_.contains(" = Add(")), 100)

  test("explain assigns deterministic plan-local ordinals and redacts literals"):
    val secret = "token-91f2\nwith-control" + ("x" * 10000) + "\u0000tail"
    val first = source.filter(row => row.col("label") === secret)
    val second = source.filter(row => row.col("label") === secret)

    assertEquals(first.explain, second.explain)
    assert(first.explain.contains("Filter[e1]"), clues(first.explain))
    assert(!first.explain.contains(secret), clues(first.explain))
    assert(!first.explain.contains("91f2"), clues(first.explain))
    assert(!first.explain.toLowerCase.contains("fingerprint"), clues(first.explain))
    assert(first.explain.length < 1000, clues(first.explain.length))

  test("execution diagnostics redact scalar values and bound expression identity"):
    val secret = "diagnostic-secret-49f1" + ("x" * 10000) + "\u0000tail"
    val expression = Expr.literal(1) + Expr.literal(2)
    val message = ExecutionError
      .ExpressionType(expression.id, DataType.Int32, ScalarValue.checkedUtf8(secret))
      .message

    assert(!message.contains(secret), clues(message))
    assert(!message.contains("49f1"), clues(message))
    assert(message.length <= 256, clues(message.length))
