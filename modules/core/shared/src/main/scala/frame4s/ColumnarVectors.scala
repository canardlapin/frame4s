package frame4s

/** Bridge from a columnar result to the Arrow-compatible batches the public API returns.
  *
  * The optimized engine computes into `ColumnarVector`, while `FrameRuntime` hands callers
  * `RecordBatch` of `ColumnArray`. Deliberately partial: it converts the materialized value vectors
  * the engine actually emits and reports a named residual for anything else, so an unrecognised
  * shape falls back to the semantic reference path instead of being guessed at. Widening it is
  * additive and each addition should arrive with a conformance test.
  */
private[frame4s] object ColumnarMaterialization:
  def toRecordBatch(schema: Schema, batch: ColumnarBatch): Either[String, RecordBatch] =
    val columns = Vector.newBuilder[ColumnArray]
    var index = 0
    var failure: Option[String] = None
    while index < batch.columns.length && failure.isEmpty do
      val vector = batch.columns(index)
      val dataType = schema.fields(index).dataType
      val converted = toColumnArray(dataType, vector) match
        case Right(value) => Right(value)
        case Left(_)      => materialize(dataType, vector)
      converted match
        case Right(value) => columns += value
        case Left(reason) => failure = Some(reason)
      index += 1
    failure match
      case Some(reason) => Left(reason)
      case None         =>
        // RecordBatch derives its row count from the columns and validates width, types,
        // lengths, and nullability against the schema, so a bridge mistake surfaces here as a
        // structured error rather than as a silently malformed table.
        RecordBatch(schema, columns.result()).left.map(_.message)

  /** An empty validity array means "no nulls", which is what the builders already expect. */
  private def toColumnArray(
      dataType: DataType,
      vector: ColumnarVector
  ): Either[String, ColumnArray] =
    val built: Option[Either[StorageError, ColumnArray]] = vector match
      case Int32Values(values)                  => Some(ColumnArray.int32(values))
      case NullableInt32Values(values, valid)   => Some(ColumnArray.int32(values, valid))
      case Int64Values(values)                  => Some(ColumnArray.int64(values))
      case NullableInt64Values(values, valid)   => Some(ColumnArray.int64(values, valid))
      case Float32Values(values, valid)         => Some(ColumnArray.float32(values, valid))
      case Float64Values(values, valid)         => Some(ColumnArray.float64(values, valid))
      case BooleanValues(values, valid)         => Some(ColumnArray.bool(values, valid))
      case Utf8Values(values)                   => Some(ColumnArray.utf8(values))
      case NullableUtf8Values(values, valid)    => Some(ColumnArray.utf8(values, valid))
      case TimestampValues(values, valid, unit) =>
        Some(ColumnArray.timestamp(values, unit, valid))
      case values: GatheredVector => return values.toColumnArray(dataType)
      case _                      => None
    built match
      case None => Left(s"columnar vector ${vector.getClass.getSimpleName} has no batch form")
      case Some(result) => result.left.map(_.message)

  /** Generic fallback: rebuild a column from its scalars using the schema's declared type.
    *
    * Some vectors are views rather than storage -- a filter yields a `SelectedVector` holding an
    * input plus a selection -- so there is no array to hand over directly. Reading through `scalar`
    * costs more than a typed copy, but it is correct for every vector shape and the declared type,
    * rather than a guess from the first non-null value, decides the result. That matters for an
    * all-null column, which carries no evidence of its own type.
    */
  private def materialize(
      dataType: DataType,
      vector: ColumnarVector
  ): Either[String, ColumnArray] =
    val length = vector.length
    val valid = new Array[Boolean](length)
    var failure: Option[String] = None

    def scalars(): Array[ScalarValue] =
      val output = new Array[ScalarValue](length)
      var index = 0
      while index < length && failure.isEmpty do
        vector.scalar(index) match
          case Right(value) =>
            output(index) = value
            valid(index) = value != ScalarValue.Null
          case Left(error) => failure = Some(error.message)
        index += 1
      output

    val values = scalars()
    failure match
      case Some(reason) => Left(reason)
      case None         =>
        val built: Either[StorageError, ColumnArray] = dataType match
          case DataType.Int32 =>
            val output = new Array[Int](length)
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Int32(actual) => output(index) = actual
                case _                         => ()
              index += 1
            ColumnArray.int32(output, valid)
          case DataType.Int64 =>
            val output = new Array[Long](length)
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Int64(actual) => output(index) = actual
                case _                         => ()
              index += 1
            ColumnArray.int64(output, valid)
          case DataType.Float32 =>
            val output = new Array[Float](length)
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Float32(actual) => output(index) = actual
                case _                           => ()
              index += 1
            ColumnArray.float32(output, valid)
          case DataType.Float64 =>
            val output = new Array[Double](length)
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Float64(actual) => output(index) = actual
                case _                           => ()
              index += 1
            ColumnArray.float64(output, valid)
          case DataType.Bool =>
            val output = new Array[Boolean](length)
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Bool(actual) => output(index) = actual
                case _                        => ()
              index += 1
            ColumnArray.bool(output, valid)
          case DataType.Utf8 =>
            // Null slots still need a non-null placeholder: the builder encodes every entry
            // before the validity bitmap decides which ones count.
            val output = Array.fill(length)("")
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Utf8(actual) => output(index) = actual
                case _                        => ()
              index += 1
            ColumnArray.utf8(output, valid)
          case DataType.Timestamp(unit) =>
            val output = new Array[Long](length)
            var index = 0
            while index < length do
              values(index) match
                case ScalarValue.Timestamp(actual, _) => output(index) = actual
                case _                                => ()
              index += 1
            ColumnArray.timestamp(output, unit, valid)
        built.left.map(_.message)

final private[frame4s] case class ColumnarBatch(
    columns: Vector[ColumnarVector],
    rowCount: Int
)

private[frame4s] object ColumnarBatch:
  def copyScalars(batch: RecordBatch): Either[ExecutionError, ColumnarBatch] =
    val columns = Vector.newBuilder[ColumnarVector]
    var column = 0
    var error: Option[ExecutionError] = None
    while column < batch.columns.length && error.isEmpty do
      val values = new Array[ScalarValue](batch.rowCount)
      var row = 0
      while row < batch.rowCount && error.isEmpty do
        batch.columns(column).scalar(row) match
          case Right(value) => values(row) = value
          case Left(value)  => error = Some(ExecutionError.Storage(value))
        row += 1
      if error.isEmpty then columns += ScalarVector(values)
      column += 1
    error.toLeft(ColumnarBatch(columns.result(), batch.rowCount))

sealed private[frame4s] trait ColumnarVector:
  def length: Int
  def scalar(index: Int): Either[ExecutionError, ScalarValue]
  def unsafeScalarHash(index: Int): Long
  def foldHash(seed: Long): Long =
    var hash = seed
    var index = 0
    while index < length do
      hash = hash * 31L + unsafeScalarHash(index)
      index += 1
    hash

final private[frame4s] case class ScalarVector(values: Array[ScalarValue]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(values(index))

  def unsafeScalarHash(index: Int): Long =
    ColumnarVector.scalarHash(values(index))

final private[frame4s] case class RawInt32Vector(
    values: Array[Byte],
    validity: Option[Array[Byte]],
    logicalOffset: Int,
    length: Int,
    valuesStart: Int = 0
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then ScalarValue.Int32(readInt(values, valuesStart + absolute * 4))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then unsafeIntValue(index).toLong
    else ColumnarVector.NullHash

  override def foldHash(seed: Long): Long =
    var hash = seed
    var index = 0
    validity match
      case None =>
        while index < length do
          hash = hash * 31L + readInt(values, valuesStart + (logicalOffset + index) * 4).toLong
          index += 1
      case Some(bytes) =>
        while index < length do
          val absolute = logicalOffset + index
          val valid = ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
          val value =
            if valid then readInt(values, valuesStart + absolute * 4).toLong
            else ColumnarVector.NullHash
          hash = hash * 31L + value
          index += 1
    hash

  def unsafeIntValue(index: Int): Int =
    readInt(values, valuesStart + (logicalOffset + index) * 4)

  def unsafeValid(index: Int): Boolean = isValid(index)

  def required: Boolean = validity.isEmpty

  private def checked(index: Int): Either[ExecutionError, Int] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(logicalOffset + index)

  private def isValid(index: Int): Boolean =
    validity.forall: bytes =>
      val absolute = logicalOffset + index
      ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1

final private[frame4s] case class RawFloat64Vector(
    values: Array[Byte],
    validity: Option[Array[Byte]],
    logicalOffset: Int,
    length: Int,
    valuesStart: Int = 0
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then
        ScalarValue.Float64(
          java.lang.Double.longBitsToDouble(readLong(values, valuesStart + absolute * 8))
        )
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then readLong(values, valuesStart + (logicalOffset + index) * 8)
    else ColumnarVector.NullHash

  def unsafeDoubleValue(index: Int): Double =
    java.lang.Double.longBitsToDouble(
      readLong(values, valuesStart + (logicalOffset + index) * 8)
    )

  def unsafeValid(index: Int): Boolean = isValid(index)

  override def foldHash(seed: Long): Long =
    var hash = seed
    var index = 0
    validity match
      case None =>
        while index < length do
          hash = hash * 31L + readLong(values, valuesStart + (logicalOffset + index) * 8)
          index += 1
      case Some(bytes) =>
        while index < length do
          val absolute = logicalOffset + index
          val valid = ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
          val value =
            if valid then readLong(values, valuesStart + absolute * 8)
            else ColumnarVector.NullHash
          hash = hash * 31L + value
          index += 1
    hash

  private def checked(index: Int): Either[ExecutionError, Int] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(logicalOffset + index)

  private def isValid(index: Int): Boolean =
    validity.forall: bytes =>
      val absolute = logicalOffset + index
      ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1

final private[frame4s] case class RawInt64Vector(
    values: Array[Byte],
    validity: Option[Array[Byte]],
    logicalOffset: Int,
    length: Int,
    timestampUnit: Option[TimeUnit],
    valuesStart: Int = 0
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then
        val value = readLong(values, valuesStart + absolute * 8)
        timestampUnit.fold[ScalarValue](ScalarValue.Int64(value))(ScalarValue.Timestamp(value, _))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then
      val value = readLong(values, valuesStart + (logicalOffset + index) * 8)
      timestampUnit.fold(value)(unit => value ^ unit.ordinal.toLong)
    else ColumnarVector.NullHash

  def unsafeLongValue(index: Int): Long =
    readLong(values, valuesStart + (logicalOffset + index) * 8)

  def unsafeValid(index: Int): Boolean = isValid(index)

  private def checked(index: Int): Either[ExecutionError, Int] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(logicalOffset + index)

  private def isValid(index: Int): Boolean =
    validity.forall: bytes =>
      val absolute = logicalOffset + index
      ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1

final private[frame4s] case class RawFloat32Vector(
    values: Array[Byte],
    validity: Option[Array[Byte]],
    logicalOffset: Int,
    length: Int,
    valuesStart: Int = 0
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then
        ScalarValue.Float32(
          java.lang.Float.intBitsToFloat(readInt(values, valuesStart + absolute * 4))
        )
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then readInt(values, valuesStart + (logicalOffset + index) * 4).toLong
    else ColumnarVector.NullHash

  def unsafeFloatValue(index: Int): Float =
    java.lang.Float.intBitsToFloat(
      readInt(values, valuesStart + (logicalOffset + index) * 4)
    )

  def unsafeValid(index: Int): Boolean = isValid(index)

  private def checked(index: Int): Either[ExecutionError, Int] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(logicalOffset + index)

  private def isValid(index: Int): Boolean =
    validity.forall: bytes =>
      val absolute = logicalOffset + index
      ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1

final private[frame4s] case class RawBooleanVector(
    values: Array[Byte],
    validity: Option[Array[Byte]],
    logicalOffset: Int,
    length: Int,
    valuesStart: Int = 0
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then ScalarValue.Bool(bit(values, valuesStart * 8 + absolute))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if !isValid(index) then ColumnarVector.NullHash
    else if bit(values, valuesStart * 8 + logicalOffset + index) then 1L
    else 2L

  def unsafeBooleanValue(index: Int): Boolean =
    bit(values, valuesStart * 8 + logicalOffset + index)

  def unsafeValid(index: Int): Boolean = isValid(index)

  private def checked(index: Int): Either[ExecutionError, Int] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(logicalOffset + index)

  private def isValid(index: Int): Boolean =
    validity.forall(bytes => bit(bytes, logicalOffset + index))

final private[frame4s] case class RawUtf8Vector(
    offsets: Array[Byte],
    values: Array[Byte],
    validity: Option[Array[Byte]],
    logicalOffset: Int,
    length: Int,
    offsetsStart: Int = 0,
    valuesStart: Int = 0
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then ScalarValue.Utf8(decode(absolute))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then unsafeStringHash(logicalOffset + index).toLong
    else ColumnarVector.NullHash

  def required: Boolean = validity.isEmpty

  def unsafeValid(index: Int): Boolean = isValid(index)

  def unsafeByteHash(index: Int): Int =
    val absolute = logicalOffset + index
    val (from, until) = unsafeBounds(absolute)
    var hash = 0x811c9dc5
    var cursor = from
    while cursor < until do
      hash = (hash ^ (values(valuesStart + cursor) & 0xff)) * 0x01000193
      cursor += 1
    hash

  /** Collision-free compact key for short UTF-8 values, or `Long.MinValue` when the value is longer
    * than seven bytes.
    *
    * The top byte stores the length and the remaining bytes store the payload. This avoids hashing
    * and byte-by-byte equality checks for the common short-key grouping case without weakening
    * equality semantics.
    */
  def unsafePackedKey(index: Int): Long =
    val absolute = logicalOffset + index
    val (from, until) = unsafeBounds(absolute)
    val length = until - from
    if length > 7 then Long.MinValue
    else
      var packed = length.toLong << 56
      var cursor = 0
      while cursor < length do
        packed |= (values(valuesStart + from + cursor) & 0xffL) << (cursor * 8)
        cursor += 1
      packed

  def unsafeBytesEqual(index: Int, other: Array[Byte]): Boolean =
    val absolute = logicalOffset + index
    val (from, until) = unsafeBounds(absolute)
    if until - from != other.length then false
    else
      var cursor = 0
      var equal = true
      while cursor < other.length && equal do
        equal = values(valuesStart + from + cursor) == other(cursor)
        cursor += 1
      equal

  def unsafeCopyBytes(index: Int): Array[Byte] =
    val absolute = logicalOffset + index
    val (from, until) = unsafeBounds(absolute)
    java.util.Arrays.copyOfRange(values, valuesStart + from, valuesStart + until)

  def unsafeCopyBytes(index: Int, target: Array[Byte], targetOffset: Int): Int =
    val absolute = logicalOffset + index
    val (from, until) = unsafeBounds(absolute)
    val length = until - from
    System.arraycopy(values, valuesStart + from, target, targetOffset, length)
    length

  def unsafeStringValue(index: Int): String =
    decode(logicalOffset + index)

  def unsafeByteLength(index: Int): Int =
    val (from, until) = unsafeBounds(logicalOffset + index)
    until - from

  def unsafeByte(index: Int, byteIndex: Int): Int =
    val (from, _) = unsafeBounds(logicalOffset + index)
    values(valuesStart + from + byteIndex) & 0xff

  private def checked(index: Int): Either[ExecutionError, Int] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(logicalOffset + index)

  private def isValid(index: Int): Boolean =
    validity.forall(bytes => bit(bytes, logicalOffset + index))

  private def decode(absolute: Int): String =
    val (from, until) = unsafeBounds(absolute)
    new String(values, valuesStart + from, until - from, "UTF-8")

  /** Compute `String.hashCode` directly from well-formed UTF-8.
    *
    * Java hashes UTF-16 code units, so supplementary code points contribute their surrogate pair.
    * Storage accepts arbitrary byte buffers; malformed input therefore takes the exact decoding
    * fallback instead of letting this optimized path invent different replacement semantics.
    */
  private def unsafeStringHash(absolute: Int): Int =
    val (from, until) = unsafeBounds(absolute)
    var hash = 0
    var cursor = from
    var valid = true
    while cursor < until && valid do
      val first = values(valuesStart + cursor) & 0xff
      if first < 0x80 then
        hash = hash * 31 + first
        cursor += 1
      else if first >= 0xc2 && first <= 0xdf && cursor + 1 < until then
        val second = values(valuesStart + cursor + 1) & 0xff
        if continuation(second) then
          hash = hash * 31 + (((first & 0x1f) << 6) | (second & 0x3f))
          cursor += 2
        else valid = false
      else if first >= 0xe0 && first <= 0xef && cursor + 2 < until then
        val second = values(valuesStart + cursor + 1) & 0xff
        val third = values(valuesStart + cursor + 2) & 0xff
        val legalSecond =
          if first == 0xe0 then second >= 0xa0 && second <= 0xbf
          else if first == 0xed then second >= 0x80 && second <= 0x9f
          else continuation(second)
        if legalSecond && continuation(third) then
          hash = hash * 31 +
            (((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f))
          cursor += 3
        else valid = false
      else if first >= 0xf0 && first <= 0xf4 && cursor + 3 < until then
        val second = values(valuesStart + cursor + 1) & 0xff
        val third = values(valuesStart + cursor + 2) & 0xff
        val fourth = values(valuesStart + cursor + 3) & 0xff
        val legalSecond =
          if first == 0xf0 then second >= 0x90 && second <= 0xbf
          else if first == 0xf4 then second >= 0x80 && second <= 0x8f
          else continuation(second)
        if legalSecond && continuation(third) && continuation(fourth) then
          val codePoint =
            ((first & 0x07) << 18) |
              ((second & 0x3f) << 12) |
              ((third & 0x3f) << 6) |
              (fourth & 0x3f)
          val supplementary = codePoint - 0x10000
          val high = 0xd800 | (supplementary >>> 10)
          val low = 0xdc00 | (supplementary & 0x3ff)
          hash = (hash * 31 + high) * 31 + low
          cursor += 4
        else valid = false
      else valid = false
    if valid then hash else decode(absolute).hashCode

  private def continuation(value: Int): Boolean =
    value >= 0x80 && value <= 0xbf

  private def unsafeBounds(absolute: Int): (Int, Int) =
    (
      readInt(offsets, offsetsStart + absolute * 4),
      readInt(offsets, offsetsStart + (absolute + 1) * 4)
    )

final private[frame4s] case class Int32Values(values: Array[Int]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Int32(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index).toLong

final private[frame4s] case class NullableInt32Values(
    values: Array[Int],
    valid: Array[Boolean]
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Int32(values(index)))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if valid(index) then values(index).toLong
    else ColumnarVector.NullHash

final private[frame4s] case class Int64Values(values: Array[Long]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Int64(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index)

final private[frame4s] case class NullableInt64Values(
    values: Array[Long],
    valid: Array[Boolean]
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Int64(values(index)))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if valid(index) then values(index)
    else ColumnarVector.NullHash

final private[frame4s] case class Float32Values(
    values: Array[Float],
    valid: Array[Boolean]
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Float32(values(index)))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if valid(index) then java.lang.Float.floatToRawIntBits(values(index)).toLong
    else ColumnarVector.NullHash

final private[frame4s] case class TimestampValues(
    values: Array[Long],
    valid: Array[Boolean],
    unit: TimeUnit
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Timestamp(values(index), unit))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if valid(index) then values(index) ^ unit.ordinal.toLong
    else ColumnarVector.NullHash

final private[frame4s] case class Float64Values(
    values: Array[Double],
    valid: Array[Boolean]
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Float64(values(index)))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if valid(index) then java.lang.Double.doubleToRawLongBits(values(index))
    else ColumnarVector.NullHash

final private[frame4s] case class BooleanValues(
    values: Array[Boolean],
    valid: Array[Boolean]
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Bool(values(index)))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if !valid(index) then ColumnarVector.NullHash
    else if values(index) then 1L
    else 2L

final private[frame4s] case class Utf8Values(values: Array[String]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Utf8(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index).hashCode.toLong

final private[frame4s] case class NullableUtf8Values(
    values: Array[String],
    valid: Array[Boolean]
) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if valid(index) then Right(ScalarValue.Utf8(values(index)))
    else Right(ScalarValue.Null)

  def unsafeScalarHash(index: Int): Long =
    if valid(index) then values(index).hashCode.toLong
    else ColumnarVector.NullHash

final private[frame4s] case class SelectedVector(
    input: ColumnarVector,
    selected: Array[Int]
) extends ColumnarVector:
  val length: Int = selected.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else input.scalar(selected(index))

  def unsafeScalarHash(index: Int): Long =
    input.unsafeScalarHash(selected(index))

  override def foldHash(seed: Long): Long =
    var hash = seed
    var index = 0
    while index < length do
      hash = hash * 31L + input.unsafeScalarHash(selected(index))
      index += 1
    hash

/** A detached columnar gather view over primitive row selections.
  *
  * Join probing records batch and row ordinals once. Every output column shares those primitive
  * arrays and resolves values lazily, avoiding row-wise `ScalarValue` arrays and a transpose pass.
  * Nullable right-side gathers carry a validity bitmap instead of a sentinel ordinal.
  */
final private[frame4s] case class GatheredVector(
    batches: Vector[DecodedBatch],
    columnIndex: Int,
    selected: JoinRowSelection,
    length: Int,
    validity: Option[Array[Byte]]
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if !isValid(index) then Right(ScalarValue.Null)
    else source(index).scalar(selected.row(index))

  def unsafeScalarHash(index: Int): Long =
    if !isValid(index) then ColumnarVector.NullHash
    else source(index).unsafeScalarHash(selected.row(index))

  private def source(index: Int): ColumnarVector =
    sourceBatch(selected.batch(index))

  private def sourceBatch(batch: Int): ColumnarVector =
    batches(batch).columns(columnIndex)

  private def isValid(index: Int): Boolean =
    validity.forall(bytes => bit(bytes, index))

  /** Materialize a gathered join column directly into fresh owned physical buffers.
    *
    * The output buffers contain no aliases to the decoded inputs. Selection validity and source
    * validity are combined while copying, so left-outer nulls and nullable source values preserve
    * the same Arrow validity contract as scalar materialization.
    */
  def toColumnArray(dataType: DataType): Either[String, ColumnArray] =
    dataType match
      case DataType.Int32           => gatherInt32()
      case DataType.Int64           => gatherInt64()
      case DataType.Float32         => gatherFloat32()
      case DataType.Float64         => gatherFloat64()
      case DataType.Bool            => gatherBoolean()
      case DataType.Utf8            => gatherUtf8()
      case DataType.Timestamp(unit) => gatherTimestamp(unit)

  private def gatherInt32(): Either[String, ColumnArray] =
    val values = new Array[Byte](length * 4)
    gatherFixed(values, "Int32"):
      case (source: RawInt32Vector, sourceRow, outputRow) =>
        if source.unsafeValid(sourceRow) then
          writeInt(values, outputRow * 4, source.unsafeIntValue(sourceRow))
          true
        else false
    .flatMap: (valid, nullCount) =>
      ColumnArray.int32FromFresh(values, length, valid, nullCount).left.map(_.message)

  private def gatherInt64(): Either[String, ColumnArray] =
    val values = new Array[Byte](length * 8)
    gatherFixed(values, "Int64"):
      case (source: RawInt64Vector, sourceRow, outputRow) if source.timestampUnit.isEmpty =>
        if source.unsafeValid(sourceRow) then
          writeLong(values, outputRow * 8, source.unsafeLongValue(sourceRow))
          true
        else false
    .flatMap: (valid, nullCount) =>
      ColumnArray.int64FromFresh(values, length, valid, nullCount).left.map(_.message)

  private def gatherFloat32(): Either[String, ColumnArray] =
    val values = new Array[Byte](length * 4)
    gatherFixed(values, "Float32"):
      case (source: RawFloat32Vector, sourceRow, outputRow) =>
        if source.unsafeValid(sourceRow) then
          writeInt(
            values,
            outputRow * 4,
            java.lang.Float.floatToRawIntBits(source.unsafeFloatValue(sourceRow))
          )
          true
        else false
    .flatMap: (valid, nullCount) =>
      ColumnArray.float32FromFresh(values, length, valid, nullCount).left.map(_.message)

  private def gatherFloat64(): Either[String, ColumnArray] =
    val values = new Array[Byte](length * 8)
    gatherFixed(values, "Float64"):
      case (source: RawFloat64Vector, sourceRow, outputRow) =>
        if source.unsafeValid(sourceRow) then
          writeLong(
            values,
            outputRow * 8,
            java.lang.Double.doubleToRawLongBits(source.unsafeDoubleValue(sourceRow))
          )
          true
        else false
    .flatMap: (valid, nullCount) =>
      ColumnArray.float64FromFresh(values, length, valid, nullCount).left.map(_.message)

  private def gatherBoolean(): Either[String, ColumnArray] =
    val values = new Array[Byte]((length + 7) >>> 3)
    gatherFixed(values, "Bool"):
      case (source: RawBooleanVector, sourceRow, outputRow) =>
        if source.unsafeValid(sourceRow) then
          if source.unsafeBooleanValue(sourceRow) then setBit(values, outputRow)
          true
        else false
    .flatMap: (valid, nullCount) =>
      ColumnArray.boolFromFresh(values, length, valid, nullCount).left.map(_.message)

  private def gatherTimestamp(unit: TimeUnit): Either[String, ColumnArray] =
    val values = new Array[Byte](length * 8)
    gatherFixed(values, s"Timestamp($unit)"):
      case (source: RawInt64Vector, sourceRow, outputRow) if source.timestampUnit.contains(unit) =>
        if source.unsafeValid(sourceRow) then
          writeLong(values, outputRow * 8, source.unsafeLongValue(sourceRow))
          true
        else false
    .flatMap: (valid, nullCount) =>
      ColumnArray
        .timestampFromFresh(values, length, unit, valid, nullCount)
        .left
        .map(_.message)

  private def gatherUtf8(): Either[String, ColumnArray] =
    val valid = new Array[Byte]((length + 7) >>> 3)
    val chunks = gatherChunkStarts()
    val chunkCount = chunks.length - 1
    val nullCounts = new Array[Int](chunkCount)
    val byteTotals = new Array[Long](chunkCount)
    val failures = new Array[String](chunkCount)
    runGatherChunks(chunkCount): ordinal =>
      measureUtf8Range(valid, chunks(ordinal), chunks(ordinal + 1)) match
        case Left(reason)          => failures(ordinal) = reason
        case Right((nulls, bytes)) =>
          nullCounts(ordinal) = nulls
          byteTotals(ordinal) = bytes

    val failure = firstFailure(failures)
    val total = byteTotals.sum
    if failure != null then Left(failure)
    else if total > Int.MaxValue.toLong then
      Left("gathered UTF-8 output exceeds the supported byte length")
    else
      val offsets = new Array[Byte]((length + 1) * 4)
      val values = new Array[Byte](total.toInt)
      val bases = new Array[Long](chunkCount)
      var chunk = 0
      var base = 0L
      while chunk < chunkCount do
        bases(chunk) = base
        base += byteTotals(chunk)
        chunk += 1
      runGatherChunks(chunkCount): ordinal =>
        writeUtf8Range(
          valid,
          offsets,
          values,
          chunks(ordinal),
          chunks(ordinal + 1),
          bases(ordinal).toInt
        )
      writeInt(offsets, length * 4, total.toInt)
      ColumnArray
        .utf8FromFresh(offsets, values, length, valid, nullCounts.sum)
        .left
        .map(_.message)

  private def measureUtf8Range(
      valid: Array[Byte],
      from: Int,
      until: Int
  ): Either[String, (Int, Long)] =
    var total = 0L
    var nullCount = 0
    var index = from
    var failure: String = null
    val selection = selected.cursorAt(from)
    while index < until && failure == null do
      if !isValid(index) then nullCount += 1
      else
        sourceBatch(selection.batch) match
          case value: RawUtf8Vector =>
            val sourceRow = selection.row
            if value.unsafeValid(sourceRow) then
              setBit(valid, index)
              total += value.unsafeByteLength(sourceRow).toLong
            else nullCount += 1
          case value =>
            failure = s"gathered Utf8 column contains ${value.getClass.getSimpleName}"
      selection.advance()
      index += 1
    if failure != null then Left(failure) else Right((nullCount, total))

  private def writeUtf8Range(
      valid: Array[Byte],
      offsets: Array[Byte],
      values: Array[Byte],
      from: Int,
      until: Int,
      base: Int
  ): Unit =
    var cursor = base
    var index = from
    val selection = selected.cursorAt(from)
    while index < until do
      writeInt(offsets, index * 4, cursor)
      if bit(valid, index) then
        val input = sourceBatch(selection.batch).asInstanceOf[RawUtf8Vector]
        cursor += input.unsafeCopyBytes(selection.row, values, cursor)
      selection.advance()
      index += 1

  private def gatherFixed(
      values: Array[Byte],
      expected: String
  )(
      copy: PartialFunction[(ColumnarVector, Int, Int), Boolean]
  ): Either[String, (Array[Byte], Int)] =
    val valid = new Array[Byte]((length + 7) >>> 3)
    val chunks = gatherChunkStarts()
    val chunkCount = chunks.length - 1
    val nullCounts = new Array[Int](chunkCount)
    val failures = new Array[String](chunkCount)
    runGatherChunks(chunkCount): ordinal =>
      gatherFixedRange(valid, chunks(ordinal), chunks(ordinal + 1), expected)(copy) match
        case Left(reason)     => failures(ordinal) = reason
        case Right(nullCount) => nullCounts(ordinal) = nullCount
    val failure = firstFailure(failures)
    if failure != null then Left(failure) else Right((valid, nullCounts.sum))

  private def gatherFixedRange(
      valid: Array[Byte],
      from: Int,
      until: Int,
      expected: String
  )(
      copy: PartialFunction[(ColumnarVector, Int, Int), Boolean]
  ): Either[String, Int] =
    var nullCount = 0
    var index = from
    var failure: String = null
    val selection = selected.cursorAt(from)
    while index < until && failure == null do
      if !isValid(index) then nullCount += 1
      else
        val input = sourceBatch(selection.batch)
        val sourceRow = selection.row
        val arguments = (input, sourceRow, index)
        if !copy.isDefinedAt(arguments) then
          failure = s"gathered $expected column contains ${input.getClass.getSimpleName}"
        else if copy(arguments) then setBit(valid, index)
        else nullCount += 1
      selection.advance()
      index += 1
    if failure != null then Left(failure) else Right(nullCount)

  /** Chunk boundaries for parallel gathering, aligned to 64 output rows.
    *
    * Alignment keeps every validity and Bool bit any two chunks write in disjoint bytes, so workers
    * never share a read-modify-write byte and the result is byte-identical to the sequential pass.
    */
  private def gatherChunkStarts(): Array[Int] =
    val workers = Parallelism.partitions(length, Scheduler.default)
    if workers <= 1 then Array(0, length)
    else
      val target = math.max(Parallelism.MinimumChunkRows, length / (workers * 4) + 1)
      val aligned = (target + 63) & ~63
      val count = ((length + aligned - 1) / aligned).max(1)
      val starts = new Array[Int](count + 1)
      var index = 0
      while index < count do
        starts(index) = index * aligned
        index += 1
      starts(count) = length
      starts

  private def runGatherChunks(chunkCount: Int)(work: Int => Unit): Unit =
    if chunkCount <= 1 then
      if chunkCount == 1 then work(0)
    else
      val tasks = new Array[Runnable](chunkCount)
      var chunk = 0
      while chunk < chunkCount do
        val ordinal = chunk
        tasks(chunk) = () => work(ordinal)
        chunk += 1
      Scheduler.default.runAll(tasks)

  private def firstFailure(failures: Array[String]): String =
    var index = 0
    while index < failures.length do
      if failures(index) != null then return failures(index)
      index += 1
    null

final private[frame4s] case class RawDictionaryVector(
    indices: RawInt32Vector,
    dictionary: ColumnarVector
) extends ColumnarVector:
  val length: Int = indices.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if !indices.unsafeValid(index) then Right(ScalarValue.Null)
    else
      val dictionaryIndex = indices.unsafeIntValue(index)
      if dictionaryIndex < 0 || dictionaryIndex >= dictionary.length then
        Left(
          ExecutionError.Storage(
            StorageError.InvalidDictionaryIndex(index, dictionaryIndex, dictionary.length)
          )
        )
      else dictionary.scalar(dictionaryIndex)

  def unsafeScalarHash(index: Int): Long =
    if !indices.unsafeValid(index) then ColumnarVector.NullHash
    else dictionary.unsafeScalarHash(indices.unsafeIntValue(index))

private[frame4s] enum ColumnBorrowError:
  case Storage(error: StorageError)
  case Residual(reason: String)

private[frame4s] object ColumnarVector:
  val NullHash = 0x61c8864680b583ebL

  /** Nest buffer read scopes so a multi-column eager kernel can read without cloning its inputs.
    *
    * The callback must produce detached output. No returned value may retain a borrowed vector or
    * its backing arrays.
    */
  def withBorrowedColumns[A](
      batch: RecordBatch,
      indices: Vector[Int]
  )(
      operation: Array[ColumnarVector] => A
  ): Either[ColumnBorrowError, A] =
    val vectors = new Array[ColumnarVector](indices.length)

    def loop(position: Int): Either[ColumnBorrowError, A] =
      if position == indices.length then Right(operation(vectors))
      else
        val index = indices(position)
        batch.columns.lift(index) match
          case None =>
            Left(
              ColumnBorrowError.Residual(
                s"columnar input $index is outside a ${batch.columns.length}-column batch"
              )
            )
          case Some(column) =>
            withBorrowed(column): vector =>
              vectors(position) = vector
              loop(position + 1)

    loop(0)

  private def withBorrowed[A](
      column: ColumnArray
  )(
      operation: ColumnarVector => Either[ColumnBorrowError, A]
  ): Either[ColumnBorrowError, A] =
    def scoped(
        result: Either[StorageError, Either[ColumnBorrowError, A]]
    ): Either[ColumnBorrowError, A] =
      result.left
        .map(ColumnBorrowError.Storage.apply)
        .flatMap(identity)

    column match
      case value: Int32Array =>
        scoped:
          value.withBorrowedValueBytes: (bytes, start, validity, logicalOffset, length) =>
            operation(RawInt32Vector(bytes, validity, logicalOffset, length, start))
      case value: Int64Array =>
        scoped:
          value.withBorrowedValueBytes: (bytes, start, validity, logicalOffset, length) =>
            operation(RawInt64Vector(bytes, validity, logicalOffset, length, None, start))
      case value: TimestampArray =>
        scoped:
          value.withBorrowedValueBytes: (bytes, start, validity, logicalOffset, length) =>
            operation(
              RawInt64Vector(bytes, validity, logicalOffset, length, Some(value.unit), start)
            )
      case value: Float32Array =>
        scoped:
          value.withBorrowedValueBytes: (bytes, start, validity, logicalOffset, length) =>
            operation(RawFloat32Vector(bytes, validity, logicalOffset, length, start))
      case value: Float64Array =>
        scoped:
          value.withBorrowedValueBytes: (bytes, start, validity, logicalOffset, length) =>
            operation(RawFloat64Vector(bytes, validity, logicalOffset, length, start))
      case value: BooleanArray =>
        scoped:
          value.withBorrowedValueBytes: (bytes, start, validity, logicalOffset, length) =>
            operation(RawBooleanVector(bytes, validity, logicalOffset, length, start))
      case value: Utf8Array =>
        scoped:
          value.withBorrowedUtf8BytesAndValidity:
            (
                offsets,
                offsetsStart,
                bytes,
                valuesStart,
                validity,
                logicalOffset,
                length
            ) =>
              operation(
                RawUtf8Vector(
                  offsets,
                  bytes,
                  validity,
                  logicalOffset,
                  length,
                  offsetsStart,
                  valuesStart
                )
              )
      case other =>
        Left(
          ColumnBorrowError.Residual(
            s"unsupported borrowed ${other.dataType} encoding ${other.encoding}"
          )
        )

  def compact(
      input: ColumnarVector,
      field: Field,
      selected: Array[Int]
  ): Either[String, ColumnarVector] = input match
    case values: RawInt32Vector if field.dataType == DataType.Int32 =>
      val output = new Array[Int](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length do
        valid(row) = values.unsafeValid(selected(row))
        if valid(row) then output(row) = values.unsafeIntValue(selected(row))
        row += 1
      if field.nullable then Right(NullableInt32Values(output, valid))
      else Right(Int32Values(output))
    case values: RawInt64Vector if field.dataType == DataType.Int64 =>
      val output = new Array[Long](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length do
        valid(row) = values.unsafeValid(selected(row))
        if valid(row) then output(row) = values.unsafeLongValue(selected(row))
        row += 1
      if field.nullable then Right(NullableInt64Values(output, valid))
      else Right(Int64Values(output))
    case values: RawInt64Vector =>
      field.dataType match
        case DataType.Timestamp(unit) if values.timestampUnit.contains(unit) =>
          val output = new Array[Long](selected.length)
          val valid = new Array[Boolean](selected.length)
          var row = 0
          while row < selected.length do
            valid(row) = values.unsafeValid(selected(row))
            if valid(row) then output(row) = values.unsafeLongValue(selected(row))
            row += 1
          Right(TimestampValues(output, valid, unit))
        case _ => Left(s"cannot compact Int64 input as ${field.dataType}")
    case values: RawFloat32Vector if field.dataType == DataType.Float32 =>
      val output = new Array[Float](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length do
        valid(row) = values.unsafeValid(selected(row))
        if valid(row) then output(row) = values.unsafeFloatValue(selected(row))
        row += 1
      Right(Float32Values(output, valid))
    case values: RawFloat64Vector if field.dataType == DataType.Float64 =>
      val output = new Array[Double](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length do
        valid(row) = values.unsafeValid(selected(row))
        if valid(row) then output(row) = values.unsafeDoubleValue(selected(row))
        row += 1
      Right(Float64Values(output, valid))
    case values: RawBooleanVector if field.dataType == DataType.Bool =>
      val output = new Array[Boolean](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length do
        valid(row) = values.unsafeValid(selected(row))
        if valid(row) then output(row) = values.unsafeBooleanValue(selected(row))
        row += 1
      Right(BooleanValues(output, valid))
    case values: RawUtf8Vector if field.dataType == DataType.Utf8 =>
      Right(compactUtf8(values, field.nullable, selected))
    case other =>
      Left(s"cannot compact ${other.getClass.getSimpleName} as ${field.dataType}")

  private def compactUtf8(
      input: RawUtf8Vector,
      nullable: Boolean,
      selected: Array[Int]
  ): RawUtf8Vector =
    var valueBytes = 0
    var row = 0
    while row < selected.length do
      if input.unsafeValid(selected(row)) then valueBytes += input.unsafeByteLength(selected(row))
      row += 1
    val offsets = new Array[Byte]((selected.length + 1) * 4)
    val values = new Array[Byte](valueBytes)
    val validity =
      if nullable then Some(new Array[Byte]((selected.length + 7) >>> 3))
      else None
    var cursor = 0
    row = 0
    while row < selected.length do
      val source = selected(row)
      writeInt(offsets, row * 4, cursor)
      if input.unsafeValid(source) then
        validity.foreach(bytes => setBit(bytes, row))
        cursor += input.unsafeCopyBytes(source, values, cursor)
      row += 1
    writeInt(offsets, selected.length * 4, cursor)
    RawUtf8Vector(offsets, values, validity, logicalOffset = 0, length = selected.length)

  def copy(column: ColumnArray): Either[String, ColumnarVector] =
    column match
      case dictionary: DictionaryArray =>
        (
          copy(dictionary.columnarIndices),
          copy(dictionary.columnarDictionary)
        ) match
          case (Left(reason), _) => Left(s"dictionary indices: $reason")
          case (_, Left(reason)) => Left(s"dictionary values: $reason")
          case (Right(indices: RawInt32Vector), Right(values)) =>
            Right(RawDictionaryVector(indices, values))
          case (Right(_), _) => Left("dictionary indices are not Int32")
      case _ => copyPlain(column)

  private def copyPlain(column: ColumnArray): Either[String, ColumnarVector] =
    column.encoding match
      case PhysicalEncoding.Plain =>
        val layout = column.layout
        column.copyPhysicalBuffers.left
          .map(_.message)
          .flatMap: buffers =>
            val zipped = layout.buffers.zip(buffers)
            val validity = zipped.collectFirst {
              case (buffer, bytes) if buffer.role == BufferRole.Validity =>
                bytes
            }
            val valueBuffer =
              zipped.collectFirst {
                case (buffer, bytes) if buffer.role == BufferRole.Values =>
                  bytes
              }
            column.dataType match
              case DataType.Int32 =>
                valueBuffer
                  .map(RawInt32Vector(_, validity, layout.logicalOffset, column.length))
                  .toRight("plain Int32 column does not expose a values buffer")
              case DataType.Int64 =>
                valueBuffer
                  .map(RawInt64Vector(_, validity, layout.logicalOffset, column.length, None))
                  .toRight("plain Int64 column does not expose a values buffer")
              case DataType.Float32 =>
                valueBuffer
                  .map(RawFloat32Vector(_, validity, layout.logicalOffset, column.length))
                  .toRight("plain Float32 column does not expose a values buffer")
              case DataType.Float64 =>
                valueBuffer
                  .map(RawFloat64Vector(_, validity, layout.logicalOffset, column.length))
                  .toRight("plain Float64 column does not expose a values buffer")
              case DataType.Bool =>
                valueBuffer
                  .map(RawBooleanVector(_, validity, layout.logicalOffset, column.length))
                  .toRight("plain boolean column does not expose a values buffer")
              case DataType.Utf8 =>
                val offsets = zipped.collectFirst {
                  case (buffer, bytes) if buffer.role == BufferRole.Offsets =>
                    bytes
                }
                (offsets, valueBuffer) match
                  case (Some(offsetBytes), Some(valueBytes)) =>
                    Right(
                      RawUtf8Vector(
                        offsetBytes,
                        valueBytes,
                        validity,
                        layout.logicalOffset,
                        column.length
                      )
                    )
                  case _ => Left("plain UTF-8 column does not expose offsets and values buffers")
              case DataType.Timestamp(unit) =>
                valueBuffer
                  .map(
                    RawInt64Vector(
                      _,
                      validity,
                      layout.logicalOffset,
                      column.length,
                      Some(unit)
                    )
                  )
                  .toRight("plain timestamp column does not expose a values buffer")
      case other => Left(s"unsupported physical encoding $other")

  def scalarHash(value: ScalarValue): Long = value match
    case ScalarValue.Null            => NullHash
    case ScalarValue.Bool(actual)    => if actual then 1L else 2L
    case ScalarValue.Int32(actual)   => actual.toLong
    case ScalarValue.Int64(actual)   => actual
    case ScalarValue.Float32(actual) =>
      java.lang.Float.floatToRawIntBits(actual).toLong
    case ScalarValue.Float64(actual) =>
      java.lang.Double.doubleToRawLongBits(actual)
    case ScalarValue.Utf8(actual)            => actual.hashCode.toLong
    case ScalarValue.Timestamp(actual, unit) => actual ^ unit.ordinal.toLong

private[frame4s] def readInt(bytes: Array[Byte], offset: Int): Int =
  (bytes(offset) & 0xff) |
    ((bytes(offset + 1) & 0xff) << 8) |
    ((bytes(offset + 2) & 0xff) << 16) |
    ((bytes(offset + 3) & 0xff) << 24)

private[frame4s] def readLong(bytes: Array[Byte], offset: Int): Long =
  (readInt(bytes, offset).toLong & 0xffffffffL) |
    (readInt(bytes, offset + 4).toLong << 32)

private[frame4s] def writeInt(bytes: Array[Byte], offset: Int, value: Int): Unit =
  bytes(offset) = value.toByte
  bytes(offset + 1) = (value >>> 8).toByte
  bytes(offset + 2) = (value >>> 16).toByte
  bytes(offset + 3) = (value >>> 24).toByte

private[frame4s] def writeLong(bytes: Array[Byte], offset: Int, value: Long): Unit =
  writeInt(bytes, offset, value.toInt)
  writeInt(bytes, offset + 4, (value >>> 32).toInt)

private[frame4s] def bit(bytes: Array[Byte], index: Int): Boolean =
  ((bytes(index >>> 3).toInt >>> (index & 7)) & 1) == 1

private[frame4s] def setBit(bytes: Array[Byte], index: Int): Unit =
  val byteIndex = index >>> 3
  bytes(byteIndex) = (bytes(byteIndex) | (1 << (index & 7))).toByte
