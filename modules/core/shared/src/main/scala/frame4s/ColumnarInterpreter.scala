package frame4s

import scala.collection.mutable.ArrayBuffer

/** Package-internal optimized execution experiment.
  *
  * This interpreter is intentionally separate from [[ReferenceInterpreter]]. It admits only kernels
  * with an exact physical contract and falls back for the whole plan when that contract is not met.
  * The reference interpreter therefore remains the semantic oracle.
  */
private[frame4s] object ColumnarInterpreter:
  def prepare(plan: LogicalPlan, sources: ReferenceSources): ColumnarExecution =
    val normalized = PlanNormalizer.normalize(plan).plan
    new ColumnarExecution(plan, sources, KernelPlan.classify(normalized))

  def prepareIndexed(
      plan: LogicalPlan,
      sources: ReferenceSources
  ): Either[SecondaryIndexError, ColumnarExecution] =
    KernelPlan.classify(plan) match
      case Some(join: HashJoin) =>
        PreparedHashJoin
          .build(join, sources)
          .map(kernel => new ColumnarExecution(plan, sources, Some(kernel)))
      case Some(kernel) =>
        Left(SecondaryIndexError.UnsupportedKernel(kernel.name))
      case None =>
        Left(SecondaryIndexError.UnsupportedLogicalShape(plan.nodeName))

  def columnChecksum(column: ColumnArray): Either[ExecutionError, Long] =
    ColumnarVector
      .copy(column)
      .left
      .map(reason => ExecutionError.UnsupportedNode(s"columnar scan: $reason"))
      .map(_.foldHash(1L))

final private[frame4s] case class ColumnarReceipt(
    physicalPlan: String,
    fallback: Option[String]
)

final private[frame4s] case class ColumnarRun(
    result: Either[ExecutionError, ColumnarResult],
    receipt: ColumnarReceipt
)

final private[frame4s] class ColumnarExecution private[frame4s] (
    val logicalPlan: LogicalPlan,
    sources: ReferenceSources,
    kernel: Option[KernelPlan]
):
  private var closed = false

  def physicalExplain: String =
    kernel match
      case Some(value) =>
        s"ColumnarExecution(operator=${value.name}, fallback=runtime-guarded)"
      case None =>
        s"ColumnarExecution(operator=ReferenceWholePlan, fallback=${fallbackReason(logicalPlan)})"

  def run(): ColumnarRun = synchronized:
    if closed then
      ColumnarRun(
        Left(ExecutionError.Storage(StorageError.SourceClosed)),
        ColumnarReceipt(
          "ColumnarExecution(operator=Closed, fallback=none)",
          fallback = None
        )
      )
    else runOpen()

  def close(): Unit = synchronized:
    if !closed then
      closed = true
      kernel.foreach(_.close())

  private def runOpen(): ColumnarRun =
    kernel match
      case Some(value) =>
        value.execute(sources) match
          case KernelAttempt.Completed(result) =>
            ColumnarRun(
              result,
              ColumnarReceipt(
                s"ColumnarExecution(operator=${value.name}, fallback=none)",
                fallback = None
              )
            )
          case KernelAttempt.Residual(reason) =>
            reference(reason)
      case None =>
        reference(fallbackReason(logicalPlan))

  private def reference(reason: String): ColumnarRun =
    val execution = ReferenceInterpreter.prepare(logicalPlan, sources)
    ColumnarRun(
      ColumnarResult.fromReference(logicalPlan, execution),
      ColumnarReceipt(
        s"ColumnarExecution(operator=ReferenceWholePlan, fallback=$reason; oracle=${execution.physicalExplain})",
        fallback = Some(reason)
      )
    )

  private def fallbackReason(plan: LogicalPlan): String =
    s"unsupported logical shape ${plan.nodeName}"

/** Detached columnar result owned by one execution.
  *
  * The current admitted kernels copy the source buffers they consume. Closing the result is still
  * mandatory so every terminal follows the same ownership protocol as future off-heap kernels.
  */
final private[frame4s] class ColumnarResult private (
    val schema: Schema,
    val order: OrderGuarantee,
    private val batches: Vector[ColumnarBatch]
):
  private var closed = false

  val rowCount: Long = batches.foldLeft(0L)(_ + _.rowCount.toLong)

  def isClosed: Boolean = synchronized(closed)

  def rows: Either[ExecutionError, Vector[Vector[ScalarValue]]] =
    if isClosed then Left(ExecutionError.Storage(StorageError.SourceClosed))
    else
      val output = Vector.newBuilder[Vector[ScalarValue]]
      var batchIndex = 0
      var error: Option[ExecutionError] = None
      while batchIndex < batches.length && error.isEmpty do
        val batch = batches(batchIndex)
        var row = 0
        while row < batch.rowCount && error.isEmpty do
          val values = Vector.newBuilder[ScalarValue]
          var column = 0
          while column < batch.columns.length && error.isEmpty do
            batch.columns(column).scalar(row) match
              case Right(value) => values += value
              case Left(value)  => error = Some(value)
            column += 1
          if error.isEmpty then output += values.result()
          row += 1
        batchIndex += 1
      error.toLeft(output.result())

  def checksum: Either[ExecutionError, Long] =
    if isClosed then Left(ExecutionError.Storage(StorageError.SourceClosed))
    else
      var hash = rowCount
      var batchIndex = 0
      while batchIndex < batches.length do
        val batch = batches(batchIndex)
        if batch.columns.length == 1 then hash = batch.columns.head.foldHash(hash)
        else
          var row = 0
          while row < batch.rowCount do
            var column = 0
            while column < batch.columns.length do
              hash = hash * 31L + batch.columns(column).unsafeScalarHash(row)
              column += 1
            row += 1
        batchIndex += 1
      Right(hash)

  def close(): Unit = synchronized:
    closed = true

private[frame4s] object ColumnarResult:
  def apply(
      schema: Schema,
      order: OrderGuarantee,
      batches: Vector[ColumnarBatch]
  ): ColumnarResult =
    new ColumnarResult(schema, order, batches)

  def fromReference(
      plan: LogicalPlan,
      execution: ReferenceExecution
  ): Either[ExecutionError, ColumnarResult] =
    execution
      .open()
      .flatMap: cursor =>
        val output = ArrayBuffer.empty[ColumnarBatch]
        try
          var done = false
          var error: Option[ExecutionError] = None
          while !done && error.isEmpty do
            cursor.nextBatch() match
              case Right(None)        => done = true
              case Right(Some(batch)) =>
                ColumnarBatch.copyScalars(batch) match
                  case Right(value) => output += value
                  case Left(value)  => error = Some(value)
                batch.close()
              case Left(value) => error = Some(value)
          error.toLeft(ColumnarResult(plan.output, plan.order, output.toVector))
        finally cursor.close()

final private case class ColumnarBatch(
    columns: Vector[ColumnarVector],
    rowCount: Int
)

private object ColumnarBatch:
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

sealed private trait ColumnarVector:
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

final private case class ScalarVector(values: Array[ScalarValue]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(values(index))

  def unsafeScalarHash(index: Int): Long =
    ColumnarVector.scalarHash(values(index))

final private case class RawInt32Vector(
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

final private case class RawFloat64Vector(
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

final private case class RawInt64Vector(
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

final private case class RawFloat32Vector(
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

final private case class RawBooleanVector(
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

final private case class RawUtf8Vector(
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

final private case class Int32Values(values: Array[Int]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Int32(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index).toLong

final private case class NullableInt32Values(
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

final private case class Int64Values(values: Array[Long]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Int64(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index)

final private case class NullableInt64Values(
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

final private case class Float32Values(
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

final private case class TimestampValues(
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

final private case class Float64Values(
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

final private case class BooleanValues(
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

final private case class Utf8Values(values: Array[String]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Utf8(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index).hashCode.toLong

final private case class NullableUtf8Values(
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

final private case class SelectedVector(
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
final private case class GatheredVector(
    batches: Vector[DecodedBatch],
    columnIndex: Int,
    selectedBatches: Array[Int],
    selectedRows: Array[Int],
    validity: Option[Array[Byte]]
) extends ColumnarVector:
  val length: Int = selectedRows.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else if !isValid(index) then Right(ScalarValue.Null)
    else source(index).scalar(selectedRows(index))

  def unsafeScalarHash(index: Int): Long =
    if !isValid(index) then ColumnarVector.NullHash
    else source(index).unsafeScalarHash(selectedRows(index))

  private def source(index: Int): ColumnarVector =
    batches(selectedBatches(index)).columns(columnIndex)

  private def isValid(index: Int): Boolean =
    validity.forall(bytes => bit(bytes, index))

final private case class RawDictionaryVector(
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

private enum ColumnBorrowError:
  case Storage(error: StorageError)
  case Residual(reason: String)

private object ColumnarVector:
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

final private class PrimitiveEvalContext:
  var error: Option[ExecutionError] = None

  def failed: Boolean = error.nonEmpty

sealed private trait NullablePrimitiveExpression:
  def valid: Boolean
  def bind(columns: Array[ColumnarVector]): Option[String]
  def inputIndices: Vector[Int]

sealed abstract private class Int32PrimitiveExpression extends NullablePrimitiveExpression:
  var valid: Boolean = false
  var value: Int = 0
  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit

final private case class Int32ColumnExpression(index: Int) extends Int32PrimitiveExpression:
  private var column: Option[RawInt32Vector] = None
  val inputIndices = Vector(index)

  def bind(columns: Array[ColumnarVector]): Option[String] =
    columns.lift(index) match
      case Some(value: RawInt32Vector) =>
        column = Some(value)
        None
      case _ => Some(s"expression input $index is not plain ${DataType.Int32}")

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    column match
      case Some(actual) =>
        valid = actual.unsafeValid(row)
        if valid then value = actual.unsafeIntValue(row)
      case None => valid = false

final private case class Int32LiteralExpression(literal: Option[Int])
    extends Int32PrimitiveExpression:
  val inputIndices = Vector.empty
  def bind(columns: Array[ColumnarVector]): Option[String] = None

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    valid = literal.nonEmpty
    literal.foreach(actual => value = actual)

final private case class Int32UnaryExpression(
    id: ExprId,
    operator: UnaryOperator,
    input: Int32PrimitiveExpression
) extends Int32PrimitiveExpression:
  val inputIndices = input.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] = input.bind(columns)

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    input.evaluate(row, columns, context)
    valid = input.valid
    if valid && !context.failed then
      operator match
        case UnaryOperator.Negate =>
          if input.value == Int.MinValue then
            context.error = Some(ExecutionError.IntegerOverflow(id, BinaryOperator.Subtract))
            valid = false
          else value = -input.value
        case _ =>
          valid = false

final private case class Int32BinaryExpression(
    id: ExprId,
    operator: BinaryOperator,
    left: Int32PrimitiveExpression,
    right: Int32PrimitiveExpression
) extends Int32PrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    valid = left.valid && right.valid && !context.failed
    if valid then
      try
        value = operator match
          case BinaryOperator.Add      => Math.addExact(left.value, right.value)
          case BinaryOperator.Subtract => Math.subtractExact(left.value, right.value)
          case BinaryOperator.Multiply => Math.multiplyExact(left.value, right.value)
          case _                       => left.value
      catch
        case _: ArithmeticException =>
          context.error = Some(ExecutionError.IntegerOverflow(id, operator))
          valid = false

sealed abstract private class Int64PrimitiveExpression extends NullablePrimitiveExpression:
  var valid: Boolean = false
  var value: Long = 0L
  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit

final private case class Int64ColumnExpression(index: Int) extends Int64PrimitiveExpression:
  private var column: Option[RawInt64Vector] = None
  val inputIndices = Vector(index)

  def bind(columns: Array[ColumnarVector]): Option[String] =
    columns.lift(index) match
      case Some(value: RawInt64Vector) =>
        column = Some(value)
        None
      case _ => Some(s"expression input $index is not plain ${DataType.Int64}")

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    column match
      case Some(actual) =>
        valid = actual.unsafeValid(row)
        if valid then value = actual.unsafeLongValue(row)
      case None => valid = false

final private case class Int64LiteralExpression(literal: Option[Long])
    extends Int64PrimitiveExpression:
  val inputIndices = Vector.empty
  def bind(columns: Array[ColumnarVector]): Option[String] = None

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    valid = literal.nonEmpty
    literal.foreach(actual => value = actual)

final private case class Int64UnaryExpression(
    id: ExprId,
    operator: UnaryOperator,
    input: Int64PrimitiveExpression
) extends Int64PrimitiveExpression:
  val inputIndices = input.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] = input.bind(columns)

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    input.evaluate(row, columns, context)
    valid = input.valid
    if valid && !context.failed then
      operator match
        case UnaryOperator.Negate =>
          if input.value == Long.MinValue then
            context.error = Some(ExecutionError.IntegerOverflow(id, BinaryOperator.Subtract))
            valid = false
          else value = -input.value
        case _ =>
          valid = false

final private case class Int64BinaryExpression(
    id: ExprId,
    operator: BinaryOperator,
    left: Int64PrimitiveExpression,
    right: Int64PrimitiveExpression
) extends Int64PrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    valid = left.valid && right.valid && !context.failed
    if valid then
      try
        value = operator match
          case BinaryOperator.Add      => Math.addExact(left.value, right.value)
          case BinaryOperator.Subtract => Math.subtractExact(left.value, right.value)
          case BinaryOperator.Multiply => Math.multiplyExact(left.value, right.value)
          case _                       => left.value
      catch
        case _: ArithmeticException =>
          context.error = Some(ExecutionError.IntegerOverflow(id, operator))
          valid = false

sealed abstract private class Float32PrimitiveExpression extends NullablePrimitiveExpression:
  var valid: Boolean = false
  var value: Float = 0.0f
  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit

final private case class Float32ColumnExpression(index: Int) extends Float32PrimitiveExpression:
  private var column: Option[RawFloat32Vector] = None
  val inputIndices = Vector(index)

  def bind(columns: Array[ColumnarVector]): Option[String] =
    columns.lift(index) match
      case Some(value: RawFloat32Vector) =>
        column = Some(value)
        None
      case _ => Some(s"expression input $index is not plain ${DataType.Float32}")

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    column match
      case Some(actual) =>
        valid = actual.unsafeValid(row)
        if valid then value = actual.unsafeFloatValue(row)
      case None => valid = false

final private case class Float32LiteralExpression(literal: Option[Float])
    extends Float32PrimitiveExpression:
  val inputIndices = Vector.empty
  def bind(columns: Array[ColumnarVector]): Option[String] = None

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    valid = literal.nonEmpty
    literal.foreach(actual => value = actual)

final private case class Float32UnaryExpression(
    operator: UnaryOperator,
    input: Float32PrimitiveExpression
) extends Float32PrimitiveExpression:
  val inputIndices = input.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] = input.bind(columns)

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    input.evaluate(row, columns, context)
    valid = input.valid
    if valid then
      value = operator match
        case UnaryOperator.Negate => -input.value
        case UnaryOperator.Sqrt   => math.sqrt(input.value.toDouble).toFloat
        case _                    => input.value

final private case class Float32BinaryExpression(
    operator: BinaryOperator,
    left: Float32PrimitiveExpression,
    right: Float32PrimitiveExpression
) extends Float32PrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    valid = left.valid && right.valid && !context.failed
    if valid then
      value = operator match
        case BinaryOperator.Add      => left.value + right.value
        case BinaryOperator.Subtract => left.value - right.value
        case BinaryOperator.Multiply => left.value * right.value
        case BinaryOperator.Divide   => left.value / right.value
        case _                       => left.value

sealed abstract private class Float64PrimitiveExpression extends NullablePrimitiveExpression:
  var valid: Boolean = false
  var value: Double = 0.0
  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit

final private case class Float64ColumnExpression(index: Int) extends Float64PrimitiveExpression:
  private var column: Option[RawFloat64Vector] = None
  val inputIndices = Vector(index)

  def bind(columns: Array[ColumnarVector]): Option[String] =
    columns.lift(index) match
      case Some(value: RawFloat64Vector) =>
        column = Some(value)
        None
      case _ => Some(s"expression input $index is not plain ${DataType.Float64}")

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    column match
      case Some(actual) =>
        valid = actual.unsafeValid(row)
        if valid then value = actual.unsafeDoubleValue(row)
      case None => valid = false

final private case class Float64LiteralExpression(literal: Option[Double])
    extends Float64PrimitiveExpression:
  val inputIndices = Vector.empty
  def bind(columns: Array[ColumnarVector]): Option[String] = None

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    valid = literal.nonEmpty
    literal.foreach(actual => value = actual)

final private case class Float64UnaryExpression(
    operator: UnaryOperator,
    input: Float64PrimitiveExpression
) extends Float64PrimitiveExpression:
  val inputIndices = input.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] = input.bind(columns)

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    input.evaluate(row, columns, context)
    valid = input.valid
    if valid then
      value = operator match
        case UnaryOperator.Negate => -input.value
        case UnaryOperator.Sqrt   => math.sqrt(input.value)
        case _                    => input.value

final private case class Float64BinaryExpression(
    operator: BinaryOperator,
    left: Float64PrimitiveExpression,
    right: Float64PrimitiveExpression
) extends Float64PrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    valid = left.valid && right.valid && !context.failed
    if valid then
      value = operator match
        case BinaryOperator.Add      => left.value + right.value
        case BinaryOperator.Subtract => left.value - right.value
        case BinaryOperator.Multiply => left.value * right.value
        case BinaryOperator.Divide   => left.value / right.value
        case _                       => left.value

sealed abstract private class Utf8PrimitiveExpression extends NullablePrimitiveExpression:
  var valid: Boolean = false
  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit
  def byteLength: Int
  def byteAt(index: Int): Int
  def stringValue: String

final private case class Utf8ColumnExpression(index: Int) extends Utf8PrimitiveExpression:
  private var column: Option[RawUtf8Vector] = None
  private var row: Int = 0
  val inputIndices = Vector(index)

  def bind(columns: Array[ColumnarVector]): Option[String] =
    columns.lift(index) match
      case Some(value: RawUtf8Vector) =>
        column = Some(value)
        None
      case _ => Some(s"expression input $index is not plain ${DataType.Utf8}")

  def evaluate(
      actualRow: Int,
      columns: Array[ColumnarVector],
      context: PrimitiveEvalContext
  ): Unit =
    row = actualRow
    valid = column.exists(_.unsafeValid(actualRow))

  def byteLength: Int = column.fold(0)(_.unsafeByteLength(row))

  def byteAt(index: Int): Int = column.fold(0)(_.unsafeByte(row, index))

  def stringValue: String = column.fold("")(_.unsafeStringValue(row))

final private case class Utf8LiteralExpression(literal: Option[String])
    extends Utf8PrimitiveExpression:
  private val bytes = literal.fold(Array.emptyByteArray)(_.getBytes("UTF-8"))
  val inputIndices = Vector.empty

  def bind(columns: Array[ColumnarVector]): Option[String] = None

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    valid = literal.nonEmpty

  def byteLength: Int = bytes.length

  def byteAt(index: Int): Int = bytes(index) & 0xff

  def stringValue: String = literal.getOrElse("")

sealed abstract private class BooleanPrimitiveExpression extends NullablePrimitiveExpression:
  var valid: Boolean = false
  var value: Boolean = false
  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit

final private case class BooleanColumnExpression(index: Int) extends BooleanPrimitiveExpression:
  private var column: Option[RawBooleanVector] = None
  val inputIndices = Vector(index)

  def bind(columns: Array[ColumnarVector]): Option[String] =
    columns.lift(index) match
      case Some(value: RawBooleanVector) =>
        column = Some(value)
        None
      case _ => Some(s"expression input $index is not plain ${DataType.Bool}")

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    column match
      case Some(actual) =>
        valid = actual.unsafeValid(row)
        if valid then value = actual.unsafeBooleanValue(row)
      case None => valid = false

final private case class BooleanLiteralExpression(literal: Option[Boolean])
    extends BooleanPrimitiveExpression:
  val inputIndices = Vector.empty
  def bind(columns: Array[ColumnarVector]): Option[String] = None

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    valid = literal.nonEmpty
    literal.foreach(actual => value = actual)

final private case class IsNullExpression(input: NullablePrimitiveExpression)
    extends BooleanPrimitiveExpression:
  val inputIndices = input.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] = input.bind(columns)

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    input match
      case value: Int32PrimitiveExpression   => value.evaluate(row, columns, context)
      case value: Int64PrimitiveExpression   => value.evaluate(row, columns, context)
      case value: Float32PrimitiveExpression => value.evaluate(row, columns, context)
      case value: Float64PrimitiveExpression => value.evaluate(row, columns, context)
      case value: Utf8PrimitiveExpression    => value.evaluate(row, columns, context)
      case value: BooleanPrimitiveExpression => value.evaluate(row, columns, context)
    valid = true
    value = !input.valid

final private case class IsTrueExpression(input: BooleanPrimitiveExpression)
    extends BooleanPrimitiveExpression:
  val inputIndices = input.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] = input.bind(columns)

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    input.evaluate(row, columns, context)
    valid = true
    value = input.valid && input.value

final private case class BooleanLogicExpression(
    operator: BinaryOperator,
    left: BooleanPrimitiveExpression,
    right: BooleanPrimitiveExpression
) extends BooleanPrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    if context.failed then valid = false
    else
      operator match
        case BinaryOperator.And =>
          if (left.valid && !left.value) || (right.valid && !right.value) then
            valid = true
            value = false
          else if left.valid && right.valid then
            valid = true
            value = true
          else valid = false
        case BinaryOperator.Or =>
          if (left.valid && left.value) || (right.valid && right.value) then
            valid = true
            value = true
          else if left.valid && right.valid then
            valid = true
            value = false
          else valid = false
        case _ => valid = false

final private case class Int32ComparisonExpression(
    operator: BinaryOperator,
    left: Int32PrimitiveExpression,
    right: Int32PrimitiveExpression
) extends BooleanPrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    if operator == BinaryOperator.NullSafeEqual then
      valid = true
      value =
        if !left.valid then !right.valid
        else right.valid && left.value == right.value
    else
      valid = left.valid && right.valid && !context.failed
      if valid then
        val compared = left.value.compare(right.value)
        value = PrimitiveExpression.comparison(operator, compared, equal = compared == 0)

final private case class Int64ComparisonExpression(
    operator: BinaryOperator,
    left: Int64PrimitiveExpression,
    right: Int64PrimitiveExpression
) extends BooleanPrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    if operator == BinaryOperator.NullSafeEqual then
      valid = true
      value =
        if !left.valid then !right.valid
        else right.valid && left.value == right.value
    else
      valid = left.valid && right.valid && !context.failed
      if valid then
        val compared = left.value.compare(right.value)
        value = PrimitiveExpression.comparison(operator, compared, equal = compared == 0)

final private case class Float32ComparisonExpression(
    operator: BinaryOperator,
    left: Float32PrimitiveExpression,
    right: Float32PrimitiveExpression
) extends BooleanPrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    if operator == BinaryOperator.NullSafeEqual then
      valid = true
      value =
        if !left.valid then !right.valid
        else right.valid && PrimitiveExpression.floatEqual(left.value, right.value)
    else
      valid = left.valid && right.valid && !context.failed
      if valid then
        val compared = PrimitiveExpression.compareFloat(left.value, right.value)
        value = PrimitiveExpression.comparison(
          operator,
          compared,
          PrimitiveExpression.floatEqual(left.value, right.value)
        )

final private case class Float64ComparisonExpression(
    operator: BinaryOperator,
    left: Float64PrimitiveExpression,
    right: Float64PrimitiveExpression
) extends BooleanPrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    if operator == BinaryOperator.NullSafeEqual then
      valid = true
      value =
        if !left.valid then !right.valid
        else right.valid && PrimitiveExpression.floatEqual(left.value, right.value)
    else
      valid = left.valid && right.valid && !context.failed
      if valid then
        val compared = PrimitiveExpression.compareFloat(left.value, right.value)
        value = PrimitiveExpression.comparison(
          operator,
          compared,
          PrimitiveExpression.floatEqual(left.value, right.value)
        )

final private case class Utf8ComparisonExpression(
    operator: BinaryOperator,
    left: Utf8PrimitiveExpression,
    right: Utf8PrimitiveExpression
) extends BooleanPrimitiveExpression:
  val inputIndices = left.inputIndices ++ right.inputIndices
  def bind(columns: Array[ColumnarVector]): Option[String] =
    left.bind(columns).orElse(right.bind(columns))

  def evaluate(row: Int, columns: Array[ColumnarVector], context: PrimitiveEvalContext): Unit =
    left.evaluate(row, columns, context)
    if !context.failed then right.evaluate(row, columns, context)
    if operator == BinaryOperator.NullSafeEqual then
      valid = true
      value =
        if !left.valid then !right.valid
        else right.valid && PrimitiveExpression.equalUtf8(left, right)
    else
      valid = left.valid && right.valid && !context.failed
      if valid then
        val compared = PrimitiveExpression.compareUtf8(left, right)
        value = PrimitiveExpression.comparison(operator, compared, equal = compared == 0)

private enum PrimitiveProjection:
  case Direct(index: Int)
  case Int32Value(expression: Int32PrimitiveExpression)
  case Int64Value(expression: Int64PrimitiveExpression)
  case Float32Value(expression: Float32PrimitiveExpression)
  case Float64Value(expression: Float64PrimitiveExpression)
  case Utf8Value(expression: Utf8PrimitiveExpression)
  case BooleanValue(expression: BooleanPrimitiveExpression)

  def bind(columns: Array[ColumnarVector]): Option[String] = this match
    case Direct(_)                => None
    case Int32Value(expression)   => expression.bind(columns)
    case Int64Value(expression)   => expression.bind(columns)
    case Float32Value(expression) => expression.bind(columns)
    case Float64Value(expression) => expression.bind(columns)
    case Utf8Value(expression)    => expression.bind(columns)
    case BooleanValue(expression) => expression.bind(columns)

  def inputIndices: Vector[Int] = this match
    case Direct(_)                => Vector.empty
    case Int32Value(expression)   => expression.inputIndices
    case Int64Value(expression)   => expression.inputIndices
    case Float32Value(expression) => expression.inputIndices
    case Float64Value(expression) => expression.inputIndices
    case Utf8Value(expression)    => expression.inputIndices
    case BooleanValue(expression) => expression.inputIndices

private object PrimitiveExpression:
  def compileProjection(expression: ResolvedExpr): Option[PrimitiveProjection] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index) =>
        Some(PrimitiveProjection.Direct(index))
      case _ =>
        expression.dataType match
          case DataType.Int32 => compileInt32(expression).map(PrimitiveProjection.Int32Value.apply)
          case DataType.Int64 => compileInt64(expression).map(PrimitiveProjection.Int64Value.apply)
          case DataType.Float32 =>
            compileFloat32(expression).map(PrimitiveProjection.Float32Value.apply)
          case DataType.Float64 =>
            compileFloat64(expression).map(PrimitiveProjection.Float64Value.apply)
          case DataType.Utf8 =>
            compileUtf8(expression).map(PrimitiveProjection.Utf8Value.apply)
          case DataType.Bool =>
            compileBoolean(expression).map(PrimitiveProjection.BooleanValue.apply)
          case _ => None

  def compileBoolean(expression: ResolvedExpr): Option[BooleanPrimitiveExpression] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index)
          if expression.dataType == DataType.Bool =>
        Some(BooleanColumnExpression(index))
      case ExprNode.Literal(LiteralValue.Bool(value)) =>
        Some(BooleanLiteralExpression(Some(value)))
      case ExprNode.Literal(LiteralValue.Null(DataType.Bool)) =>
        Some(BooleanLiteralExpression(None))
      case ExprNode.Unary(UnaryOperator.IsTrue, input) =>
        compileBoolean(input).map(IsTrueExpression.apply)
      case ExprNode.Unary(UnaryOperator.IsNull, input) =>
        compileNullable(input).map(IsNullExpression.apply)
      case ExprNode.Binary(operator @ (BinaryOperator.And | BinaryOperator.Or), left, right) =>
        for
          lhs <- compileBoolean(left)
          rhs <- compileBoolean(right)
        yield BooleanLogicExpression(operator, lhs, rhs)
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Equal | BinaryOperator.NullSafeEqual | BinaryOperator.NotEqual |
              BinaryOperator.LessThan | BinaryOperator.LessThanOrEqual |
              BinaryOperator.GreaterThan | BinaryOperator.GreaterThanOrEqual
            ),
            left,
            right
          ) if left.dataType == DataType.Int32 && right.dataType == DataType.Int32 =>
        for
          lhs <- compileInt32(left)
          rhs <- compileInt32(right)
        yield Int32ComparisonExpression(operator, lhs, rhs)
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Equal | BinaryOperator.NullSafeEqual | BinaryOperator.NotEqual |
              BinaryOperator.LessThan | BinaryOperator.LessThanOrEqual |
              BinaryOperator.GreaterThan | BinaryOperator.GreaterThanOrEqual
            ),
            left,
            right
          ) if left.dataType == DataType.Int64 && right.dataType == DataType.Int64 =>
        for
          lhs <- compileInt64(left)
          rhs <- compileInt64(right)
        yield Int64ComparisonExpression(operator, lhs, rhs)
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Equal | BinaryOperator.NullSafeEqual | BinaryOperator.NotEqual |
              BinaryOperator.LessThan | BinaryOperator.LessThanOrEqual |
              BinaryOperator.GreaterThan | BinaryOperator.GreaterThanOrEqual
            ),
            left,
            right
          ) if left.dataType == DataType.Float32 && right.dataType == DataType.Float32 =>
        for
          lhs <- compileFloat32(left)
          rhs <- compileFloat32(right)
        yield Float32ComparisonExpression(operator, lhs, rhs)
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Equal | BinaryOperator.NullSafeEqual | BinaryOperator.NotEqual |
              BinaryOperator.LessThan | BinaryOperator.LessThanOrEqual |
              BinaryOperator.GreaterThan | BinaryOperator.GreaterThanOrEqual
            ),
            left,
            right
          ) if left.dataType == DataType.Float64 && right.dataType == DataType.Float64 =>
        for
          lhs <- compileFloat64(left)
          rhs <- compileFloat64(right)
        yield Float64ComparisonExpression(operator, lhs, rhs)
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Equal | BinaryOperator.NullSafeEqual | BinaryOperator.NotEqual |
              BinaryOperator.LessThan | BinaryOperator.LessThanOrEqual |
              BinaryOperator.GreaterThan | BinaryOperator.GreaterThanOrEqual
            ),
            left,
            right
          ) if left.dataType == DataType.Utf8 && right.dataType == DataType.Utf8 =>
        for
          lhs <- compileUtf8(left)
          rhs <- compileUtf8(right)
        yield Utf8ComparisonExpression(operator, lhs, rhs)
      case _ => None

  private def compileNullable(expression: ResolvedExpr): Option[NullablePrimitiveExpression] =
    expression.dataType match
      case DataType.Int32   => compileInt32(expression)
      case DataType.Int64   => compileInt64(expression)
      case DataType.Float32 => compileFloat32(expression)
      case DataType.Float64 => compileFloat64(expression)
      case DataType.Utf8    => compileUtf8(expression)
      case DataType.Bool    => compileBoolean(expression)
      case _                => None

  private def compileInt32(expression: ResolvedExpr): Option[Int32PrimitiveExpression] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index)
          if expression.dataType == DataType.Int32 =>
        Some(Int32ColumnExpression(index))
      case ExprNode.Literal(LiteralValue.Int32(value)) =>
        Some(Int32LiteralExpression(Some(value)))
      case ExprNode.Literal(LiteralValue.Null(DataType.Int32)) =>
        Some(Int32LiteralExpression(None))
      case ExprNode.Unary(UnaryOperator.Negate, input) =>
        compileInt32(input).map(Int32UnaryExpression(expression.id, UnaryOperator.Negate, _))
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Add | BinaryOperator.Subtract | BinaryOperator.Multiply
            ),
            left,
            right
          ) =>
        for
          lhs <- compileInt32(left)
          rhs <- compileInt32(right)
        yield Int32BinaryExpression(expression.id, operator, lhs, rhs)
      case _ => None

  private def compileInt64(expression: ResolvedExpr): Option[Int64PrimitiveExpression] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index)
          if expression.dataType == DataType.Int64 =>
        Some(Int64ColumnExpression(index))
      case ExprNode.Literal(LiteralValue.Int64(value)) =>
        Some(Int64LiteralExpression(Some(value)))
      case ExprNode.Literal(LiteralValue.Null(DataType.Int64)) =>
        Some(Int64LiteralExpression(None))
      case ExprNode.Unary(UnaryOperator.Negate, input) =>
        compileInt64(input).map(Int64UnaryExpression(expression.id, UnaryOperator.Negate, _))
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Add | BinaryOperator.Subtract | BinaryOperator.Multiply
            ),
            left,
            right
          ) =>
        for
          lhs <- compileInt64(left)
          rhs <- compileInt64(right)
        yield Int64BinaryExpression(expression.id, operator, lhs, rhs)
      case _ => None

  private def compileFloat32(expression: ResolvedExpr): Option[Float32PrimitiveExpression] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index)
          if expression.dataType == DataType.Float32 =>
        Some(Float32ColumnExpression(index))
      case ExprNode.Literal(LiteralValue.Float32(value)) =>
        Some(Float32LiteralExpression(Some(value)))
      case ExprNode.Literal(LiteralValue.Null(DataType.Float32)) =>
        Some(Float32LiteralExpression(None))
      case ExprNode.Unary(operator @ (UnaryOperator.Negate | UnaryOperator.Sqrt), input) =>
        compileFloat32(input).map(Float32UnaryExpression(operator, _))
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Add | BinaryOperator.Subtract | BinaryOperator.Multiply |
              BinaryOperator.Divide
            ),
            left,
            right
          ) =>
        for
          lhs <- compileFloat32(left)
          rhs <- compileFloat32(right)
        yield Float32BinaryExpression(operator, lhs, rhs)
      case _ => None

  private def compileFloat64(expression: ResolvedExpr): Option[Float64PrimitiveExpression] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index)
          if expression.dataType == DataType.Float64 =>
        Some(Float64ColumnExpression(index))
      case ExprNode.Literal(LiteralValue.Float64(value)) =>
        Some(Float64LiteralExpression(Some(value)))
      case ExprNode.Literal(LiteralValue.Null(DataType.Float64)) =>
        Some(Float64LiteralExpression(None))
      case ExprNode.Unary(operator @ (UnaryOperator.Negate | UnaryOperator.Sqrt), input) =>
        compileFloat64(input).map(Float64UnaryExpression(operator, _))
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.Add | BinaryOperator.Subtract | BinaryOperator.Multiply |
              BinaryOperator.Divide
            ),
            left,
            right
          ) =>
        for
          lhs <- compileFloat64(left)
          rhs <- compileFloat64(right)
        yield Float64BinaryExpression(operator, lhs, rhs)
      case _ => None

  private def compileUtf8(expression: ResolvedExpr): Option[Utf8PrimitiveExpression] =
    expression.node match
      case ExprNode.Column(InputRef.Current, _, _, _, index)
          if expression.dataType == DataType.Utf8 =>
        Some(Utf8ColumnExpression(index))
      case ExprNode.Literal(LiteralValue.Utf8(value)) =>
        Some(Utf8LiteralExpression(Some(value)))
      case ExprNode.Literal(LiteralValue.Null(DataType.Utf8)) =>
        Some(Utf8LiteralExpression(None))
      case _ => None

  def comparison(operator: BinaryOperator, compared: Int, equal: Boolean): Boolean =
    operator match
      case BinaryOperator.Equal              => equal
      case BinaryOperator.NotEqual           => !equal
      case BinaryOperator.LessThan           => compared < 0
      case BinaryOperator.LessThanOrEqual    => compared <= 0
      case BinaryOperator.GreaterThan        => compared > 0
      case BinaryOperator.GreaterThanOrEqual => compared >= 0
      case BinaryOperator.NullSafeEqual      => equal
      case _                                 => false

  def floatEqual(left: Double, right: Double): Boolean =
    !left.isNaN && !right.isNaN && left == right

  def compareFloat(left: Double, right: Double): Int =
    if left.isNaN then if right.isNaN then 0 else 1
    else if right.isNaN then -1
    else left.compare(right)

  def equalUtf8(left: Utf8PrimitiveExpression, right: Utf8PrimitiveExpression): Boolean =
    if left.byteLength != right.byteLength then false
    else
      var index = 0
      var equal = true
      while index < left.byteLength && equal do
        equal = left.byteAt(index) == right.byteAt(index)
        index += 1
      equal

  def compareUtf8(left: Utf8PrimitiveExpression, right: Utf8PrimitiveExpression): Int =
    val limit = math.min(left.byteLength, right.byteLength)
    var index = 0
    while index < limit do
      val compared = left.byteAt(index).compare(right.byteAt(index))
      if compared != 0 then return compared
      index += 1
    left.byteLength.compare(right.byteLength)

final private case class PrimitiveExpressionPipeline(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    predicate: Option[BooleanPrimitiveExpression],
    projections: Vector[PrimitiveProjection]
) extends KernelPlan:
  val name =
    s"ExpressionPipeline[filter=${predicate.nonEmpty},projections=${projections.length}]"
  private val borrowedIndices =
    (predicate.toVector.flatMap(_.inputIndices) ++ projections.flatMap(_.inputIndices)).distinct
  private val directIndices = projections
    .collect:
      case PrimitiveProjection.Direct(index) => index
    .distinct

  def execute(sources: ReferenceSources): KernelAttempt =
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val output = Vector.newBuilder[ColumnarBatch]
        val context = new PrimitiveEvalContext
        var residual: Option[String] = None
        var batchIndex = 0
        while batchIndex < batches.length && residual.isEmpty && !context.failed do
          val batch = batches(batchIndex)
          val directColumns = Array.fill[Option[ColumnarVector]](batch.columns.length)(None)
          val plainDirect = Vector.newBuilder[Int]
          var direct = 0
          while direct < directIndices.length && residual.isEmpty do
            val index = directIndices(direct)
            batch.columns.lift(index) match
              case None =>
                residual = Some(
                  s"direct projection $index is outside a ${batch.columns.length}-column batch"
                )
              case Some(column) if column.encoding == PhysicalEncoding.Plain =>
                plainDirect += index
              case Some(column) =>
                ColumnarVector.copy(column) match
                  case Right(value) => directColumns(index) = Some(value)
                  case Left(reason) => residual = Some(reason)
            direct += 1

          if residual.isEmpty then
            val batchBorrowedIndices = (borrowedIndices ++ plainDirect.result()).distinct
            val borrowed =
              ColumnarVector.withBorrowedColumns(
                batch,
                batchBorrowedIndices
              ): borrowedColumns =>
                val columns = new Array[ColumnarVector](batch.columns.length)
                var borrowedIndex = 0
                while borrowedIndex < batchBorrowedIndices.length do
                  columns(batchBorrowedIndices(borrowedIndex)) = borrowedColumns(borrowedIndex)
                  borrowedIndex += 1

                residual = predicate.flatMap(_.bind(columns))
                var projection = 0
                while projection < projections.length && residual.isEmpty do
                  residual = projections(projection).bind(columns)
                  projection += 1

                if residual.isEmpty then
                  val selected = new Array[Int](batch.rowCount)
                  var selectedCount = 0
                  var row = 0
                  while row < batch.rowCount && !context.failed do
                    predicate match
                      case Some(filter) =>
                        filter.evaluate(row, columns, context)
                        if filter.valid && filter.value then
                          selected(selectedCount) = row
                          selectedCount += 1
                      case None =>
                        selected(selectedCount) = row
                        selectedCount += 1
                    row += 1

                  if !context.failed then
                    val compact = java.util.Arrays.copyOf(selected, selectedCount)
                    val projected = Vector.newBuilder[ColumnarVector]
                    projection = 0
                    while projection < projections.length && !context.failed && residual.isEmpty
                    do
                      evaluateProjection(
                        projections(projection),
                        outputSchema.fields(projection),
                        columns,
                        directColumns,
                        compact,
                        context
                      ) match
                        case Right(value) => projected += value
                        case Left(reason) => residual = Some(reason)
                      projection += 1
                    if !context.failed && residual.isEmpty then
                      output += ColumnarBatch(projected.result(), selectedCount)
            borrowed match
              case Left(ColumnBorrowError.Storage(error)) =>
                context.error = Some(ExecutionError.Storage(error))
              case Left(ColumnBorrowError.Residual(reason)) =>
                residual = Some(reason)
              case Right(()) => ()
          batchIndex += 1

        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              context.error.toLeft(ColumnarResult(outputSchema, order, output.result()))
            )

  private def evaluateProjection(
      projection: PrimitiveProjection,
      field: Field,
      columns: Array[ColumnarVector],
      directColumns: Array[Option[ColumnarVector]],
      selected: Array[Int],
      context: PrimitiveEvalContext
  ): Either[String, ColumnarVector] = projection match
    case PrimitiveProjection.Direct(index) =>
      directColumns(index) match
        case Some(column) =>
          Right(
            if selected.length == column.length then column
            else SelectedVector(column, selected)
          )
        case None => ColumnarVector.compact(columns(index), field, selected)
    case PrimitiveProjection.Int32Value(expression) =>
      val values = new Array[Int](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length && !context.failed do
        expression.evaluate(selected(row), columns, context)
        valid(row) = expression.valid
        if expression.valid then values(row) = expression.value
        row += 1
      Right(
        if field.nullable then NullableInt32Values(values, valid)
        else Int32Values(values)
      )
    case PrimitiveProjection.Int64Value(expression) =>
      val values = new Array[Long](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length && !context.failed do
        expression.evaluate(selected(row), columns, context)
        valid(row) = expression.valid
        if expression.valid then values(row) = expression.value
        row += 1
      Right(
        if field.nullable then NullableInt64Values(values, valid)
        else Int64Values(values)
      )
    case PrimitiveProjection.Float32Value(expression) =>
      val values = new Array[Float](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length && !context.failed do
        expression.evaluate(selected(row), columns, context)
        valid(row) = expression.valid
        if expression.valid then values(row) = expression.value
        row += 1
      Right(Float32Values(values, valid))
    case PrimitiveProjection.Float64Value(expression) =>
      val values = new Array[Double](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length && !context.failed do
        expression.evaluate(selected(row), columns, context)
        valid(row) = expression.valid
        if expression.valid then values(row) = expression.value
        row += 1
      Right(Float64Values(values, valid))
    case PrimitiveProjection.Utf8Value(expression) =>
      val values = new Array[String](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length && !context.failed do
        expression.evaluate(selected(row), columns, context)
        valid(row) = expression.valid
        if expression.valid then values(row) = expression.stringValue
        row += 1
      Right(
        if field.nullable then NullableUtf8Values(values, valid)
        else Utf8Values(values)
      )
    case PrimitiveProjection.BooleanValue(expression) =>
      val values = new Array[Boolean](selected.length)
      val valid = new Array[Boolean](selected.length)
      var row = 0
      while row < selected.length && !context.failed do
        expression.evaluate(selected(row), columns, context)
        valid(row) = expression.valid
        if expression.valid then values(row) = expression.value
        row += 1
      Right(BooleanValues(values, valid))

private enum KernelAttempt:
  case Completed(result: Either[ExecutionError, ColumnarResult])
  case Residual(reason: String)

sealed private trait KernelPlan:
  def name: String
  def execute(sources: ReferenceSources): KernelAttempt
  def close(): Unit = ()

private object KernelPlan:
  def classify(plan: LogicalPlan): Option[KernelPlan] = plan match
    case LogicalPlan.Project(
          LogicalPlan.Source(reference, inputSchema),
          expressions,
          outputSchema
        ) =>
      directProjection(expressions)
        .map: indices =>
          DirectProjection(reference, inputSchema, outputSchema, plan.order, indices)
        .orElse:
          primitiveProjections(expressions).map: projections =>
            PrimitiveExpressionPipeline(
              reference,
              inputSchema,
              outputSchema,
              plan.order,
              None,
              projections
            )
    case LogicalPlan.Project(
          LogicalPlan.Filter(
            LogicalPlan.Source(reference, inputSchema),
            predicate,
            _
          ),
          expressions,
          outputSchema
        ) =>
      (for
        filter <- int32Filter(predicate)
        projections <- int32Projections(expressions)
      yield FusedInt32(
        reference,
        inputSchema,
        outputSchema,
        plan.order,
        filter,
        projections
      )).orElse:
        for
          filter <- PrimitiveExpression.compileBoolean(predicate)
          projections <- primitiveProjections(expressions)
        yield PrimitiveExpressionPipeline(
          reference,
          inputSchema,
          outputSchema,
          plan.order,
          Some(filter),
          projections
        )
    case LogicalPlan.Filter(
          LogicalPlan.Source(reference, inputSchema),
          predicate,
          outputSchema
        ) =>
      int32Filter(predicate)
        .map: filter =>
          FilterInt32(reference, inputSchema, outputSchema, plan.order, filter)
        .orElse:
          PrimitiveExpression
            .compileBoolean(predicate)
            .map: filter =>
              PrimitiveExpressionPipeline(
                reference,
                inputSchema,
                outputSchema,
                plan.order,
                Some(filter),
                inputSchema.fields.indices.toVector.map(PrimitiveProjection.Direct.apply)
              )
    case LogicalPlan.Aggregate(
          LogicalPlan.Source(reference, inputSchema),
          keys,
          aggregates,
          outputSchema
        ) =>
      aggregate(
        reference,
        inputSchema,
        outputSchema,
        plan.order,
        keys,
        aggregates,
        inputSchema.fields.indices.toVector
      )
    case LogicalPlan.Aggregate(
          LogicalPlan.Project(
            LogicalPlan.Source(reference, inputSchema),
            projections,
            _
          ),
          keys,
          aggregates,
          outputSchema
        ) =>
      directProjection(projections).flatMap: sourceIndices =>
        aggregate(
          reference,
          inputSchema,
          outputSchema,
          plan.order,
          keys,
          aggregates,
          sourceIndices
        )
    case LogicalPlan.Join(
          LogicalPlan.Source(leftReference, leftSchema),
          LogicalPlan.Source(rightReference, rightSchema),
          kind @ (
            JoinKind.Inner | JoinKind.LeftOuter | JoinKind.LeftSemi | JoinKind.LeftAnti
          ),
          condition,
          columns,
          outputSchema
        ) =>
      joinKeys(condition).map: (leftKey, rightKey) =>
        HashJoin(
          leftReference,
          leftSchema,
          rightReference,
          rightSchema,
          outputSchema,
          plan.order,
          kind,
          leftKey,
          rightKey,
          columns
        )
    case LogicalPlan.UnionAll(
          LogicalPlan.Source(leftReference, leftSchema),
          LogicalPlan.Source(rightReference, rightSchema),
          outputSchema
        ) =>
      Some(
        StreamingUnionAll(
          leftReference,
          leftSchema,
          rightReference,
          rightSchema,
          outputSchema,
          plan.order
        )
      )
    case _ => None

  private def directProjection(expressions: Vector[NamedExpression]): Option[Vector[Int]] =
    val indices = expressions
      .map(_.expression.node)
      .map:
        case ExprNode.Column(InputRef.Current, _, _, _, index) => Some(index)
        case _                                                 => None
    sequence(indices)

  private def int32Filter(expression: ResolvedExpr): Option[Int32Filter] =
    expression.node match
      case ExprNode.Binary(
            operator @ (
              BinaryOperator.GreaterThan | BinaryOperator.GreaterThanOrEqual |
              BinaryOperator.LessThan | BinaryOperator.LessThanOrEqual
            ),
            ResolvedExpr(
              _,
              DataType.Int32,
              false,
              ExprNode.Column(InputRef.Current, _, _, _, index)
            ),
            ResolvedExpr(_, DataType.Int32, false, ExprNode.Literal(LiteralValue.Int32(value)))
          ) =>
        Some(Int32Filter(index, operator, value))
      case _ => None

  private def int32Projections(
      expressions: Vector[NamedExpression]
  ): Option[Vector[Int32Projection]] =
    sequence:
      expressions.map: named =>
        named.expression match
          case ResolvedExpr(
                id,
                DataType.Int32,
                false,
                ExprNode.Column(InputRef.Current, _, _, _, index)
              ) =>
            Some(Int32Projection.Direct(index, id))
          case ResolvedExpr(
                id,
                DataType.Int32,
                false,
                ExprNode.Binary(
                  BinaryOperator.Add,
                  ResolvedExpr(
                    _,
                    DataType.Int32,
                    false,
                    ExprNode.Column(InputRef.Current, _, _, _, index)
                  ),
                  ResolvedExpr(
                    _,
                    DataType.Int32,
                    false,
                    ExprNode.Literal(LiteralValue.Int32(value))
                  )
                )
              ) =>
            Some(Int32Projection.Add(index, value, id))
          case _ => None

  private def primitiveProjections(
      expressions: Vector[NamedExpression]
  ): Option[Vector[PrimitiveProjection]] =
    sequence(expressions.map(named => PrimitiveExpression.compileProjection(named.expression)))

  private def aggregate(
      reference: SourceRef,
      inputSchema: Schema,
      outputSchema: Schema,
      order: OrderGuarantee,
      keys: Vector[NamedExpression],
      aggregates: Vector[NamedAggregateExpression],
      sourceIndices: Vector[Int]
  ): Option[KernelPlan] =
    val distinctPlan = Option.when(aggregates.isEmpty):
      val indices = keys
        .map(_.expression.node)
        .map:
          case ExprNode.Column(InputRef.Current, _, _, _, index) =>
            sourceIndices.lift(index)
          case _ => None
      sequence(indices).map: columns =>
        distinct(reference, inputSchema, outputSchema, order, columns)

    distinctPlan.flatten.orElse:
      val compiledKeys = sequence:
        keys.map:
          case NamedExpression(
                _,
                ResolvedExpr(
                  _,
                  keyType @ (DataType.Int32 | DataType.Utf8),
                  nullable,
                  ExprNode.Column(InputRef.Current, _, _, _, keyIndex)
                )
              ) =>
            sourceIndices.lift(keyIndex).map(AggregateKey(_, keyType, nullable))
          case _ => None
      val compiledAggregates = sequence:
        aggregates.map: named =>
          named.expression.node match
            case AggregateNode.Count      => Some(AggregateSpec.Count)
            case AggregateNode.Sum(input) =>
              aggregateInput(input)
                .flatMap((index, _) => sourceIndices.lift(index))
                .map(AggregateSpec.Sum.apply)
            case AggregateNode.Mean(input) =>
              aggregateInput(input)
                .flatMap((index, _) => sourceIndices.lift(index))
                .map(AggregateSpec.Mean.apply)
            case AggregateNode.VariancePop(input) =>
              aggregateInput(input)
                .flatMap((index, _) => sourceIndices.lift(index))
                .map(AggregateSpec.VariancePop.apply)
            case _ => None

      for
        compiledKeyVector <- compiledKeys
        compiled <- compiledAggregates
        if compiledKeyVector.nonEmpty
      yield
        val numericIndices = compiled.flatMap(_.inputIndex)
        compiledKeyVector match
          case Vector(AggregateKey(keyIndex, keyType, false))
              if numericIndices.distinct.size <= 1 =>
            (keyType, compiled) match
              case (DataType.Utf8, Vector(AggregateSpec.Sum(valueIndex))) =>
                Utf8SumAggregate(
                  reference,
                  inputSchema,
                  outputSchema,
                  order,
                  keyIndex,
                  valueIndex
                )
              case (DataType.Utf8, _) =>
                Utf8PrimitiveAggregate(
                  reference,
                  inputSchema,
                  outputSchema,
                  order,
                  keyIndex,
                  compiled
                )
              case (DataType.Int32, _) =>
                Int32HashAggregate(
                  reference,
                  inputSchema,
                  outputSchema,
                  order,
                  keyIndex,
                  compiled
                )
              case _ =>
                HashAggregate(
                  reference,
                  inputSchema,
                  outputSchema,
                  order,
                  keyIndex,
                  keyType,
                  compiled
                )
          case _ =>
            GeneralHashAggregate(
              reference,
              inputSchema,
              outputSchema,
              order,
              compiledKeyVector,
              compiled
            )

  private def distinct(
      reference: SourceRef,
      inputSchema: Schema,
      outputSchema: Schema,
      order: OrderGuarantee,
      indices: Vector[Int]
  ): KernelPlan =
    indices match
      case Vector(index)
          if inputSchema.fields
            .lift(index)
            .exists(field => field.dataType == DataType.Int32 && !field.nullable) =>
        Int32Distinct(reference, inputSchema, outputSchema, order, index)
      case _ =>
        HashDistinct(reference, inputSchema, outputSchema, order, indices)

  private def aggregateInput(input: ResolvedExpr): Option[(Int, ExprId)] =
    input match
      case ResolvedExpr(
            id,
            DataType.Float64,
            _,
            ExprNode.Column(InputRef.Current, _, _, _, index)
          ) =>
        Some(index -> id)
      case _ => None

  private def joinKeys(condition: ResolvedExpr): Option[(Int, Int)] =
    def equal(expression: ResolvedExpr): Option[(Int, Int)] =
      expression.node match
        case ExprNode.Binary(
              BinaryOperator.Equal,
              ResolvedExpr(
                _,
                DataType.Int32,
                _,
                ExprNode.Column(InputRef.Left, _, _, _, left)
              ),
              ResolvedExpr(
                _,
                DataType.Int32,
                _,
                ExprNode.Column(InputRef.Right, _, _, _, right)
              )
            ) =>
          Some(left -> right)
        case ExprNode.Binary(
              BinaryOperator.Equal,
              ResolvedExpr(
                _,
                DataType.Int32,
                _,
                ExprNode.Column(InputRef.Right, _, _, _, right)
              ),
              ResolvedExpr(
                _,
                DataType.Int32,
                _,
                ExprNode.Column(InputRef.Left, _, _, _, left)
              )
            ) =>
          Some(left -> right)
        case _ => None

    condition.node match
      case ExprNode.Unary(UnaryOperator.IsTrue, input) => equal(input)
      case _                                           => equal(condition)

  private def sequence[A](values: Vector[Option[A]]): Option[Vector[A]] =
    if values.forall(_.isDefined) then Some(values.flatten) else None

final private case class DirectProjection(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    indices: Vector[Int]
) extends KernelPlan:
  val name = "ScanProject[plain-int32-or-float64]"

  def execute(sources: ReferenceSources): KernelAttempt =
    sources.openProjected(reference, inputSchema, indices) match
      case Left(error)   => KernelAttempt.Completed(Left(error))
      case Right(cursor) =>
        val output = ArrayBuffer.empty[ColumnarBatch]
        var done = false
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        try
          while !done && error.isEmpty && residual.isEmpty do
            cursor.nextBatch() match
              case Right(None)        => done = true
              case Left(value)        => error = Some(value)
              case Right(Some(batch)) =>
                val columns = Vector.newBuilder[ColumnarVector]
                var index = 0
                while index < indices.length && error.isEmpty && residual.isEmpty do
                  batch.columns.lift(index) match
                    case None =>
                      error = Some(
                        ExecutionError.InvalidColumnIndex(
                          ExprId.derived(s"columnar-project:${indices(index)}"),
                          InputRef.Current.qualifier,
                          index,
                          batch.columns.length
                        )
                      )
                    case Some(column) =>
                      ColumnarVector.copy(column) match
                        case Right(value) => columns += value
                        case Left(value)  => residual = Some(value)
                  index += 1
                if error.isEmpty && residual.isEmpty then
                  output += ColumnarBatch(columns.result(), batch.rowCount)
                batch.close()
        finally cursor.close()
        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              error.toLeft(ColumnarResult(outputSchema, order, output.toVector))
            )

final private case class Int32Filter(
    index: Int,
    operator: BinaryOperator,
    literal: Int
):
  def keep(value: Int): Boolean = operator match
    case BinaryOperator.GreaterThan        => value > literal
    case BinaryOperator.GreaterThanOrEqual => value >= literal
    case BinaryOperator.LessThan           => value < literal
    case BinaryOperator.LessThanOrEqual    => value <= literal
    case _                                 => false

final private case class FilterInt32(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    filter: Int32Filter
) extends KernelPlan:
  val name = "FilterSelection[checked-int32]"

  def execute(sources: ReferenceSources): KernelAttempt =
    val indices = Vector.range(0, inputSchema.size)
    sources.openProjected(reference, inputSchema, indices) match
      case Left(error)   => KernelAttempt.Completed(Left(error))
      case Right(cursor) =>
        val output = ArrayBuffer.empty[ColumnarBatch]
        var done = false
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        try
          while !done && error.isEmpty && residual.isEmpty do
            cursor.nextBatch() match
              case Right(None)        => done = true
              case Left(value)        => error = Some(value)
              case Right(Some(batch)) =>
                val decoded = new Array[ColumnarVector](batch.columns.length)
                var column = 0
                while column < batch.columns.length && residual.isEmpty do
                  ColumnarVector.copy(batch.columns(column)) match
                    case Right(value) => decoded(column) = value
                    case Left(value)  => residual = Some(value)
                  column += 1
                if residual.isEmpty then
                  decoded(filter.index) match
                    case predicate: RawInt32Vector if predicate.required =>
                      val selected = new Array[Int](batch.rowCount)
                      var selectedCount = 0
                      var row = 0
                      while row < batch.rowCount do
                        if filter.keep(predicate.unsafeIntValue(row)) then
                          selected(selectedCount) = row
                          selectedCount += 1
                        row += 1
                      val compact = java.util.Arrays.copyOf(selected, selectedCount)
                      output += ColumnarBatch(
                        decoded.toVector.map(SelectedVector(_, compact)),
                        selectedCount
                      )
                    case _: RawInt32Vector =>
                      residual = Some(s"filter column ${filter.index} became nullable")
                    case _ =>
                      residual = Some(s"filter column ${filter.index} is not Int32")
                batch.close()
        finally cursor.close()
        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              error.toLeft(ColumnarResult(outputSchema, order, output.toVector))
            )

private enum AggregateSpec:
  case Count
  case Sum(index: Int)
  case Mean(index: Int)
  case VariancePop(index: Int)

  def inputIndex: Option[Int] = this match
    case Count              => None
    case Sum(index)         => Some(index)
    case Mean(index)        => Some(index)
    case VariancePop(index) => Some(index)

final private case class AggregateKey(
    index: Int,
    dataType: DataType,
    nullable: Boolean
)

private enum StoredGroupKey:
  case Null
  case Int32(value: Int)
  case Utf8(value: Array[Byte])

  def scalar: ScalarValue = this match
    case Null         => ScalarValue.Null
    case Int32(value) => ScalarValue.Int32(value)
    case Utf8(value)  => ScalarValue.Utf8(new String(value, "UTF-8"))

/** Allocation-free-per-row composite grouping index.
  *
  * Key material is copied only when a new group is created. Probes compare primitive values or
  * borrowed UTF-8 bytes against those retained group keys, so increasing key arity does not create
  * a `Vector[ScalarValue]` for every input row.
  */
final private class CompositeGroupIndex(keys: Vector[AggregateKey]):
  private var hashes = new Array[Int](32)
  private var groups = Array.fill(32)(-1)
  private var retained = new Array[Array[StoredGroupKey]](32)
  private var size = 0

  def groupCount: Int = size

  def findOrPut(vectors: Array[ColumnarVector], row: Int): Int =
    if (size + 1) * 2 > groups.length then grow()
    val hash = rowHash(vectors, row)
    var slot = hash & (groups.length - 1)
    while groups(slot) >= 0 &&
      (hashes(slot) != hash || !matches(retained(groups(slot)), vectors, row))
    do slot = (slot + 1) & (groups.length - 1)
    val found = groups(slot)
    if found >= 0 then found
    else
      ensureRetainedCapacity()
      val created = size
      retained(created) = copyKey(vectors, row)
      hashes(slot) = hash
      groups(slot) = created
      size += 1
      created

  def scalar(group: Int, key: Int): ScalarValue =
    retained(group)(key).scalar

  private def rowHash(vectors: Array[ColumnarVector], row: Int): Int =
    var hash = 1
    var key = 0
    while key < keys.length do
      val component = (keys(key).dataType, vectors(key)) match
        case (DataType.Int32, values: RawInt32Vector) =>
          if values.unsafeValid(row) then mix(values.unsafeIntValue(row))
          else 0x61c88647
        case (DataType.Utf8, values: RawUtf8Vector) =>
          if values.unsafeValid(row) then values.unsafeByteHash(row)
          else 0x61c88647
        case _ => 0
      hash = hash * 31 + component
      key += 1
    mix(hash)

  private def matches(
      expected: Array[StoredGroupKey],
      vectors: Array[ColumnarVector],
      row: Int
  ): Boolean =
    var key = 0
    var equal = true
    while key < keys.length && equal do
      equal = (expected(key), vectors(key)) match
        case (StoredGroupKey.Null, values: RawInt32Vector) =>
          !values.unsafeValid(row)
        case (StoredGroupKey.Int32(value), values: RawInt32Vector) =>
          values.unsafeValid(row) && values.unsafeIntValue(row) == value
        case (StoredGroupKey.Null, values: RawUtf8Vector) =>
          !values.unsafeValid(row)
        case (StoredGroupKey.Utf8(value), values: RawUtf8Vector) =>
          values.unsafeValid(row) && values.unsafeBytesEqual(row, value)
        case _ => false
      key += 1
    equal

  private def copyKey(
      vectors: Array[ColumnarVector],
      row: Int
  ): Array[StoredGroupKey] =
    val copied = new Array[StoredGroupKey](keys.length)
    var key = 0
    while key < keys.length do
      copied(key) = vectors(key) match
        case values: RawInt32Vector =>
          if values.unsafeValid(row) then StoredGroupKey.Int32(values.unsafeIntValue(row))
          else StoredGroupKey.Null
        case values: RawUtf8Vector =>
          if values.unsafeValid(row) then StoredGroupKey.Utf8(values.unsafeCopyBytes(row))
          else StoredGroupKey.Null
        case _ => StoredGroupKey.Null
      key += 1
    copied

  private def ensureRetainedCapacity(): Unit =
    if size == retained.length then retained = java.util.Arrays.copyOf(retained, size * 2)

  private def grow(): Unit =
    val oldHashes = hashes
    val oldGroups = groups
    hashes = new Array[Int](oldHashes.length * 2)
    groups = Array.fill(oldGroups.length * 2)(-1)
    var oldSlot = 0
    while oldSlot < oldGroups.length do
      if oldGroups(oldSlot) >= 0 then
        var slot = oldHashes(oldSlot) & (groups.length - 1)
        while groups(slot) >= 0 do slot = (slot + 1) & (groups.length - 1)
        hashes(slot) = oldHashes(oldSlot)
        groups(slot) = oldGroups(oldSlot)
      oldSlot += 1

  private def mix(value: Int): Int =
    var hash = value
    hash ^= hash >>> 16
    hash *= 0x7feb352d
    hash ^= hash >>> 15
    hash *= 0x846ca68b
    hash ^ (hash >>> 16)

final private class MultiMeasureState(requirements: AggregateRequirements):
  private var numericCounts = new Array[Long](32)
  private var sums =
    if requirements.sum then new Array[Double](32) else Array.emptyDoubleArray
  private var meanTotals =
    if requirements.mean then new Array[Double](32) else Array.emptyDoubleArray
  private var means =
    if requirements.variance then new Array[Double](32) else Array.emptyDoubleArray
  private var m2 =
    if requirements.variance then new Array[Double](32) else Array.emptyDoubleArray

  def ensureCapacity(required: Int): Unit =
    if required > numericCounts.length then
      var next = numericCounts.length * 2
      while next < required do next *= 2
      numericCounts = java.util.Arrays.copyOf(numericCounts, next)
      if requirements.sum then sums = java.util.Arrays.copyOf(sums, next)
      if requirements.mean then meanTotals = java.util.Arrays.copyOf(meanTotals, next)
      if requirements.variance then
        means = java.util.Arrays.copyOf(means, next)
        m2 = java.util.Arrays.copyOf(m2, next)

  def add(group: Int, value: Double): Unit =
    val first = numericCounts(group) == 0L
    numericCounts(group) += 1L
    if requirements.sum then sums(group) = if first then value else sums(group) + value
    if requirements.mean then meanTotals(group) += value
    if requirements.variance then
      val delta = value - means(group)
      means(group) += delta / numericCounts(group).toDouble
      val delta2 = value - means(group)
      m2(group) += delta * delta2

  def result(spec: AggregateSpec, size: Int): ColumnarVector =
    val valid = new Array[Boolean](size)
    var group = 0
    while group < size do
      valid(group) = numericCounts(group) > 0L
      group += 1
    spec match
      case AggregateSpec.Sum(_) =>
        Float64Values(java.util.Arrays.copyOf(sums, size), valid)
      case AggregateSpec.Mean(_) =>
        val output = new Array[Double](size)
        group = 0
        while group < size do
          output(group) = meanTotals(group) / numericCounts(group).toDouble
          group += 1
        Float64Values(output, valid)
      case AggregateSpec.VariancePop(_) =>
        val output = new Array[Double](size)
        group = 0
        while group < size do
          output(group) = m2(group) / numericCounts(group).toDouble
          group += 1
        Float64Values(output, valid)
      case AggregateSpec.Count =>
        Int64Values(Array.fill(size)(0L))

/** General grouped reduction for arbitrary Int32/UTF-8 key vectors and Float64 measure vectors.
  *
  * Specialized one-key kernels remain available for their narrower fast paths. This kernel owns the
  * structural contract: any key arity, nullable keys, any number of measure columns, projected
  * source lineage, repeated aggregate use of one measure, and stable first-group order.
  */
final private case class GeneralHashAggregate(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    keys: Vector[AggregateKey],
    aggregates: Vector[AggregateSpec]
) extends KernelPlan:
  private val measureIndices = aggregates.flatMap(_.inputIndex).distinct
  val name = s"HashAggregate[General,keys=${keys.length},measures=${measureIndices.length}]"
  private val needsCount = aggregates.contains(AggregateSpec.Count)
  private val requirements = measureIndices.map: index =>
    AggregateRequirements.from(aggregates.filter(_.inputIndex.contains(index)))

  def execute(sources: ReferenceSources): KernelAttempt =
    val required = (keys.map(_.index) ++ measureIndices).distinct
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val groupIndex = new CompositeGroupIndex(keys)
        val states = requirements.map(new MultiMeasureState(_)).toArray
        var rowCounts = if needsCount then new Array[Long](32) else Array.emptyLongArray
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        var batchIndex = 0
        while batchIndex < batches.length && error.isEmpty && residual.isEmpty do
          val batch = batches(batchIndex)
          val borrowed =
            ColumnarVector.withBorrowedColumns(batch, required): decoded =>
              val keyVectors = new Array[ColumnarVector](keys.length)
              var key = 0
              while key < keys.length && residual.isEmpty do
                val vector = decoded(required.indexOf(keys(key).index))
                val compatible = (keys(key).dataType, vector) match
                  case (DataType.Int32, _: RawInt32Vector) => true
                  case (DataType.Utf8, _: RawUtf8Vector)   => true
                  case _                                   => false
                if compatible then keyVectors(key) = vector
                else
                  residual = Some(
                    s"aggregate key ${keys(key).index} is not plain ${keys(key).dataType}"
                  )
                key += 1

              val measureVectors = new Array[RawFloat64Vector](measureIndices.length)
              var measure = 0
              while measure < measureIndices.length && residual.isEmpty do
                decoded(required.indexOf(measureIndices(measure))) match
                  case value: RawFloat64Vector => measureVectors(measure) = value
                  case _                       =>
                    residual = Some(
                      s"aggregate input ${measureIndices(measure)} is not plain Float64"
                    )
                measure += 1

              if residual.isEmpty then
                var row = 0
                while row < batch.rowCount do
                  val group = groupIndex.findOrPut(keyVectors, row)
                  val requiredSize = group + 1
                  if needsCount && requiredSize > rowCounts.length then
                    var next = rowCounts.length * 2
                    while next < requiredSize do next *= 2
                    rowCounts = java.util.Arrays.copyOf(rowCounts, next)
                  if needsCount then rowCounts(group) += 1L
                  measure = 0
                  while measure < measureVectors.length do
                    val values = measureVectors(measure)
                    states(measure).ensureCapacity(requiredSize)
                    if values.unsafeValid(row) then
                      states(measure).add(group, values.unsafeDoubleValue(row))
                    measure += 1
                  row += 1
          borrowed match
            case Left(ColumnBorrowError.Storage(value)) =>
              error = Some(ExecutionError.Storage(value))
            case Left(ColumnBorrowError.Residual(reason)) =>
              residual = Some(reason)
            case Right(()) => ()
          batchIndex += 1

        error match
          case Some(value) => KernelAttempt.Completed(Left(value))
          case None        =>
            residual match
              case Some(reason) => KernelAttempt.Residual(reason)
              case None         =>
                val size = groupIndex.groupCount
                val columns = Vector.newBuilder[ColumnarVector]
                var key = 0
                while key < keys.length do
                  columns += ScalarVector(
                    Array.tabulate(size)(group => groupIndex.scalar(group, key))
                  )
                  key += 1
                aggregates.foreach:
                  case AggregateSpec.Count =>
                    columns += Int64Values(java.util.Arrays.copyOf(rowCounts, size))
                  case spec @ AggregateSpec.Sum(inputIndex) =>
                    val measure = measureIndices.indexOf(inputIndex)
                    columns += states(measure).result(spec, size)
                  case spec @ AggregateSpec.Mean(inputIndex) =>
                    val measure = measureIndices.indexOf(inputIndex)
                    columns += states(measure).result(spec, size)
                  case spec @ AggregateSpec.VariancePop(inputIndex) =>
                    val measure = measureIndices.indexOf(inputIndex)
                    columns += states(measure).result(spec, size)
                KernelAttempt.Completed(
                  Right(
                    ColumnarResult(
                      outputSchema,
                      order,
                      Vector(ColumnarBatch(columns.result(), size))
                    )
                  )
                )

final private class AggregateGroup(val key: ScalarValue):
  private var needs: AggregateRequirements = AggregateRequirements.all
  var rows: Long = 0L
  var numericCount: Long = 0L
  var sum: Double = 0.0
  var meanTotal: Double = 0.0
  var mean: Double = 0.0
  var m2: Double = 0.0

  def configure(requirements: AggregateRequirements): this.type =
    needs = requirements
    this

  def addNull(): Unit =
    if needs.count then rows += 1L

  def addValue(actual: Double): Unit =
    if needs.count then rows += 1L
    if needs.numeric then
      val first = numericCount == 0L
      numericCount += 1L
      if needs.sum then sum = if first then actual else sum + actual
      if needs.mean then meanTotal += actual
      if needs.variance then
        val delta = actual - mean
        mean += delta / numericCount.toDouble
        val delta2 = actual - mean
        m2 += delta * delta2

final private case class AggregateRequirements(
    count: Boolean,
    numeric: Boolean,
    sum: Boolean,
    mean: Boolean,
    variance: Boolean
)

private object AggregateRequirements:
  val all: AggregateRequirements =
    AggregateRequirements(count = true, numeric = true, sum = true, mean = true, variance = true)

  def from(aggregates: Vector[AggregateSpec]): AggregateRequirements =
    val count = aggregates.contains(AggregateSpec.Count)
    val numeric = aggregates.exists(_.inputIndex.nonEmpty)
    val sum = aggregates.exists:
      case AggregateSpec.Sum(_) => true
      case _                    => false
    val mean = aggregates.exists:
      case AggregateSpec.Mean(_) => true
      case _                     => false
    val variance = aggregates.exists:
      case AggregateSpec.VariancePop(_) => true
      case _                            => false
    AggregateRequirements(count, numeric, sum, mean, variance)

final private class IntGroupIndex:
  private var keys = new Array[Int](32)
  private var groups = Array.fill(32)(-1)
  private var size = 0

  def find(key: Int): Int =
    var slot = mix(key) & (groups.length - 1)
    while groups(slot) >= 0 && keys(slot) != key do slot = (slot + 1) & (groups.length - 1)
    groups(slot)

  def put(key: Int, group: Int): Unit =
    if (size + 1) * 2 >= groups.length then grow()
    var slot = mix(key) & (groups.length - 1)
    while groups(slot) >= 0 do slot = (slot + 1) & (groups.length - 1)
    keys(slot) = key
    groups(slot) = group
    size += 1

  private def grow(): Unit =
    val oldKeys = keys
    val oldGroups = groups
    keys = new Array[Int](oldKeys.length * 2)
    groups = Array.fill(oldGroups.length * 2)(-1)
    var index = 0
    while index < oldGroups.length do
      if oldGroups(index) >= 0 then
        var slot = mix(oldKeys(index)) & (groups.length - 1)
        while groups(slot) >= 0 do slot = (slot + 1) & (groups.length - 1)
        keys(slot) = oldKeys(index)
        groups(slot) = oldGroups(index)
      index += 1

  private def mix(value: Int): Int =
    var hash = value
    hash ^= hash >>> 16
    hash *= 0x7feb352d
    hash ^= hash >>> 15
    hash *= 0x846ca68b
    hash ^ (hash >>> 16)

final private class Utf8GroupIndex:
  private var hashes = new Array[Int](32)
  private var packedKeys = new Array[Long](32)
  private var packedSlots = new Array[Boolean](32)
  private var groups = Array.fill(32)(-1)
  private val keys = ArrayBuffer.empty[Array[Byte]]
  private var size = 0

  def find(column: RawUtf8Vector, row: Int): Int =
    val packed = column.unsafePackedKey(row)
    if packed != Long.MinValue then
      var slot = mixPacked(packed) & (groups.length - 1)
      while groups(slot) >= 0 && (!packedSlots(slot) || packedKeys(slot) != packed) do
        slot = (slot + 1) & (groups.length - 1)
      groups(slot)
    else
      val hash = column.unsafeByteHash(row)
      var slot = hash & (groups.length - 1)
      while groups(slot) >= 0 &&
        (packedSlots(slot) || hashes(slot) != hash ||
          !column.unsafeBytesEqual(row, keys(groups(slot))))
      do slot = (slot + 1) & (groups.length - 1)
      groups(slot)

  def put(column: RawUtf8Vector, row: Int, group: Int): Array[Byte] =
    if (size + 1) * 2 > groups.length then grow()
    val packed = column.unsafePackedKey(row)
    val hash = if packed == Long.MinValue then column.unsafeByteHash(row) else 0
    var slot =
      if packed == Long.MinValue then hash & (groups.length - 1)
      else mixPacked(packed) & (groups.length - 1)
    while groups(slot) >= 0 do slot = (slot + 1) & (groups.length - 1)
    val key = column.unsafeCopyBytes(row)
    hashes(slot) = hash
    packedKeys(slot) = packed
    packedSlots(slot) = packed != Long.MinValue
    groups(slot) = group
    keys += key
    size += 1
    key

  private def grow(): Unit =
    val oldHashes = hashes
    val oldPackedKeys = packedKeys
    val oldPackedSlots = packedSlots
    val oldGroups = groups
    hashes = new Array[Int](oldHashes.length * 2)
    packedKeys = new Array[Long](oldPackedKeys.length * 2)
    packedSlots = new Array[Boolean](oldPackedSlots.length * 2)
    groups = Array.fill(oldGroups.length * 2)(-1)
    var index = 0
    while index < oldGroups.length do
      if oldGroups(index) >= 0 then
        var slot =
          if oldPackedSlots(index) then mixPacked(oldPackedKeys(index)) & (groups.length - 1)
          else oldHashes(index) & (groups.length - 1)
        while groups(slot) >= 0 do slot = (slot + 1) & (groups.length - 1)
        hashes(slot) = oldHashes(index)
        packedKeys(slot) = oldPackedKeys(index)
        packedSlots(slot) = oldPackedSlots(index)
        groups(slot) = oldGroups(index)
      index += 1

  private def mixPacked(value: Long): Int =
    var hash = (value ^ (value >>> 32)).toInt
    hash ^= hash >>> 16
    hash *= 0x7feb352d
    hash ^ (hash >>> 15)

final private case class Utf8SumAggregate(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    keyIndex: Int,
    valueIndex: Int
) extends KernelPlan:
  val name = "HashAggregate[Utf8,SumOnly]"

  def execute(sources: ReferenceSources): KernelAttempt =
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val accumulator = new Utf8SumAccumulator
        var residual: Option[String] = None
        var batchIndex = 0
        while batchIndex < batches.length && residual.isEmpty do
          val batch = batches(batchIndex)
          (batch.columns(keyIndex), batch.columns(valueIndex)) match
            case (key: Utf8Array, values: Float64Array) if key.nullCount == 0 =>
              key
                .withBorrowedUtf8Bytes:
                  (offsets, offsetsStart, keyValues, keyValuesStart, keyOffset, keyLength) =>
                    values.withBorrowedValueBytes:
                      (numericValues, numericStart, validity, numericOffset, numericLength) =>
                        val keyVector = RawUtf8Vector(
                          offsets,
                          keyValues,
                          None,
                          keyOffset,
                          keyLength,
                          offsetsStart,
                          keyValuesStart
                        )
                        val valueVector = RawFloat64Vector(
                          numericValues,
                          validity,
                          numericOffset,
                          numericLength,
                          numericStart
                        )
                        accumulator.addBatch(keyVector, valueVector, batch.rowCount)
                .flatMap(identity) match
                case Left(error) => residual = Some(error.message)
                case Right(())   => ()
            case (key: Utf8Array, _: Float64Array) if key.nullCount > 0 =>
              residual = Some("aggregate UTF-8 key became nullable")
            case (_: Utf8Array, _) =>
              residual = Some("aggregate input is not plain Float64")
            case _ =>
              residual = Some("aggregate UTF-8 key is not plain UTF-8")
          batchIndex += 1

        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              Right(accumulator.result(outputSchema, order))
            )

final private class Utf8SumAccumulator:
  private val index = new Utf8SumIndex
  private var keys = new Array[Array[Byte]](32)
  private var sums = new Array[Double](32)
  private var counts = new Array[Long](32)
  private var size = 0

  def addBatch(
      key: RawUtf8Vector,
      values: RawFloat64Vector,
      rowCount: Int
  ): Unit =
    var row = 0
    while row < rowCount do
      val group = index.findOrPut(key, row, size)
      if group == size then
        val _ = append(key, row)
      if values.unsafeValid(row) then
        val actual = values.unsafeDoubleValue(row)
        sums(group) = if counts(group) == 0L then actual else sums(group) + actual
        counts(group) += 1L
      row += 1

  private def append(key: RawUtf8Vector, row: Int): Int =
    if size == keys.length then
      keys = java.util.Arrays.copyOf(keys, size * 2)
      sums = java.util.Arrays.copyOf(sums, size * 2)
      counts = java.util.Arrays.copyOf(counts, size * 2)
    val created = size
    keys(created) = key.unsafeCopyBytes(row)
    size += 1
    created

  def result(schema: Schema, order: OrderGuarantee): ColumnarResult =
    val outputKeys = new Array[String](size)
    val outputValid = new Array[Boolean](size)
    var group = 0
    while group < size do
      outputKeys(group) = new String(keys(group), "UTF-8")
      outputValid(group) = counts(group) > 0L
      group += 1
    ColumnarResult(
      schema,
      order,
      Vector(
        ColumnarBatch(
          Vector(
            Utf8Values(outputKeys),
            Float64Values(java.util.Arrays.copyOf(sums, size), outputValid)
          ),
          size
        )
      )
    )

final private class Utf8SumIndex:
  private var packedKeys = new Array[Long](32)
  private var groups = Array.fill(32)(-1)
  private var longKeys: scala.collection.mutable.HashMap[String, Int] | Null = null

  def findOrPut(column: RawUtf8Vector, row: Int, newGroup: Int): Int =
    val packed = column.unsafePackedKey(row)
    if packed == Long.MinValue then
      val key = column.unsafeStringValue(row)
      val index =
        if longKeys == null then
          val created = scala.collection.mutable.HashMap.empty[String, Int]
          longKeys = created
          created
        else longKeys.nn
      index.getOrElseUpdate(key, newGroup)
    else
      if (newGroup + 1) * 2 > groups.length then grow()
      var slot = mix(packed) & (groups.length - 1)
      while groups(slot) >= 0 && packedKeys(slot) != packed do
        slot = (slot + 1) & (groups.length - 1)
      val found = groups(slot)
      if found >= 0 then found
      else
        packedKeys(slot) = packed
        groups(slot) = newGroup
        newGroup

  private def grow(): Unit =
    val oldKeys = packedKeys
    val oldGroups = groups
    packedKeys = new Array[Long](oldKeys.length * 2)
    groups = Array.fill(oldGroups.length * 2)(-1)
    var index = 0
    while index < oldGroups.length do
      if oldGroups(index) >= 0 then
        var slot = mix(oldKeys(index)) & (groups.length - 1)
        while groups(slot) >= 0 do slot = (slot + 1) & (groups.length - 1)
        packedKeys(slot) = oldKeys(index)
        groups(slot) = oldGroups(index)
      index += 1

  private def mix(value: Long): Int =
    var hash = (value ^ (value >>> 32)).toInt
    hash ^= hash >>> 16
    hash *= 0x7feb352d
    hash ^ (hash >>> 15)

final private case class Utf8PrimitiveAggregate(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    keyIndex: Int,
    aggregates: Vector[AggregateSpec]
) extends KernelPlan:
  val name = "HashAggregate[Utf8,Primitive]"
  private val requirements = AggregateRequirements.from(aggregates)

  def execute(sources: ReferenceSources): KernelAttempt =
    val valueIndex = aggregates.flatMap(_.inputIndex).headOption
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val accumulator = new Utf8AggregateAccumulator(requirements)
        var batchIndex = 0
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        while batchIndex < batches.length && error.isEmpty && residual.isEmpty do
          val batch = batches(batchIndex)
          batch.columns(keyIndex) match
            case key: Utf8Array if key.nullCount == 0 =>
              val added =
                valueIndex match
                  case None =>
                    addCountBatch(key, accumulator)
                  case Some(index) =>
                    batch.columns(index) match
                      case values: Float64Array =>
                        addNumericBatch(key, values, accumulator)
                      case _ =>
                        residual = Some("aggregate input is not plain Float64")
                        Right(())
              added match
                case Left(value) => error = Some(ExecutionError.Storage(value))
                case Right(())   => ()
            case _: Utf8Array =>
              residual = Some("aggregate UTF-8 key became nullable")
            case _ =>
              residual = Some("aggregate UTF-8 key is not plain UTF-8")
          batchIndex += 1

        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              error.toLeft(accumulator.result(outputSchema, order, aggregates))
            )

  private def addCountBatch(
      key: Utf8Array,
      accumulator: Utf8AggregateAccumulator
  ): Either[StorageError, Unit] =
    key.withBorrowedUtf8Bytes:
      (offsets, offsetsStart, keyValues, keyValuesStart, keyOffset, keyLength) =>
        accumulator.addBatch(
          RawUtf8Vector(
            offsets,
            keyValues,
            None,
            keyOffset,
            keyLength,
            offsetsStart,
            keyValuesStart
          ),
          None,
          keyLength
        )

  private def addNumericBatch(
      key: Utf8Array,
      values: Float64Array,
      accumulator: Utf8AggregateAccumulator
  ): Either[StorageError, Unit] =
    key
      .withBorrowedUtf8Bytes:
        (offsets, offsetsStart, keyValues, keyValuesStart, keyOffset, keyLength) =>
          values.withBorrowedValueBytes:
            (numericValues, numericStart, validity, numericOffset, numericLength) =>
              accumulator.addBatch(
                RawUtf8Vector(
                  offsets,
                  keyValues,
                  None,
                  keyOffset,
                  keyLength,
                  offsetsStart,
                  keyValuesStart
                ),
                Some(
                  RawFloat64Vector(
                    numericValues,
                    validity,
                    numericOffset,
                    numericLength,
                    numericStart
                  )
                ),
                keyLength
              )
      .flatMap(identity)

final private class Utf8AggregateAccumulator(requirements: AggregateRequirements):
  private val index = new Utf8SumIndex
  private var keys = new Array[Array[Byte]](32)
  private var rows =
    if requirements.count then new Array[Long](32) else new Array[Long](0)
  private var numericCounts =
    if requirements.numeric then new Array[Long](32) else new Array[Long](0)
  private var sums =
    if requirements.sum then new Array[Double](32) else new Array[Double](0)
  private var meanTotals =
    if requirements.mean then new Array[Double](32) else new Array[Double](0)
  private var means =
    if requirements.variance then new Array[Double](32) else new Array[Double](0)
  private var m2 =
    if requirements.variance then new Array[Double](32) else new Array[Double](0)
  private var size = 0

  def addBatch(
      key: RawUtf8Vector,
      values: Option[RawFloat64Vector],
      rowCount: Int
  ): Unit =
    var row = 0
    while row < rowCount do
      val group = index.findOrPut(key, row, size)
      if group == size then append(key, row)
      if requirements.count then rows(group) += 1L
      values match
        case Some(numeric) if numeric.unsafeValid(row) =>
          val first = numericCounts(group) == 0L
          numericCounts(group) += 1L
          val value = numeric.unsafeDoubleValue(row)
          if requirements.sum then sums(group) = if first then value else sums(group) + value
          if requirements.mean then meanTotals(group) += value
          if requirements.variance then
            val delta = value - means(group)
            means(group) += delta / numericCounts(group).toDouble
            val delta2 = value - means(group)
            m2(group) += delta * delta2
        case _ => ()
      row += 1

  private def append(key: RawUtf8Vector, row: Int): Unit =
    if size == keys.length then
      val next = size * 2
      keys = java.util.Arrays.copyOf(keys, next)
      if requirements.count then rows = java.util.Arrays.copyOf(rows, next)
      if requirements.numeric then numericCounts = java.util.Arrays.copyOf(numericCounts, next)
      if requirements.sum then sums = java.util.Arrays.copyOf(sums, next)
      if requirements.mean then meanTotals = java.util.Arrays.copyOf(meanTotals, next)
      if requirements.variance then
        means = java.util.Arrays.copyOf(means, next)
        m2 = java.util.Arrays.copyOf(m2, next)
    keys(size) = key.unsafeCopyBytes(row)
    size += 1

  def result(
      schema: Schema,
      order: OrderGuarantee,
      aggregates: Vector[AggregateSpec]
  ): ColumnarResult =
    val outputKeys = new Array[String](size)
    var group = 0
    while group < size do
      outputKeys(group) = new String(keys(group), "UTF-8")
      group += 1

    val valid =
      if requirements.numeric then
        val output = new Array[Boolean](size)
        group = 0
        while group < size do
          output(group) = numericCounts(group) > 0L
          group += 1
        output
      else Array.emptyBooleanArray

    val columns = Vector.newBuilder[ColumnarVector]
    columns += Utf8Values(outputKeys)
    aggregates.foreach:
      case AggregateSpec.Count =>
        columns += Int64Values(java.util.Arrays.copyOf(rows, size))
      case AggregateSpec.Sum(_) =>
        columns += Float64Values(java.util.Arrays.copyOf(sums, size), valid)
      case AggregateSpec.Mean(_) =>
        val output = new Array[Double](size)
        group = 0
        while group < size do
          output(group) = meanTotals(group) / numericCounts(group).toDouble
          group += 1
        columns += Float64Values(output, valid)
      case AggregateSpec.VariancePop(_) =>
        val output = new Array[Double](size)
        group = 0
        while group < size do
          output(group) = m2(group) / numericCounts(group).toDouble
          group += 1
        columns += Float64Values(output, valid)

    ColumnarResult(
      schema,
      order,
      Vector(ColumnarBatch(columns.result(), size))
    )

final private case class Int32HashAggregate(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    keyIndex: Int,
    aggregates: Vector[AggregateSpec]
) extends KernelPlan:
  val name = "HashAggregate[Int32,Primitive]"
  private val requirements = AggregateRequirements.from(aggregates)

  def execute(sources: ReferenceSources): KernelAttempt =
    val valueIndex = aggregates.flatMap(_.inputIndex).headOption
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val accumulator = new Int32AggregateAccumulator(requirements)
        var batchIndex = 0
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        while batchIndex < batches.length && error.isEmpty && residual.isEmpty do
          val batch = batches(batchIndex)
          batch.columns(keyIndex) match
            case key: Int32Array if key.nullCount == 0 =>
              val added =
                valueIndex match
                  case None =>
                    addCountBatch(key, accumulator)
                  case Some(index) =>
                    batch.columns(index) match
                      case values: Float64Array =>
                        addNumericBatch(key, values, accumulator)
                      case _ =>
                        residual = Some("aggregate input is not plain Float64")
                        Right(())
              added match
                case Left(value) => error = Some(ExecutionError.Storage(value))
                case Right(())   => ()
            case _: Int32Array =>
              residual = Some("aggregate Int32 key became nullable")
            case _ =>
              residual = Some("aggregate Int32 key is not plain Int32")
          batchIndex += 1

        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              error.toLeft(accumulator.result(outputSchema, order, aggregates))
            )

  private def addCountBatch(
      key: Int32Array,
      accumulator: Int32AggregateAccumulator
  ): Either[StorageError, Unit] =
    key.withBorrowedValueBytes: (keyBytes, keyStart, _, keyOffset, keyLength) =>
      var row = 0
      while row < keyLength do
        accumulator.add(
          readInt(keyBytes, keyStart + (keyOffset + row) * 4),
          valid = false,
          value = 0.0
        )
        row += 1

  private def addNumericBatch(
      key: Int32Array,
      values: Float64Array,
      accumulator: Int32AggregateAccumulator
  ): Either[StorageError, Unit] =
    key
      .withBorrowedValueBytes: (keyBytes, keyStart, _, keyOffset, keyLength) =>
        values.withBorrowedValueBytes: (valueBytes, valueStart, validity, valueOffset, _) =>
          var row = 0
          while row < keyLength do
            val valid = validity.forall(bit(_, valueOffset + row))
            accumulator.add(
              readInt(keyBytes, keyStart + (keyOffset + row) * 4),
              valid,
              java.lang.Double.longBitsToDouble(
                readLong(valueBytes, valueStart + (valueOffset + row) * 8)
              )
            )
            row += 1
      .flatMap(identity)

final private class Int32AggregateAccumulator(requirements: AggregateRequirements):
  private val groups = new IntGroupIndex
  private var keys = new Array[Int](32)
  private var rows =
    if requirements.count then new Array[Long](32) else new Array[Long](0)
  private var numericCounts =
    if requirements.numeric then new Array[Long](32) else new Array[Long](0)
  private var sums =
    if requirements.sum then new Array[Double](32) else new Array[Double](0)
  private var meanTotals =
    if requirements.mean then new Array[Double](32) else new Array[Double](0)
  private var means =
    if requirements.variance then new Array[Double](32) else new Array[Double](0)
  private var m2 =
    if requirements.variance then new Array[Double](32) else new Array[Double](0)
  private var size = 0

  def add(key: Int, valid: Boolean, value: Double): Unit =
    val found = groups.find(key)
    val group =
      if found >= 0 then found
      else
        ensureCapacity()
        val created = size
        keys(created) = key
        groups.put(key, created)
        size += 1
        created

    if requirements.count then rows(group) += 1L
    if requirements.numeric && valid then
      val first = numericCounts(group) == 0L
      numericCounts(group) += 1L
      if requirements.sum then sums(group) = if first then value else sums(group) + value
      if requirements.mean then meanTotals(group) += value
      if requirements.variance then
        val delta = value - means(group)
        means(group) += delta / numericCounts(group).toDouble
        val delta2 = value - means(group)
        m2(group) += delta * delta2

  private def ensureCapacity(): Unit =
    if size == keys.length then
      val next = size * 2
      keys = java.util.Arrays.copyOf(keys, next)
      if requirements.count then rows = java.util.Arrays.copyOf(rows, next)
      if requirements.numeric then numericCounts = java.util.Arrays.copyOf(numericCounts, next)
      if requirements.sum then sums = java.util.Arrays.copyOf(sums, next)
      if requirements.mean then meanTotals = java.util.Arrays.copyOf(meanTotals, next)
      if requirements.variance then
        means = java.util.Arrays.copyOf(means, next)
        m2 = java.util.Arrays.copyOf(m2, next)

  def result(
      schema: Schema,
      order: OrderGuarantee,
      aggregates: Vector[AggregateSpec]
  ): ColumnarResult =
    val columns = Vector.newBuilder[ColumnarVector]
    columns += Int32Values(java.util.Arrays.copyOf(keys, size))
    val valid =
      if requirements.numeric then
        val output = new Array[Boolean](size)
        var group = 0
        while group < size do
          output(group) = numericCounts(group) > 0L
          group += 1
        output
      else Array.emptyBooleanArray

    aggregates.foreach:
      case AggregateSpec.Count =>
        columns += Int64Values(java.util.Arrays.copyOf(rows, size))
      case AggregateSpec.Sum(_) =>
        columns += Float64Values(java.util.Arrays.copyOf(sums, size), valid)
      case AggregateSpec.Mean(_) =>
        val output = new Array[Double](size)
        var group = 0
        while group < size do
          output(group) = meanTotals(group) / numericCounts(group).toDouble
          group += 1
        columns += Float64Values(output, valid)
      case AggregateSpec.VariancePop(_) =>
        val output = new Array[Double](size)
        var group = 0
        while group < size do
          output(group) = m2(group) / numericCounts(group).toDouble
          group += 1
        columns += Float64Values(output, valid)

    ColumnarResult(
      schema,
      order,
      Vector(ColumnarBatch(columns.result(), size))
    )

final private case class HashAggregate(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    keyIndex: Int,
    keyType: DataType,
    aggregates: Vector[AggregateSpec]
) extends KernelPlan:
  val name = s"HashAggregate[$keyType]"
  private val requirements = AggregateRequirements.from(aggregates)

  def execute(sources: ReferenceSources): KernelAttempt =
    val valueIndex = aggregates.flatMap(_.inputIndex).headOption
    val required = (keyIndex +: valueIndex.toVector).distinct
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val groups = ArrayBuffer.empty[AggregateGroup]
        val intGroups = new IntGroupIndex
        val utf8Groups = new Utf8GroupIndex
        var residual: Option[String] = None
        var batchIndex = 0
        while batchIndex < batches.length && residual.isEmpty do
          val batch = batches(batchIndex)
          val decoded = new Array[ColumnarVector](required.length)
          var column = 0
          while column < required.length && residual.isEmpty do
            ColumnarVector.copy(batch.columns(required(column))) match
              case Right(value) => decoded(column) = value
              case Left(value)  => residual = Some(value)
            column += 1
          if residual.isEmpty then
            val keyPosition = required.indexOf(keyIndex)
            val valuePosition = valueIndex.map(required.indexOf)
            var numeric: Option[RawFloat64Vector] = None
            valuePosition.foreach: position =>
              decoded(position) match
                case value: RawFloat64Vector => numeric = Some(value)
                case _ => residual = Some("aggregate input is not plain Float64")
            if residual.isEmpty then
              keyType match
                case DataType.Int32 =>
                  decoded(keyPosition) match
                    case key: RawInt32Vector if key.required =>
                      var row = 0
                      while row < batch.rowCount do
                        val actual = key.unsafeIntValue(row)
                        val found = intGroups.find(actual)
                        val groupIndex =
                          if found >= 0 then found
                          else
                            val created = appendGroup(groups, ScalarValue.Int32(actual))
                            intGroups.put(actual, created)
                            created
                        add(groups(groupIndex), numeric, row)
                        row += 1
                    case _ =>
                      residual = Some("aggregate Int32 key is not required plain Int32")
                case DataType.Utf8 =>
                  decoded(keyPosition) match
                    case key: RawUtf8Vector if key.required =>
                      var row = 0
                      while row < batch.rowCount do
                        val found = utf8Groups.find(key, row)
                        val groupIndex =
                          if found >= 0 then found
                          else
                            val created = groups.length
                            val bytes = utf8Groups.put(key, row, created)
                            appendGroup(
                              groups,
                              ScalarValue.Utf8(new String(bytes, "UTF-8"))
                            )
                        add(groups(groupIndex), numeric, row)
                        row += 1
                    case _ =>
                      residual = Some("aggregate UTF-8 key is not required plain UTF-8")
                case other =>
                  residual = Some(s"unsupported aggregate key $other")
          batchIndex += 1

        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              Right(result(groups))
            )

  private def appendGroup(
      groups: ArrayBuffer[AggregateGroup],
      key: ScalarValue
  ): Int =
    val index = groups.length
    groups += new AggregateGroup(key).configure(requirements)
    index

  private def add(
      group: AggregateGroup,
      numeric: Option[RawFloat64Vector],
      row: Int
  ): Unit =
    numeric match
      case Some(values) if values.unsafeValid(row) =>
        group.addValue(values.unsafeDoubleValue(row))
      case _ =>
        group.addNull()

  private def result(groups: ArrayBuffer[AggregateGroup]): ColumnarResult =
    val columns = Vector.newBuilder[ColumnarVector]
    keyType match
      case DataType.Int32 =>
        columns += Int32Values(
          groups.map(_.key).collect { case ScalarValue.Int32(value) => value }.toArray
        )
      case DataType.Utf8 =>
        columns += Utf8Values(
          groups.map(_.key).collect { case ScalarValue.Utf8(value) => value }.toArray
        )
      case _ => ()
    aggregates.foreach:
      case AggregateSpec.Count =>
        columns += Int64Values(groups.map(_.rows).toArray)
      case AggregateSpec.Sum(_) =>
        columns += Float64Values(
          groups.map(_.sum).toArray,
          groups.map(_.numericCount > 0L).toArray
        )
      case AggregateSpec.Mean(_) =>
        columns += Float64Values(
          groups.map(group => group.meanTotal / group.numericCount.toDouble).toArray,
          groups.map(_.numericCount > 0L).toArray
        )
      case AggregateSpec.VariancePop(_) =>
        columns += Float64Values(
          groups.map(group => group.m2 / group.numericCount.toDouble).toArray,
          groups.map(_.numericCount > 0L).toArray
        )
    ColumnarResult(
      outputSchema,
      order,
      Vector(ColumnarBatch(columns.result(), groups.length))
    )

final private case class DecodedBatch(
    columns: Vector[ColumnarVector],
    rowCount: Int
)

final private class JoinIntIndex(expectedRows: Int):
  private val capacity =
    var value = 16
    while value < math.max(16, expectedRows * 2) do value *= 2
    value
  private val keys = new Array[Int](capacity)
  private val heads = Array.fill(capacity)(-1)
  private val tails = Array.fill(capacity)(-1)
  val next: Array[Int] = Array.fill(expectedRows)(-1)

  def add(key: Int, row: Int): Unit =
    var slot = mix(key) & (capacity - 1)
    while heads(slot) >= 0 && keys(slot) != key do slot = (slot + 1) & (capacity - 1)
    if heads(slot) < 0 then
      keys(slot) = key
      heads(slot) = row
      tails(slot) = row
    else
      next(tails(slot)) = row
      tails(slot) = row

  def first(key: Int): Int =
    var slot = mix(key) & (capacity - 1)
    while heads(slot) >= 0 && keys(slot) != key do slot = (slot + 1) & (capacity - 1)
    heads(slot)

  private def mix(value: Int): Int =
    var hash = value
    hash ^= hash >>> 16
    hash *= 0x7feb352d
    hash ^= hash >>> 15
    hash *= 0x846ca68b
    hash ^ (hash >>> 16)

final private case class HashJoin(
    leftReference: SourceRef,
    leftSchema: Schema,
    rightReference: SourceRef,
    rightSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    kind: JoinKind,
    leftKey: Int,
    rightKey: Int,
    columns: Vector[JoinColumn]
) extends KernelPlan:
  val name = s"HashJoin[$kind,Int32,SelectionGather]"

  def execute(sources: ReferenceSources): KernelAttempt =
    (decode(sources, leftReference, leftSchema), decode(sources, rightReference, rightSchema)) match
      case (Left(Left(error)), _)      => KernelAttempt.Completed(Left(error))
      case (_, Left(Left(error)))      => KernelAttempt.Completed(Left(error))
      case (Left(Right(reason)), _)    => KernelAttempt.Residual(reason)
      case (_, Left(Right(reason)))    => KernelAttempt.Residual(reason)
      case (Right(left), Right(right)) =>
        join(left, right) match
          case Left(error)  => KernelAttempt.Completed(Left(error))
          case Right(value) => KernelAttempt.Completed(Right(value))

  def decode(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema
  ): Either[Either[ExecutionError, String], Vector[DecodedBatch]] =
    sources.borrowedBatches(reference, schema) match
      case Left(error)    => Left(Left(error))
      case Right(batches) =>
        val output = Vector.newBuilder[DecodedBatch]
        var batchIndex = 0
        var residual: Option[String] = None
        while batchIndex < batches.length && residual.isEmpty do
          val batch = batches(batchIndex)
          val columns = Vector.newBuilder[ColumnarVector]
          var column = 0
          while column < batch.columns.length && residual.isEmpty do
            ColumnarVector.copy(batch.columns(column)) match
              case Right(value) => columns += value
              case Left(value)  => residual = Some(value)
            column += 1
          if residual.isEmpty then output += DecodedBatch(columns.result(), batch.rowCount)
          batchIndex += 1
        residual.toLeft(output.result()).left.map(Right.apply)

  private def join(
      left: Vector[DecodedBatch],
      right: Vector[DecodedBatch]
  ): Either[ExecutionError, ColumnarResult] =
    val rightRows = right.foldLeft(0)(_ + _.rowCount)
    val rightBatch = new Array[Int](rightRows)
    val rightRow = new Array[Int](rightRows)
    val index = new JoinIntIndex(rightRows)
    var global = 0
    var batchIndex = 0
    var error: Option[ExecutionError] = None
    while batchIndex < right.length && error.isEmpty do
      right(batchIndex).columns(rightKey) match
        case key: RawInt32Vector =>
          var row = 0
          while row < right(batchIndex).rowCount do
            rightBatch(global) = batchIndex
            rightRow(global) = row
            if key.unsafeValid(row) then index.add(key.unsafeIntValue(row), global)
            global += 1
            row += 1
        case _ =>
          error = Some(
            ExecutionError.UnsupportedNode("hash join right key is not plain Int32")
          )
      batchIndex += 1

    val selections =
      new JoinSelectionBuilder(RightSelectionMode.forJoin(columns, kind))
    batchIndex = 0
    while batchIndex < left.length && error.isEmpty do
      val leftBatch = left(batchIndex)
      leftBatch.columns(leftKey) match
        case key: RawInt32Vector =>
          var row = 0
          while row < leftBatch.rowCount && error.isEmpty do
            var candidate =
              if key.unsafeValid(row) then index.first(key.unsafeIntValue(row))
              else -1
            val existence = kind == JoinKind.LeftSemi || kind == JoinKind.LeftAnti
            val matched = candidate >= 0
            if existence then
              val emit =
                (kind == JoinKind.LeftSemi && matched) ||
                  (kind == JoinKind.LeftAnti && !matched)
              if emit then selections.appendLeftOnly(batchIndex, row)
            else
              while candidate >= 0 && error.isEmpty do
                selections.appendMatched(
                  batchIndex,
                  row,
                  rightBatch(candidate),
                  rightRow(candidate)
                )
                candidate = index.next(candidate)
            if !existence && !matched && kind == JoinKind.LeftOuter && error.isEmpty then
              selections.appendLeftOnly(batchIndex, row)
            row += 1
        case _ =>
          error = Some(
            ExecutionError.UnsupportedNode("hash join left key is not plain Int32")
          )
      batchIndex += 1

    materialize(left, right, selections, error)

  def materialize(
      left: Vector[DecodedBatch],
      right: Vector[DecodedBatch],
      selections: JoinSelectionBuilder,
      error: Option[ExecutionError]
  ): Either[ExecutionError, ColumnarResult] =
    error match
      case Some(value) => Left(value)
      case None        =>
        val selected = selections.result()
        val output = columns.map:
          case JoinColumn.Left(index) =>
            GatheredVector(
              left,
              index,
              selected.leftBatches,
              selected.leftRows,
              validity = None
            )
          case JoinColumn.Right(index) =>
            GatheredVector(
              right,
              index,
              selected.rightBatches,
              selected.rightRows,
              selected.rightValidity
            )
        Right(
          ColumnarResult(
            outputSchema,
            order,
            Vector(ColumnarBatch(output, selected.length))
          )
        )

final private case class JoinSelection(
    leftBatches: Array[Int],
    leftRows: Array[Int],
    rightBatches: Array[Int],
    rightRows: Array[Int],
    rightValidity: Option[Array[Byte]]
):
  val length: Int = leftRows.length

private enum RightSelectionMode:
  case Absent
  case Required
  case Nullable

private object RightSelectionMode:
  def forJoin(columns: Vector[JoinColumn], kind: JoinKind): RightSelectionMode =
    val hasRight = columns.exists:
      case JoinColumn.Right(_) => true
      case JoinColumn.Left(_)  => false
    if !hasRight then RightSelectionMode.Absent
    else
      kind match
        case JoinKind.Inner     => RightSelectionMode.Required
        case JoinKind.LeftOuter => RightSelectionMode.Nullable
        case JoinKind.LeftSemi  => RightSelectionMode.Nullable
        case JoinKind.LeftAnti  => RightSelectionMode.Nullable

final private class JoinSelectionBuilder(
    rightMode: RightSelectionMode
):
  private var leftBatches = new Array[Int](16)
  private var leftRows = new Array[Int](16)
  private var rightBatches =
    if rightMode != RightSelectionMode.Absent then new Array[Int](16)
    else Array.emptyIntArray
  private var rightRows =
    if rightMode != RightSelectionMode.Absent then new Array[Int](16)
    else Array.emptyIntArray
  private var rightValidity =
    if rightMode == RightSelectionMode.Nullable then new Array[Byte](2)
    else Array.emptyByteArray
  private var length = 0

  def appendLeftOnly(leftBatch: Int, leftRow: Int): Unit =
    ensureCapacity(length + 1)
    leftBatches(length) = leftBatch
    leftRows(length) = leftRow
    length += 1

  def appendMatched(
      leftBatch: Int,
      leftRow: Int,
      rightBatch: Int,
      rightRow: Int
  ): Unit =
    ensureCapacity(length + 1)
    leftBatches(length) = leftBatch
    leftRows(length) = leftRow
    if rightMode != RightSelectionMode.Absent then
      rightBatches(length) = rightBatch
      rightRows(length) = rightRow
      if rightValidity.nonEmpty then
        val byte = length >>> 3
        rightValidity(byte) = (rightValidity(byte).toInt | (1 << (length & 7))).toByte
    length += 1

  def result(): JoinSelection =
    new JoinSelection(
      java.util.Arrays.copyOf(leftBatches, length),
      java.util.Arrays.copyOf(leftRows, length),
      if rightMode != RightSelectionMode.Absent then java.util.Arrays.copyOf(rightBatches, length)
      else Array.emptyIntArray,
      if rightMode != RightSelectionMode.Absent then java.util.Arrays.copyOf(rightRows, length)
      else Array.emptyIntArray,
      if rightValidity.nonEmpty then
        Some(java.util.Arrays.copyOf(rightValidity, (length + 7) >>> 3))
      else None
    )

  private def ensureCapacity(required: Int): Unit =
    if required > leftRows.length then
      val next = math.max(required, leftRows.length * 2)
      leftBatches = java.util.Arrays.copyOf(leftBatches, next)
      leftRows = java.util.Arrays.copyOf(leftRows, next)
      if rightMode != RightSelectionMode.Absent then
        rightBatches = java.util.Arrays.copyOf(rightBatches, next)
        rightRows = java.util.Arrays.copyOf(rightRows, next)
      if rightValidity.nonEmpty then
        rightValidity = java.util.Arrays.copyOf(rightValidity, (next + 7) >>> 3)

final private class PreparedHashJoin private (
    join: HashJoin,
    private var right: Vector[DecodedBatch],
    private var rightBatch: Array[Int],
    private var rightRow: Array[Int],
    index: Int32SecondaryIndex
) extends KernelPlan:
  val name = s"Prepared${join.name}"

  def execute(sources: ReferenceSources): KernelAttempt =
    index
      .withView(
        sources,
        join.rightReference,
        join.rightSchema,
        join.rightKey
      ): view =>
        join.decode(sources, join.leftReference, join.leftSchema) match
          case Left(Left(error))   => KernelAttempt.Completed(Left(error))
          case Left(Right(reason)) => KernelAttempt.Residual(reason)
          case Right(left)         =>
            KernelAttempt.Completed(probe(left, view))
      .fold(
        error => KernelAttempt.Completed(Left(PreparedHashJoin.executionError(error))),
        identity
      )

  override def close(): Unit =
    index.close()
    right = Vector.empty
    rightBatch = Array.emptyIntArray
    rightRow = Array.emptyIntArray

  private def probe(
      left: Vector[DecodedBatch],
      view: Int32SecondaryIndex
  ): Either[ExecutionError, ColumnarResult] =
    val selections =
      new JoinSelectionBuilder(
        RightSelectionMode.forJoin(join.columns, join.kind)
      )
    var batchIndex = 0
    var error: Option[ExecutionError] = None
    while batchIndex < left.length && error.isEmpty do
      val leftBatch = left(batchIndex)
      leftBatch.columns(join.leftKey) match
        case key: RawInt32Vector =>
          var row = 0
          while row < leftBatch.rowCount && error.isEmpty do
            var candidate =
              if key.unsafeValid(row) then view.firstUnsafe(key.unsafeIntValue(row))
              else -1
            val existence =
              join.kind == JoinKind.LeftSemi || join.kind == JoinKind.LeftAnti
            val matched = candidate >= 0
            if existence then
              val emit =
                (join.kind == JoinKind.LeftSemi && matched) ||
                  (join.kind == JoinKind.LeftAnti && !matched)
              if emit then selections.appendLeftOnly(batchIndex, row)
            else
              while candidate >= 0 && error.isEmpty do
                val sourceOrdinal = view.rowUnsafe(candidate)
                selections.appendMatched(
                  batchIndex,
                  row,
                  rightBatch(sourceOrdinal),
                  rightRow(sourceOrdinal)
                )
                candidate = view.nextUnsafe(candidate)
            if !existence &&
              !matched &&
              join.kind == JoinKind.LeftOuter &&
              error.isEmpty
            then selections.appendLeftOnly(batchIndex, row)
            row += 1
        case _ =>
          error = Some(
            ExecutionError.UnsupportedNode(
              "prepared hash join left key is not plain Int32"
            )
          )
      batchIndex += 1
    join.materialize(left, right, selections, error)

private object PreparedHashJoin:
  def build(
      join: HashJoin,
      sources: ReferenceSources
  ): Either[SecondaryIndexError, PreparedHashJoin] =
    join.decode(sources, join.rightReference, join.rightSchema) match
      case Left(reason) => Left(preparationError(reason))
      case Right(right) =>
        Int32SecondaryIndex
          .build(
            sources,
            join.rightReference,
            join.rightSchema,
            join.rightKey
          )
          .map: index =>
            val rightBatch = new Array[Int](index.rowCount)
            val rightRow = new Array[Int](index.rowCount)
            var global = 0
            var batch = 0
            while batch < right.length do
              var row = 0
              while row < right(batch).rowCount do
                rightBatch(global) = batch
                rightRow(global) = row
                global += 1
                row += 1
              batch += 1
            new PreparedHashJoin(join, right, rightBatch, rightRow, index)

  def executionError(error: SecondaryIndexError): ExecutionError =
    error match
      case SecondaryIndexError.Closed =>
        ExecutionError.Storage(StorageError.SourceClosed)
      case SecondaryIndexError.Storage(value) =>
        ExecutionError.Storage(value)
      case SecondaryIndexError.SourceMismatch =>
        ExecutionError.UnsupportedNode(SecondaryIndexError.SourceMismatch.message)
      case value @ SecondaryIndexError.InvalidColumnIndex(_, _) =>
        ExecutionError.UnsupportedNode(value.message)
      case value @ SecondaryIndexError.UnsupportedColumn(_, _, _) =>
        ExecutionError.UnsupportedNode(value.message)
      case value @ SecondaryIndexError.UnsupportedKernel(_) =>
        ExecutionError.UnsupportedNode(value.message)
      case value @ SecondaryIndexError.UnsupportedLogicalShape(_) =>
        ExecutionError.UnsupportedNode(value.message)
      case value @ SecondaryIndexError.PreparationResidual(_) =>
        ExecutionError.UnsupportedNode(value.message)
      case value @ SecondaryIndexError.TooManyRows(_) =>
        ExecutionError.UnsupportedNode(value.message)
      case value @ SecondaryIndexError.InvalidRowOrdinal(_, _) =>
        ExecutionError.UnsupportedNode(value.message)
      case SecondaryIndexError.Execution(value) =>
        value
      case SecondaryIndexError.Source(value) =>
        value

  private def preparationError(
      error: Either[ExecutionError, String]
  ): SecondaryIndexError =
    error match
      case Left(ExecutionError.Storage(value)) =>
        SecondaryIndexError.Storage(value)
      case Left(value) =>
        SecondaryIndexError.Execution(value)
      case Right(reason) =>
        SecondaryIndexError.PreparationResidual(reason)

final private case class StreamingUnionAll(
    leftReference: SourceRef,
    leftSchema: Schema,
    rightReference: SourceRef,
    rightSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee
) extends KernelPlan:
  val name = "StreamingUnionAll"

  def execute(sources: ReferenceSources): KernelAttempt =
    (copy(sources, leftReference, leftSchema), copy(sources, rightReference, rightSchema)) match
      case (Left(Left(error)), _)      => KernelAttempt.Completed(Left(error))
      case (_, Left(Left(error)))      => KernelAttempt.Completed(Left(error))
      case (Left(Right(reason)), _)    => KernelAttempt.Residual(reason)
      case (_, Left(Right(reason)))    => KernelAttempt.Residual(reason)
      case (Right(left), Right(right)) =>
        KernelAttempt.Completed(
          Right(ColumnarResult(outputSchema, order, left ++ right))
        )

  private def copy(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema
  ): Either[Either[ExecutionError, String], Vector[ColumnarBatch]] =
    sources.borrowedBatches(reference, schema) match
      case Left(error)    => Left(Left(error))
      case Right(batches) =>
        val output = Vector.newBuilder[ColumnarBatch]
        var batchIndex = 0
        var residual: Option[String] = None
        while batchIndex < batches.length && residual.isEmpty do
          val batch = batches(batchIndex)
          val columns = Vector.newBuilder[ColumnarVector]
          var column = 0
          while column < batch.columns.length && residual.isEmpty do
            ColumnarVector.copy(batch.columns(column)) match
              case Right(value) => columns += value
              case Left(value)  => residual = Some(value)
            column += 1
          if residual.isEmpty then output += ColumnarBatch(columns.result(), batch.rowCount)
          batchIndex += 1
        residual.toLeft(output.result()).left.map(Right.apply)

final private case class Int32Distinct(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    index: Int
) extends KernelPlan:
  val name = "HashDistinct[Int32,Primitive]"

  def execute(sources: ReferenceSources): KernelAttempt =
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val groups = new IntGroupIndex
        var values = new Array[Int](32)
        var size = 0
        var batchIndex = 0
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        while batchIndex < batches.length && error.isEmpty && residual.isEmpty do
          val batch = batches(batchIndex)
          batch.columns(index) match
            case column: Int32Array if column.nullCount == 0 =>
              val borrowed =
                column.withBorrowedValueBytes: (bytes, start, _, logicalOffset, length) =>
                  var row = 0
                  while row < length do
                    val actual = readInt(bytes, start + (logicalOffset + row) * 4)
                    if groups.find(actual) < 0 then
                      if size == values.length then
                        values = java.util.Arrays.copyOf(values, size * 2)
                      values(size) = actual
                      groups.put(actual, size)
                      size += 1
                    row += 1
              borrowed match
                case Left(value) => error = Some(ExecutionError.Storage(value))
                case Right(())   => ()
            case _: Int32Array =>
              residual = Some(s"distinct column $index became nullable")
            case _ =>
              residual = Some(s"distinct column $index is not plain Int32")
          batchIndex += 1

        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              error.toLeft(
                ColumnarResult(
                  outputSchema,
                  order,
                  Vector(
                    ColumnarBatch(
                      Vector(Int32Values(java.util.Arrays.copyOf(values, size))),
                      size
                    )
                  )
                )
              )
            )

private enum DistinctAtom:
  case Null
  case Bool(value: Boolean)
  case Int32(value: Int)
  case Int64(value: Long)
  case Float32(bits: Int)
  case Float64(bits: Long)
  case Utf8(value: String)
  case Timestamp(value: Long, unit: TimeUnit)

private object DistinctAtom:
  def apply(value: ScalarValue): DistinctAtom = value match
    case ScalarValue.Null            => Null
    case ScalarValue.Bool(actual)    => Bool(actual)
    case ScalarValue.Int32(actual)   => Int32(actual)
    case ScalarValue.Int64(actual)   => Int64(actual)
    case ScalarValue.Float32(actual) =>
      val normalized = if actual == 0.0f then 0.0f else actual
      Float32(java.lang.Float.floatToIntBits(normalized))
    case ScalarValue.Float64(actual) =>
      val normalized = if actual == 0.0 then 0.0 else actual
      Float64(java.lang.Double.doubleToLongBits(normalized))
    case ScalarValue.Utf8(actual)            => Utf8(actual)
    case ScalarValue.Timestamp(actual, unit) => Timestamp(actual, unit)

final private case class HashDistinct(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    indices: Vector[Int]
) extends KernelPlan:
  val name = "HashDistinct[logical-key]"

  def execute(sources: ReferenceSources): KernelAttempt =
    sources.borrowedBatches(reference, inputSchema) match
      case Left(error)    => KernelAttempt.Completed(Left(error))
      case Right(batches) =>
        val seen = scala.collection.mutable.LinkedHashSet.empty[Vector[DistinctAtom]]
        val rows = ArrayBuffer.empty[Array[ScalarValue]]
        var batchIndex = 0
        var error: Option[ExecutionError] = None
        while batchIndex < batches.length && error.isEmpty do
          val batch = batches(batchIndex)
          var row = 0
          while row < batch.rowCount && error.isEmpty do
            val values = new Array[ScalarValue](indices.length)
            var column = 0
            while column < indices.length && error.isEmpty do
              batch.columns(indices(column)).scalar(row) match
                case Right(value) => values(column) = value
                case Left(value)  => error = Some(ExecutionError.Storage(value))
              column += 1
            if error.isEmpty then
              val key = values.toVector.map(DistinctAtom.apply)
              if seen.add(key) then rows += values
            row += 1
          batchIndex += 1
        if indices.isEmpty && rows.isEmpty then rows += Array.empty[ScalarValue]
        error match
          case Some(value) => KernelAttempt.Completed(Left(value))
          case None        =>
            val columns = Vector.tabulate(indices.length): column =>
              val values = new Array[ScalarValue](rows.length)
              var row = 0
              while row < rows.length do
                values(row) = rows(row)(column)
                row += 1
              ScalarVector(values)
            KernelAttempt.Completed(
              Right(
                ColumnarResult(
                  outputSchema,
                  order,
                  Vector(ColumnarBatch(columns, rows.length))
                )
              )
            )

private enum Int32Projection:
  case Direct(index: Int, id: ExprId)
  case Add(index: Int, literal: Int, id: ExprId)

  def columnIndex: Int = this match
    case Direct(value, _) => value
    case Add(value, _, _) => value

final private case class FusedInt32(
    reference: SourceRef,
    inputSchema: Schema,
    outputSchema: Schema,
    order: OrderGuarantee,
    filter: Int32Filter,
    projections: Vector[Int32Projection]
) extends KernelPlan:
  val name = "FusedFilterProjectCheckedInt32"

  def execute(sources: ReferenceSources): KernelAttempt =
    val required = (filter.index +: projections.map(_.columnIndex)).distinct
    sources.openProjected(reference, inputSchema, required) match
      case Left(error)   => KernelAttempt.Completed(Left(error))
      case Right(cursor) =>
        val output = ArrayBuffer.empty[ColumnarBatch]
        var done = false
        var error: Option[ExecutionError] = None
        var residual: Option[String] = None
        try
          while !done && error.isEmpty && residual.isEmpty do
            cursor.nextBatch() match
              case Right(None)        => done = true
              case Left(value)        => error = Some(value)
              case Right(Some(batch)) =>
                evaluateBatch(batch, required) match
                  case Right(value)      => output += value
                  case Left(Left(value)) =>
                    error = Some(value)
                  case Left(Right(value)) =>
                    residual = Some(value)
                batch.close()
        finally cursor.close()
        residual match
          case Some(reason) => KernelAttempt.Residual(reason)
          case None         =>
            KernelAttempt.Completed(
              error.toLeft(ColumnarResult(outputSchema, order, output.toVector))
            )

  private def evaluateBatch(
      batch: RecordBatch,
      required: Vector[Int]
  ): Either[Either[ExecutionError, String], ColumnarBatch] =
    val decoded = new Array[RawInt32Vector](inputSchema.size)
    var index = 0
    var error: Option[Either[ExecutionError, String]] = None
    while index < required.length && error.isEmpty do
      batch.columns.lift(index) match
        case None =>
          error = Some(
            Left(
              ExecutionError.InvalidColumnIndex(
                ExprId.derived(s"columnar-fused:${required(index)}"),
                InputRef.Current.qualifier,
                required(index),
                batch.columns.length
              )
            )
          )
        case Some(column) =>
          ColumnarVector.copy(column) match
            case Right(value: RawInt32Vector) if value.required =>
              decoded(required(index)) = value
            case Right(_: RawInt32Vector) =>
              error = Some(Right(s"column ${required(index)} became nullable"))
            case Right(_) =>
              error = Some(Right(s"column ${required(index)} is not non-null Int32"))
            case Left(value) => error = Some(Right(value))
      index += 1

    error match
      case Some(value) => Left(value)
      case None        =>
        val values = projections.map(_ => new Array[Int](batch.rowCount))
        var outputRows = 0
        var row = 0
        while row < batch.rowCount && error.isEmpty do
          val actual = decoded(filter.index).unsafeIntValue(row)
          if filter.keep(actual) then
            var projectionIndex = 0
            while projectionIndex < projections.length && error.isEmpty do
              val projection = projections(projectionIndex)
              val input = decoded(projection.columnIndex).unsafeIntValue(row)
              projection match
                case Int32Projection.Direct(_, _) =>
                  values(projectionIndex)(outputRows) = input
                case Int32Projection.Add(_, literal, id) =>
                  val result = input.toLong + literal.toLong
                  if result < Int.MinValue.toLong || result > Int.MaxValue.toLong then
                    error = Some(
                      Left(ExecutionError.IntegerOverflow(id, BinaryOperator.Add))
                    )
                  else values(projectionIndex)(outputRows) = result.toInt
              projectionIndex += 1
            if error.isEmpty then outputRows += 1
          row += 1
        error match
          case Some(value) => Left(value)
          case None        =>
            val columns = values.map: buffer =>
              Int32Values(java.util.Arrays.copyOf(buffer, outputRows))
            Right(ColumnarBatch(columns, outputRows))

private def readInt(bytes: Array[Byte], offset: Int): Int =
  (bytes(offset) & 0xff) |
    ((bytes(offset + 1) & 0xff) << 8) |
    ((bytes(offset + 2) & 0xff) << 16) |
    ((bytes(offset + 3) & 0xff) << 24)

private def readLong(bytes: Array[Byte], offset: Int): Long =
  (readInt(bytes, offset).toLong & 0xffffffffL) |
    (readInt(bytes, offset + 4).toLong << 32)

private def writeInt(bytes: Array[Byte], offset: Int, value: Int): Unit =
  bytes(offset) = value.toByte
  bytes(offset + 1) = (value >>> 8).toByte
  bytes(offset + 2) = (value >>> 16).toByte
  bytes(offset + 3) = (value >>> 24).toByte

private def bit(bytes: Array[Byte], index: Int): Boolean =
  ((bytes(index >>> 3).toInt >>> (index & 7)) & 1) == 1

private def setBit(bytes: Array[Byte], index: Int): Unit =
  val byteIndex = index >>> 3
  bytes(byteIndex) = (bytes(byteIndex) | (1 << (index & 7))).toByte
