package frame4s

import scala.NamedTuple
import scala.deriving.Mirror

/** The input operation that failed while constructing a [[Table]] from rows. */
enum TableInputStage:
  case AcquireIterator
  case HasNext
  case Next
  case EncodeRow
  case BuildBatch

  private[frame4s] def description: String = this match
    case AcquireIterator => "acquiring the row iterator"
    case HasNext         => "checking for the next row"
    case Next            => "reading the next row"
    case EncodeRow       => "encoding a row"
    case BuildBatch      => "building a record batch"

/** A structured failure while reading or constructing a materialized table.
  *
  * These errors deliberately sit above [[StorageError]]: a storage failure describes a physical
  * buffer problem, while this ADT also distinguishes user-facing row, schema, and scalar decode
  * failures.
  */
enum TableReadError:
  case Closed
  case RowOutOfBounds(index: Long, rowCount: Long)
  case SchemaMismatch(expected: Schema, actual: Schema)
  case InvalidBatchSize(size: Int)
  case InputFailure(stage: TableInputStage, row: Long, detail: String)
  case InvalidValue(row: Long, column: Int, name: String, error: ValueError)
  case ScalarDecode(
      row: Long,
      column: Int,
      name: String,
      value: ScalarValue,
      expected: DataType,
      nullable: Boolean
  )
  case InvalidRenderOptions(detail: String)
  case Storage(error: StorageError)

  def message: String = this match
    case Closed                          => "table is closed"
    case RowOutOfBounds(index, rowCount) =>
      s"row index $index is outside a table with $rowCount rows"
    case SchemaMismatch(expected, actual) =>
      s"table schema $actual does not match row schema $expected"
    case InvalidBatchSize(size)           => s"row batch size must be positive; found $size"
    case InputFailure(stage, row, detail) =>
      s"table input failed while ${stage.description} at row $row: $detail"
    case InvalidValue(row, column, name, error) =>
      s"row $row column $column ('$name') is invalid: ${error.message}"
    case ScalarDecode(row, column, name, value, expected, nullable) =>
      val suffix = if nullable then " or null" else ""
      s"row $row column $column ('$name') value $value cannot be decoded as $expected$suffix"
    case InvalidRenderOptions(detail) => s"invalid table rendering options: $detail"
    case Storage(error)               => error.message

final case class TableReadFailure(error: TableReadError) extends RuntimeException(error.message)

/** Bounded rendering controls.
  *
  * `maxRows` bounds materialized row reads, `maxWidth` bounds every rendered line, and
  * `maxCellWidth` bounds each individual cell before the total-width selection is applied.
  */
final case class TableRenderOptions(
    maxRows: Int = 20,
    maxWidth: Int = 120,
    maxCellWidth: Int = 32
)

/** Lawful conversion between one Scala field type and its storage scalar.
  *
  * The hierarchy is sealed so an unrelated runtime representation cannot be claimed for a typed
  * field. `Option[A]` is the sole nullable instance.
  */
sealed trait ScalarCodec[A]:
  private[frame4s] def decode(value: ScalarValue): Option[A]
  private[frame4s] def encode(value: A): Either[ValueError, ScalarValue]

object ScalarCodec:
  given ScalarCodec[Boolean] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Bool(actual) => Some(actual)
      case _                        => None
    private[frame4s] def encode(value: Boolean) = Right(ScalarValue.Bool(value))

  given ScalarCodec[Int] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Int32(actual) => Some(actual)
      case _                         => None
    private[frame4s] def encode(value: Int) = Right(ScalarValue.Int32(value))

  given ScalarCodec[Long] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Int64(actual) => Some(actual)
      case _                         => None
    private[frame4s] def encode(value: Long) = Right(ScalarValue.Int64(value))

  given ScalarCodec[Float] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Float32(actual) => Some(actual)
      case _                           => None
    private[frame4s] def encode(value: Float) = Right(ScalarValue.Float32(value))

  given ScalarCodec[Double] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Float64(actual) => Some(actual)
      case _                           => None
    private[frame4s] def encode(value: Double) = Right(ScalarValue.Float64(value))

  given ScalarCodec[String] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Utf8(actual) => Some(actual.value)
      case _                        => None
    private[frame4s] def encode(value: String) = ScalarValue.utf8(value)

  given ScalarCodec[TimestampMicros] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Timestamp(actual, TimeUnit.Microsecond) =>
        Some(TimestampMicros(actual))
      case _ => None
    private[frame4s] def encode(value: TimestampMicros) =
      Right(ScalarValue.Timestamp(value.toLong, TimeUnit.Microsecond))

  given [A](using valueCodec: ScalarCodec[A]): ScalarCodec[Option[A]] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Null => Some(None)
      case actual           => valueCodec.decode(actual).map(Some(_))
    private[frame4s] def encode(value: Option[A]) = value match
      case Some(actual) => valueCodec.encode(actual)
      case None         => Right(ScalarValue.Null)

final private[frame4s] case class RowEncodingError(column: Int, error: ValueError)

private[frame4s] object RowCodecSupport:
  def decodeField[A](
      codec: ScalarCodec[A],
      table: Table[?],
      batch: RecordBatch,
      batchRow: Int,
      logicalRow: Long,
      column: Int
  ): Either[TableReadError, A] =
    val field = table.schema.fields(column)
    batch
      .columns(column)
      .scalar(batchRow)
      .left
      .map(TableReadError.Storage.apply)
      .flatMap: scalar =>
        codec
          .decode(scalar)
          .toRight:
            TableReadError.ScalarDecode(
              logicalRow,
              column,
              field.name,
              scalar,
              field.dataType,
              field.nullable
            )

  def encodeField[A](
      codec: ScalarCodec[A],
      value: A,
      column: Int
  ): Either[RowEncodingError, ScalarValue] =
    codec.encode(value).left.map(RowEncodingError(column, _))

  def sequence[Error, Value](
      values: Seq[Either[Error, Value]]
  ): Either[Error, Vector[Value]] =
    val output = Vector.newBuilder[Value]
    val iterator = values.iterator
    var error: Option[Error] = None
    while iterator.hasNext && error.isEmpty do
      iterator.next() match
        case Left(value)  => error = Some(value)
        case Right(value) => output += value
    error.toLeft(output.result())

  def valuesTuple[
      S <: NamedTuple.AnyNamedTuple,
      Values <: Tuple
  ](row: S): Values =
    NamedTupleRepresentation.retype[S, Values](row)

  def namedTuple[
      S <: NamedTuple.AnyNamedTuple,
      Values <: Tuple
  ](values: Values): S =
    NamedTupleRepresentation.retype[Values, S](values)

/** Derives named-tuple rows from the same sealed schema descriptor used by [[Frame]].
  *
  * A compile-time derivation writes one flat, ordered field program from the sealed [[ScalarCodec]]
  * witnesses. This avoids a schema-width-sized implicit chain while preserving exact field types,
  * deterministic first-error reporting, and the single audited named-tuple representation bridge.
  */
sealed trait RowCodec[S <: NamedTuple.AnyNamedTuple]:
  def schema: Schema
  private[frame4s] def decode(
      table: Table[S],
      batch: RecordBatch,
      batchRow: Int,
      logicalRow: Long
  ): Either[TableReadError, S]
  private[frame4s] def encode(row: S): Either[RowEncodingError, Vector[ScalarValue]]

object RowCodec:
  final class Evidence[
      S <: NamedTuple.AnyNamedTuple
  ] @scala.annotation.publicInBinary private[frame4s] (
      val schema: Schema,
      decodeRow: (
          Table[S],
          RecordBatch,
          Int,
          Long
      ) => Either[TableReadError, S],
      encodeRow: S => Either[RowEncodingError, Vector[ScalarValue]]
  ) extends RowCodec[S]:

    private[frame4s] def decode(
        table: Table[S],
        batch: RecordBatch,
        batchRow: Int,
        logicalRow: Long
    ): Either[TableReadError, S] =
      decodeRow(table, batch, batchRow, logicalRow)

    private[frame4s] def encode(
        row: S
    ): Either[RowEncodingError, Vector[ScalarValue]] =
      encodeRow(row)

  transparent inline given derived[S <: NamedTuple.AnyNamedTuple](using
      descriptor: SchemaDescriptor[S]
  ): RowCodec[S] =
    ${ RowCodecMacros.rowCodec[S]('descriptor) }

/** Exact case-class bridge. `NamedTuple.From[P]` preserves field labels, order, and types; the
  * compiler therefore refuses reordered, missing, extra, or differently nullable products.
  */
object ProductRows:
  def toNamedTuple[P <: Product](
      value: P
  )(using mirror: Mirror.ProductOf[P]): NamedTuple.From[P] =
    val tuple: mirror.MirroredElemTypes = Tuple.fromProductTyped(value)
    val named =
      NamedTuple[mirror.MirroredElemLabels, mirror.MirroredElemTypes](tuple)
    NamedTupleRepresentation.retype(named)

  def fromNamedTuple[P <: Product](
      value: NamedTuple.From[P]
  )(using mirror: Mirror.ProductOf[P]): P =
    mirror.fromProduct(
      NamedTupleRepresentation.retype[NamedTuple.From[P], mirror.MirroredElemTypes](value)
    )

/** The sole representation cast used by row codecs.
  *
  * Scala 3.7 defines `NamedTuple[N, V]` as an opaque labeling of the same runtime `V` tuple, and
  * `NamedTuple.From[P]` uses `Mirror.ProductOf[P]`'s element labels and values. The compiler cannot
  * currently prove the inverse decomposition for an abstract `S <: AnyNamedTuple`, so this
  * representation equality is localized here and defended by inductive narrow/wide codec tests.
  */
private object NamedTupleRepresentation:
  def retype[A, B](value: A): B = value.asInstanceOf[B]
