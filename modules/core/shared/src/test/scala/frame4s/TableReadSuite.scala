package frame4s

class TableReadSuite extends munit.FunSuite:
  type Row = (
      id: Int,
      label: String,
      score: Option[Double],
      at: TimestampMicros
  )

  final case class Item(
      id: Int,
      label: String,
      score: Option[Double],
      at: TimestampMicros
  )

  type Wide = (
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

  private val rows: Vector[Row] = Vector(
    (id = 1, label = "", score = Some(Double.NaN), at = TimestampMicros(10L)),
    (id = 2, label = "東京", score = None, at = TimestampMicros(20L)),
    (
      id = 3,
      label = "a very long value",
      score = Some(Double.PositiveInfinity),
      at = TimestampMicros(30L)
    )
  )

  private def table(input: Vector[Row] = rows): Table[Row] =
    Table.fromRows(input, batchSize = 2).fold(error => fail(error.message), identity)

  test("named rows, cells, and columns decode to exact detached Scala types"):
    val input = table()
    try
      assertEquals(input.row(1), Right(rows(1)))
      assertEquals(input.cell(0, "id"), Right(1))
      assertEquals(input.cell(1, "score"), Right(None))
      assertEquals(input.column("label"), Right(Vector("", "東京", "a very long value")))
    finally input.close()

  test("decoded rows outlive the owner and closed access is structured"):
    val input = table()
    val detached = input.row(1)
    input.close()
    assertEquals(detached, Right(rows(1)))
    assertEquals(input.row(0), Left(TableReadError.Closed))
    assertEquals(input.column("id"), Left(TableReadError.Closed))
    assertEquals(input.show(), Left(TableReadError.Closed))

  test("row bounds are distinct from storage and closed failures"):
    val input = table()
    try
      assertEquals(input.row(-1), Left(TableReadError.RowOutOfBounds(-1, 3)))
      assertEquals(input.cell(3, "id"), Left(TableReadError.RowOutOfBounds(3, 3)))
    finally input.close()

  test("case-class conversion requires NamedTuple.From exact schema"):
    val input = Table
      .fromProducts(
        Vector(
          Item(1, "", Some(Double.NaN), TimestampMicros(10L)),
          Item(2, "東京", None, TimestampMicros(20L))
        )
      )
      .fold(error => fail(error.message), identity)
    try
      assertEquals(
        input.rowAs[Item](1),
        Right(Item(2, "東京", None, TimestampMicros(20L)))
      )
    finally input.close()

  test("named-tuple representation bridge is inductive through 32 fields"):
    val row: Wide = (
      c01 = 1,
      c02 = 2,
      c03 = 3,
      c04 = 4,
      c05 = 5,
      c06 = 6,
      c07 = 7,
      c08 = 8,
      c09 = 9,
      c10 = 10,
      c11 = 11,
      c12 = 12,
      c13 = 13,
      c14 = 14,
      c15 = 15,
      c16 = 16,
      c17 = 17,
      c18 = 18,
      c19 = 19,
      c20 = 20,
      c21 = 21,
      c22 = 22,
      c23 = 23,
      c24 = 24,
      c25 = 25,
      c26 = 26,
      c27 = 27,
      c28 = 28,
      c29 = 29,
      c30 = 30,
      c31 = 31,
      c32 = 32
    )
    val input = Table.fromRows(Vector(row)).fold(error => fail(error.message), identity)
    try
      assertEquals(input.row(0), Right(row))
      assertEquals(input.cell(0, "c32"), Right(32))
    finally input.close()

  test("case-class bridge rejects reordered, missing, and differently nullable schemas"):
    val reordered = compileErrors("""
      import frame4s.*
      type R = (id: Int, label: String)
      case class P(label: String, id: Int)
      val table: Table[R] = ???
      table.rowAs[P](0)
    """)
    val missing = compileErrors("""
      import frame4s.*
      type R = (id: Int, label: String)
      case class P(id: Int)
      val table: Table[R] = ???
      table.rowAs[P](0)
    """)
    val extra = compileErrors("""
      import frame4s.*
      type R = (id: Int)
      case class P(id: Int, label: String)
      val table: Table[R] = ???
      table.rowAs[P](0)
    """)
    val nullable = compileErrors("""
      import frame4s.*
      type R = (id: Int, score: Option[Double])
      case class P(id: Int, score: Double)
      val table: Table[R] = ???
      table.rowAs[P](0)
    """)
    Vector(reordered, missing, extra, nullable).foreach: diagnostic =>
      assert(diagnostic.contains("Cannot prove"))

  test("empty and all-null tables are lawful"):
    val empty = table(Vector.empty)
    val allNull = table(
      Vector((id = 1, label = "a", score = None, at = TimestampMicros(1L)))
    )
    try
      assertEquals(empty.rowCount, 0L)
      assertEquals(empty.column("score"), Right(Vector.empty))
      assertEquals(allNull.column("score"), Right(Vector(None)))
    finally
      empty.close()
      allNull.close()

  test("fromRows releases prior and partially built buffers on structured encode failure"):
    val tracker = new BufferTracker
    val invalid: Row =
      (id = 3, label = null, score = None, at = TimestampMicros(3L))
    val result = Table.fromRowsTracked(rows.take(2) :+ invalid, 2, tracker)
    result match
      case Left(TableReadError.ScalarDecode(2, 1, "label", ScalarValue.Utf8(null), _, false)) =>
        ()
      case other => fail(s"expected structured null-string encode failure, found $other")
    val snapshot = tracker.snapshot
    assertEquals(snapshot.activeOwners, 0)
    assertEquals(snapshot.activeViews, 0)
    assert(snapshot.releasedOwners > 0L)

  test("schema rendering is deterministic, line-bounded, and ownership-neutral"):
    val input = table()
    try
      val rendered = input.showSchema(24).fold(error => fail(error.message), identity)
      assert(rendered.startsWith("Schema\n0. id: Int32 required"))
      assert(rendered.contains("score: Float64"))
      assert(rendered.linesIterator.forall(_.length <= 24))
      assert(!input.isClosed)
    finally input.close()

  test("bounded rendering specifies special values, Unicode, truncation, and omitted rows"):
    val input = table()
    try
      val rendered = input
        .show(TableRenderOptions(maxRows = 2, maxWidth = 34, maxCellWidth = 8))
        .fold(error => fail(error.message), identity)
      assertEquals(
        rendered,
        """||id|label|score|at      |
          ||--|-----|-----|--------|
          ||1 |""   |NaN  |10@Micr…|
          ||2 |東京   |null |20@Micr…|
          |… +1 rows""".stripMargin
      )
      assert(rendered.linesIterator.forall(_.length <= 34))
      val allRows = input.show().fold(error => fail(error.message), identity)
      assert(allRows.contains("Infinity"))
    finally input.close()

  test("show reads no row beyond the explicit bound"):
    val input = table()
    input.batches(1).close()
    try
      val first = input
        .show(TableRenderOptions(maxRows = 1, maxWidth = 40))
        .fold(error => fail(error.message), identity)
      assert(first.contains("|1 "))
      input.show(TableRenderOptions(maxRows = 3, maxWidth = 40)) match
        case Left(TableReadError.Storage(StorageError.BufferClosed)) => ()
        case other => fail(s"expected a closed later batch only when requested, found $other")
    finally input.close()

  test("wide rendering remains line-bounded and reports omitted columns"):
    val input = table()
    try
      val rendered = input
        .show(TableRenderOptions(maxRows = 1, maxWidth = 8, maxCellWidth = 8))
        .fold(error => fail(error.message), identity)
      assert(rendered.linesIterator.forall(_.length <= 8))
      assert(rendered.contains("+3"))
    finally input.close()
