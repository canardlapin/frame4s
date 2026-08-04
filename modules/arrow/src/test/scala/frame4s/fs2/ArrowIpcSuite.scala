package frame4s.fs2

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.util.Collections
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.TimeStampSecVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.TimeUnit as ArrowTimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field as ArrowField
import org.apache.arrow.vector.types.pojo.FieldType as ArrowFieldType
import org.apache.arrow.vector.types.pojo.Schema as ArrowSchema
import scala.jdk.CollectionConverters.*
import frame4s.*

class ArrowIpcSuite extends munit.FunSuite:
  type Input = (
      flag: Option[Boolean],
      i32: Int,
      i64: Long,
      f32: Float,
      f64: Option[Double],
      text: String,
      time: TimestampMicros
  )

  private val schema = summon[SchemaDescriptor[Input]].schema

  private def storage[A](result: Either[StorageError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def batch: RecordBatch =
    storage:
      RecordBatch(
        schema,
        Vector(
          storage(ColumnArray.bool(Array(true, false), Array(true, false))),
          storage(ColumnArray.int32(Array(1, 2))),
          storage(ColumnArray.int64(Array(3L, 4L))),
          storage(ColumnArray.float32(Array(1.25f, Float.NaN))),
          storage(ColumnArray.float64(Array(2.5, 0.0), Array(true, false))),
          storage(ColumnArray.utf8(Array("arrow", "λ"))),
          storage(
            ColumnArray.timestamp(
              Array(1000L, 2000L),
              TimeUnit.Microsecond
            )
          )
        )
      )

  private def scalars(batch: RecordBatch, name: String): Vector[ScalarValue] =
    val column = storage(batch.column(name))
    Vector.tabulate(batch.rowCount)(index => storage(column.scalar(index)))

  private def frameSchema(fields: Vector[Field]): Schema =
    Schema(fields).fold(error => fail(error.message), identity)

  private def intRoot[A](width: Int)(operation: VectorSchemaRoot => A): A =
    val allocator = new RootAllocator()
    val arrowSchema = new ArrowSchema(
      Vector
        .tabulate(width): index =>
          new ArrowField(
            s"c$index",
            ArrowFieldType.notNullable(new ArrowType.Int(32, true)),
            Collections.emptyList()
          )
        .asJava
    )
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    try
      root.allocateNew()
      var index = 0
      while index < width do
        root.getVector(index) match
          case vector: IntVector =>
            vector.setSafe(0, index)
            vector.setValueCount(1)
          case other => fail(s"expected IntVector, found ${other.getClass.getName}")
        index += 1
      root.setRowCount(1)
      operation(root)
    finally
      root.close()
      allocator.close()

  private def secondsTimestampBytes: Array[Byte] =
    val allocator = new RootAllocator()
    val arrowSchema = new ArrowSchema(
      List(
        new ArrowField(
          "at",
          ArrowFieldType.notNullable(new ArrowType.Timestamp(ArrowTimeUnit.SECOND, null)),
          Collections.emptyList()
        )
      ).asJava
    )
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    val output = new ByteArrayOutputStream
    val writer = new ArrowStreamWriter(root, null, Channels.newChannel(output))
    try
      root.allocateNew()
      root.getVector(0) match
        case vector: TimeStampSecVector =>
          vector.setSafe(0, 1L)
          vector.setValueCount(1)
        case other => fail(s"expected TimeStampSecVector, found ${other.getClass.getName}")
      root.setRowCount(1)
      writer.start()
      writer.writeBatch()
      writer.end()
      output.toByteArray
    finally
      writer.close()
      root.close()
      allocator.close()

  private def timezoneTimestampBytes: Array[Byte] =
    val allocator = new RootAllocator()
    val arrowSchema = new ArrowSchema(
      List(
        new ArrowField(
          "at",
          ArrowFieldType.notNullable(
            new ArrowType.Timestamp(ArrowTimeUnit.MICROSECOND, "UTC")
          ),
          Collections.emptyList()
        )
      ).asJava
    )
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    val output = new ByteArrayOutputStream
    val writer = new ArrowStreamWriter(root, null, Channels.newChannel(output))
    try
      writer.start()
      writer.end()
      output.toByteArray
    finally
      writer.close()
      root.close()
      allocator.close()

  test("Arrow IPC stream roundtrip preserves schema, values, nulls, and timestamp units"):
    val input = batch
    val sink = new ArrowIpcFrameSink[IO]()
    val written = sink
      .write(schema, Stream.emit(input))
      .unsafeRunSync()
      .fold(error => fail(error.message), identity)
    var acquired: Option[ArrowIpcFrameSource[IO]] = None
    val observed = ArrowIpcFrameSource
      .resource[IO](written.bytes)
      .use: source =>
        acquired = Some(source)
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO(fail(error.message))
            case Right(scan) =>
              scan.batches
                .evalMap: output =>
                  IO:
                    (
                      scalars(output, "flag"),
                      scalars(output, "f32"),
                      scalars(output, "f64"),
                      scalars(output, "text"),
                      scalars(output, "time")
                    )
                .compile
                .lastOrError
      .unsafeRunSync()

    assertEquals(written.receipt.rows, 2L)
    assert(written.bytes.nonEmpty)
    assertEquals(observed._1, Vector(ScalarValue.Bool(true), ScalarValue.Null))
    observed._2.last match
      case ScalarValue.Float32(value) => assert(value.isNaN)
      case other                      => fail(s"expected Float32 NaN, found $other")
    assertEquals(observed._3, Vector(ScalarValue.Float64(2.5), ScalarValue.Null))
    assertEquals(observed._4, Vector("arrow", "λ").map(ScalarValue.checkedUtf8))
    assertEquals(
      observed._5,
      Vector(
        ScalarValue.Timestamp(1000L, TimeUnit.Microsecond),
        ScalarValue.Timestamp(2000L, TimeUnit.Microsecond)
      )
    )

    acquired.get.inspect.unsafeRunSync() match
      case Left(SourceError.Closed) => ()
      case other                    => fail(s"expected finalized Arrow source, found $other")
    input.close()

  test("malformed Arrow IPC acquisition is structured and releases native resources"):
    val malformed = Array[Byte](1, 2, 3, 4)
    var attempt = 0
    while attempt < 20 do
      ArrowIpcFrameSource
        .resource[IO](malformed)
        .use(_ => IO.unit)
        .attempt
        .unsafeRunSync() match
        case Left(failure @ SourceFailure(error @ SourceError.Open(cause))) =>
          assert(error.cause.exists(_ eq cause))
          assert(failure.getCause eq cause)
        case other => fail(s"expected structured Arrow open failure, found $other")
      attempt += 1

  test("Arrow sink returns arbitrary upstream failures without rendering their cause"):
    val cause = new IllegalStateException("credential=arrow-secret")
    new ArrowIpcFrameSink[IO]()
      .write(schema, Stream.raiseError[IO](cause))
      .unsafeRunSync() match
      case Left(error @ SinkError.Upstream(actual)) =>
        assert(actual eq cause)
        assert(error.cause.exists(_ eq cause))
        assert(!error.message.contains("credential=arrow-secret"))
      case other => fail(s"expected structured Arrow upstream failure, found $other")

  test("Arrow source snapshots caller bytes when its resource is constructed"):
    val input = batch
    val written = new ArrowIpcFrameSink[IO]()
      .write(schema, Stream.emit(input))
      .unsafeRunSync()
      .fold(error => fail(error.message), identity)
    input.close()

    val source = ArrowIpcFrameSource.resource[IO](written.bytes)
    java.util.Arrays.fill(written.bytes, 0.toByte)

    source
      .use(_.inspect)
      .map:
        case Right(inspection) => assertEquals(inspection.schema, schema)
        case Left(error)       => fail(error.message)
      .unsafeToFuture()

  test("partial decoded columns close exactly once at every failure position"):
    val width = 6
    (0 until width).foreach: failureIndex =>
      intRoot(width): root =>
        val fields = Vector.tabulate(width): index =>
          DynamicFrame.field(
            s"c$index",
            if index == failureIndex then DataType.Bool else DataType.Int32,
            nullable = false
          )
        val tracker = new BufferTracker
        ArrowIpcCodec.decodeBatch(frameSchema(fields), root, tracker) match
          case Left(SourceError.SchemaMismatch(_)) => ()
          case other                               =>
            fail(s"expected injected column $failureIndex failure, found $other")
        assertEquals(
          tracker.snapshot,
          BufferSnapshot(
            activeOwners = 0,
            activeViews = 0,
            releasedOwners = failureIndex.toLong
          )
        )

  test("RecordBatch validation failure closes every decoded Arrow column"):
    intRoot(1): root =>
      root.getVector(0) match
        case vector: IntVector => vector.setNull(0)
        case other             => fail(s"expected IntVector, found ${other.getClass.getName}")
      val declared = frameSchema(Vector(DynamicFrame.field("c0", DataType.Int32, nullable = false)))
      val tracker = new BufferTracker

      ArrowIpcCodec.decodeBatch(declared, root, tracker) match
        case Left(SourceError.Storage(_)) => ()
        case other => fail(s"expected RecordBatch validation failure, found $other")
      assertEquals(
        tracker.snapshot,
        BufferSnapshot(activeOwners = 0, activeViews = 0, releasedOwners = 2L)
      )

  test("a nonfatal Arrow vector exception closes every previously decoded column"):
    intRoot(3): root =>
      root.getVector(2).close()
      val declared = frameSchema(
        Vector.tabulate(3)(index =>
          DynamicFrame.field(s"c$index", DataType.Int32, nullable = false)
        )
      )
      val tracker = new BufferTracker

      val failure = intercept[RuntimeException]:
        ArrowIpcCodec.decodeBatch(declared, root, tracker)
      assert(failure.getClass.getName.nonEmpty)
      assertEquals(
        tracker.snapshot,
        BufferSnapshot(activeOwners = 0, activeViews = 0, releasedOwners = 2L)
      )

  test("Arrow 0.1 rejects timestamp units without a public typed schema"):
    ArrowIpcFrameSource
      .resource[IO](secondsTimestampBytes)
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(SourceFailure(SourceError.SchemaMismatch(detail))) =>
          assert(detail.contains("microsecond"))
        case other => fail(s"expected unsupported timestamp source error, found $other")
      .unsafeRunSync()

    val secondsSchema = frameSchema(
      Vector(DynamicFrame.field("at", DataType.Timestamp(TimeUnit.Second), nullable = false))
    )
    val secondsBatch = storage:
      RecordBatch(
        secondsSchema,
        Vector(storage(ColumnArray.timestamp(Array(1L), TimeUnit.Second)))
      )
    try
      new ArrowIpcFrameSink[IO]()
        .write(secondsSchema, Stream.emit(secondsBatch))
        .unsafeRunSync() match
        case Left(SinkError.InvalidRequest(detail)) => assert(detail.contains("microsecond"))
        case other                                  =>
          fail(s"expected unsupported timestamp sink error, found $other")
    finally secondsBatch.close()

  test("Arrow 0.1 rejects timezone-bearing timestamps"):
    ArrowIpcFrameSource
      .resource[IO](timezoneTimestampBytes)
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(SourceFailure(SourceError.SchemaMismatch(detail))) =>
          assert(detail.contains("timezone-free"))
        case other => fail(s"expected unsupported timestamp timezone error, found $other")
      .unsafeRunSync()
