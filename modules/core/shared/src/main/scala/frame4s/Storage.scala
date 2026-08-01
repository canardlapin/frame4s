package frame4s

import scala.NamedTuple
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

enum StorageError:
  case BufferClosed
  case InvalidRange(offset: Int, length: Int, available: Int)
  case InvalidValidityLength(expected: Int, actual: Int)
  case ColumnLengthMismatch(expected: Int, actual: Int, column: Int)
  case ColumnCountMismatch(expected: Int, actual: Int)
  case ColumnTypeMismatch(column: Int, expected: DataType, actual: DataType)
  case RequiredColumnContainsNull(column: Int, nullCount: Int)
  case SchemaMismatch(expected: Schema, actual: Schema)
  case ColumnNotFound(name: String)
  case NullValue(index: Int)
  case InvalidUtf8Offsets(index: Int, start: Int, end: Int, available: Int)
  case InvalidDictionaryIndex(index: Int, value: Int, dictionarySize: Int)
  case DictionaryContainsNull(nullCount: Int)
  case SourceAlreadyOpened
  case SourceClosed
  case Unexpected(error: String)

  def message: String = this match
    case BufferClosed                            => "buffer is closed"
    case InvalidRange(offset, length, available) =>
      s"range offset=$offset length=$length exceeds available length $available"
    case InvalidValidityLength(expected, actual) =>
      s"validity length $actual does not match value length $expected"
    case ColumnLengthMismatch(expected, actual, column) =>
      s"column $column length $actual does not match batch length $expected"
    case ColumnCountMismatch(expected, actual) =>
      s"column count $actual does not match schema field count $expected"
    case ColumnTypeMismatch(column, expected, actual) =>
      s"column $column has type $actual; expected $expected"
    case RequiredColumnContainsNull(column, nullCount) =>
      s"required column $column contains $nullCount null values"
    case SchemaMismatch(expected, actual) =>
      s"batch schema $actual does not match table schema $expected"
    case ColumnNotFound(name)                             => s"column '$name' does not exist"
    case NullValue(index)                                 => s"value at index $index is null"
    case InvalidUtf8Offsets(index, start, end, available) =>
      s"UTF-8 offsets at $index are invalid: $start..$end within $available bytes"
    case InvalidDictionaryIndex(index, value, dictionarySize) =>
      s"dictionary index at $index is $value; dictionary size is $dictionarySize"
    case DictionaryContainsNull(nullCount) =>
      s"dictionary values contain $nullCount null values; nulls belong in the index vector"
    case SourceAlreadyOpened => "owned batch source has already been opened"
    case SourceClosed        => "batch source is closed"
    case Unexpected(error)   => error

enum BufferOwnership:
  case Owned
  case Borrowed

final case class BufferSnapshot(activeOwners: Int, activeViews: Int, releasedOwners: Long)

final class BufferTracker:
  private var owners = 0
  private var views = 0
  private var released = 0L

  private[frame4s] def ownerOpened(): Unit = synchronized:
    owners += 1

  private[frame4s] def ownerReleased(): Unit = synchronized:
    owners -= 1
    released += 1

  private[frame4s] def viewOpened(): Unit = synchronized:
    views += 1

  private[frame4s] def viewClosed(): Unit = synchronized:
    views -= 1

  def snapshot: BufferSnapshot = synchronized:
    BufferSnapshot(owners, views, released)

final private class BufferState(
    val bytes: Array[Byte],
    val ownership: BufferOwnership,
    tracker: BufferTracker,
    releaseExternal: () => Unit
):
  private var references = 1
  private var released = false

  tracker.ownerOpened()

  def retain(): Either[StorageError, Unit] = synchronized:
    if released then Left(StorageError.BufferClosed)
    else
      references += 1
      Right(())

  def release(): Unit = synchronized:
    if !released then
      references -= 1
      if references == 0 then
        released = true
        releaseExternal()
        tracker.ownerReleased()

  def isReleased: Boolean = synchronized(released)

final class Buffer private (
    private val state: BufferState,
    val offset: Int,
    val length: Int,
    val ownership: BufferOwnership,
    private val tracker: BufferTracker
):
  private var closed = false
  tracker.viewOpened()

  def isClosed: Boolean = synchronized(closed || state.isReleased)

  def slice(relativeOffset: Int, sliceLength: Int): Either[StorageError, Buffer] = synchronized:
    if isClosed then Left(StorageError.BufferClosed)
    else if relativeOffset < 0 || sliceLength < 0 || relativeOffset + sliceLength > length then
      Left(StorageError.InvalidRange(relativeOffset, sliceLength, length))
    else
      state
        .retain()
        .map: _ =>
          new Buffer(state, offset + relativeOffset, sliceLength, ownership, tracker)

  private[frame4s] def read[A](operation: (Array[Byte], Int) => A): Either[StorageError, A] =
    synchronized:
      if isClosed then Left(StorageError.BufferClosed)
      else Right(operation(state.bytes, offset))

  def byteAt(index: Int): Either[StorageError, Byte] =
    if index < 0 || index >= length then Left(StorageError.InvalidRange(index, 1, length))
    else read((bytes, start) => bytes(start + index))

  def copyBytes: Either[StorageError, Array[Byte]] =
    read((bytes, start) => java.util.Arrays.copyOfRange(bytes, start, start + length))

  def close(): Unit = synchronized:
    if !closed then
      closed = true
      state.release()
      tracker.viewClosed()

object Buffer:
  def owned(bytes: Array[Byte], tracker: BufferTracker = new BufferTracker): Buffer =
    val copied = bytes.clone()
    val state = new BufferState(copied, BufferOwnership.Owned, tracker, () => ())
    new Buffer(state, 0, copied.length, BufferOwnership.Owned, tracker)

  /** Take ownership of a newly allocated buffer without copying it.
    *
    * Package-internal kernels may use this only when the array was allocated for the returned
    * buffer and no alias escapes. Public builders retain their defensive-copy contract.
    */
  private[frame4s] def ownedFresh(
      bytes: Array[Byte],
      tracker: BufferTracker = new BufferTracker
  ): Buffer =
    val state = new BufferState(bytes, BufferOwnership.Owned, tracker, () => ())
    new Buffer(state, 0, bytes.length, BufferOwnership.Owned, tracker)

  def borrowed(
      bytes: Array[Byte],
      release: () => Unit,
      tracker: BufferTracker = new BufferTracker
  ): Buffer =
    val state = new BufferState(bytes, BufferOwnership.Borrowed, tracker, release)
    new Buffer(state, 0, bytes.length, BufferOwnership.Borrowed, tracker)

enum PhysicalEncoding:
  case Plain
  case Dictionary(indexType: DataType, valueType: DataType)

enum BufferRole:
  case Validity
  case Offsets
  case Values
  case DictionaryIndices
  case DictionaryValues

final case class BufferLayout(role: BufferRole, byteLength: Int, bitWidth: Int)

final case class ArrayLayout(
    dataType: DataType,
    encoding: PhysicalEncoding,
    logicalOffset: Int,
    length: Int,
    buffers: Vector[BufferLayout]
)

enum ScalarValue:
  case Null
  case Bool(value: Boolean)
  case Int32(value: Int)
  case Int64(value: Long)
  case Float32(value: Float)
  case Float64(value: Double)
  case Utf8(value: String)
  case Timestamp(value: Long, unit: TimeUnit)

private object LittleEndian:
  def int(bytes: Array[Byte], offset: Int): Int =
    (bytes(offset) & 0xff) |
      ((bytes(offset + 1) & 0xff) << 8) |
      ((bytes(offset + 2) & 0xff) << 16) |
      ((bytes(offset + 3) & 0xff) << 24)

  def long(bytes: Array[Byte], offset: Int): Long =
    (bytes(offset).toLong & 0xffL) |
      ((bytes(offset + 1).toLong & 0xffL) << 8) |
      ((bytes(offset + 2).toLong & 0xffL) << 16) |
      ((bytes(offset + 3).toLong & 0xffL) << 24) |
      ((bytes(offset + 4).toLong & 0xffL) << 32) |
      ((bytes(offset + 5).toLong & 0xffL) << 40) |
      ((bytes(offset + 6).toLong & 0xffL) << 48) |
      ((bytes(offset + 7).toLong & 0xffL) << 56)

  def putInt(bytes: Array[Byte], offset: Int, value: Int): Unit =
    bytes(offset) = value.toByte
    bytes(offset + 1) = (value >>> 8).toByte
    bytes(offset + 2) = (value >>> 16).toByte
    bytes(offset + 3) = (value >>> 24).toByte

  def putLong(bytes: Array[Byte], offset: Int, value: Long): Unit =
    bytes(offset) = value.toByte
    bytes(offset + 1) = (value >>> 8).toByte
    bytes(offset + 2) = (value >>> 16).toByte
    bytes(offset + 3) = (value >>> 24).toByte
    bytes(offset + 4) = (value >>> 32).toByte
    bytes(offset + 5) = (value >>> 40).toByte
    bytes(offset + 6) = (value >>> 48).toByte
    bytes(offset + 7) = (value >>> 56).toByte

sealed trait Validity:
  def length: Int
  def nullCount: Int
  def isValid(index: Int): Either[StorageError, Boolean]
  private[frame4s] def slice(offset: Int, length: Int): Either[StorageError, Validity]
  private[frame4s] def layouts: Vector[BufferLayout]
  private[frame4s] def copyBuffers: Either[StorageError, Vector[Array[Byte]]]
  def close(): Unit

object Validity:
  final private class Required(val length: Int) extends Validity:
    val nullCount = 0

    def isValid(index: Int): Either[StorageError, Boolean] =
      if index < 0 || index >= length then Left(StorageError.InvalidRange(index, 1, length))
      else Right(true)

    private[frame4s] def slice(offset: Int, sliceLength: Int): Either[StorageError, Validity] =
      if offset < 0 || sliceLength < 0 || offset + sliceLength > length then
        Left(StorageError.InvalidRange(offset, sliceLength, length))
      else Right(new Required(sliceLength))

    private[frame4s] val layouts = Vector.empty
    private[frame4s] val copyBuffers = Right(Vector.empty)

    def close(): Unit = ()

  final private class Bitmap(
      buffer: Buffer,
      bitOffset: Int,
      val length: Int,
      val nullCount: Int
  ) extends Validity:
    def isValid(index: Int): Either[StorageError, Boolean] =
      if index < 0 || index >= length then Left(StorageError.InvalidRange(index, 1, length))
      else
        val absolute = bitOffset + index
        buffer
          .byteAt(absolute >>> 3)
          .map: value =>
            ((value.toInt >>> (absolute & 7)) & 1) == 1

    private[frame4s] def slice(offset: Int, sliceLength: Int): Either[StorageError, Validity] =
      if offset < 0 || sliceLength < 0 || offset + sliceLength > length then
        Left(StorageError.InvalidRange(offset, sliceLength, length))
      else
        var nulls = 0
        var index = 0
        var error: Option[StorageError] = None
        while index < sliceLength && error.isEmpty do
          isValid(offset + index) match
            case Right(false) => nulls += 1
            case Right(true)  => ()
            case Left(value)  => error = Some(value)
          index += 1
        error match
          case Some(value) => Left(value)
          case None        =>
            buffer
              .slice(0, buffer.length)
              .map(new Bitmap(_, bitOffset + offset, sliceLength, nulls))

    private[frame4s] def layouts = Vector(BufferLayout(BufferRole.Validity, buffer.length, 1))
    private[frame4s] def copyBuffers = buffer.copyBytes.map(Vector(_))

    def close(): Unit = buffer.close()

  def fromFlags(
      valid: Array[Boolean],
      tracker: BufferTracker = new BufferTracker
  ): Validity =
    var nulls = 0
    var index = 0
    while index < valid.length do
      if !valid(index) then nulls += 1
      index += 1
    if nulls == 0 then new Required(valid.length)
    else
      val bytes = new Array[Byte]((valid.length + 7) >>> 3)
      index = 0
      while index < valid.length do
        if valid(index) then
          val byteIndex = index >>> 3
          bytes(byteIndex) = (bytes(byteIndex) | (1 << (index & 7))).toByte
        index += 1
      new Bitmap(Buffer.owned(bytes, tracker), 0, valid.length, nulls)

  private[frame4s] def fromFreshBitmap(
      length: Int,
      bytes: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker
  ): Either[StorageError, Validity] =
    val expectedBytes = (length + 7) >>> 3
    if length < 0 || nullCount < 0 || nullCount > length then
      Left(
        StorageError.Unexpected(
          s"invalid fresh validity length=$length nullCount=$nullCount"
        )
      )
    else if nullCount == 0 then Right(new Required(length))
    else if bytes.length != expectedBytes then
      Left(
        StorageError.Unexpected(
          s"fresh validity buffer length ${bytes.length} does not match $expectedBytes bytes"
        )
      )
    else
      Right(
        new Bitmap(
          Buffer.ownedFresh(bytes, tracker),
          0,
          length,
          nullCount
        )
      )

sealed trait ColumnArray:
  def dataType: DataType
  def encoding: PhysicalEncoding
  def length: Int
  def nullCount: Int
  def layout: ArrayLayout
  def copyPhysicalBuffers: Either[StorageError, Vector[Array[Byte]]]
  def scalar(index: Int): Either[StorageError, ScalarValue]
  def slice(offset: Int, length: Int): Either[StorageError, ColumnArray]
  def close(): Unit

abstract private class FixedWidthArray[Self <: ColumnArray](
    val dataType: DataType,
    val length: Int,
    protected val logicalOffset: Int,
    protected val width: Int,
    protected val values: Buffer,
    protected val validity: Validity
) extends ColumnArray:
  val encoding = PhysicalEncoding.Plain
  def nullCount: Int = validity.nullCount
  def layout: ArrayLayout = ArrayLayout(
    dataType,
    encoding,
    logicalOffset,
    length,
    validity.layouts :+ BufferLayout(BufferRole.Values, values.length, width * 8)
  )

  def copyPhysicalBuffers: Either[StorageError, Vector[Array[Byte]]] =
    validity.copyBuffers.flatMap: validityBuffers =>
      values.copyBytes.map(validityBuffers :+ _)

  /** Borrow the values buffer for one non-escaping internal operation.
    *
    * The callback runs while the buffer's read lock is held, so the backing bytes cannot be
    * released during the operation. Validity remains a small detached copy because sliced bitmap
    * offsets are part of the column view. Callers must not retain either byte array.
    */
  private[frame4s] def withBorrowedValueBytes[A](
      operation: (Array[Byte], Int, Option[Array[Byte]], Int, Int) => A
  ): Either[StorageError, A] =
    validity.copyBuffers.flatMap: validityBuffers =>
      values.read: (bytes, start) =>
        operation(bytes, start, validityBuffers.headOption, logicalOffset, length)

  protected def validIndex(index: Int): Either[StorageError, Int] =
    if index < 0 || index >= length then Left(StorageError.InvalidRange(index, 1, length))
    else Right(logicalOffset + index)

  protected def readValue(index: Int): Either[StorageError, ScalarValue]

  def scalar(index: Int): Either[StorageError, ScalarValue] =
    validIndex(index).flatMap: _ =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if valid then readValue(index) else Right(ScalarValue.Null)

  protected def sliced(
      offset: Int,
      sliceLength: Int,
      retainedValues: Buffer,
      retainedValidity: Validity
  ): Self

  override def slice(offset: Int, sliceLength: Int): Either[StorageError, Self] =
    if offset < 0 || sliceLength < 0 || offset + sliceLength > length then
      Left(StorageError.InvalidRange(offset, sliceLength, length))
    else
      values
        .slice(0, values.length)
        .flatMap: retainedValues =>
          validity.slice(offset, sliceLength) match
            case Right(retainedValidity) =>
              Right(sliced(offset, sliceLength, retainedValues, retainedValidity))
            case Left(error) =>
              retainedValues.close()
              Left(error)

  def close(): Unit =
    validity.close()
    values.close()

final class Int32Array private[frame4s] (
    length: Int,
    logicalOffset: Int,
    values: Buffer,
    validity: Validity
) extends FixedWidthArray[Int32Array](DataType.Int32, length, logicalOffset, 4, values, validity):
  def value(index: Int): Either[StorageError, Int] =
    validIndex(index).flatMap: absolute =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Left(StorageError.NullValue(index))
          else values.read((bytes, start) => LittleEndian.int(bytes, start + absolute * 4))

  private[frame4s] def optionalValue(index: Int): Either[StorageError, Option[Int]] =
    validIndex(index).flatMap: absolute =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Right(None)
          else
            values
              .read((bytes, start) => LittleEndian.int(bytes, start + absolute * 4))
              .map(Some(_))

  protected def readValue(index: Int) = value(index).map(ScalarValue.Int32.apply)

  protected def sliced(
      offset: Int,
      sliceLength: Int,
      retainedValues: Buffer,
      retainedValidity: Validity
  ) =
    new Int32Array(sliceLength, logicalOffset + offset, retainedValues, retainedValidity)

final class Int64Array private[frame4s] (
    length: Int,
    logicalOffset: Int,
    values: Buffer,
    validity: Validity
) extends FixedWidthArray[Int64Array](DataType.Int64, length, logicalOffset, 8, values, validity):
  def value(index: Int): Either[StorageError, Long] =
    validIndex(index).flatMap: absolute =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Left(StorageError.NullValue(index))
          else values.read((bytes, start) => LittleEndian.long(bytes, start + absolute * 8))

  protected def readValue(index: Int) = value(index).map(ScalarValue.Int64.apply)

  protected def sliced(
      offset: Int,
      sliceLength: Int,
      retainedValues: Buffer,
      retainedValidity: Validity
  ) =
    new Int64Array(sliceLength, logicalOffset + offset, retainedValues, retainedValidity)

final class Float32Array private[frame4s] (
    length: Int,
    logicalOffset: Int,
    values: Buffer,
    validity: Validity
) extends FixedWidthArray[Float32Array](
      DataType.Float32,
      length,
      logicalOffset,
      4,
      values,
      validity
    ):
  def value(index: Int): Either[StorageError, Float] =
    validIndex(index).flatMap: absolute =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Left(StorageError.NullValue(index))
          else
            values.read((bytes, start) =>
              java.lang.Float.intBitsToFloat(LittleEndian.int(bytes, start + absolute * 4))
            )

  protected def readValue(index: Int) = value(index).map(ScalarValue.Float32.apply)

  protected def sliced(
      offset: Int,
      sliceLength: Int,
      retainedValues: Buffer,
      retainedValidity: Validity
  ) =
    new Float32Array(sliceLength, logicalOffset + offset, retainedValues, retainedValidity)

final class Float64Array private[frame4s] (
    length: Int,
    logicalOffset: Int,
    values: Buffer,
    validity: Validity
) extends FixedWidthArray[Float64Array](
      DataType.Float64,
      length,
      logicalOffset,
      8,
      values,
      validity
    ):
  def value(index: Int): Either[StorageError, Double] =
    validIndex(index).flatMap: absolute =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Left(StorageError.NullValue(index))
          else
            values.read((bytes, start) =>
              java.lang.Double.longBitsToDouble(LittleEndian.long(bytes, start + absolute * 8))
            )

  protected def readValue(index: Int) = value(index).map(ScalarValue.Float64.apply)

  protected def sliced(
      offset: Int,
      sliceLength: Int,
      retainedValues: Buffer,
      retainedValidity: Validity
  ) =
    new Float64Array(sliceLength, logicalOffset + offset, retainedValues, retainedValidity)

final class TimestampArray private[frame4s] (
    val unit: TimeUnit,
    length: Int,
    logicalOffset: Int,
    values: Buffer,
    validity: Validity
) extends FixedWidthArray[TimestampArray](
      DataType.Timestamp(unit),
      length,
      logicalOffset,
      8,
      values,
      validity
    ):
  def value(index: Int): Either[StorageError, Long] =
    validIndex(index).flatMap: absolute =>
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Left(StorageError.NullValue(index))
          else values.read((bytes, start) => LittleEndian.long(bytes, start + absolute * 8))

  protected def readValue(index: Int) = value(index).map(ScalarValue.Timestamp(_, unit))

  protected def sliced(
      offset: Int,
      sliceLength: Int,
      retainedValues: Buffer,
      retainedValidity: Validity
  ) =
    new TimestampArray(unit, sliceLength, logicalOffset + offset, retainedValues, retainedValidity)

final class BooleanArray private[frame4s] (
    val length: Int,
    private val logicalOffset: Int,
    private val values: Buffer,
    private val validity: Validity
) extends ColumnArray:
  val dataType = DataType.Bool
  val encoding = PhysicalEncoding.Plain
  def nullCount: Int = validity.nullCount
  def layout: ArrayLayout = ArrayLayout(
    dataType,
    encoding,
    logicalOffset,
    length,
    validity.layouts :+ BufferLayout(BufferRole.Values, values.length, 1)
  )

  def copyPhysicalBuffers: Either[StorageError, Vector[Array[Byte]]] =
    validity.copyBuffers.flatMap: validityBuffers =>
      values.copyBytes.map(validityBuffers :+ _)

  private[frame4s] def withBorrowedValueBytes[A](
      operation: (Array[Byte], Int, Option[Array[Byte]], Int, Int) => A
  ): Either[StorageError, A] =
    validity.copyBuffers.flatMap: validityBuffers =>
      values.read: (bytes, start) =>
        operation(bytes, start, validityBuffers.headOption, logicalOffset, length)

  def value(index: Int): Either[StorageError, Boolean] =
    if index < 0 || index >= length then Left(StorageError.InvalidRange(index, 1, length))
    else
      validity
        .isValid(index)
        .flatMap: valid =>
          if !valid then Left(StorageError.NullValue(index))
          else
            val absolute = logicalOffset + index
            values.byteAt(absolute >>> 3).map(byte => ((byte.toInt >>> (absolute & 7)) & 1) == 1)

  def scalar(index: Int): Either[StorageError, ScalarValue] =
    validity
      .isValid(index)
      .flatMap: valid =>
        if valid then value(index).map(ScalarValue.Bool.apply) else Right(ScalarValue.Null)

  override def slice(offset: Int, sliceLength: Int): Either[StorageError, BooleanArray] =
    if offset < 0 || sliceLength < 0 || offset + sliceLength > length then
      Left(StorageError.InvalidRange(offset, sliceLength, length))
    else
      values
        .slice(0, values.length)
        .flatMap: retainedValues =>
          validity.slice(offset, sliceLength) match
            case Right(retainedValidity) =>
              Right(
                new BooleanArray(
                  sliceLength,
                  logicalOffset + offset,
                  retainedValues,
                  retainedValidity
                )
              )
            case Left(error) =>
              retainedValues.close()
              Left(error)

  def close(): Unit =
    validity.close()
    values.close()

final class Utf8Array private[frame4s] (
    val length: Int,
    private val logicalOffset: Int,
    private val offsets: Buffer,
    private val values: Buffer,
    private val validity: Validity
) extends ColumnArray:
  val dataType = DataType.Utf8
  val encoding = PhysicalEncoding.Plain
  def nullCount: Int = validity.nullCount
  def layout: ArrayLayout = ArrayLayout(
    dataType,
    encoding,
    logicalOffset,
    length,
    validity.layouts ++ Vector(
      BufferLayout(BufferRole.Offsets, offsets.length, 32),
      BufferLayout(BufferRole.Values, values.length, 8)
    )
  )

  def copyPhysicalBuffers: Either[StorageError, Vector[Array[Byte]]] =
    validity.copyBuffers.flatMap: validityBuffers =>
      offsets.copyBytes.flatMap: offsetBytes =>
        values.copyBytes.map(valueBytes => validityBuffers ++ Vector(offsetBytes, valueBytes))

  /** Borrow UTF-8 offsets and values for one non-escaping internal operation.
    *
    * The callback is scoped by both buffer read locks. It is intended for eager kernels that
    * produce detached output and must not retain either byte array.
    */
  private[frame4s] def withBorrowedUtf8Bytes[A](
      operation: (Array[Byte], Int, Array[Byte], Int, Int, Int) => A
  ): Either[StorageError, A] =
    offsets
      .read: (offsetBytes, offsetStart) =>
        values.read: (valueBytes, valueStart) =>
          operation(
            offsetBytes,
            offsetStart,
            valueBytes,
            valueStart,
            logicalOffset,
            length
          )
      .flatMap(identity)

  /** Borrow UTF-8 offsets, values, and a detached validity bitmap for one eager kernel.
    *
    * The callback remains scoped by both buffer read locks. The validity copy carries the logical
    * slice offset; the borrowed offsets and values must not escape the callback.
    */
  private[frame4s] def withBorrowedUtf8BytesAndValidity[A](
      operation: (
          Array[Byte],
          Int,
          Array[Byte],
          Int,
          Option[Array[Byte]],
          Int,
          Int
      ) => A
  ): Either[StorageError, A] =
    validity.copyBuffers.flatMap: validityBuffers =>
      offsets
        .read: (offsetBytes, offsetStart) =>
          values.read: (valueBytes, valueStart) =>
            operation(
              offsetBytes,
              offsetStart,
              valueBytes,
              valueStart,
              validityBuffers.headOption,
              logicalOffset,
              length
            )
        .flatMap(identity)

  private def bounds(index: Int): Either[StorageError, (Int, Int)] =
    if index < 0 || index >= length then Left(StorageError.InvalidRange(index, 1, length))
    else
      offsets
        .read: (bytes, start) =>
          val absolute = logicalOffset + index
          (
            LittleEndian.int(bytes, start + absolute * 4),
            LittleEndian.int(bytes, start + (absolute + 1) * 4)
          )
        .flatMap: (from, until) =>
          if from < 0 || until < from || until > values.length then
            Left(StorageError.InvalidUtf8Offsets(index, from, until, values.length))
          else Right((from, until))

  def value(index: Int): Either[StorageError, String] =
    validity
      .isValid(index)
      .flatMap: valid =>
        if !valid then Left(StorageError.NullValue(index))
        else
          bounds(index).flatMap: (from, until) =>
            values.read: (bytes, start) =>
              new String(bytes, start + from, until - from, "UTF-8")

  def scalar(index: Int): Either[StorageError, ScalarValue] =
    validity
      .isValid(index)
      .flatMap: valid =>
        if valid then value(index).map(ScalarValue.Utf8.apply) else Right(ScalarValue.Null)

  override def slice(offset: Int, sliceLength: Int): Either[StorageError, Utf8Array] =
    if offset < 0 || sliceLength < 0 || offset + sliceLength > length then
      Left(StorageError.InvalidRange(offset, sliceLength, length))
    else
      offsets
        .slice(0, offsets.length)
        .flatMap: retainedOffsets =>
          values
            .slice(0, values.length)
            .flatMap: retainedValues =>
              validity.slice(offset, sliceLength) match
                case Right(retainedValidity) =>
                  Right(
                    new Utf8Array(
                      sliceLength,
                      logicalOffset + offset,
                      retainedOffsets,
                      retainedValues,
                      retainedValidity
                    )
                  )
                case Left(error) =>
                  retainedOffsets.close()
                  retainedValues.close()
                  Left(error)

  def close(): Unit =
    validity.close()
    offsets.close()
    values.close()

final class DictionaryArray private[frame4s] (
    private val indices: Int32Array,
    private val dictionary: ColumnArray
) extends ColumnArray:
  val dataType = dictionary.dataType
  val encoding = PhysicalEncoding.Dictionary(DataType.Int32, dictionary.dataType)
  def length: Int = indices.length
  def nullCount: Int = indices.nullCount
  private[frame4s] def columnarIndices: Int32Array = indices
  private[frame4s] def columnarDictionary: ColumnArray = dictionary
  def layout: ArrayLayout = ArrayLayout(
    dataType,
    encoding,
    0,
    length,
    indices.layout.buffers.map(_.copy(role = BufferRole.DictionaryIndices)) ++
      dictionary.layout.buffers.map(_.copy(role = BufferRole.DictionaryValues))
  )

  def copyPhysicalBuffers: Either[StorageError, Vector[Array[Byte]]] =
    indices.copyPhysicalBuffers.flatMap: indexBuffers =>
      dictionary.copyPhysicalBuffers.map(indexBuffers ++ _)

  def scalar(index: Int): Either[StorageError, ScalarValue] =
    indices
      .optionalValue(index)
      .flatMap:
        case None        => Right(ScalarValue.Null)
        case Some(value) =>
          if value < 0 || value >= dictionary.length then
            Left(StorageError.InvalidDictionaryIndex(index, value, dictionary.length))
          else dictionary.scalar(value)

  override def slice(offset: Int, sliceLength: Int): Either[StorageError, DictionaryArray] =
    indices
      .slice(offset, sliceLength)
      .flatMap: retainedIndices =>
        dictionary.slice(0, dictionary.length) match
          case Right(retainedDictionary) =>
            Right(new DictionaryArray(retainedIndices, retainedDictionary))
          case Left(error) =>
            retainedIndices.close()
            Left(error)

  def close(): Unit =
    indices.close()
    dictionary.close()

object ColumnArray:
  private def validity(
      length: Int,
      valid: Array[Boolean],
      tracker: BufferTracker
  ): Either[StorageError, Validity] =
    if valid.length != length then Left(StorageError.InvalidValidityLength(length, valid.length))
    else Right(Validity.fromFlags(valid, tracker))

  private def allValid(length: Int): Array[Boolean] = Array.fill(length)(true)

  private def freshValidity(
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker
  ): Either[StorageError, Validity] =
    Validity.fromFreshBitmap(length, valid, nullCount, tracker)

  private def expectedFreshBytes(
      dataType: DataType,
      length: Int,
      actual: Int,
      width: Int
  ): Either[StorageError, Unit] =
    val expected = length.toLong * width.toLong
    if length < 0 || expected > Int.MaxValue.toLong || actual.toLong != expected then
      Left(
        StorageError.Unexpected(
          s"fresh $dataType buffer length $actual does not match $length values at width $width"
        )
      )
    else Right(())

  private[frame4s] def int32FromFresh(
      values: Array[Byte],
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Int32Array] =
    expectedFreshBytes(DataType.Int32, length, values.length, 4).flatMap: _ =>
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new Int32Array(length, 0, Buffer.ownedFresh(values, tracker), validity)

  private[frame4s] def int64FromFresh(
      values: Array[Byte],
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Int64Array] =
    expectedFreshBytes(DataType.Int64, length, values.length, 8).flatMap: _ =>
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new Int64Array(length, 0, Buffer.ownedFresh(values, tracker), validity)

  private[frame4s] def float32FromFresh(
      values: Array[Byte],
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Float32Array] =
    expectedFreshBytes(DataType.Float32, length, values.length, 4).flatMap: _ =>
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new Float32Array(length, 0, Buffer.ownedFresh(values, tracker), validity)

  private[frame4s] def float64FromFresh(
      values: Array[Byte],
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Float64Array] =
    expectedFreshBytes(DataType.Float64, length, values.length, 8).flatMap: _ =>
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new Float64Array(length, 0, Buffer.ownedFresh(values, tracker), validity)

  private[frame4s] def boolFromFresh(
      values: Array[Byte],
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, BooleanArray] =
    val expected = (length + 7) >>> 3
    if length < 0 || values.length != expected then
      Left(
        StorageError.Unexpected(
          s"fresh Bool buffer length ${values.length} does not match $length values"
        )
      )
    else
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new BooleanArray(length, 0, Buffer.ownedFresh(values, tracker), validity)

  private[frame4s] def utf8FromFresh(
      offsets: Array[Byte],
      values: Array[Byte],
      length: Int,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Utf8Array] =
    expectedFreshBytes(DataType.Int32, length + 1, offsets.length, 4).flatMap: _ =>
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new Utf8Array(
          length,
          0,
          Buffer.ownedFresh(offsets, tracker),
          Buffer.ownedFresh(values, tracker),
          validity
        )

  private[frame4s] def timestampFromFresh(
      values: Array[Byte],
      length: Int,
      unit: TimeUnit,
      valid: Array[Byte],
      nullCount: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, TimestampArray] =
    expectedFreshBytes(DataType.Timestamp(unit), length, values.length, 8).flatMap: _ =>
      freshValidity(length, valid, nullCount, tracker).map: validity =>
        new TimestampArray(
          unit,
          length,
          0,
          Buffer.ownedFresh(values, tracker),
          validity
        )

  def int32(
      input: Array[Int],
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Int32Array] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).map: validity =>
      val bytes = new Array[Byte](input.length * 4)
      var index = 0
      while index < input.length do
        LittleEndian.putInt(bytes, index * 4, input(index))
        index += 1
      new Int32Array(input.length, 0, Buffer.owned(bytes, tracker), validity)

  def int64(
      input: Array[Long],
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Int64Array] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).map: validity =>
      val bytes = new Array[Byte](input.length * 8)
      var index = 0
      while index < input.length do
        LittleEndian.putLong(bytes, index * 8, input(index))
        index += 1
      new Int64Array(input.length, 0, Buffer.owned(bytes, tracker), validity)

  def float32(
      input: Array[Float],
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Float32Array] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).map: validity =>
      val bytes = new Array[Byte](input.length * 4)
      var index = 0
      while index < input.length do
        LittleEndian.putInt(bytes, index * 4, java.lang.Float.floatToRawIntBits(input(index)))
        index += 1
      new Float32Array(input.length, 0, Buffer.owned(bytes, tracker), validity)

  def float64(
      input: Array[Double],
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Float64Array] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).map: validity =>
      val bytes = new Array[Byte](input.length * 8)
      var index = 0
      while index < input.length do
        LittleEndian.putLong(bytes, index * 8, java.lang.Double.doubleToRawLongBits(input(index)))
        index += 1
      new Float64Array(input.length, 0, Buffer.owned(bytes, tracker), validity)

  def bool(
      input: Array[Boolean],
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, BooleanArray] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).map: validity =>
      val bytes = new Array[Byte]((input.length + 7) >>> 3)
      var index = 0
      while index < input.length do
        if input(index) then
          val byteIndex = index >>> 3
          bytes(byteIndex) = (bytes(byteIndex) | (1 << (index & 7))).toByte
        index += 1
      new BooleanArray(input.length, 0, Buffer.owned(bytes, tracker), validity)

  def utf8(
      input: Array[String],
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Utf8Array] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).flatMap: validity =>
      val encoded = new Array[Array[Byte]](input.length)
      var total = 0
      var index = 0
      var error: Option[StorageError] = None
      while index < input.length && error.isEmpty do
        // A slot marked invalid carries no value, so its element is never dereferenced and
        // occupies zero bytes. A slot marked valid must carry one: a null there is a caller
        // error and belongs in the error channel, not a NullPointerException.
        if !flags(index) then encoded(index) = Array.emptyByteArray
        else
          val value = input(index)
          if value == null then error = Some(StorageError.NullValue(index))
          else
            val bytes = value.getBytes("UTF-8")
            encoded(index) = bytes
            total += bytes.length
        index += 1
      error match
        case Some(value) =>
          validity.close()
          Left(value)
        case None =>
          val offsets = new Array[Byte]((input.length + 1) * 4)
          val values = new Array[Byte](total)
          var cursor = 0
          index = 0
          while index < input.length do
            LittleEndian.putInt(offsets, index * 4, cursor)
            val bytes = encoded(index)
            Array.copy(bytes, 0, values, cursor, bytes.length)
            cursor += bytes.length
            index += 1
          LittleEndian.putInt(offsets, input.length * 4, cursor)
          Right(
            new Utf8Array(
              input.length,
              0,
              Buffer.owned(offsets, tracker),
              Buffer.owned(values, tracker),
              validity
            )
          )

  def timestamp(
      input: Array[Long],
      unit: TimeUnit,
      valid: Array[Boolean] = Array.emptyBooleanArray,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, TimestampArray] =
    val flags = if valid.isEmpty then allValid(input.length) else valid
    validity(input.length, flags, tracker).map: validity =>
      val bytes = new Array[Byte](input.length * 8)
      var index = 0
      while index < input.length do
        LittleEndian.putLong(bytes, index * 8, input(index))
        index += 1
      new TimestampArray(unit, input.length, 0, Buffer.owned(bytes, tracker), validity)

  /** Nulls in a dictionary-encoded column are carried by the index vector's validity, following
    * Arrow. A null in the dictionary itself would be invisible to `nullCount` and could therefore
    * pass a non-nullable field's validation while still decoding to `ScalarValue.Null`.
    */
  def dictionary(
      indices: Int32Array,
      values: ColumnArray
  ): Either[StorageError, DictionaryArray] =
    if values.nullCount > 0 then Left(StorageError.DictionaryContainsNull(values.nullCount))
    else Right(new DictionaryArray(indices, values))

final class RecordBatch private (
    val schema: Schema,
    private[frame4s] val columns: Vector[ColumnArray],
    val rowCount: Int
):
  private var closed = false

  def isClosed: Boolean = synchronized(closed)

  def column(name: String): Either[StorageError, ColumnArray] =
    val index = schema.fields.indexWhere(_.name == name)
    if index < 0 then Left(StorageError.ColumnNotFound(name))
    else if isClosed then Left(StorageError.BufferClosed)
    else Right(columns(index))

  def slice(offset: Int, length: Int): Either[StorageError, RecordBatch] =
    if isClosed then Left(StorageError.BufferClosed)
    else if offset < 0 || length < 0 || offset + length > rowCount then
      Left(StorageError.InvalidRange(offset, length, rowCount))
    else
      val retained = ArrayBuffer.empty[ColumnArray]
      var index = 0
      var error: Option[StorageError] = None
      while index < columns.length && error.isEmpty do
        columns(index).slice(offset, length) match
          case Right(column) => retained += column
          case Left(value)   => error = Some(value)
        index += 1
      error match
        case Some(value) =>
          retained.foreach(_.close())
          Left(value)
        case None => Right(new RecordBatch(schema, retained.toVector, length))

  def close(): Unit = synchronized:
    if !closed then
      closed = true
      columns.foreach(_.close())

object RecordBatch:
  def apply(schema: Schema, columns: Vector[ColumnArray]): Either[StorageError, RecordBatch] =
    if columns.length != schema.size then
      Left(StorageError.ColumnCountMismatch(schema.size, columns.length))
    else
      val rowCount = columns.headOption.fold(0)(_.length)
      var index = 0
      var error: Option[StorageError] = None
      while index < columns.length && error.isEmpty do
        val column = columns(index)
        val field = schema.fields(index)
        if column.length != rowCount then
          error = Some(StorageError.ColumnLengthMismatch(rowCount, column.length, index))
        else if column.dataType != field.dataType then
          error = Some(StorageError.ColumnTypeMismatch(index, field.dataType, column.dataType))
        else if !field.nullable && column.nullCount > 0 then
          error = Some(StorageError.RequiredColumnContainsNull(index, column.nullCount))
        index += 1
      error match
        case Some(value) => Left(value)
        case None        => Right(new RecordBatch(schema, columns, rowCount))

  private[frame4s] def empty(
      schema: Schema,
      rowCount: Int
  ): Either[StorageError, RecordBatch] =
    if schema.size != 0 then Left(StorageError.ColumnCountMismatch(schema.size, 0))
    else if rowCount < 0 then Left(StorageError.InvalidRange(0, rowCount, 0))
    else Right(new RecordBatch(schema, Vector.empty, rowCount))

final class Table[S <: NamedTuple.AnyNamedTuple] private (
    val schema: Schema,
    private[frame4s] val batches: Vector[RecordBatch]
):
  private var closed = false

  val rowCount: Long = batches.foldLeft(0L)(_ + _.rowCount.toLong)

  def isClosed: Boolean = synchronized(closed)

  private def located(index: Long): Either[TableReadError, (RecordBatch, Int)] =
    if isClosed then Left(TableReadError.Closed)
    else if index < 0L || index >= rowCount then
      Left(TableReadError.RowOutOfBounds(index, rowCount))
    else
      var remaining = index
      var batchIndex = 0
      while remaining >= batches(batchIndex).rowCount.toLong do
        remaining -= batches(batchIndex).rowCount.toLong
        batchIndex += 1
      Right((batches(batchIndex), remaining.toInt))

  /** Decode a detached immutable named-tuple row. The returned row owns no table buffer and may
    * safely outlive this table's resource scope.
    */
  def row(index: Long)(using codec: RowCodec[S]): Either[TableReadError, S] =
    if codec.schema != schema then Left(TableReadError.SchemaMismatch(codec.schema, schema))
    else
      located(index).flatMap: (batch, batchRow) =>
        codec.decode(this, batch, batchRow, index)

  /** Decode one named cell with its exact schema type. */
  inline def cell[Name <: String & Singleton](
      index: Long,
      name: Name
  )(using
      lookup: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      codec: ScalarCodec[SchemaFieldType[S, Name]]
  ): Either[TableReadError, SchemaFieldType[S, Name]] =
    located(index).flatMap: (batch, batchRow) =>
      val field = schema.fields(lookup.index)
      batch
        .columns(lookup.index)
        .scalar(batchRow)
        .left
        .map(TableReadError.Storage.apply)
        .flatMap: value =>
          codec
            .decode(value)
            .toRight:
              TableReadError.ScalarDecode(
                index,
                lookup.index,
                field.name,
                value,
                field.dataType,
                field.nullable
              )

  /** Decode a detached immutable named column. This is a read view, not a second transformation
    * algebra; the returned values own no table buffers.
    */
  inline def column[Name <: String & Singleton](
      name: Name
  )(using
      lookup: ColumnLookup[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
      codec: ScalarCodec[SchemaFieldType[S, Name]]
  ): Either[TableReadError, Vector[SchemaFieldType[S, Name]]] =
    if isClosed then Left(TableReadError.Closed)
    else
      val output = Vector.newBuilder[SchemaFieldType[S, Name]]
      var batchIndex = 0
      var logicalRow = 0L
      var error: Option[TableReadError] = None
      while batchIndex < batches.length && error.isEmpty do
        val batch = batches(batchIndex)
        val field = schema.fields(lookup.index)
        var batchRow = 0
        while batchRow < batch.rowCount && error.isEmpty do
          batch.columns(lookup.index).scalar(batchRow) match
            case Left(value)  => error = Some(TableReadError.Storage(value))
            case Right(value) =>
              codec.decode(value) match
                case Some(decoded) => output += decoded
                case None          =>
                  error = Some(
                    TableReadError.ScalarDecode(
                      logicalRow,
                      lookup.index,
                      field.name,
                      value,
                      field.dataType,
                      field.nullable
                    )
                  )
          logicalRow += 1L
          batchRow += 1
        batchIndex += 1
      error.toLeft(output.result())

  /** Decode one row as an exactly matching case class or enum-case product. */
  def rowAs[P <: Product](
      index: Long
  )(using
      same: NamedTuple.From[P] =:= S,
      mirror: scala.deriving.Mirror.ProductOf[P],
      codec: RowCodec[S]
  ): Either[TableReadError, P] =
    row(index).map(value => ProductRows.fromNamedTuple(same.flip.apply(value)))

  /** Render a deterministic, ownership-neutral, bounded table preview. */
  def show(
      options: TableRenderOptions = TableRenderOptions()
  ): Either[TableReadError, String] =
    TableRendering.render(this, options)

  /** Render every schema field with bounded line width and no data scan. */
  def showSchema(maxWidth: Int = 120): Either[TableReadError, String] =
    TableRendering.renderSchema(this, maxWidth)

  def close(): Unit = synchronized:
    if !closed then
      closed = true
      batches.foreach(_.close())

object Table:
  def apply[S <: NamedTuple.AnyNamedTuple](
      batches: Vector[RecordBatch]
  )(using descriptor: SchemaDescriptor[S]): Either[StorageError, Table[S]] =
    val expected = descriptor.schema
    batches.find(_.schema != expected) match
      case Some(batch) => Left(StorageError.SchemaMismatch(expected, batch.schema))
      case None        => Right(new Table(expected, batches))

  /** Construct a fully owned table from detached named-tuple rows.
    *
    * Rows are encoded in bounded batches. If any batch fails validation, all previously allocated
    * buffers are released before the structured error is returned.
    */
  def fromRows[S <: NamedTuple.AnyNamedTuple](
      rows: IterableOnce[S],
      batchSize: Int = 1024
  )(using
      descriptor: SchemaDescriptor[S],
      codec: RowCodec[S]
  ): Either[TableReadError, Table[S]] =
    fromRowsTracked(rows, batchSize, new BufferTracker)

  private[frame4s] def fromRowsTracked[S <: NamedTuple.AnyNamedTuple](
      rows: IterableOnce[S],
      batchSize: Int,
      tracker: BufferTracker
  )(using
      descriptor: SchemaDescriptor[S],
      codec: RowCodec[S]
  ): Either[TableReadError, Table[S]] =
    if batchSize <= 0 then Left(TableReadError.InvalidBatchSize(batchSize))
    else
      val batches = ArrayBuffer.empty[RecordBatch]
      val iterator = rows.iterator
      var error: Option[TableReadError] = None
      var logicalStart = 0L
      while iterator.hasNext && error.isEmpty do
        val chunkBuilder = Vector.newBuilder[Vector[ScalarValue]]
        var count = 0
        while count < batchSize && iterator.hasNext do
          chunkBuilder += codec.encode(iterator.next())
          count += 1
        val chunk = chunkBuilder.result()
        TableConstruction.batch(descriptor.schema, chunk, logicalStart, tracker) match
          case Right(batch) => batches += batch
          case Left(value)  => error = Some(value)
        logicalStart += chunk.length.toLong
      error match
        case Some(value) =>
          batches.foreach(_.close())
          Left(value)
        case None =>
          apply[S](batches.toVector) match
            case Right(table) => Right(table)
            case Left(value)  =>
              batches.foreach(_.close())
              Left(TableReadError.Storage(value))

  /** Construct an owned typed table directly from an exactly matching case-class product. */
  def fromProducts[P <: Product](
      rows: IterableOnce[P],
      batchSize: Int = 1024
  )(using
      mirror: scala.deriving.Mirror.ProductOf[P],
      descriptor: SchemaDescriptor[NamedTuple.From[P]],
      codec: RowCodec[NamedTuple.From[P]]
  ): Either[TableReadError, Table[NamedTuple.From[P]]] =
    fromRows(rows.iterator.map(ProductRows.toNamedTuple(_)), batchSize)

private object TableConstruction:
  def batch(
      schema: Schema,
      rows: Vector[Vector[ScalarValue]],
      logicalStart: Long,
      tracker: BufferTracker
  ): Either[TableReadError, RecordBatch] =
    val columns = ArrayBuffer.empty[ColumnArray]
    var column = 0
    var error: Option[TableReadError] = None
    while column < schema.size && error.isEmpty do
      val field = schema.fields(column)
      val values = rows.map(_(column))
      buildColumn(field, column, values, logicalStart, tracker) match
        case Right(array) => columns += array
        case Left(value)  => error = Some(value)
      column += 1
    error match
      case Some(value) =>
        columns.foreach(_.close())
        Left(value)
      case None =>
        if schema.size == 0 then
          RecordBatch.empty(schema, rows.length).left.map(TableReadError.Storage.apply)
        else RecordBatch(schema, columns.toVector).left.map(TableReadError.Storage.apply)

  private def buildColumn(
      field: Field,
      column: Int,
      values: Vector[ScalarValue],
      logicalStart: Long,
      tracker: BufferTracker
  ): Either[TableReadError, ColumnArray] =
    val valid = values.map(_ != ScalarValue.Null).toArray
    def mismatch(index: Int, value: ScalarValue): TableReadError =
      TableReadError.ScalarDecode(
        logicalStart + index.toLong,
        column,
        field.name,
        value,
        field.dataType,
        field.nullable
      )
    def requiredNull: Option[TableReadError] =
      Option
        .when(!field.nullable):
          values.indexWhere(_ == ScalarValue.Null)
        .filter(_ >= 0)
        .map(index => mismatch(index, ScalarValue.Null))

    def compatible(value: ScalarValue): Boolean = value match
      case ScalarValue.Null               => true
      case ScalarValue.Bool(_)            => field.dataType == DataType.Bool
      case ScalarValue.Int32(_)           => field.dataType == DataType.Int32
      case ScalarValue.Int64(_)           => field.dataType == DataType.Int64
      case ScalarValue.Float32(_)         => field.dataType == DataType.Float32
      case ScalarValue.Float64(_)         => field.dataType == DataType.Float64
      case ScalarValue.Utf8(value)        => field.dataType == DataType.Utf8 && value != null
      case ScalarValue.Timestamp(_, unit) => field.dataType == DataType.Timestamp(unit)

    val incompatible =
      values.zipWithIndex.collectFirst:
        case (value, index) if !compatible(value) => mismatch(index, value)

    requiredNull
      .orElse(incompatible)
      .toLeft(())
      .flatMap: _ =>
        field.dataType match
          case DataType.Bool =>
            collect(values):
              case ScalarValue.Bool(value) => Some(value)
              case ScalarValue.Null        => Some(false)
              case _                       => None
            .flatMap(array =>
              ColumnArray.bool(array, valid, tracker).left.map(TableReadError.Storage.apply)
            )
          case DataType.Int32 =>
            collect(values):
              case ScalarValue.Int32(value) => Some(value)
              case ScalarValue.Null         => Some(0)
              case _                        => None
            .flatMap(array =>
              ColumnArray.int32(array, valid, tracker).left.map(TableReadError.Storage.apply)
            )
          case DataType.Int64 =>
            collect(values):
              case ScalarValue.Int64(value) => Some(value)
              case ScalarValue.Null         => Some(0L)
              case _                        => None
            .flatMap(array =>
              ColumnArray.int64(array, valid, tracker).left.map(TableReadError.Storage.apply)
            )
          case DataType.Float32 =>
            collect(values):
              case ScalarValue.Float32(value) => Some(value)
              case ScalarValue.Null           => Some(0.0f)
              case _                          => None
            .flatMap(array =>
              ColumnArray.float32(array, valid, tracker).left.map(TableReadError.Storage.apply)
            )
          case DataType.Float64 =>
            collect(values):
              case ScalarValue.Float64(value) => Some(value)
              case ScalarValue.Null           => Some(0.0)
              case _                          => None
            .flatMap(array =>
              ColumnArray.float64(array, valid, tracker).left.map(TableReadError.Storage.apply)
            )
          case DataType.Utf8 =>
            collect(values):
              case ScalarValue.Utf8(value) => Some(value)
              case ScalarValue.Null        => Some("")
              case _                       => None
            .flatMap(array =>
              ColumnArray.utf8(array, valid, tracker).left.map(TableReadError.Storage.apply)
            )
          case DataType.Timestamp(unit) =>
            collect(values):
              case ScalarValue.Timestamp(value, actual) if actual == unit => Some(value)
              case ScalarValue.Null                                       => Some(0L)
              case _                                                      => None
            .flatMap(array =>
              ColumnArray
                .timestamp(array, unit, valid, tracker)
                .left
                .map(TableReadError.Storage.apply)
            )

  private def collect[A: scala.reflect.ClassTag](
      values: Vector[ScalarValue]
  )(decode: ScalarValue => Option[A]): Either[TableReadError, Array[A]] =
    val output = new Array[A](values.length)
    var index = 0
    var failed = false
    while index < values.length && !failed do
      decode(values(index)) match
        case Some(value) => output(index) = value
        case None        => failed = true
      index += 1
    if failed then
      Left(
        TableReadError.Storage(StorageError.Unexpected("row codec emitted an incompatible scalar"))
      )
    else Right(output)

private object TableRendering:
  private val Ellipsis = "…"

  def render[S <: NamedTuple.AnyNamedTuple](
      table: Table[S],
      options: TableRenderOptions
  ): Either[TableReadError, String] =
    validate(options).flatMap: _ =>
      if table.isClosed then Left(TableReadError.Closed)
      else
        val count = math.min(table.rowCount, options.maxRows.toLong).toInt
        readRows(table, count).map: rows =>
          val headers = table.schema.fields.map(_.name)
          val rendered = rows.map(_.map(renderScalar))
          val widths = headers.indices
            .map: column =>
              val observed =
                rendered.iterator.map(_(column).length).foldLeft(headers(column).length)(math.max)
              math.min(observed, options.maxCellWidth)
            .toVector
          val boundedWidths =
            if widths.nonEmpty && widths.head + 2 > options.maxWidth then
              widths.updated(0, options.maxWidth - 2)
            else widths
          val selected = selectColumns(boundedWidths, options.maxWidth)
          val shownHeaders = selected.map(headers)
          val shownWidths = selected.map(boundedWidths)
          val lines = Vector(
            line(shownHeaders, shownWidths),
            separator(shownWidths)
          ) ++ rendered.map(row => line(selected.map(row), shownWidths))
          val omittedColumns = selected.length < headers.length
          val omittedRows = table.rowCount - count.toLong
          val suffix = Vector(
            Option.when(omittedColumns)(s"$Ellipsis +${headers.length - selected.length} cols"),
            Option.when(omittedRows > 0L)(s"$Ellipsis +$omittedRows rows")
          ).flatten
          (lines ++ suffix.map(limit(_, options.maxWidth))).mkString("\n")

  def renderSchema(
      table: Table[?],
      maxWidth: Int
  ): Either[TableReadError, String] =
    if table.isClosed then Left(TableReadError.Closed)
    else if maxWidth < 8 then
      Left(TableReadError.InvalidRenderOptions("schema maxWidth must be at least 8"))
    else
      val fields = table.schema.fields.zipWithIndex.map: (field, index) =>
        val nullability = if field.nullable then "optional" else "required"
        limit(s"$index. ${field.name}: ${field.dataType} $nullability", maxWidth)
      Right((Vector("Schema") ++ fields).mkString("\n"))

  private def validate(options: TableRenderOptions): Either[TableReadError, Unit] =
    if options.maxRows < 0 then
      Left(TableReadError.InvalidRenderOptions("maxRows must be non-negative"))
    else if options.maxWidth < 8 then
      Left(TableReadError.InvalidRenderOptions("maxWidth must be at least 8"))
    else if options.maxCellWidth < 1 then
      Left(TableReadError.InvalidRenderOptions("maxCellWidth must be positive"))
    else Right(())

  private def readRows(
      table: Table[?],
      count: Int
  ): Either[TableReadError, Vector[Vector[ScalarValue]]] =
    val output = Vector.newBuilder[Vector[ScalarValue]]
    var remaining = count
    var batchIndex = 0
    var error: Option[TableReadError] = None
    while remaining > 0 && batchIndex < table.batches.length && error.isEmpty do
      val batch = table.batches(batchIndex)
      val rows = math.min(remaining, batch.rowCount)
      var row = 0
      while row < rows && error.isEmpty do
        val values = Vector.newBuilder[ScalarValue]
        var column = 0
        while column < batch.columns.length && error.isEmpty do
          batch.columns(column).scalar(row) match
            case Right(value) => values += value
            case Left(value)  => error = Some(TableReadError.Storage(value))
          column += 1
        if error.isEmpty then output += values.result()
        row += 1
      remaining -= rows
      batchIndex += 1
    error.toLeft(output.result())

  private def renderScalar(value: ScalarValue): String = value match
    case ScalarValue.Null                    => "null"
    case ScalarValue.Bool(actual)            => actual.toString
    case ScalarValue.Int32(actual)           => actual.toString
    case ScalarValue.Int64(actual)           => actual.toString
    case ScalarValue.Float32(actual)         => actual.toString
    case ScalarValue.Float64(actual)         => actual.toString
    case ScalarValue.Utf8("")                => "\"\""
    case ScalarValue.Utf8(actual)            => actual
    case ScalarValue.Timestamp(actual, unit) => s"$actual@$unit"

  private def selectColumns(widths: Vector[Int], maxWidth: Int): Vector[Int] =
    val selected = Vector.newBuilder[Int]
    var used = 1
    var column = 0
    while column < widths.length && used + widths(column) + 1 <= maxWidth do
      selected += column
      used += widths(column) + 1
      column += 1
    if column == 0 && widths.nonEmpty then Vector(0)
    else selected.result()

  private def line(values: Vector[String], widths: Vector[Int]): String =
    values
      .zip(widths)
      .map: (value, width) =>
        val truncated = limit(value, width)
        truncated + (" " * (width - truncated.length))
      .mkString("|", "|", "|")

  private def separator(widths: Vector[Int]): String =
    widths.map("-" * _).mkString("|", "|", "|")

  private def limit(value: String, width: Int): String =
    if value.length <= width then value
    else if width == 1 then Ellipsis
    else safePrefix(value, width - 1) + Ellipsis

  private def safePrefix(value: String, maxCodeUnits: Int): String =
    val output = new StringBuilder
    var index = 0
    while index < value.length && output.length < maxCodeUnits do
      val current = value.charAt(index)
      val pair =
        Character.isHighSurrogate(current) &&
          index + 1 < value.length &&
          Character.isLowSurrogate(value.charAt(index + 1))
      val units = if pair then 2 else 1
      if output.length + units <= maxCodeUnits then
        output.append(current)
        if pair then output.append(value.charAt(index + 1))
      index += units
    output.result()

trait BatchCursor extends AutoCloseable:
  def nextBatch(): Either[StorageError, Option[RecordBatch]]
  def close(): Unit

trait BatchSource:
  def schema: Schema
  def open(): Either[StorageError, BatchCursor]

  final def use[A](operation: BatchCursor => Either[StorageError, A]): Either[StorageError, A] =
    open().flatMap: cursor =>
      Using(cursor)(operation).toEither.left
        .map(error => StorageError.Unexpected(exceptionDetail(error)))
        .flatMap(identity)

  final def collect[S <: NamedTuple.AnyNamedTuple](using
      descriptor: SchemaDescriptor[S]
  ): Either[StorageError, Table[S]] =
    use: cursor =>
      val batches = ArrayBuffer.empty[RecordBatch]
      var done = false
      var error: Option[StorageError] = None
      while !done && error.isEmpty do
        cursor.nextBatch() match
          case Right(Some(batch)) => batches += batch
          case Right(None)        => done = true
          case Left(value)        => error = Some(value)
      error match
        case Some(value) =>
          batches.foreach(_.close())
          Left(value)
        case None =>
          Table[S](batches.toVector) match
            case right @ Right(_) => right
            case left @ Left(_)   =>
              batches.foreach(_.close())
              left

private def exceptionDetail(error: Throwable): String =
  Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)

final class OwnedBatchSource private (
    val schema: Schema,
    private val input: Vector[RecordBatch]
) extends BatchSource:
  private var opened = false

  def open(): Either[StorageError, BatchCursor] = synchronized:
    if opened then Left(StorageError.SourceAlreadyOpened)
    else
      opened = true
      Right:
        new BatchCursor:
          private var index = 0
          private var closed = false

          def nextBatch(): Either[StorageError, Option[RecordBatch]] = synchronized:
            if closed then Left(StorageError.SourceClosed)
            else if index >= input.length then Right(None)
            else
              val batch = input(index)
              index += 1
              Right(Some(batch))

          def close(): Unit = synchronized:
            if !closed then
              closed = true
              while index < input.length do
                input(index).close()
                index += 1

object OwnedBatchSource:
  def apply(schema: Schema, batches: Vector[RecordBatch]): Either[StorageError, OwnedBatchSource] =
    batches.find(_.schema != schema) match
      case Some(batch) => Left(StorageError.SchemaMismatch(schema, batch.schema))
      case None        => Right(new OwnedBatchSource(schema, batches))
