package frame4s.fs2

import cats.effect.Async
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.kernel.Outcome
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.util.Collections
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit as ArrowTimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field as ArrowField
import org.apache.arrow.vector.types.pojo.FieldType as ArrowFieldType
import org.apache.arrow.vector.types.pojo.Schema as ArrowSchema
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import frame4s.*

/** JVM source backed by an Arrow IPC stream decoded into owned frame4s batches.
  *
  * Acquire it through [[ArrowIpcFrameSource.resource]]. All decoded buffers close when the source
  * resource exits.
  */
final class ArrowIpcFrameSource[F[_]] private (
    delegate: InMemoryFrameSource[F]
)(using F: Async[F])
    extends FrameSource[F]:
  def inspect: F[Either[SourceError, SourceInspection]] = delegate.inspect

  def plan(request: ScanRequest): F[Either[SourceError, PlannedScan[F]]] =
    delegate.plan(request)

  private[fs2] def close: F[Either[SourceError, Unit]] = delegate.close

  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, SinkReceipt]] =
    new ArrowIpcFrameSink[F]().write(schema, batches).map(_.map(_.receipt))

object ArrowIpcFrameSource:
  /** Decode one Arrow IPC stream from an immutable byte snapshot.
    *
    * The input array is copied immediately when this resource value is constructed. Malformed or
    * unsupported Arrow data raises [[SourceFailure]] in `F`; operational failures retain their
    * cause without rendering it. Successfully decoded batches are owned by the returned resource.
    */
  def resource[F[_]](
      bytes: Array[Byte]
  )(using F: Async[F]): Resource[F, ArrowIpcFrameSource[F]] =
    val snapshot =
      if bytes == null then Left(SourceError.InvalidRequest("Arrow IPC bytes cannot be raw null"))
      else Right(bytes.clone())
    FrameSource.owningResource:
      F.fromEither(snapshot.leftMap(SourceFailure.apply))
        .flatMap(ArrowIpcCodec.decode[F])
        .flatMap(result => F.fromEither(result.leftMap(SourceFailure.apply)))
        .map: (schema, batches) =>
          new ArrowIpcFrameSource(InMemoryFrameSource.owned(schema, batches))

final case class ArrowIpcWriteResult(
    bytes: Array[Byte],
    receipt: SinkReceipt
)

/** JVM Arrow IPC encoder that returns a byte snapshot and an exact sink receipt.
  *
  * Ordinary upstream, write, and close failures are returned as [[SinkError]]; cancellation and
  * fatal platform failures remain effect-level.
  */
final class ArrowIpcFrameSink[F[_]](using F: Async[F]) extends FrameSink[F, ArrowIpcWriteResult]:
  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, ArrowIpcWriteResult]] =
    ArrowIpcCodec.encode(schema, batches)

private[fs2] object ArrowIpcCodec:
  final private case class WriterContext(
      output: ByteArrayOutputStream,
      root: VectorSchemaRoot,
      writer: ArrowStreamWriter
  )

  def encode[F[_]](
      schema: Schema,
      batches: Stream[F, RecordBatch]
  )(using F: Async[F]): F[Either[SinkError, ArrowIpcWriteResult]] =
    arrowSchema(schema) match
      case Left(error)         => F.pure(Left(error))
      case Right(nativeSchema) =>
        val encoded = writerResource(nativeSchema)
          .use: context =>
            val initialize = AdapterFailureBoundary.sinkEffect(
              F.blocking(context.writer.start()),
              SinkError.Write.apply
            )
            val encoded = batches
              .evalMap: batch =>
                if batch.schema != schema then
                  F.raiseError[Long](SinkFailure(SinkError.SchemaMismatch(schema, batch.schema)))
                else
                  AdapterFailureBoundary
                    .sinkEffect(
                      F.blocking(writeBatch(schema, context, batch)),
                      SinkError.Write.apply
                    )
                    .flatMap(result => F.fromEither(result.leftMap(SinkFailure.apply)))
              .compile
              .fold((0L, 0L)): (state, rows) =>
                (state._1 + rows, state._2 + 1L)
            initialize *> encoded.flatMap: (rows, batchCount) =>
              val finish = F.blocking:
                context.writer.end()
                val bytes = context.output.toByteArray
                ArrowIpcWriteResult(
                  bytes,
                  SinkReceipt(rows, batchCount, bytes.length.toLong)
                )
              AdapterFailureBoundary.sinkEffect(finish, SinkError.Write.apply)
        AdapterFailureBoundary.sinkEither(
          encoded.map(result => Right(result): Either[SinkError, ArrowIpcWriteResult]),
          SinkError.Upstream.apply
        )

  def decode[F[_]](
      bytes: Array[Byte]
  )(using F: Async[F]): F[Either[SourceError, (Schema, Vector[RecordBatch])]] =
    Ref
      .of[F, Vector[RecordBatch]](Vector.empty)
      .flatMap: retained =>
        def closeRetained: F[Unit] =
          retained.get.flatMap: batches =>
            batches.traverse_(batch =>
              AdapterFailureBoundary.sourceEffect(
                F.delay(batch.close()),
                SourceError.Close.apply
              )
            )

        val decoded = readerResource(bytes)
          .use: reader =>
            for
              root <- F.blocking(reader.getVectorSchemaRoot)
              schema <- F.fromEither(
                frameSchema(root.getSchema).leftMap(SourceFailure.apply)
              )
              _ <- readBatches(reader, root, schema, retained)
              batches <- retained.get
            yield (schema, batches)
        val guarded = decoded.guaranteeCase:
          case Outcome.Succeeded(_) => F.unit
          case _                    => closeRetained
        AdapterFailureBoundary.sourceEither(
          guarded,
          SourceError.Open.apply
        )

  private def writerResource[F[_]](
      schema: ArrowSchema
  )(using F: Async[F]): Resource[F, WriterContext] =
    sinkAutoCloseable(F.blocking(new RootAllocator()))
      .flatMap: allocator =>
        sinkAutoCloseable(
          F.blocking(VectorSchemaRoot.create(schema, allocator))
        )
          .flatMap: root =>
            val output = new ByteArrayOutputStream
            sinkAutoCloseable(
              F.blocking(
                new ArrowStreamWriter(
                  root,
                  null,
                  Channels.newChannel(output)
                )
              )
            ).map(writer => WriterContext(output, root, writer))

  private def readerResource[F[_]](
      bytes: Array[Byte]
  )(using F: Async[F]): Resource[F, ArrowStreamReader] =
    sourceAutoCloseable(F.blocking(new RootAllocator()))
      .flatMap: allocator =>
        sourceAutoCloseable:
          F.blocking(
            new ArrowStreamReader(
              new ByteArrayInputStream(bytes),
              allocator
            )
          )

  private def sinkAutoCloseable[F[_], A <: AutoCloseable](
      acquire: F[A]
  )(using F: Async[F]): Resource[F, A] =
    Resource.make(
      AdapterFailureBoundary.sinkEffect(acquire, SinkError.Write.apply)
    ): value =>
      AdapterFailureBoundary.sinkEffect(
        F.blocking(value.close()),
        SinkError.Close.apply
      )

  private def sourceAutoCloseable[F[_], A <: AutoCloseable](
      acquire: F[A]
  )(using F: Async[F]): Resource[F, A] =
    Resource.make(
      AdapterFailureBoundary.sourceEffect(acquire, SourceError.Open.apply)
    ): value =>
      AdapterFailureBoundary.sourceEffect(
        F.blocking(value.close()),
        SourceError.Close.apply
      )

  private def readBatches[F[_]](
      reader: ArrowStreamReader,
      root: VectorSchemaRoot,
      schema: Schema,
      retained: Ref[F, Vector[RecordBatch]]
  )(using F: Async[F]): F[Unit] =
    AdapterFailureBoundary
      .sourceEffect(
        F.blocking(reader.loadNextBatch()),
        SourceError.Read.apply
      )
      .flatMap:
        case false => F.unit
        case true  =>
          (
            F.uncancelable: _ =>
              AdapterFailureBoundary
                .sourceEffect(
                  F.blocking(decodeBatch(schema, root, new BufferTracker)),
                  SourceError.Read.apply
                )
                .flatMap(result => F.fromEither(result.leftMap(SourceFailure.apply)))
                .flatMap(batch => retained.update(_ :+ batch))
          ) *> readBatches(reader, root, schema, retained)

  private def writeBatch(
      schema: Schema,
      context: WriterContext,
      batch: RecordBatch
  ): Either[SinkError, Long] =
    val root = context.root
    try
      root.allocateNew()
      root.setRowCount(batch.rowCount)
      var column = 0
      var error: Option[SinkError] = None
      while column < schema.size && error.isEmpty do
        val vector = root.getVector(column)
        var row = 0
        while row < batch.rowCount && error.isEmpty do
          batch.columns(column).scalar(row) match
            case Right(value) =>
              set(vector, column, row, value) match
                case Left(value) => error = Some(value)
                case Right(_)    => ()
            case Left(value) => error = Some(SinkError.Storage(value))
          row += 1
        vector.setValueCount(batch.rowCount)
        column += 1
      error match
        case Some(value) => Left(value)
        case None        =>
          context.writer.writeBatch()
          Right(batch.rowCount.toLong)
    catch case NonFatal(error) => Left(SinkError.Write(error))
    finally root.clear()

  private def arrowSchema(schema: Schema): Either[SinkError, ArrowSchema] =
    if schema == null then Left(SinkError.InvalidRequest("Arrow IPC schema cannot be raw null"))
    else
      val fields = schema.fields.map: field =>
        arrowType(field.dataType).map: dataType =>
          new ArrowField(
            field.name,
            new ArrowFieldType(field.nullable, dataType, null, null),
            Collections.emptyList()
          )
      fields
        .foldLeft[Either[SinkError, Vector[ArrowField]]](Right(Vector.empty)):
          case (result, value) => result.flatMap(current => value.map(current :+ _))
        .map(values => new ArrowSchema(values.asJava))

  private def arrowType(dataType: DataType): Either[SinkError, ArrowType] = dataType match
    case DataType.Bool    => Right(ArrowType.Bool.INSTANCE)
    case DataType.Int32   => Right(new ArrowType.Int(32, true))
    case DataType.Int64   => Right(new ArrowType.Int(64, true))
    case DataType.Float32 =>
      Right(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE))
    case DataType.Float64 =>
      Right(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
    case DataType.Utf8                            => Right(ArrowType.Utf8.INSTANCE)
    case DataType.Timestamp(TimeUnit.Microsecond) =>
      Right(new ArrowType.Timestamp(ArrowTimeUnit.MICROSECOND, null))
    case DataType.Timestamp(_) =>
      Left(
        SinkError.InvalidRequest(
          "frame4s-arrow 0.1 supports only timezone-free microsecond timestamps"
        )
      )

  private def frameSchema(schema: ArrowSchema): Either[SourceError, Schema] =
    val fields = schema.getFields.asScala.toVector.map: field =>
      frameType(field.getType).map: dataType =>
        DynamicFrame.field(field.getName, dataType, field.isNullable)
    fields
      .foldLeft[Either[SourceError, Vector[Field]]](Right(Vector.empty)):
        case (result, value) => result.flatMap(current => value.map(current :+ _))
      .flatMap(values => Schema(values).leftMap(error => SourceError.SchemaMismatch(error.message)))

  private def frameType(dataType: ArrowType): Either[SourceError, DataType] = dataType match
    case _: ArrowType.Bool => Right(DataType.Bool)
    case value: ArrowType.Int if value.getBitWidth == 32 && value.getIsSigned =>
      Right(DataType.Int32)
    case value: ArrowType.Int if value.getBitWidth == 64 && value.getIsSigned =>
      Right(DataType.Int64)
    case value: ArrowType.FloatingPoint if value.getPrecision == FloatingPointPrecision.SINGLE =>
      Right(DataType.Float32)
    case value: ArrowType.FloatingPoint if value.getPrecision == FloatingPointPrecision.DOUBLE =>
      Right(DataType.Float64)
    case _: ArrowType.Utf8 => Right(DataType.Utf8)
    case value: ArrowType.Timestamp
        if value.getTimezone == null && value.getUnit == ArrowTimeUnit.MICROSECOND =>
      Right(DataType.Timestamp(TimeUnit.Microsecond))
    case _: ArrowType.Timestamp =>
      Left(
        SourceError.SchemaMismatch(
          "frame4s-arrow 0.1 supports only timezone-free microsecond timestamps"
        )
      )
    case other =>
      Left(SourceError.SchemaMismatch(s"unsupported Arrow IPC type $other"))

  private def set(
      vector: FieldVector,
      column: Int,
      row: Int,
      value: ScalarValue
  ): Either[SinkError, Unit] =
    (vector, value) match
      case (current: BitVector, ScalarValue.Bool(actual)) =>
        current.setSafe(row, if actual then 1 else 0)
        Right(())
      case (current: IntVector, ScalarValue.Int32(actual)) =>
        current.setSafe(row, actual)
        Right(())
      case (current: BigIntVector, ScalarValue.Int64(actual)) =>
        current.setSafe(row, actual)
        Right(())
      case (current: Float4Vector, ScalarValue.Float32(actual)) =>
        current.setSafe(row, actual)
        Right(())
      case (current: Float8Vector, ScalarValue.Float64(actual)) =>
        current.setSafe(row, actual)
        Right(())
      case (current: VarCharVector, ScalarValue.Utf8(actual)) =>
        current.setSafe(row, actual.value.getBytes(StandardCharsets.UTF_8))
        Right(())
      case (current: TimeStampMicroVector, ScalarValue.Timestamp(actual, TimeUnit.Microsecond)) =>
        current.setSafe(row, actual)
        Right(())
      case (current, ScalarValue.Null) => setNull(current, column, row, value)
      case _                           => Left(SinkError.Encode(row.toLong, column, value))

  private def setNull(
      vector: FieldVector,
      column: Int,
      row: Int,
      value: ScalarValue
  ): Either[SinkError, Unit] =
    vector match
      case current: BitVector =>
        current.setNull(row)
        Right(())
      case current: IntVector =>
        current.setNull(row)
        Right(())
      case current: BigIntVector =>
        current.setNull(row)
        Right(())
      case current: Float4Vector =>
        current.setNull(row)
        Right(())
      case current: Float8Vector =>
        current.setNull(row)
        Right(())
      case current: VarCharVector =>
        current.setNull(row)
        Right(())
      case current: TimeStampMicroVector =>
        current.setNull(row)
        Right(())
      case _ => Left(SinkError.Encode(row.toLong, column, value))

  private[fs2] def decodeBatch(
      schema: Schema,
      root: VectorSchemaRoot,
      tracker: BufferTracker
  ): Either[SourceError, RecordBatch] =
    val columns = scala.collection.mutable.ArrayBuffer.empty[ColumnArray]
    var index = 0
    var error: Option[SourceError] = None
    try
      while index < schema.size && error.isEmpty do
        decodeColumn(
          schema.fields(index).dataType,
          root.getVector(index),
          root.getRowCount,
          tracker
        ) match
          case Right(column) => columns += column
          case Left(value)   => error = Some(value)
        index += 1

      error match
        case Some(value) =>
          closeDecoded(columns.toVector) match
            case Some(cause) => Left(SourceError.Close(cause))
            case None        => Left(value)
        case None =>
          val decoded = columns.toVector
          RecordBatch(schema, decoded) match
            case Right(batch) => Right(batch)
            case Left(value)  =>
              closeDecoded(decoded) match
                case Some(cause) => Left(SourceError.Close(cause))
                case None        => Left(SourceError.Storage(value))
    catch
      case NonFatal(cause) =>
        closeDecoded(columns.toVector).foreach(cause.addSuppressed)
        throw cause

  private def closeDecoded(columns: Vector[ColumnArray]): Option[Throwable] =
    var failure: Option[Throwable] = None
    var index = columns.length - 1
    while index >= 0 do
      try columns(index).close()
      catch
        case NonFatal(cause) =>
          failure match
            case Some(first) => first.addSuppressed(cause)
            case None        => failure = Some(cause)
      index -= 1
    failure

  private def decodeColumn(
      dataType: DataType,
      vector: FieldVector,
      length: Int,
      tracker: BufferTracker
  ): Either[SourceError, ColumnArray] =
    val valid = Array.tabulate(length)(index => !vector.isNull(index))
    def invalid: Left[SourceError, Nothing] =
      Left(
        SourceError.SchemaMismatch(s"vector ${vector.getClass.getName} does not match $dataType")
      )
    val decoded: Either[SourceError, Either[StorageError, ColumnArray]] =
      (dataType, vector) match
        case (DataType.Bool, current: BitVector) =>
          Right(
            ColumnArray.bool(
              Array.tabulate(length)(index => valid(index) && current.get(index) != 0),
              valid,
              tracker
            )
          )
        case (DataType.Int32, current: IntVector) =>
          Right(
            ColumnArray.int32(
              Array.tabulate(length)(index => if valid(index) then current.get(index) else 0),
              valid,
              tracker
            )
          )
        case (DataType.Int64, current: BigIntVector) =>
          Right(
            ColumnArray.int64(
              Array.tabulate(length)(index => if valid(index) then current.get(index) else 0L),
              valid,
              tracker
            )
          )
        case (DataType.Float32, current: Float4Vector) =>
          Right(
            ColumnArray.float32(
              Array.tabulate(length)(index => if valid(index) then current.get(index) else 0.0f),
              valid,
              tracker
            )
          )
        case (DataType.Float64, current: Float8Vector) =>
          Right(
            ColumnArray.float64(
              Array.tabulate(length)(index => if valid(index) then current.get(index) else 0.0),
              valid,
              tracker
            )
          )
        case (DataType.Utf8, current: VarCharVector) =>
          Right(
            ColumnArray.utf8(
              Array.tabulate(length): index =>
                if valid(index) then
                  val bytes = current.get(index)
                  if bytes == null then null
                  else new String(bytes, StandardCharsets.UTF_8)
                else "",
              valid,
              tracker
            )
          )
        case (DataType.Timestamp(TimeUnit.Microsecond), current: TimeStampMicroVector) =>
          Right(
            ColumnArray.timestamp(
              Array.tabulate(length)(index => if valid(index) then current.get(index) else 0L),
              TimeUnit.Microsecond,
              valid,
              tracker
            )
          )
        case _ => invalid
    decoded.flatMap(_.leftMap(SourceError.Storage.apply))
