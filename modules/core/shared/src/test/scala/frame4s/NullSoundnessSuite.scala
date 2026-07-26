package frame4s

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

  test("ColumnArray.utf8 does not dereference or encode an invalid slot"):
    val array = value:
      ColumnArray.utf8(Array("a", null, "b"), Array(true, false, true))
    try
      assertEquals(array.scalar(0), Right(ScalarValue.Utf8("a"): ScalarValue))
      assertEquals(array.scalar(1), Right(ScalarValue.Null: ScalarValue))
      assertEquals(array.scalar(2), Right(ScalarValue.Utf8("b"): ScalarValue))

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
