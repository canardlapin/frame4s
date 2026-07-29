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
    new ColumnarExecution(plan, sources, KernelPlan.classify(plan))

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
    length: Int
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then ScalarValue.Int32(readInt(values, absolute * 4))
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
          hash = hash * 31L + readInt(values, (logicalOffset + index) * 4).toLong
          index += 1
      case Some(bytes) =>
        while index < length do
          val absolute = logicalOffset + index
          val valid = ((bytes(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
          val value =
            if valid then readInt(values, absolute * 4).toLong
            else ColumnarVector.NullHash
          hash = hash * 31L + value
          index += 1
    hash

  def unsafeIntValue(index: Int): Int =
    readInt(values, (logicalOffset + index) * 4)

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
    timestampUnit: Option[TimeUnit]
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then
        val value = readLong(values, absolute * 8)
        timestampUnit.fold[ScalarValue](ScalarValue.Int64(value))(ScalarValue.Timestamp(value, _))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then
      val value = readLong(values, (logicalOffset + index) * 8)
      timestampUnit.fold(value)(unit => value ^ unit.ordinal.toLong)
    else ColumnarVector.NullHash

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
    length: Int
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then
        ScalarValue.Float32(java.lang.Float.intBitsToFloat(readInt(values, absolute * 4)))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if isValid(index) then readInt(values, (logicalOffset + index) * 4).toLong
    else ColumnarVector.NullHash

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
    length: Int
) extends ColumnarVector:
  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    checked(index).map: absolute =>
      if isValid(index) then ScalarValue.Bool(bit(values, absolute))
      else ScalarValue.Null

  def unsafeScalarHash(index: Int): Long =
    if !isValid(index) then ColumnarVector.NullHash
    else if bit(values, logicalOffset + index) then 1L
    else 2L

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

  def unsafeStringValue(index: Int): String =
    decode(logicalOffset + index)

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

final private case class Int64Values(values: Array[Long]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Int64(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index)

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

final private case class Utf8Values(values: Array[String]) extends ColumnarVector:
  val length: Int = values.length

  def scalar(index: Int): Either[ExecutionError, ScalarValue] =
    if index < 0 || index >= length then
      Left(ExecutionError.Storage(StorageError.InvalidRange(index, 1, length)))
    else Right(ScalarValue.Utf8(values(index)))

  def unsafeScalarHash(index: Int): Long = values(index).hashCode.toLong

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

private object ColumnarVector:
  val NullHash = 0x61c8864680b583ebL

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
      directProjection(expressions).map: indices =>
        DirectProjection(reference, inputSchema, outputSchema, plan.order, indices)
    case LogicalPlan.Project(
          LogicalPlan.Filter(
            LogicalPlan.Source(reference, inputSchema),
            predicate,
            _
          ),
          expressions,
          outputSchema
        ) =>
      for
        filter <- int32Filter(predicate)
        projections <- int32Projections(expressions)
      yield FusedInt32(
        reference,
        inputSchema,
        outputSchema,
        plan.order,
        filter,
        projections
      )
    case LogicalPlan.Filter(
          LogicalPlan.Source(reference, inputSchema),
          predicate,
          outputSchema
        ) =>
      int32Filter(predicate).map: filter =>
        FilterInt32(reference, inputSchema, outputSchema, plan.order, filter)
    case LogicalPlan.Aggregate(
          LogicalPlan.Source(reference, inputSchema),
          keys,
          aggregates,
          outputSchema
        ) =>
      aggregate(reference, inputSchema, outputSchema, plan.order, keys, aggregates)
    case LogicalPlan.Aggregate(
          LogicalPlan.Project(
            LogicalPlan.Source(reference, sourceSchema),
            projections,
            _
          ),
          keys,
          aggregates,
          outputSchema
        ) if aggregates.isEmpty =>
      for
        projected <- directProjection(projections)
        keyIndices <- directProjection(keys)
      yield HashDistinct(
        reference,
        sourceSchema,
        outputSchema,
        plan.order,
        keyIndices.map(projected)
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

  private def aggregate(
      reference: SourceRef,
      inputSchema: Schema,
      outputSchema: Schema,
      order: OrderGuarantee,
      keys: Vector[NamedExpression],
      aggregates: Vector[NamedAggregateExpression]
  ): Option[KernelPlan] =
    val distinct = Option.when(aggregates.isEmpty):
      val indices = keys
        .map(_.expression.node)
        .map:
          case ExprNode.Column(InputRef.Current, _, _, _, index) => Some(index)
          case _                                                 => None
      sequence(indices).map: columns =>
        HashDistinct(reference, inputSchema, outputSchema, order, columns)

    distinct.flatten.orElse:
      keys match
        case Vector(
              NamedExpression(
                _,
                ResolvedExpr(
                  _,
                  keyType @ (DataType.Int32 | DataType.Utf8),
                  false,
                  ExprNode.Column(InputRef.Current, _, _, _, keyIndex)
                )
              )
            ) =>
          val specs = aggregates.map: named =>
            named.expression.node match
              case AggregateNode.Count      => Some(AggregateSpec.Count)
              case AggregateNode.Sum(input) =>
                aggregateInput(input).map((index, _) => AggregateSpec.Sum(index))
              case AggregateNode.Mean(input) =>
                aggregateInput(input).map((index, _) => AggregateSpec.Mean(index))
              case AggregateNode.VariancePop(input) =>
                aggregateInput(input).map((index, _) => AggregateSpec.VariancePop(index))
              case _ => None
          sequence(specs).flatMap: compiled =>
            val numericIndices = compiled.flatMap(_.inputIndex)
            Option
              .when(numericIndices.distinct.size <= 1):
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
        case _ => None

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

final private class AggregateGroup(val key: ScalarValue):
  private var needs: AggregateRequirements = AggregateRequirements.all
  var rows: Long = 0L
  var numericCount: Long = 0L
  var sum: Double = 0.0
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
      numericCount += 1L
      if needs.sum then sum += actual
      if needs.variance then
        val delta = actual - mean
        mean += delta / numericCount.toDouble
        val delta2 = actual - mean
        m2 += delta * delta2

final private case class AggregateRequirements(
    count: Boolean,
    numeric: Boolean,
    sum: Boolean,
    variance: Boolean
)

private object AggregateRequirements:
  val all: AggregateRequirements =
    AggregateRequirements(count = true, numeric = true, sum = true, variance = true)

  def from(aggregates: Vector[AggregateSpec]): AggregateRequirements =
    val count = aggregates.contains(AggregateSpec.Count)
    val numeric = aggregates.exists(_.inputIndex.nonEmpty)
    val sum = aggregates.exists:
      case AggregateSpec.Sum(_) | AggregateSpec.Mean(_) => true
      case _                                            => false
    val variance = aggregates.exists:
      case AggregateSpec.VariancePop(_) => true
      case _                            => false
    AggregateRequirements(count, numeric, sum, variance)

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
        sums(group) += values.unsafeDoubleValue(row)
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
          groups.map(group => group.sum / group.numericCount.toDouble).toArray,
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

private def bit(bytes: Array[Byte], index: Int): Boolean =
  ((bytes(index >>> 3).toInt >>> (index & 7)) & 1) == 1
