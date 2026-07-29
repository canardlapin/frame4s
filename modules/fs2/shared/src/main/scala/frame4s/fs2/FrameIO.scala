package frame4s.fs2

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Chunk
import fs2.Pull
import fs2.Stream
import fs2.text
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import frame4s.*

enum PushdownFeature:
  case Projection
  case Predicate
  case Limit
  case BatchSize

final case class SourceCapabilities(
    projection: Boolean,
    predicate: Boolean,
    limit: Boolean,
    batchSize: Boolean,
    streaming: Boolean
):
  def supports(feature: PushdownFeature): Boolean = feature match
    case PushdownFeature.Projection => projection
    case PushdownFeature.Predicate  => predicate
    case PushdownFeature.Limit      => limit
    case PushdownFeature.BatchSize  => batchSize

enum PortablePredicate:
  case Equal(column: String, value: LiteralValue)
  case NotEqual(column: String, value: LiteralValue)
  case LessThan(column: String, value: LiteralValue)
  case LessThanOrEqual(column: String, value: LiteralValue)
  case GreaterThan(column: String, value: LiteralValue)
  case GreaterThanOrEqual(column: String, value: LiteralValue)
  case IsNull(column: String)
  case And(left: PortablePredicate, right: PortablePredicate)
  case Or(left: PortablePredicate, right: PortablePredicate)

/** Pushdown request offered to a [[FrameSource]].
  *
  * A source may accept only the features it can implement exactly. It records accepted and residual
  * work in [[PushdownReceipt]]; residual semantics remain the runtime's responsibility.
  */
final case class ScanRequest(
    columns: Vector[String] = Vector.empty,
    predicate: Option[PortablePredicate] = None,
    limit: Option[Long] = None,
    batchSize: Option[Int] = None
):
  def requestedFeatures: Vector[PushdownFeature] =
    Vector(
      Option.when(columns.nonEmpty)(PushdownFeature.Projection),
      predicate.map(_ => PushdownFeature.Predicate),
      limit.map(_ => PushdownFeature.Limit),
      batchSize.map(_ => PushdownFeature.BatchSize)
    ).flatten

final case class PushdownReceipt(
    requested: Vector[PushdownFeature],
    accepted: Vector[PushdownFeature],
    residual: Vector[PushdownFeature],
    columnsRead: Vector[String]
)

/** Structured source inspection, planning, decoding, storage, and lifecycle failures. */
enum SourceError:
  case InvalidRequest(detail: String)
  case MissingColumn(name: String)
  case SchemaMismatch(detail: String)
  case Decode(row: Int, column: Int, value: String, expected: DataType)
  case MalformedCsv(row: Int, detail: String)
  case Storage(error: StorageError)
  case Open(detail: String)
  case Close(detail: String)

  def message: String = this match
    case InvalidRequest(value)                => value
    case MissingColumn(name)                  => s"source column '$name' does not exist"
    case SchemaMismatch(value)                => value
    case Decode(row, column, value, expected) =>
      s"row $row column $column value '$value' cannot be decoded as $expected"
    case MalformedCsv(row, value) => s"malformed CSV row $row: $value"
    case Storage(error)           => error.message
    case Open(value)              => s"source open failed: $value"
    case Close(value)             => s"source close failed: $value"

final case class SourceFailure(error: SourceError) extends RuntimeException(error.message)

final case class SourceInspection(
    schema: Schema,
    capabilities: SourceCapabilities
)

final case class PlannedScan[F[_]](
    schema: Schema,
    receipt: PushdownReceipt,
    batches: Stream[F, RecordBatch]
)

/** A scoped source that can inspect itself and plan a stream of owned record batches.
  *
  * Acquire sources through `Resource`; callers do not invoke `close` directly. Every emitted batch
  * is scoped by the runtime and closes on completion, failure, early termination, or cancellation.
  */
trait FrameSource[F[_]]:
  def inspect: F[Either[SourceError, SourceInspection]]
  def plan(request: ScanRequest): F[Either[SourceError, PlannedScan[F]]]
  private[fs2] def close: F[Either[SourceError, Unit]]

object FrameSource:
  private[fs2] def owningResource[F[_], A <: FrameSource[F]](
      acquire: F[A]
  )(using F: Async[F]): Resource[F, A] =
    Resource.make(acquire): source =>
      source.close.flatMap(result => F.fromEither(result.leftMap(SourceFailure.apply)))

enum SinkError:
  case SchemaMismatch(expected: Schema, actual: Schema)
  case Storage(error: StorageError)
  case Encode(row: Long, column: Int, value: ScalarValue)
  case Write(detail: String)
  case Close(detail: String)

  def message: String = this match
    case SchemaMismatch(expected, actual) =>
      s"sink expected $expected but received $actual"
    case Storage(error)             => error.message
    case Encode(row, column, value) =>
      s"cannot encode row $row column $column value $value"
    case Write(value) => s"sink write failed: $value"
    case Close(value) => s"sink close failed: $value"

final case class SinkReceipt(
    rows: Long,
    batches: Long,
    bytes: Long
)

/** A destination that consumes a schema and a scoped stream of record batches.
  *
  * Implementations return structured [[SinkError]] values and a result-specific receipt rather than
  * hiding partial writes behind exceptions.
  */
trait FrameSink[F[_], Result]:
  def write(schema: Schema, batches: Stream[F, RecordBatch]): F[Either[SinkError, Result]]

/** Re-runnable source backed by validated in-memory record batches.
  *
  * Prefer `rowsBinding` for ordinary typed Scala values. The borrowed constructor leaves batch
  * ownership with the caller; the owned constructor transfers cleanup to the source resource.
  */
final class InMemoryFrameSource[F[_]] private (
    schema: Schema,
    sourceBatches: Vector[RecordBatch],
    ownsBatches: Boolean
)(using F: Async[F])
    extends FrameSource[F]:
  private var closed = false

  val capabilities: SourceCapabilities =
    SourceCapabilities(
      projection = true,
      predicate = false,
      limit = true,
      batchSize = false,
      streaming = true
    )

  def inspect: F[Either[SourceError, SourceInspection]] =
    F.delay:
      synchronized:
        if closed then Left(SourceError.Open("source is closed"))
        else Right(SourceInspection(schema, capabilities))

  def plan(request: ScanRequest): F[Either[SourceError, PlannedScan[F]]] =
    F.delay:
      synchronized:
        if closed then Left(SourceError.Open("source is closed"))
        else
          validate(request).flatMap: selection =>
            val selectedFields = selection.map(schema.fields)
            Schema(selectedFields).left
              .map(error => SourceError.InvalidRequest(error.message))
              .map: output =>
                val requested = request.requestedFeatures
                val accepted = requested.filter(capabilities.supports)
                val residual = requested.filterNot(capabilities.supports)
                PlannedScan(
                  output,
                  PushdownReceipt(
                    requested,
                    accepted,
                    residual,
                    selectedFields.map(_.name)
                  ),
                  scan(selection, output, request.limit)
                )

  private[fs2] def close: F[Either[SourceError, Unit]] =
    F.delay:
      synchronized:
        if !closed then
          closed = true
          if ownsBatches then sourceBatches.foreach(_.close())
        Right(())

  private def validate(request: ScanRequest): Either[SourceError, Vector[Int]] =
    if request.limit.exists(_ < 0) then
      Left(SourceError.InvalidRequest("scan limit must be non-negative"))
    else if request.batchSize.exists(_ <= 0) then
      Left(SourceError.InvalidRequest("scan batch size must be positive"))
    else
      val names =
        if request.columns.isEmpty then schema.fields.map(_.name)
        else request.columns
      names.foldLeft[Either[SourceError, Vector[Int]]](Right(Vector.empty)):
        case (result, name) =>
          result.flatMap: indexes =>
            val index = schema.fields.indexWhere(_.name == name)
            if index < 0 then Left(SourceError.MissingColumn(name))
            else Right(indexes :+ index)

  private def scan(
      selection: Vector[Int],
      output: Schema,
      limit: Option[Long]
  ): Stream[F, RecordBatch] =
    def loop(index: Int, remaining: Long): Stream[F, RecordBatch] =
      if index >= sourceBatches.length || remaining == 0L then Stream.empty
      else
        val source = sourceBatches(index)
        val count = math.min(source.rowCount.toLong, remaining).toInt
        Stream
          .bracket(
            F.fromEither(
              project(source, selection, output, count)
                .leftMap(SourceFailure.apply)
            )
          )(batch => F.delay(batch.close()))
          .flatMap(Stream.emit) ++
          loop(index + 1, remaining - count.toLong)
    loop(0, limit.getOrElse(Long.MaxValue))

  private def project(
      source: RecordBatch,
      selection: Vector[Int],
      output: Schema,
      count: Int
  ): Either[SourceError, RecordBatch] =
    val columns = ArrayBuffer.empty[ColumnArray]
    var index = 0
    var error: Option[SourceError] = None
    while index < selection.length && error.isEmpty do
      source.columns(selection(index)).slice(0, count) match
        case Right(column) => columns += column
        case Left(value)   => error = Some(SourceError.Storage(value))
      index += 1
    error match
      case Some(value) =>
        columns.foreach(_.close())
        Left(value)
      case None =>
        RecordBatch(output, columns.toVector)
          .leftMap(SourceError.Storage.apply)

object InMemoryFrameSource:
  def apply[F[_]: Async](schema: Schema, batches: Vector[RecordBatch]): InMemoryFrameSource[F] =
    new InMemoryFrameSource(schema, batches, ownsBatches = false)

  private[fs2] def owned[F[_]: Async](
      schema: Schema,
      batches: Vector[RecordBatch]
  ): InMemoryFrameSource[F] =
    new InMemoryFrameSource(schema, batches, ownsBatches = true)

  /** Bind detached named-tuple rows without exposing schemas or manual batches.
    *
    * Each runtime acquisition constructs and owns a fresh table, so the binding and its pure frame
    * can be safely reused across invocation scopes.
    */
  def rowsBinding[
      F[_],
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      rows: Iterable[S],
      batchSize: Int = 1024
  )(using
      F: Async[F],
      descriptor: SchemaDescriptor[S],
      codec: RowCodec[S]
  ): SourceBinding[F, S] =
    SourceBinding(reference, rowsResource(rows, batchSize))

  /** Bind detached named-tuple rows for one-source execution.
    *
    * Use [[rowsBinding]] with an explicit [[SourceRef]] when the binding will participate in a
    * multi-source runtime.
    */
  def rows[
      F[_],
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      rows: Iterable[S],
      batchSize: Int = 1024
  )(using
      F: Async[F],
      descriptor: SchemaDescriptor[S],
      codec: RowCodec[S]
  ): SourceBinding[F, S] =
    SourceBinding.singleSource(
      SourceRef.singleSourceValues,
      rowsResource(rows, batchSize)
    )

  private def rowsResource[
      F[_],
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      rows: Iterable[S],
      batchSize: Int
  )(using
      F: Async[F],
      descriptor: SchemaDescriptor[S],
      codec: RowCodec[S]
  ): Resource[F, InMemoryFrameSource[F]] =
    val table =
      Resource.make(
        F.delay(Table.fromRows(rows, batchSize))
          .flatMap(result => F.fromEither(result.left.map(TableReadFailure.apply)))
      )(value => F.delay(value.close()))
    val source = table.flatMap: value =>
      FrameSource.owningResource(
        F.delay(InMemoryFrameSource[F](value.schema, value.batches))
      )
    source

  /** Borrow an existing table for the runtime scope; the caller remains its owner. */
  def borrowedBinding[
      F[_],
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      table: Table[S]
  )(using
      F: Async[F],
      descriptor: SchemaDescriptor[S]
  ): SourceBinding[F, S] =
    SourceBinding(
      reference,
      FrameSource.owningResource(
        F.delay(InMemoryFrameSource[F](table.schema, table.batches))
      )
    )

enum CsvCoercion:
  case Strict
  case TrimWhitespace

/** Runtime CSV decoding options paired with an explicit [[Schema]]. */
final case class CsvReadOptions(
    schema: Schema,
    delimiter: Char = ',',
    header: Boolean = true,
    nullTokens: Set[String] = Set("", "null"),
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024
)

/** Typed CSV settings omit the runtime schema because it is derived from the named-tuple type. */
/** Typed CSV settings used by the `binding` convenience constructors.
  *
  * The named-tuple [[SchemaDescriptor]] supplies the schema; no runtime inference is treated as
  * compile-time evidence.
  */
final case class CsvSettings(
    delimiter: Char = ',',
    header: Boolean = true,
    nullTokens: Set[String] = Set("", "null"),
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024
):
  private[fs2] def options(schema: Schema): CsvReadOptions =
    CsvReadOptions(schema, delimiter, header, nullTokens, coercion, batchSize)

/** Portable incremental CSV source for strings, bytes, or characters.
  *
  * Parsing begins when the planned batch stream runs. The source retains only incremental parser
  * state and emits bounded batches according to the accepted request.
  */
final class CsvFrameSource[F[_]] private (
    input: Stream[F, Char],
    options: CsvReadOptions
)(using F: Async[F])
    extends FrameSource[F]:
  private var closed = false

  private val capabilities = SourceCapabilities(
    projection = false,
    predicate = false,
    limit = true,
    batchSize = true,
    streaming = true
  )

  def inspect: F[Either[SourceError, SourceInspection]] =
    F.delay:
      synchronized:
        validateOptions.map(_ => SourceInspection(options.schema, capabilities))

  def plan(request: ScanRequest): F[Either[SourceError, PlannedScan[F]]] =
    F.delay:
      synchronized:
        validateOptions.flatMap: _ =>
          validateRequest(request).map: _ =>
            val requested = request.requestedFeatures
            val accepted = requested.filter(capabilities.supports)
            val residual = requested.filterNot(capabilities.supports)
            val batchSize = request.batchSize.getOrElse(options.batchSize)
            PlannedScan(
              options.schema,
              PushdownReceipt(
                requested,
                accepted,
                residual,
                options.schema.fields.map(_.name)
              ),
              CsvStreaming.batches(
                input,
                options.copy(batchSize = batchSize),
                request.limit
              )
            )

  private[fs2] def close: F[Either[SourceError, Unit]] =
    F.delay:
      synchronized:
        closed = true
        Right(())

  private def validateOptions: Either[SourceError, Unit] =
    if closed then Left(SourceError.Open("source is closed"))
    else if options.batchSize <= 0 then
      Left(SourceError.InvalidRequest("CSV batch size must be positive"))
    else if options.delimiter == '"' || options.delimiter == '\r' || options.delimiter == '\n' then
      Left(SourceError.InvalidRequest("CSV delimiter cannot be a quote or line break"))
    else Right(())

  private def validateRequest(request: ScanRequest): Either[SourceError, Unit] =
    if request.limit.exists(_ < 0L) then
      Left(SourceError.InvalidRequest("scan limit must be non-negative"))
    else if request.batchSize.exists(_ <= 0) then
      Left(SourceError.InvalidRequest("scan batch size must be positive"))
    else
      request.columns.find(name => options.schema.field(name).isEmpty) match
        case Some(name) => Left(SourceError.MissingColumn(name))
        case None       => Right(())

/** Resource and typed-binding constructors for portable CSV input. */
object CsvFrameSource:
  /** Clearly bounded convenience for an in-memory string. Decoding remains incremental and starts
    * only when the planned batch stream is run.
    */
  def resource[F[_]](
      input: String,
      options: CsvReadOptions
  )(using F: Async[F]): Resource[F, CsvFrameSource[F]] =
    characters(Stream.emits(input.toVector).covary[F], options)

  /** Portable incremental UTF-8 byte-stream acquisition. */
  def bytes[F[_]](
      input: Stream[F, Byte],
      options: CsvReadOptions
  )(using F: Async[F]): Resource[F, CsvFrameSource[F]] =
    characters(
      input
        .through(text.utf8.decode)
        .flatMap(value => Stream.emits(value.toVector)),
      options
    )

  /** Portable incremental character-stream acquisition. */
  def characters[F[_]](
      input: Stream[F, Char],
      options: CsvReadOptions
  )(using F: Async[F]): Resource[F, CsvFrameSource[F]] =
    FrameSource.owningResource:
      F.delay(new CsvFrameSource(input, options))

  def binding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      input: String,
      settings: CsvSettings = CsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding(reference, resource(input, settings.options(descriptor.schema)))

  /** Describe one typed in-memory CSV source without choosing a multi-source identity. */
  def binding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      input: String
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    binding(input, CsvSettings())

  def binding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      input: String,
      settings: CsvSettings
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding.singleSource(
      SourceRef.singleSourceScan,
      resource(input, settings.options(descriptor.schema))
    )

  def byteBinding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      input: Stream[F, Byte],
      settings: CsvSettings = CsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding(reference, bytes(input, settings.options(descriptor.schema)))

  def characterBinding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      input: Stream[F, Char],
      settings: CsvSettings = CsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding(reference, characters(input, settings.options(descriptor.schema)))

final case class CsvWriteResult(
    text: String,
    receipt: SinkReceipt
)

/** In-memory CSV encoder that returns text plus row, batch, and byte counts. */
final class CsvFrameSink[F[_]](
    delimiter: Char = ',',
    includeHeader: Boolean = true,
    nullValue: String = ""
)(using F: Async[F])
    extends FrameSink[F, CsvWriteResult]:
  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, CsvWriteResult]] =
    batches
      .evalMap: batch =>
        F.blocking(
          CsvCodec.encode(schema, Vector(batch), delimiter, includeHeader = false, nullValue)
        )
      .compile
      .toVector
      .map: encoded =>
        encoded.foldLeft[Either[SinkError, Vector[CsvWriteResult]]](Right(Vector.empty)):
          case (result, value) => result.flatMap(values => value.map(values :+ _))
      .map:
        _.map: parts =>
          val header =
            if includeHeader then CsvCodec.header(schema, delimiter)
            else ""
          val text = header + parts.map(_.text).mkString
          CsvWriteResult(
            text,
            SinkReceipt(
              parts.map(_.receipt.rows).sum,
              parts.map(_.receipt.batches).sum,
              text.length.toLong
            )
          )

final case class TsvReadOptions(
    schema: Schema,
    header: Boolean = true,
    nullTokens: Set[String] = Set("", "null"),
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024
):
  private[fs2] def csvOptions: CsvReadOptions =
    CsvReadOptions(
      schema = schema,
      delimiter = '\t',
      header = header,
      nullTokens = nullTokens,
      coercion = coercion,
      batchSize = batchSize
    )

/** Typed TSV settings; semantics match [[CsvSettings]] with a tab delimiter. */
final case class TsvSettings(
    header: Boolean = true,
    nullTokens: Set[String] = Set("", "null"),
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024
):
  private[fs2] def csvSettings: CsvSettings =
    CsvSettings('\t', header, nullTokens, coercion, batchSize)

/** Portable TSV source implemented by the same incremental parser and ownership rules as CSV. */
final class TsvFrameSource[F[_]] private (
    delegate: CsvFrameSource[F]
) extends FrameSource[F]:
  def inspect: F[Either[SourceError, SourceInspection]] = delegate.inspect
  def plan(request: ScanRequest): F[Either[SourceError, PlannedScan[F]]] =
    delegate.plan(request)
  private[fs2] def close: F[Either[SourceError, Unit]] = delegate.close

/** Resource and typed-binding constructors for portable TSV input. */
object TsvFrameSource:
  def resource[F[_]](
      input: String,
      options: TsvReadOptions
  )(using F: Async[F]): Resource[F, TsvFrameSource[F]] =
    CsvFrameSource
      .resource(input, options.csvOptions)
      .map(new TsvFrameSource(_))

  def bytes[F[_]](
      input: Stream[F, Byte],
      options: TsvReadOptions
  )(using F: Async[F]): Resource[F, TsvFrameSource[F]] =
    CsvFrameSource
      .bytes(input, options.csvOptions)
      .map(new TsvFrameSource(_))

  def characters[F[_]](
      input: Stream[F, Char],
      options: TsvReadOptions
  )(using F: Async[F]): Resource[F, TsvFrameSource[F]] =
    CsvFrameSource
      .characters(input, options.csvOptions)
      .map(new TsvFrameSource(_))

  def binding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      input: String,
      settings: TsvSettings = TsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    CsvFrameSource.binding(reference, input, settings.csvSettings)

  /** Describe one typed in-memory TSV source without choosing a multi-source identity. */
  def binding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      input: String
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    binding(input, TsvSettings())

  def binding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      input: String,
      settings: TsvSettings
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    CsvFrameSource.binding(input, settings.csvSettings)

  def byteBinding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      input: Stream[F, Byte],
      settings: TsvSettings = TsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    CsvFrameSource.byteBinding(reference, input, settings.csvSettings)

  def characterBinding[
      F[_]: Async,
      S <: scala.NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      input: Stream[F, Char],
      settings: TsvSettings = TsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    CsvFrameSource.characterBinding(reference, input, settings.csvSettings)

final case class TsvWriteResult(
    text: String,
    receipt: SinkReceipt
)

final class TsvFrameSink[F[_]](
    includeHeader: Boolean = true,
    nullValue: String = ""
)(using F: Async[F])
    extends FrameSink[F, TsvWriteResult]:
  private val delegate =
    new CsvFrameSink[F](
      delimiter = '\t',
      includeHeader = includeHeader,
      nullValue = nullValue
    )

  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, TsvWriteResult]] =
    delegate
      .write(schema, batches)
      .map(_.map(result => TsvWriteResult(result.text, result.receipt)))

/** JVM capability boundary for Arrow IPC sources and writes.
  *
  * The portable FS2 module exposes the contract; the Apache Arrow implementation is available only
  * from the JVM artifact.
  */
trait ArrowIpcPlatform[F[_]] extends FrameSource[F]:
  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, SinkReceipt]]

final private case class CsvRecord(number: Int, values: Vector[String])

/** Per-compilation CSV state machine. It retains only the current record plus completed records
  * from the current input chunk, so retained input is bounded by the largest in-progress record and
  * the downstream batch size.
  */
final private class CsvParser(delimiter: Char):
  private val fields = ArrayBuffer.empty[String]
  private val field = new StringBuilder
  private var quoted = false
  private var afterQuote = false
  private var rowStarted = false
  private var skipLineFeed = false
  private var recordNumber = 1

  def feed(chunk: Chunk[Char]): Either[SourceError, Vector[CsvRecord]] =
    val completed = Vector.newBuilder[CsvRecord]
    val iterator = chunk.iterator
    var error: Option[SourceError] = None
    while iterator.hasNext && error.isEmpty do
      val current = iterator.next()
      if skipLineFeed then
        skipLineFeed = false
        if current != '\n' then
          consume(current, completed) match
            case Some(value) => error = Some(value)
            case None        => ()
      else
        consume(current, completed) match
          case Some(value) => error = Some(value)
          case None        => ()
    error.toLeft(completed.result())

  def finish(): Either[SourceError, Vector[CsvRecord]] =
    if quoted then Left(SourceError.MalformedCsv(recordNumber, "unterminated quoted field"))
    else if rowStarted || fields.nonEmpty || field.nonEmpty || afterQuote then
      Right(Vector(finishRecord()))
    else Right(Vector.empty)

  private def consume(
      current: Char,
      completed: scala.collection.mutable.Builder[CsvRecord, Vector[CsvRecord]]
  ): Option[SourceError] =
    if quoted then
      if current == '"' then
        quoted = false
        afterQuote = true
      else
        field.append(current)
        rowStarted = true
      None
    else if afterQuote then
      current match
        case '"' =>
          field.append('"')
          quoted = true
          afterQuote = false
          rowStarted = true
          None
        case value if value == delimiter =>
          finishField()
          afterQuote = false
          None
        case '\n' =>
          completed += finishRecord()
          None
        case '\r' =>
          completed += finishRecord()
          skipLineFeed = true
          None
        case value =>
          Some(
            SourceError.MalformedCsv(
              recordNumber,
              s"unexpected '$value' after closing quote"
            )
          )
    else
      current match
        case '"' if field.isEmpty =>
          quoted = true
          rowStarted = true
          None
        case '"' =>
          Some(SourceError.MalformedCsv(recordNumber, "quote inside unquoted field"))
        case value if value == delimiter =>
          finishField()
          rowStarted = true
          None
        case '\n' =>
          completed += finishRecord()
          None
        case '\r' =>
          completed += finishRecord()
          skipLineFeed = true
          None
        case value =>
          field.append(value)
          rowStarted = true
          None

  private def finishField(): Unit =
    fields += field.result()
    field.clear()

  private def finishRecord(): CsvRecord =
    finishField()
    val result = CsvRecord(recordNumber, fields.toVector)
    fields.clear()
    field.clear()
    quoted = false
    afterQuote = false
    rowStarted = false
    recordNumber += 1
    result

private object CsvStreaming:
  def batches[F[_]](
      input: Stream[F, Char],
      options: CsvReadOptions,
      limit: Option[Long]
  )(using F: Async[F]): Stream[F, RecordBatch] =
    val records =
      Stream
        .eval(F.delay(new CsvParser(options.delimiter)))
        .flatMap: parser =>
          val chunks =
            input.chunks
              .evalMap(chunk => F.fromEither(parser.feed(chunk).leftMap(SourceFailure.apply)))
              .flatMap(Stream.emits)
          chunks ++ Stream
            .eval(
              F.fromEither(parser.finish().leftMap(SourceFailure.apply))
            )
            .flatMap(Stream.emits)

    val data =
      if options.header then validateHeader(records, options.schema)
      else records
    val limited = limit.fold(data)(data.take)

    limited
      .chunkN(options.batchSize, allowFewer = true)
      .evalMap: chunk =>
        F.fromEither(
          CsvCodec
            .buildBatch(options, chunk.toVector)
            .leftMap(SourceFailure.apply)
        )
      .flatMap: batch =>
        Stream
          .bracket(F.pure(batch))(value => F.delay(value.close()))
          .flatMap(Stream.emit)

  private def validateHeader[F[_]](
      records: Stream[F, CsvRecord],
      schema: Schema
  )(using F: Async[F]): Stream[F, CsvRecord] =
    records.pull.uncons1
      .flatMap:
        case None =>
          Pull.raiseError(SourceFailure(SourceError.MalformedCsv(1, "missing header")))
        case Some((header, tail)) =>
          val expected = schema.fields.map(_.name)
          if header.values == expected then tail.pull.echo
          else
            Pull.raiseError(
              SourceFailure(
                SourceError.SchemaMismatch(
                  s"CSV header ${header.values.mkString(",")} does not match ${expected.mkString(",")}"
                )
              )
            )
      .stream

private object CsvCodec:
  def header(schema: Schema, delimiter: Char): String =
    schema.fields.map(field => quote(field.name, delimiter)).mkString(delimiter.toString) + "\n"

  private[fs2] def buildBatch(
      options: CsvReadOptions,
      rows: Vector[CsvRecord]
  ): Either[SourceError, RecordBatch] =
    rows.find(_.values.length != options.schema.size) match
      case Some(row) =>
        Left(
          SourceError.MalformedCsv(
            row.number,
            s"expected ${options.schema.size} fields but found ${row.values.length}"
          )
        )
      case None =>
        val columns = options.schema.fields.zipWithIndex.map: (field, column) =>
          val values = rows.map: row =>
            val raw = row.values(column)
            val value =
              if options.coercion == CsvCoercion.TrimWhitespace then raw.trim
              else raw
            (value, row.number)
          decodeColumn(field, column, values, options.nullTokens)
        sequence(columns).flatMap: decoded =>
          RecordBatch(options.schema, decoded).leftMap(SourceError.Storage.apply)

  private def decodeColumn(
      field: Field,
      column: Int,
      values: Vector[(String, Int)],
      nullTokens: Set[String]
  ): Either[SourceError, ColumnArray] =
    val valid = values.map((value, _) => !nullTokens.contains(value)).toArray
    def decode[A: ClassTag](
        expected: DataType
    )(parser: String => Option[A]): Either[SourceError, Array[A]] =
      val output = new Array[A](values.length)
      var index = 0
      var error: Option[SourceError] = None
      while index < values.length && error.isEmpty do
        val (value, row) = values(index)
        if valid(index) then
          parser(value) match
            case Some(decoded) => output(index) = decoded
            case None          => error = Some(SourceError.Decode(row, column + 1, value, expected))
        index += 1
      error match
        case Some(value) => Left(value)
        case None        => Right(output)

    field.dataType match
      case DataType.Bool =>
        decode(DataType.Bool):
          case "true"  => Some(true)
          case "false" => Some(false)
          case _       => None
        .flatMap(values => ColumnArray.bool(values, valid).leftMap(SourceError.Storage.apply))
      case DataType.Int32 =>
        decode(DataType.Int32)(_.toIntOption)
          .flatMap(values => ColumnArray.int32(values, valid).leftMap(SourceError.Storage.apply))
      case DataType.Int64 =>
        decode(DataType.Int64)(_.toLongOption)
          .flatMap(values => ColumnArray.int64(values, valid).leftMap(SourceError.Storage.apply))
      case DataType.Float32 =>
        decode(DataType.Float32)(_.toFloatOption)
          .flatMap(values => ColumnArray.float32(values, valid).leftMap(SourceError.Storage.apply))
      case DataType.Float64 =>
        decode(DataType.Float64)(_.toDoubleOption)
          .flatMap(values => ColumnArray.float64(values, valid).leftMap(SourceError.Storage.apply))
      case DataType.Utf8 =>
        val decoded = values.map(_._1).toArray
        ColumnArray.utf8(decoded, valid).leftMap(SourceError.Storage.apply)
      case DataType.Timestamp(unit) =>
        decode(DataType.Timestamp(unit))(_.toLongOption)
          .flatMap(values =>
            ColumnArray.timestamp(values, unit, valid).leftMap(SourceError.Storage.apply)
          )

  def encode(
      schema: Schema,
      batches: Vector[RecordBatch],
      delimiter: Char,
      includeHeader: Boolean,
      nullValue: String
  ): Either[SinkError, CsvWriteResult] =
    batches.find(_.schema != schema) match
      case Some(batch) => Left(SinkError.SchemaMismatch(schema, batch.schema))
      case None        =>
        val output = new StringBuilder
        if includeHeader then output.append(header(schema, delimiter))
        var rows = 0L
        var batchIndex = 0
        var error: Option[SinkError] = None
        while batchIndex < batches.length && error.isEmpty do
          val batch = batches(batchIndex)
          var row = 0
          while row < batch.rowCount && error.isEmpty do
            val encoded = new Array[String](schema.size)
            var column = 0
            while column < schema.size && error.isEmpty do
              batch.columns(column).scalar(row) match
                case Right(ScalarValue.Null) => encoded(column) = nullValue
                case Right(value)            => encoded(column) = quote(render(value), delimiter)
                case Left(value)             => error = Some(SinkError.Storage(value))
              column += 1
            if error.isEmpty then
              output.append(encoded.mkString(delimiter.toString))
              output.append('\n')
              rows += 1
            row += 1
          batchIndex += 1
        error match
          case Some(value) => Left(value)
          case None        =>
            val text = output.result()
            Right(
              CsvWriteResult(text, SinkReceipt(rows, batches.length.toLong, text.length.toLong))
            )

  private def render(value: ScalarValue): String = value match
    case ScalarValue.Null                 => ""
    case ScalarValue.Bool(actual)         => actual.toString
    case ScalarValue.Int32(actual)        => actual.toString
    case ScalarValue.Int64(actual)        => actual.toString
    case ScalarValue.Float32(actual)      => actual.toString
    case ScalarValue.Float64(actual)      => actual.toString
    case ScalarValue.Utf8(actual)         => actual
    case ScalarValue.Timestamp(actual, _) => actual.toString

  private def quote(value: String, delimiter: Char): String =
    if value.exists(character =>
        character == delimiter || character == '"' || character == '\n' || character == '\r'
      )
    then s"\"${value.replace("\"", "\"\"")}\""
    else value

  private def sequence[A](values: Vector[Either[SourceError, A]]): Either[SourceError, Vector[A]] =
    values.foldLeft[Either[SourceError, Vector[A]]](Right(Vector.empty)):
      case (result, value) => result.flatMap(current => value.map(current :+ _))
