package frame4s

import scala.util.Try

class NullSoundnessSuite extends munit.FunSuite:
  private def value[A](result: Either[StorageError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def littleEndianInt(bytes: Array[Byte], index: Int): Int =
    val start = index * 4
    (bytes(start) & 0xff) |
      ((bytes(start + 1) & 0xff) << 8) |
      ((bytes(start + 2) & 0xff) << 16) |
      ((bytes(start + 3) & 0xff) << 24)

  test("Schema.apply returns Left for a null field name instead of throwing"):
    assertEquals(
      Schema(Vector(DynamicFrame.field(null, DataType.Utf8, true))),
      Left(SchemaError.NullFieldName(0))
    )
    assertEquals(Schema(null), Left(SchemaError.NullFields))
    assertEquals(
      Schema(Vector(DynamicFrame.field("value", null))),
      Left(SchemaError.NullFieldType(0))
    )
    assertEquals(
      Schema(Vector(DynamicFrame.field("at", DataType.Timestamp(null)))),
      Left(SchemaError.NullTimestampUnit(0))
    )

  test("source ordering rejects raw null through the planning error channel"):
    assertEquals(
      SourceRef.scan("source", "source", null),
      Left(FrameError.NullSourceOrder)
    )
    assertEquals(
      SourceRef.scan("source", "source", OrderGuarantee.Sorted(null)),
      Left(FrameError.InvalidSourceOrder)
    )
    assertEquals(
      SourceRef.scan("source", "source", OrderGuarantee.Sorted(Vector(null))),
      Left(FrameError.InvalidSourceOrder)
    )

  test("ColumnArray.utf8 rejects a valid null and releases allocated validity"):
    val tracker = new BufferTracker
    assertEquals(
      ColumnArray
        .utf8(Array("ignored", null), Array(false, true), tracker)
        .left
        .map(_.message),
      Left(StorageError.NullValue(1).message)
    )
    assertEquals(tracker.snapshot, BufferSnapshot(0, 0, 1))

  test("public column constructors classify null container inputs"):
    assertEquals(
      ColumnArray.int32(null),
      Left(StorageError.NullInput("column values"))
    )
    assertEquals(
      ColumnArray.utf8(Array("value"), null),
      Left(StorageError.NullInput("column validity"))
    )
    assertEquals(
      ColumnArray.timestamp(Array(1L), null),
      Left(StorageError.NullInput("timestamp unit"))
    )
    assertEquals(
      ColumnArray.dictionary(null, null),
      Left(StorageError.NullInput("dictionary indices"))
    )

  test("ColumnArray.utf8 does not dereference or encode an invalid slot"):
    val array = value:
      ColumnArray.utf8(Array("a", null, "b"), Array(true, false, true))
    try
      assertEquals(array.scalar(0), Right(ScalarValue.checkedUtf8("a"): ScalarValue))
      assertEquals(array.scalar(1), Right(ScalarValue.Null: ScalarValue))
      assertEquals(array.scalar(2), Right(ScalarValue.checkedUtf8("b"): ScalarValue))

      val buffers = value(array.copyPhysicalBuffers)
      assertEquals(
        array.layout.buffers.map(_.role),
        Vector(BufferRole.Validity, BufferRole.Offsets, BufferRole.Values)
      )
      assertEquals(
        Vector.tabulate(4)(index => littleEndianInt(buffers(1), index)),
        Vector(0, 1, 1, 2)
      )
      assertEquals(buffers(2).toVector, Vector('a'.toByte, 'b'.toByte))
    finally array.close()

  test("typed literals reject ascribed null and Some(null) before a plan exists"):
    val raw = Try(Expr.literal(null: String))
    val nested = Try(Expr.literal(Some(null): Option[String]))

    assertEquals(raw.failed.toOption.map(_.getClass), Some(classOf[InvalidValueFailure]))
    assertEquals(nested.failed.toOption.map(_.getClass), Some(classOf[InvalidValueFailure]))
    assertEquals(Expr.literalChecked(null: String), Left(ValueError.NullUtf8))
    assertEquals(
      Expr.literalChecked(Some(null): Option[String]),
      Left(ValueError.NullUtf8)
    )

  test("scalar conveniences reject an ascribed null before a plan exists"):
    type Row = (label: String)
    val frame = Frame.source[Row]("null-scalar").fold(error => fail(error.message), identity)
    val raw: String = null

    assertEquals(
      Try(frame.filter(row => row.col("label") === raw)).failed.toOption.map(_.getClass),
      Some(classOf[InvalidValueFailure])
    )

  test("public UTF-8 value constructors cannot create a value containing raw null"):
    assertEquals(Utf8Value.from(null), Left(ValueError.NullUtf8))
    assertEquals(LiteralValue.utf8(null), Left(ValueError.NullUtf8))
    assertEquals(ScalarValue.utf8(null), Left(ValueError.NullUtf8))

    val directCases = compileErrors("""
      import frame4s.*
      val literal = LiteralValue.Utf8(null)
      val scalar = ScalarValue.Utf8(null)
    """)
    assert(directCases.contains("Required: frame4s.Utf8Value"), clues(directCases))

  test("a validated dynamic UTF-8 literal cannot contain raw null"):
    val literal = LiteralValue.utf8("value").fold(error => fail(error.message), identity)
    val dynamic = DynamicExpr.literal(literal)
    assertEquals(dynamic.dataType, DataType.Utf8)
    assertEquals(LiteralValue.utf8(null), Left(ValueError.NullUtf8))
    assertEquals(DynamicExpr.literalChecked(null), Left(ValueError.NullLiteral))
    assertEquals(
      DynamicExpr.literalChecked(LiteralValue.Null(null)),
      Left(ValueError.NullDataType)
    )
    assertEquals(
      DynamicExpr.literalChecked(LiteralValue.Timestamp(1L, null)),
      Left(ValueError.NullTimeUnit)
    )
