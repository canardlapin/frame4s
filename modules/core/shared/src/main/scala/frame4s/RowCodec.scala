package frame4s

import scala.NamedTuple
import scala.deriving.Mirror

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
    case InvalidBatchSize(size) => s"row batch size must be positive; found $size"
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
  private[frame4s] def encode(value: A): ScalarValue

object ScalarCodec:
  given ScalarCodec[Boolean] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Bool(actual) => Some(actual)
      case _                        => None
    private[frame4s] def encode(value: Boolean) = ScalarValue.Bool(value)

  given ScalarCodec[Int] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Int32(actual) => Some(actual)
      case _                         => None
    private[frame4s] def encode(value: Int) = ScalarValue.Int32(value)

  given ScalarCodec[Long] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Int64(actual) => Some(actual)
      case _                         => None
    private[frame4s] def encode(value: Long) = ScalarValue.Int64(value)

  given ScalarCodec[Float] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Float32(actual) => Some(actual)
      case _                           => None
    private[frame4s] def encode(value: Float) = ScalarValue.Float32(value)

  given ScalarCodec[Double] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Float64(actual) => Some(actual)
      case _                           => None
    private[frame4s] def encode(value: Double) = ScalarValue.Float64(value)

  given ScalarCodec[String] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Utf8(actual) => Some(actual)
      case _                        => None
    private[frame4s] def encode(value: String) = ScalarValue.Utf8(value)

  given ScalarCodec[TimestampMicros] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Timestamp(actual, TimeUnit.Microsecond) =>
        Some(TimestampMicros(actual))
      case _ => None
    private[frame4s] def encode(value: TimestampMicros) =
      ScalarValue.Timestamp(value.toLong, TimeUnit.Microsecond)

  given [A](using valueCodec: ScalarCodec[A]): ScalarCodec[Option[A]] with
    private[frame4s] def decode(value: ScalarValue) = value match
      case ScalarValue.Null => Some(None)
      case actual           => valueCodec.decode(actual).map(Some(_))
    private[frame4s] def encode(value: Option[A]) = value match
      case Some(actual) => valueCodec.encode(actual)
      case None         => ScalarValue.Null

private[frame4s] trait TupleRowCodec[Values <: Tuple]:
  def decode(
      table: Table[?],
      batch: RecordBatch,
      batchRow: Int,
      logicalRow: Long,
      columnOffset: Int
  ): Either[TableReadError, Values]
  def encode(values: Values): Vector[ScalarValue]

private[frame4s] object TupleRowCodec:
  given TupleRowCodec[EmptyTuple] with
    def decode(
        table: Table[?],
        batch: RecordBatch,
        batchRow: Int,
        logicalRow: Long,
        columnOffset: Int
    ) = Right(EmptyTuple)

    def encode(values: EmptyTuple) = Vector.empty

  given [Head, Tail <: Tuple](using
      headCodec: ScalarCodec[Head],
      tailCodec: TupleRowCodec[Tail]
  ): TupleRowCodec[Head *: Tail] with
    def decode(
        table: Table[?],
        batch: RecordBatch,
        batchRow: Int,
        logicalRow: Long,
        columnOffset: Int
    ): Either[TableReadError, Head *: Tail] =
      val field = table.schema.fields(columnOffset)
      batch
        .columns(columnOffset)
        .scalar(batchRow)
        .left
        .map(TableReadError.Storage.apply)
        .flatMap: scalar =>
          headCodec
            .decode(scalar)
            .toRight:
              TableReadError.ScalarDecode(
                logicalRow,
                columnOffset,
                field.name,
                scalar,
                field.dataType,
                field.nullable
              )
        .flatMap: head =>
          tailCodec
            .decode(table, batch, batchRow, logicalRow, columnOffset + 1)
            .map(head *: _)

    def encode(values: Head *: Tail): Vector[ScalarValue] =
      headCodec.encode(values.head) +: tailCodec.encode(values.tail)

/** Derives named-tuple rows from the same sealed schema descriptor used by [[Frame]].
  *
  * Named tuples erase to their ordinary value tuple, so derivation is inductive over
  * `NamedTuple.DropNames[S]`; construction and `.toTuple` are compiler-defined zero-copy
  * conversions. No user-visible cast or macro is involved.
  */
sealed trait RowCodec[S <: NamedTuple.AnyNamedTuple]:
  def schema: Schema
  private[frame4s] def decode(
      table: Table[S],
      batch: RecordBatch,
      batchRow: Int,
      logicalRow: Long
  ): Either[TableReadError, S]
  private[frame4s] def encode(row: S): Vector[ScalarValue]

object RowCodec:
  given derived[S <: NamedTuple.AnyNamedTuple](using
      descriptor: SchemaDescriptor[S],
      values: TupleRowCodec[NamedTuple.DropNames[S]]
  ): RowCodec[S] with
    val schema = descriptor.schema

    private[frame4s] def decode(
        table: Table[S],
        batch: RecordBatch,
        batchRow: Int,
        logicalRow: Long
    ): Either[TableReadError, S] =
      values
        .decode(table, batch, batchRow, logicalRow, 0)
        .map: tuple =>
          val named =
            NamedTuple[NamedTuple.Names[S], NamedTuple.DropNames[S]](tuple)
          NamedTupleRepresentation.retype[
            NamedTuple.NamedTuple[NamedTuple.Names[S], NamedTuple.DropNames[S]],
            S
          ](named)

    private[frame4s] def encode(row: S): Vector[ScalarValue] =
      values.encode(
        NamedTupleRepresentation.retype[S, NamedTuple.DropNames[S]](row)
      )

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
