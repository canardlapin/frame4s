package frameconsumer

import frame4s.*
import scala.compiletime.testing.typeCheckErrors

class PublicApiSuite extends munit.FunSuite:
  type Input = (id: Int, label: String)

  test("the typed query surface is usable outside frame4s"):
    val source = Frame.source[Input]("input").fold(error => fail(error.message), identity)
    val query: Frame[(label: String, nextId: Int)] =
      source
        .filter(row => row.col("id") > Expr.literal(0))
        .select(row => Tuple1(row.col("label").as("label")))
        .withColumn("nextId")(_ => Expr.literal(1))

    assertEquals(query.schema.fields.map(_.name), Vector("label", "nextId"))

  test("SchemaDescriptor cannot be implemented outside frame4s"):
    val errors = typeCheckErrors("""
      import frame4s.*
      new SchemaDescriptor[(x: Int)]:
        def schema = summon[SchemaDescriptor[(z: String)]].schema
    """)
    assert(
      errors.nonEmpty,
      "a forged SchemaDescriptor compiles, so Frame[S] can carry an unrelated runtime schema"
    )

  test("unsupported schema fields retain a user-facing derivation error"):
    val errors = typeCheckErrors("""
      import frame4s.*
      summon[SchemaDescriptor[(amount: BigDecimal)]]
    """)
    val message = errors.headOption.fold(fail("expected a compile error"))(_.message)
    assert(message.contains("Schema field 'amount' has unsupported type"), message)
    assert(!message.contains("match type"), message)

  test("resolved plan node constructors are not part of the public API"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type S = (id: Int)
      val schema = summon[SchemaDescriptor[S]].schema
      val source = SourceRef.scan("id", "source").toOption.get
      LogicalPlan.Source(source, schema)
    """)
    assert(errors.nonEmpty)
