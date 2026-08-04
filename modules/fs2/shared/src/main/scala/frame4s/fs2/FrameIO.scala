package frame4s.fs2

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Chunk
import fs2.Pull
import fs2.Stream
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import frame4s.*

private[fs2] object AdapterMessage:
  val MaximumLength = 256

  def bounded(value: String): String =
    val normalized = Option(value)
      .getOrElse("<missing detail>")
      .map: character =>
        if character < ' ' || character == '\u007f' then ' ' else character
    if normalized.length <= MaximumLength then normalized
    else normalized.take(MaximumLength - 3) + "..."

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

/** Exact position in decoded delimited text.
  *
  * Records and fields are one-based. `offset` is the zero-based number of decoded `Char` values
  * consumed before this position, including a stripped transport BOM when one is present.
  */
final case class SourceLocation(record: Long, field: Int, offset: Long)

/** Finite diagnostic text retained from a source.
  *
  * The text is available for programmatic inspection, but `toString` deliberately redacts it so an
  * excerpt cannot leak through ordinary exception, log, or assertion rendering.
  */
final class SourceExcerpt private (
    val text: String,
    val startOffset: Long,
    val truncatedBefore: Boolean,
    val truncatedAfter: Boolean
):
  override def equals(other: Any): Boolean = other match
    case that: SourceExcerpt =>
      text == that.text &&
      startOffset == that.startOffset &&
      truncatedBefore == that.truncatedBefore &&
      truncatedAfter == that.truncatedAfter
    case _ => false

  override def hashCode(): Int =
    var result = text.hashCode()
    result = 31 * result + startOffset.hashCode()
    result = 31 * result + truncatedBefore.hashCode()
    31 * result + truncatedAfter.hashCode()

  override def toString: String =
    s"SourceExcerpt(<redacted>,start=$startOffset,length=${text.length}," +
      s"truncatedBefore=$truncatedBefore,truncatedAfter=$truncatedAfter)"

private[fs2] object SourceExcerpt:
  def apply(
      text: String,
      startOffset: Long,
      truncatedBefore: Boolean,
      truncatedAfter: Boolean
  ): SourceExcerpt =
    new SourceExcerpt(text, startOffset, truncatedBefore, truncatedAfter)

/** Structured source inspection, planning, decoding, storage, and lifecycle failures.
  *
  * Operational cases retain their original throwable in [[cause]], but [[message]] and `toString`
  * never render it. Public messages are deterministic and bounded to 256 characters.
  */
enum SourceError:
  case InvalidRequest(detail: String)
  case MissingColumn(name: String)
  case SchemaMismatch(detail: String)
  case Decode(location: SourceLocation, excerpt: SourceExcerpt, expected: DataType)
  case MalformedDelimited(location: SourceLocation, detail: String, excerpt: SourceExcerpt)
  case Storage(error: StorageError)
  case Open(underlying: Throwable)
  case Read(underlying: Throwable)
  case InvalidUtf8(byteOffset: Long, underlying: Throwable)
  case Upstream(underlying: Throwable)
  case Close(underlying: Throwable)
  case Closed

  def cause: Option[Throwable] = this match
    case Open(underlying)           => Option(underlying)
    case Read(underlying)           => Option(underlying)
    case InvalidUtf8(_, underlying) => Option(underlying)
    case Upstream(underlying)       => Option(underlying)
    case Close(underlying)          => Option(underlying)
    case _                          => None

  def message: String =
    AdapterMessage.bounded:
      this match
        case InvalidRequest(value)         => value
        case MissingColumn(name)           => s"source column '$name' does not exist"
        case SchemaMismatch(value)         => value
        case Decode(location, _, expected) =>
          s"${renderLocation(location)} value <redacted> cannot be decoded as $expected"
        case MalformedDelimited(location, detail, _) =>
          s"malformed delimited input at ${renderLocation(location)}: $detail"
        case Storage(error)             => error.message
        case Open(_)                    => "source open failed"
        case Read(_)                    => "source read failed"
        case InvalidUtf8(byteOffset, _) =>
          s"source contains malformed UTF-8 at byte $byteOffset"
        case Upstream(_) => "source upstream failed"
        case Close(_)    => "source close failed"
        case Closed      => "source is closed"

  private def renderLocation(location: SourceLocation): String =
    s"record ${location.record} field ${location.field} offset ${location.offset}"

  override def toString: String = message

/** Effect-channel wrapper used after source acquisition or streaming begins. */
final class SourceFailure private (val error: SourceError)
    extends RuntimeException(error.message, error.cause.orNull)

object SourceFailure:
  def apply(error: SourceError): SourceFailure = new SourceFailure(error)

  def unapply(failure: SourceFailure): Some[SourceError] = Some(failure.error)

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
    Resource.make(
      AdapterFailureBoundary.sourceEffect(acquire, SourceError.Open.apply)
    ): source =>
      AdapterFailureBoundary
        .sourceEffect(source.close, SourceError.Close.apply)
        .flatMap(result => F.fromEither(result.leftMap(SourceFailure.apply)))

/** Structured sink encoding, upstream, write, and lifecycle failures.
  *
  * Operational cases retain their original throwable in [[cause]], while ordinary public
  * diagnostics use the bounded, cause-free [[message]].
  */
enum SinkError:
  case InvalidRequest(detail: String)
  case SchemaMismatch(expected: Schema, actual: Schema)
  case Storage(error: StorageError)
  case Encode(row: Long, column: Int, value: ScalarValue)
  case Write(underlying: Throwable)
  case Upstream(underlying: Throwable)
  case Close(underlying: Throwable)

  def cause: Option[Throwable] = this match
    case Write(underlying)    => Option(underlying)
    case Upstream(underlying) => Option(underlying)
    case Close(underlying)    => Option(underlying)
    case _                    => None

  def message: String =
    AdapterMessage.bounded:
      this match
        case InvalidRequest(detail)           => detail
        case SchemaMismatch(expected, actual) =>
          s"sink expected $expected but received $actual"
        case Storage(error)         => error.message
        case Encode(row, column, _) =>
          s"cannot encode row $row column $column value <redacted>"
        case Write(_)    => "sink write failed"
        case Upstream(_) => "sink upstream failed"
        case Close(_)    => "sink close failed"

  override def toString: String = message

/** Internal effect-channel carrier used while a sink assembles its declared `Either` result. */
final class SinkFailure private (val error: SinkError)
    extends RuntimeException(error.message, error.cause.orNull)

object SinkFailure:
  def apply(error: SinkError): SinkFailure = new SinkFailure(error)

  def unapply(failure: SinkFailure): Some[SinkError] = Some(failure.error)

private[fs2] object AdapterFailureBoundary:
  def isOrdinary(cause: Throwable): Boolean = NonFatal(cause)

  def sourceEffect[F[_], A](
      effect: F[A],
      classify: Throwable => SourceError
  )(using F: Async[F]): F[A] =
    effect.handleErrorWith:
      case failure: SourceFailure     => F.raiseError(failure)
      case cause if isOrdinary(cause) => F.raiseError(SourceFailure(classify(cause)))
      case fatal                      => F.raiseError(fatal)

  def sourceStream[F[_], A](
      stream: Stream[F, A],
      classify: Throwable => SourceError
  )(using F: Async[F]): Stream[F, A] =
    stream.handleErrorWith:
      case failure: SourceFailure     => Stream.raiseError[F](failure)
      case cause if isOrdinary(cause) =>
        Stream.raiseError[F](SourceFailure(classify(cause)))
      case fatal => Stream.raiseError[F](fatal)

  def sourceEither[F[_], A](
      effect: F[A],
      classify: Throwable => SourceError
  )(using F: Async[F]): F[Either[SourceError, A]] =
    effect
      .map(Right.apply)
      .handleErrorWith:
        case failure: SourceFailure     => F.pure(Left(failure.error))
        case cause if isOrdinary(cause) => F.pure(Left(classify(cause)))
        case fatal                      => F.raiseError(fatal)

  def sinkEffect[F[_], A](
      effect: F[A],
      classify: Throwable => SinkError
  )(using F: Async[F]): F[A] =
    effect.handleErrorWith:
      case failure: SinkFailure       => F.raiseError(failure)
      case cause if isOrdinary(cause) => F.raiseError(SinkFailure(classify(cause)))
      case fatal                      => F.raiseError(fatal)

  def sinkEither[F[_], A](
      effect: F[Either[SinkError, A]],
      classify: Throwable => SinkError
  )(using F: Async[F]): F[Either[SinkError, A]] =
    effect.handleErrorWith:
      case failure: SinkFailure       => F.pure(Left(failure.error))
      case cause if isOrdinary(cause) => F.pure(Left(classify(cause)))
      case fatal                      => F.raiseError(fatal)

final case class SinkReceipt(
    rows: Long,
    batches: Long,
    bytes: Long
)

/** A destination that consumes a schema and a scoped stream of record batches.
  *
  * Built-in implementations return every ordinary nonfatal upstream, encoding, write, and close
  * failure as [[SinkError]] with a result-specific receipt. Cancellation and fatal platform errors
  * remain on the effect runtime. A failure does not imply rollback of bytes already written.
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
        if closed then Left(SourceError.Closed)
        else Right(SourceInspection(schema, capabilities))

  def plan(request: ScanRequest): F[Either[SourceError, PlannedScan[F]]] =
    F.delay:
      synchronized:
        if closed then Left(SourceError.Closed)
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

/** Construction failures for a quote-aware delimited null policy. */
enum NullPolicyError:
  case EmptyTokens
  case NullToken
  case NullWriteToken
  case WriteTokenNotRecognized(token: String)

  def message: String = this match
    case EmptyTokens                => "a null policy must recognize at least one unquoted token"
    case NullToken                  => "a null token cannot be raw null"
    case NullWriteToken             => "the null write token cannot be raw null"
    case WriteTokenNotRecognized(_) =>
      "the null write token must be one of the recognized unquoted tokens"

  override def toString: String = message

/** Quote-aware null recognition for CSV and TSV.
  *
  * Only unquoted cells can be null. A quoted cell is always data, even when its decoded text equals
  * a configured token. [[writeToken]] selects the token emitted for absent values.
  */
final class NullPolicy private (
    val unquotedTokens: Set[String],
    val writeToken: String
):
  private[fs2] def recognizes(value: String): Boolean = unquotedTokens.contains(value)

  private[fs2] def collidesWithData(value: String): Boolean =
    value.isEmpty ||
      value.trim != value ||
      unquotedTokens.contains(value) ||
      unquotedTokens.contains(value.trim)

  override def equals(other: Any): Boolean = other match
    case that: NullPolicy =>
      unquotedTokens == that.unquotedTokens && writeToken == that.writeToken
    case _ => false

  override def hashCode(): Int = 31 * unquotedTokens.hashCode() + writeToken.hashCode()

  override def toString: String =
    s"NullPolicy(unquotedTokens=<redacted:${unquotedTokens.size}>,writeToken=<redacted>)"

object NullPolicy:
  val default: NullPolicy = new NullPolicy(Set("", "null"), "")

  def unquotedTokens(
      tokens: Set[String],
      writeToken: String
  ): Either[NullPolicyError, NullPolicy] =
    if tokens == null then Left(NullPolicyError.NullToken)
    else if tokens.isEmpty then Left(NullPolicyError.EmptyTokens)
    else if tokens.exists(_ == null) then Left(NullPolicyError.NullToken)
    else if writeToken == null then Left(NullPolicyError.NullWriteToken)
    else if !tokens.contains(writeToken) then
      Left(NullPolicyError.WriteTokenNotRecognized(writeToken))
    else Right(new NullPolicy(tokens, writeToken))

/** Construction failures for finite CSV and TSV reader bounds. */
enum DelimitedLimitError:
  case NonPositiveRecord(value: Int)
  case NonPositiveField(value: Int)
  case FieldExceedsRecord(field: Int, record: Int)
  case NonPositiveErrorExcerpt(value: Int)

  def message: String = this match
    case NonPositiveRecord(value) =>
      s"maximum record size must be positive, found $value"
    case NonPositiveField(value) =>
      s"maximum field size must be positive, found $value"
    case FieldExceedsRecord(field, record) =>
      s"maximum field size $field cannot exceed maximum record size $record"
    case NonPositiveErrorExcerpt(value) =>
      s"maximum error excerpt size must be positive, found $value"

  override def toString: String = message

/** Finite state and diagnostic bounds for CSV and TSV readers.
  *
  * Bounds count decoded `Char` values rather than bytes. Record size includes delimiters, quotes,
  * and quoted line breaks but excludes the terminating line break. Field size counts decoded field
  * text. Use [[DelimitedReadLimits.create]] for custom validated bounds.
  */
final class DelimitedReadLimits private (
    val maxRecordChars: Int,
    val maxFieldChars: Int,
    val maxErrorExcerptChars: Int
):
  override def equals(other: Any): Boolean = other match
    case that: DelimitedReadLimits =>
      maxRecordChars == that.maxRecordChars &&
      maxFieldChars == that.maxFieldChars &&
      maxErrorExcerptChars == that.maxErrorExcerptChars
    case _ => false

  override def hashCode(): Int =
    31 * (31 * maxRecordChars + maxFieldChars) + maxErrorExcerptChars

  override def toString: String =
    s"DelimitedReadLimits($maxRecordChars,$maxFieldChars,$maxErrorExcerptChars)"

object DelimitedReadLimits:
  final val DefaultMaxRecordChars: Int = 16 * 1024 * 1024
  final val DefaultMaxFieldChars: Int = 4 * 1024 * 1024
  final val DefaultMaxErrorExcerptChars: Int = 160

  val default: DelimitedReadLimits =
    new DelimitedReadLimits(
      DefaultMaxRecordChars,
      DefaultMaxFieldChars,
      DefaultMaxErrorExcerptChars
    )

  def create(
      maxRecordChars: Int = DefaultMaxRecordChars,
      maxFieldChars: Int = DefaultMaxFieldChars,
      maxErrorExcerptChars: Int = DefaultMaxErrorExcerptChars
  ): Either[DelimitedLimitError, DelimitedReadLimits] =
    if maxRecordChars <= 0 then Left(DelimitedLimitError.NonPositiveRecord(maxRecordChars))
    else if maxFieldChars <= 0 then Left(DelimitedLimitError.NonPositiveField(maxFieldChars))
    else if maxFieldChars > maxRecordChars then
      Left(DelimitedLimitError.FieldExceedsRecord(maxFieldChars, maxRecordChars))
    else if maxErrorExcerptChars <= 0 then
      Left(DelimitedLimitError.NonPositiveErrorExcerpt(maxErrorExcerptChars))
    else
      Right(
        new DelimitedReadLimits(
          maxRecordChars,
          maxFieldChars,
          maxErrorExcerptChars
        )
      )

/** Runtime CSV decoding options paired with an explicit [[Schema]]. */
final case class CsvReadOptions(
    schema: Schema,
    delimiter: Char = ',',
    header: Boolean = true,
    nullPolicy: NullPolicy = NullPolicy.default,
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024,
    limits: DelimitedReadLimits = DelimitedReadLimits.default
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
    nullPolicy: NullPolicy = NullPolicy.default,
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024,
    limits: DelimitedReadLimits = DelimitedReadLimits.default
):
  private[fs2] def options(schema: Schema): CsvReadOptions =
    CsvReadOptions(
      schema = schema,
      delimiter = delimiter,
      header = header,
      nullPolicy = nullPolicy,
      coercion = coercion,
      batchSize = batchSize,
      limits = limits
    )

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
    if closed then Left(SourceError.Closed)
    else if options == null then
      Left(SourceError.InvalidRequest("CSV read options cannot be raw null"))
    else if options.schema == null then
      Left(SourceError.InvalidRequest("CSV schema cannot be raw null"))
    else if options.nullPolicy == null then
      Left(SourceError.InvalidRequest("CSV null policy cannot be raw null"))
    else if options.coercion == null then
      Left(SourceError.InvalidRequest("CSV coercion cannot be raw null"))
    else if options.limits == null then
      Left(SourceError.InvalidRequest("CSV read limits cannot be raw null"))
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

  /** Portable incremental strict UTF-8 byte-stream acquisition.
    *
    * Malformed input raises `SourceFailure(SourceError.InvalidUtf8(...))`; replacement decoding is
    * never used.
    */
  def bytes[F[_]](
      input: Stream[F, Byte],
      options: CsvReadOptions
  )(using F: Async[F]): Resource[F, CsvFrameSource[F]] =
    val bytes =
      AdapterFailureBoundary.sourceStream(input, SourceError.Upstream.apply)
    val decodedCharacters = StrictUtf8.decode(bytes)
    characters(
      decodedCharacters
        .flatMap(value => Stream.emits(value.toVector)),
      options
    )

  /** Portable incremental character-stream acquisition. */
  def characters[F[_]](
      input: Stream[F, Char],
      options: CsvReadOptions
  )(using F: Async[F]): Resource[F, CsvFrameSource[F]] =
    FrameSource.owningResource:
      F.delay:
        new CsvFrameSource(
          AdapterFailureBoundary.sourceStream(input, SourceError.Upstream.apply),
          options
        )

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
    nullPolicy: NullPolicy = NullPolicy.default
)(using F: Async[F])
    extends FrameSink[F, CsvWriteResult]:
  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, CsvWriteResult]] =
    val validation =
      if schema == null then Left(SinkError.InvalidRequest("CSV schema cannot be raw null"))
      else CsvCodec.validateWriteOptions(delimiter, nullPolicy)
    validation match
      case Left(error) => F.pure(Left(error))
      case Right(())   =>
        val encoded = batches
          .evalMap: batch =>
            AdapterFailureBoundary.sinkEffect(
              F.blocking(
                CsvCodec.encode(
                  schema,
                  Vector(batch),
                  delimiter,
                  includeHeader = false,
                  nullPolicy
                )
              ),
              SinkError.Write.apply
            )
          .compile
          .toVector
          .map: encoded =>
            encoded.foldLeft[Either[SinkError, Vector[CsvWriteResult]]](Right(Vector.empty)):
              case (result, value) => result.flatMap(values => value.map(values :+ _))
          .flatMap:
            case Left(error)  => F.pure(Left(error))
            case Right(parts) =>
              AdapterFailureBoundary
                .sinkEffect(
                  F.delay:
                    val header =
                      if includeHeader then CsvCodec.header(schema, delimiter)
                      else ""
                    val text = header + parts.map(_.text).mkString
                    CsvWriteResult(
                      text,
                      SinkReceipt(
                        parts.map(_.receipt.rows).sum,
                        parts.map(_.receipt.batches).sum,
                        Utf8Length(text)
                      )
                    )
                  ,
                  SinkError.Write.apply
                )
                .map(Right.apply)
        AdapterFailureBoundary.sinkEither(encoded, SinkError.Upstream.apply)

final case class TsvReadOptions(
    schema: Schema,
    header: Boolean = true,
    nullPolicy: NullPolicy = NullPolicy.default,
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024,
    limits: DelimitedReadLimits = DelimitedReadLimits.default
):
  private[fs2] def csvOptions: CsvReadOptions =
    CsvReadOptions(
      schema = schema,
      delimiter = '\t',
      header = header,
      nullPolicy = nullPolicy,
      coercion = coercion,
      batchSize = batchSize,
      limits = limits
    )

/** Typed TSV settings; semantics match [[CsvSettings]] with a tab delimiter. */
final case class TsvSettings(
    header: Boolean = true,
    nullPolicy: NullPolicy = NullPolicy.default,
    coercion: CsvCoercion = CsvCoercion.Strict,
    batchSize: Int = 1024,
    limits: DelimitedReadLimits = DelimitedReadLimits.default
):
  private[fs2] def csvSettings: CsvSettings =
    CsvSettings(
      delimiter = '\t',
      header = header,
      nullPolicy = nullPolicy,
      coercion = coercion,
      batchSize = batchSize,
      limits = limits
    )

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
    nullPolicy: NullPolicy = NullPolicy.default
)(using F: Async[F])
    extends FrameSink[F, TsvWriteResult]:
  private val delegate =
    new CsvFrameSink[F](
      delimiter = '\t',
      includeHeader = includeHeader,
      nullPolicy = nullPolicy
    )

  def write(
      schema: Schema,
      batches: Stream[F, RecordBatch]
  ): F[Either[SinkError, TsvWriteResult]] =
    delegate
      .write(schema, batches)
      .map(_.map(result => TsvWriteResult(result.text, result.receipt)))

private object StrictUtf8:
  def decode[F[_]](input: Stream[F, Byte])(using F: Async[F]): Stream[F, String] =
    Stream
      .eval(F.delay(new Decoder))
      .flatMap: decoder =>
        val decoded = input.chunks
          .evalMap: chunk =>
            F.fromEither(
              decoder
                .feed(chunk)
                .leftMap: (offset, cause) =>
                  SourceFailure(SourceError.InvalidUtf8(offset, cause))
            )
          .flatMap: value =>
            if value.isEmpty then Stream.empty else Stream.emit(value)
        decoded ++ Stream
          .eval(
            F.fromEither(
              decoder
                .finish()
                .leftMap: (offset, cause) =>
                  SourceFailure(SourceError.InvalidUtf8(offset, cause))
            )
          )
          .drain

  final private class Decoder:
    private var continuationBytes = 0
    private var codePoint = 0
    private var minimumCodePoint = 0
    private var offset = 0L

    def feed(chunk: Chunk[Byte]): Either[(Long, Throwable), String] =
      val output = new StringBuilder
      val iterator = chunk.iterator
      var error: Option[(Long, Throwable)] = None
      while iterator.hasNext && error.isEmpty do
        val current = iterator.next() & 0xff
        if continuationBytes == 0 then
          if current <= 0x7f then output.append(current.toChar)
          else if current >= 0xc2 && current <= 0xdf then begin(current & 0x1f, 1, 0x80)
          else if current >= 0xe0 && current <= 0xef then begin(current & 0x0f, 2, 0x800)
          else if current >= 0xf0 && current <= 0xf4 then begin(current & 0x07, 3, 0x10000)
          else error = Some(invalid("invalid leading byte"))
        else if current < 0x80 || current > 0xbf then
          error = Some(invalid("invalid continuation byte"))
        else
          codePoint = (codePoint << 6) | (current & 0x3f)
          continuationBytes -= 1
          if continuationBytes == 0 then
            if codePoint < minimumCodePoint then error = Some(invalid("overlong encoding"))
            else if codePoint >= 0xd800 && codePoint <= 0xdfff then
              error = Some(invalid("surrogate code point"))
            else if codePoint > 0x10ffff then
              error = Some(invalid("code point exceeds Unicode range"))
            else appendCodePoint(output, codePoint)
        offset += 1L
      error.toLeft(output.result())

    def finish(): Either[(Long, Throwable), Unit] =
      if continuationBytes == 0 then Right(())
      else Left(invalid("truncated code point"))

    private def begin(initial: Int, expected: Int, minimum: Int): Unit =
      codePoint = initial
      continuationBytes = expected
      minimumCodePoint = minimum

    private def appendCodePoint(output: StringBuilder, value: Int): Unit =
      if value <= 0xffff then output.append(value.toChar)
      else
        val supplementary = value - 0x10000
        output.append(((supplementary >>> 10) + 0xd800).toChar)
        output.append(((supplementary & 0x3ff) + 0xdc00).toChar)

    private def invalid(reason: String): (Long, Throwable) =
      offset -> new IllegalArgumentException(s"malformed UTF-8 at byte $offset: $reason")

final private case class CsvCell(
    text: String,
    quoted: Boolean,
    location: SourceLocation,
    excerpt: SourceExcerpt
)

final private case class CsvRecord(
    number: Long,
    cells: Vector[CsvCell],
    excerpt: SourceExcerpt,
    endOffset: Long
):
  def values: Vector[String] = cells.map(_.text)

/** Per-compilation delimited-text state machine.
  *
  * Explicit record, field, excerpt, parser-chunk, and downstream batch limits bound retained input
  * independently of upstream chunking. The state also retains whether each field was quoted so null
  * decoding cannot confuse syntax with data.
  */
final private class CsvParser(
    delimiter: Char,
    expectedFields: Int,
    limits: DelimitedReadLimits
):
  private val fields = ArrayBuffer.empty[CsvCell]
  private val field = new StringBuilder
  private val fieldContext = new StringBuilder
  private val context = new StringBuilder
  private var quoted = false
  private var fieldWasQuoted = false
  private var afterQuote = false
  private var rowStarted = false
  private var skipLineFeed = false
  private var recordNumber = 1L
  private var offset = 0L
  private var fieldStartOffset = 0L
  private var contextStartOffset = 0L
  private var contextTruncated = false
  private var fieldContextTruncated = false
  private var recordChars = 0

  def feed(chunk: Chunk[Char]): Either[SourceError, Vector[CsvRecord]] =
    val completed = Vector.newBuilder[CsvRecord]
    val iterator = chunk.iterator
    var error: Option[SourceError] = None
    while iterator.hasNext && error.isEmpty do
      val current = iterator.next()
      if offset == 0L && current == '\ufeff' then
        fieldStartOffset = 1L
        contextStartOffset = 1L
      else if skipLineFeed then
        skipLineFeed = false
        if current == '\n' then
          fieldStartOffset = offset + 1L
          contextStartOffset = offset + 1L
        else
          consume(current, completed) match
            case Some(value) => error = Some(value)
            case None        => ()
      else
        consume(current, completed) match
          case Some(value) => error = Some(value)
          case None        => ()
      offset += 1L
    error.toLeft(completed.result())

  def finish(): Either[SourceError, Vector[CsvRecord]] =
    if quoted then Left(malformed("unterminated quoted field"))
    else if rowStarted || fields.nonEmpty || field.nonEmpty || afterQuote then
      Right(Vector(finishRecord(offset)))
    else Right(Vector.empty)

  private def consume(
      current: Char,
      completed: scala.collection.mutable.Builder[CsvRecord, Vector[CsvRecord]]
  ): Option[SourceError] =
    val terminatesRecord = !quoted && (current == '\n' || current == '\r')
    val terminatesField =
      !quoted && (current == delimiter || current == '\n' || current == '\r')
    val sizeError =
      if terminatesRecord then None
      else appendRecord(current)

    sizeError.orElse:
      if !terminatesField then appendFieldContext(current)
      if quoted then
        if current == '"' then
          quoted = false
          afterQuote = true
          None
        else
          appendField(current) match
            case error @ Some(_) => error
            case None            =>
              rowStarted = true
              None
      else if afterQuote then
        current match
          case '"' =>
            appendField('"') match
              case error @ Some(_) => error
              case None            =>
                quoted = true
                afterQuote = false
                rowStarted = true
                None
          case value if value == delimiter =>
            excessField().orElse:
              finishField()
              afterQuote = false
              None
          case '\n' =>
            completed += finishRecord(offset)
            None
          case '\r' =>
            completed += finishRecord(offset)
            skipLineFeed = true
            None
          case _ =>
            Some(malformed("unexpected character after closing quote"))
      else
        current match
          case '"' if field.isEmpty =>
            quoted = true
            fieldWasQuoted = true
            rowStarted = true
            None
          case '"' =>
            Some(malformed("quote inside unquoted field"))
          case value if value == delimiter =>
            excessField().orElse:
              finishField()
              rowStarted = true
              None
          case '\n' =>
            completed += finishRecord(offset)
            None
          case '\r' =>
            completed += finishRecord(offset)
            skipLineFeed = true
            None
          case value =>
            appendField(value) match
              case error @ Some(_) => error
              case None            =>
                rowStarted = true
                None

  private def appendRecord(current: Char): Option[SourceError] =
    if recordChars >= limits.maxRecordChars then
      Some(malformed(s"record exceeds ${limits.maxRecordChars} characters"))
    else
      if context.length >= limits.maxErrorExcerptChars then
        context.deleteCharAt(0)
        contextStartOffset += 1L
        contextTruncated = true
      context.append(current)
      recordChars += 1
      None

  private def appendField(current: Char): Option[SourceError] =
    if field.length >= limits.maxFieldChars then
      Some(malformed(s"field exceeds ${limits.maxFieldChars} characters"))
    else
      field.append(current)
      None

  private def appendFieldContext(current: Char): Unit =
    if fieldContext.length < limits.maxErrorExcerptChars then fieldContext.append(current)
    else fieldContextTruncated = true

  private def excessField(): Option[SourceError] =
    if fields.length + 1 >= expectedFields then
      Some(
        SourceError.MalformedDelimited(
          SourceLocation(recordNumber, expectedFields + 1, offset),
          s"expected $expectedFields fields but found more",
          currentExcerpt
        )
      )
    else None

  private def malformed(detail: String): SourceError =
    SourceError.MalformedDelimited(
      SourceLocation(recordNumber, fields.length + 1, offset),
      detail,
      currentExcerpt
    )

  private def currentExcerpt: SourceExcerpt =
    SourceExcerpt(
      context.result(),
      contextStartOffset,
      truncatedBefore = contextTruncated,
      truncatedAfter = false
    )

  private def finishField(): Unit =
    fields += CsvCell(
      field.result(),
      fieldWasQuoted,
      SourceLocation(recordNumber, fields.length + 1, fieldStartOffset),
      SourceExcerpt(
        fieldContext.result(),
        fieldStartOffset,
        truncatedBefore = false,
        truncatedAfter = fieldContextTruncated
      )
    )
    field.clear()
    fieldContext.clear()
    fieldWasQuoted = false
    fieldContextTruncated = false
    fieldStartOffset = offset + 1L

  private def finishRecord(endOffset: Long): CsvRecord =
    finishField()
    val result = CsvRecord(recordNumber, fields.toVector, currentExcerpt, endOffset)
    fields.clear()
    field.clear()
    fieldContext.clear()
    context.clear()
    quoted = false
    fieldWasQuoted = false
    afterQuote = false
    rowStarted = false
    contextTruncated = false
    fieldContextTruncated = false
    recordChars = 0
    recordNumber += 1L
    fieldStartOffset = offset + 1L
    contextStartOffset = offset + 1L
    result

private object CsvStreaming:
  private val MaxParserChunkChars = 4096

  def batches[F[_]](
      input: Stream[F, Char],
      options: CsvReadOptions,
      limit: Option[Long]
  )(using F: Async[F]): Stream[F, RecordBatch] =
    val records =
      Stream
        .eval(
          F.delay(
            new CsvParser(
              options.delimiter,
              options.schema.size,
              options.limits
            )
          )
        )
        .flatMap: parser =>
          val chunks =
            input
              .chunkLimit(MaxParserChunkChars)
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
          Pull.raiseError(
            SourceFailure(
              SourceError.MalformedDelimited(
                SourceLocation(1L, 1, 0L),
                "missing header",
                SourceExcerpt("", 0L, truncatedBefore = false, truncatedAfter = false)
              )
            )
          )
        case Some((header, tail)) =>
          val expected = schema.fields.map(_.name)
          if header.values == expected then tail.pull.echo
          else
            val mismatch =
              header.values
                .zipAll(expected, "", "")
                .indexWhere((actual, declared) => actual != declared) + 1
            Pull.raiseError(
              SourceFailure(
                SourceError.SchemaMismatch(
                  s"CSV header does not match the declared schema at column $mismatch"
                )
              )
            )
      .stream

private object CsvCodec:
  def header(schema: Schema, delimiter: Char): String =
    schema.fields
      .map(field => quote(field.name, delimiter, force = field.name.startsWith("\ufeff")))
      .mkString(delimiter.toString) + "\n"

  private[fs2] def buildBatch(
      options: CsvReadOptions,
      rows: Vector[CsvRecord]
  ): Either[SourceError, RecordBatch] =
    rows.find(_.values.length != options.schema.size) match
      case Some(row) =>
        val field =
          if row.cells.length < options.schema.size then row.cells.length + 1
          else options.schema.size + 1
        Left(
          SourceError.MalformedDelimited(
            SourceLocation(row.number, field, row.endOffset),
            s"expected ${options.schema.size} fields but found ${row.cells.length}",
            row.excerpt
          )
        )
      case None =>
        val columns = options.schema.fields.zipWithIndex.map: (field, column) =>
          val values = rows.map: row =>
            val cell = row.cells(column)
            val value =
              if options.coercion == CsvCoercion.TrimWhitespace && !cell.quoted then cell.text.trim
              else cell.text
            cell.copy(text = value)
          decodeColumn(field, values, options.nullPolicy)
        sequence(columns).flatMap: decoded =>
          RecordBatch(options.schema, decoded) match
            case Right(batch) => Right(batch)
            case Left(error)  =>
              decoded.foreach(_.close())
              Left(SourceError.Storage(error))

  private def decodeColumn(
      field: Field,
      values: Vector[CsvCell],
      nullPolicy: NullPolicy
  ): Either[SourceError, ColumnArray] =
    val valid = values.map(cell => cell.quoted || !nullPolicy.recognizes(cell.text)).toArray
    def decode[A: ClassTag](
        expected: DataType
    )(parser: String => Option[A]): Either[SourceError, Array[A]] =
      val output = new Array[A](values.length)
      var index = 0
      var error: Option[SourceError] = None
      while index < values.length && error.isEmpty do
        val cell = values(index)
        if valid(index) then
          parser(cell.text) match
            case Some(decoded) => output(index) = decoded
            case None          =>
              error = Some(SourceError.Decode(cell.location, cell.excerpt, expected))
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
        val decoded = values.map(_.text).toArray
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
      nullPolicy: NullPolicy
  ): Either[SinkError, CsvWriteResult] =
    validateWriteOptions(delimiter, nullPolicy).flatMap: _ =>
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
                  case Right(ScalarValue.Null) => encoded(column) = nullPolicy.writeToken
                  case Right(value)            =>
                    val rendered = render(value)
                    val force =
                      nullPolicy.collidesWithData(rendered) || rendered.startsWith("\ufeff")
                    encoded(column) = quote(rendered, delimiter, force)
                  case Left(value) => error = Some(SinkError.Storage(value))
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
                CsvWriteResult(text, SinkReceipt(rows, batches.length.toLong, Utf8Length(text)))
              )

  def validateWriteOptions(
      delimiter: Char,
      nullPolicy: NullPolicy
  ): Either[SinkError, Unit] =
    if nullPolicy == null then Left(SinkError.InvalidRequest("CSV null policy cannot be raw null"))
    else if delimiter == '"' || delimiter == '\r' || delimiter == '\n' then
      Left(SinkError.InvalidRequest("CSV delimiter cannot be a quote or line break"))
    else if nullPolicy.writeToken.startsWith("\ufeff") ||
      nullPolicy.writeToken.trim != nullPolicy.writeToken ||
      nullPolicy.writeToken.exists(character =>
        character == delimiter || character == '"' || character == '\n' || character == '\r'
      )
    then
      Left(
        SinkError.InvalidRequest(
          "the null write token must be safe to emit as one unquoted delimited field"
        )
      )
    else Right(())

  private def render(value: ScalarValue): String = value match
    case ScalarValue.Null                 => ""
    case ScalarValue.Bool(actual)         => actual.toString
    case ScalarValue.Int32(actual)        => actual.toString
    case ScalarValue.Int64(actual)        => actual.toString
    case ScalarValue.Float32(actual)      => actual.toString
    case ScalarValue.Float64(actual)      => actual.toString
    case ScalarValue.Utf8(actual)         => actual.value
    case ScalarValue.Timestamp(actual, _) => actual.toString

  private def quote(value: String, delimiter: Char, force: Boolean): String =
    if force || value.exists(character =>
        character == delimiter || character == '"' || character == '\n' || character == '\r'
      )
    then s"\"${value.replace("\"", "\"\"")}\""
    else value

  private def sequence(
      values: Vector[Either[SourceError, ColumnArray]]
  ): Either[SourceError, Vector[ColumnArray]] =
    values.collectFirst { case Left(error) => error } match
      case Some(error) =>
        values.foreach:
          case Right(column) => column.close()
          case Left(_)       => ()
        Left(error)
      case None => Right(values.collect { case Right(column) => column })

private object Utf8Length:
  def apply(value: String): Long =
    var bytes = 0L
    var index = 0
    while index < value.length do
      val current = value.charAt(index)
      if current <= 0x7f then bytes += 1L
      else if current <= 0x7ff then bytes += 2L
      else if Character.isHighSurrogate(current) &&
        index + 1 < value.length &&
        Character.isLowSurrogate(value.charAt(index + 1))
      then
        bytes += 4L
        index += 1
      else bytes += 3L
      index += 1
    bytes
