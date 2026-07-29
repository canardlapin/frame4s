package frame4s

import scala.util.Sorting

private[frame4s] enum SecondaryIndexError:
  case Closed
  case SourceMismatch
  case InvalidColumnIndex(index: Int, columns: Int)
  case UnsupportedColumn(index: Int, dataType: DataType, encoding: PhysicalEncoding)
  case UnsupportedKernel(name: String)
  case UnsupportedLogicalShape(nodeName: String)
  case PreparationResidual(reason: String)
  case TooManyRows(rows: Long)
  case InvalidRowOrdinal(index: Int, size: Int)
  case Execution(error: ExecutionError)
  case Source(error: ExecutionError)
  case Storage(error: StorageError)

  def message: String = this match
    case Closed         => "secondary index is closed"
    case SourceMismatch =>
      "secondary index belongs to a different source binding"
    case InvalidColumnIndex(index, columns) =>
      s"secondary-index column $index is outside source width $columns"
    case UnsupportedColumn(index, dataType, encoding) =>
      s"secondary-index column $index is $dataType/$encoding, expected plain Int32"
    case UnsupportedKernel(name) =>
      s"secondary-index preparation does not support kernel $name"
    case UnsupportedLogicalShape(nodeName) =>
      s"secondary-index preparation does not support logical shape $nodeName"
    case PreparationResidual(reason) =>
      s"secondary-index preparation encountered unsupported physical input: $reason"
    case TooManyRows(rows) =>
      s"secondary index supports at most ${Int32SecondaryIndex.MaxRows} rows, received $rows"
    case InvalidRowOrdinal(index, size) =>
      s"secondary-index result position $index is outside result size $size"
    case Execution(error) => error.message
    case Source(error)    => error.message
    case Storage(error)   => error.message

sealed abstract private[frame4s] class Int32RowSelection private[frame4s] ():
  def size: Int

  def rowOrdinal(index: Int): Either[SecondaryIndexError, Int] =
    if index < 0 || index >= size then Left(SecondaryIndexError.InvalidRowOrdinal(index, size))
    else Right(unsafeOrdinal(index))

  /** Internal fast path after the caller has proved `0 <= index < size`. */
  private[frame4s] def unsafeOrdinal(index: Int): Int

private object EmptyInt32RowSelection extends Int32RowSelection:
  val size = 0

  private[frame4s] def unsafeOrdinal(index: Int): Int =
    throw new IndexOutOfBoundsException(index.toString)

final private class SingleInt32RowSelection(ordinal: Int) extends Int32RowSelection:
  val size = 1

  private[frame4s] def unsafeOrdinal(index: Int): Int =
    ordinal

final private class ManyInt32RowSelection(ordinals: Array[Int]) extends Int32RowSelection:
  val size: Int = ordinals.length

  private[frame4s] def unsafeOrdinal(index: Int): Int =
    ordinals(index)

private[frame4s] object Int32RowSelection:
  def empty: Int32RowSelection =
    EmptyInt32RowSelection

  def single(ordinal: Int): Int32RowSelection =
    new SingleInt32RowSelection(ordinal)

  def fromOwned(ordinals: Array[Int]): Int32RowSelection =
    ordinals.length match
      case 0 => empty
      case 1 => single(ordinals(0))
      case _ => new ManyInt32RowSelection(ordinals)

private[frame4s] enum SecondaryIndexLayout:
  case FastHash
  case CompactSorted
  case PackedSorted
  case FlatHashRows
  case GroupedHash

  def label: String = this match
    case FastHash      => "fast-hash"
    case CompactSorted => "compact-sorted"
    case PackedSorted  => "packed-sorted"
    case FlatHashRows  => "flat-hash-rows"
    case GroupedHash   => "grouped-hash"

private enum SortedSecondaryIndexLayout:
  case Compact
  case Packed

  def publicLayout: SecondaryIndexLayout = this match
    case Compact => SecondaryIndexLayout.CompactSorted
    case Packed  => SecondaryIndexLayout.PackedSorted

  def backend(
      keys: Array[Int],
      ordinals: Array[Int],
      rowCount: Int
  ): Int32IndexBackend = this match
    case Compact => new CompactSortedBackend(keys, ordinals)
    case Packed  =>
      new PackedSortedBackend(keys, Int32OrdinalStore.from(ordinals, rowCount))

private[frame4s] object SecondaryIndexHash:
  def mix(value: Int): Int =
    var hash = value
    hash ^= hash >>> 16
    hash *= 0x7feb352d
    hash ^= hash >>> 15
    hash *= 0x846ca68b
    hash ^ (hash >>> 16)

  def initialSlot(key: Int, capacity: Int): Int =
    val nonNegativeHash = mix(key) & Int.MaxValue
    ((nonNegativeHash.toLong * capacity.toLong) >>> 31).toInt

sealed private[frame4s] trait Int32IndexBackend:
  def first(key: Int): Int
  def next(token: Int): Int
  def row(token: Int): Int
  def select(key: Int): Int32RowSelection
  def ownedBytes: Long
  def close(): Unit
  def balancedBatchStrategy: SecondaryIndexBatchStrategy =
    SecondaryIndexBatchStrategy.Sort
  def dominantBatchStrategy: SecondaryIndexBatchStrategy =
    SecondaryIndexBatchStrategy.DominantMerge

  def mergeBalancedStreams(
      tokens: Array[Int],
      tokenCount: Int,
      ordinals: Array[Int]
  ): Unit =
    var heapSize = tokenCount
    var parent = (heapSize >>> 1) - 1
    while parent >= 0 do
      siftDown(tokens, parent, heapSize)
      parent -= 1

    var output = 0
    while heapSize > 0 do
      val token = tokens(0)
      ordinals(output) = row(token)
      output += 1
      val following = next(token)
      if following >= 0 then tokens(0) = following
      else
        heapSize -= 1
        if heapSize > 0 then tokens(0) = tokens(heapSize)
      if heapSize > 1 then siftDown(tokens, 0, heapSize)

  def mergeDominantStreams(
      tokens: Array[Int],
      tokenCount: Int,
      dominantIndex: Int,
      ordinals: Array[Int]
  ): Unit =
    var query = 0
    while query < tokenCount do
      ordinals(query) = tokens(query)
      query += 1

    var minorCount = 0
    query = 0
    while query < tokenCount do
      if query != dominantIndex then
        var token = ordinals(query)
        while token >= 0 do
          tokens(minorCount) = row(token)
          minorCount += 1
          token = next(token)
      query += 1
    java.util.Arrays.sort(tokens, 0, minorCount)

    var dominantToken = ordinals(dominantIndex)
    var minor = 0
    var output = 0
    while dominantToken >= 0 && minor < minorCount do
      val dominantRow = row(dominantToken)
      val minorRow = tokens(minor)
      if dominantRow <= minorRow then
        ordinals(output) = dominantRow
        dominantToken = next(dominantToken)
      else
        ordinals(output) = minorRow
        minor += 1
      output += 1
    while dominantToken >= 0 do
      ordinals(output) = row(dominantToken)
      output += 1
      dominantToken = next(dominantToken)
    while minor < minorCount do
      ordinals(output) = tokens(minor)
      output += 1
      minor += 1

  private def siftDown(tokens: Array[Int], root: Int, heapSize: Int): Unit =
    var parent = root
    var done = false
    while !done do
      val left = parent * 2 + 1
      if left >= heapSize then done = true
      else
        val right = left + 1
        val child =
          if right < heapSize && row(tokens(right)) < row(tokens(left)) then right
          else left
        if row(tokens(parent)) <= row(tokens(child)) then done = true
        else
          val previous = tokens(parent)
          tokens(parent) = tokens(child)
          tokens(child) = previous
          parent = child

final private class FastHashBackend(
    private var keys: Array[Int],
    private var heads: Array[Int],
    private var nextRows: Array[Int]
) extends Int32IndexBackend:
  private val mask = keys.length - 1

  val ownedBytes: Long =
    (keys.length.toLong + heads.length.toLong + nextRows.length.toLong) * 4L

  override val balancedBatchStrategy: SecondaryIndexBatchStrategy =
    SecondaryIndexBatchStrategy.HeapMerge

  override def mergeDominantStreams(
      tokens: Array[Int],
      tokenCount: Int,
      dominantIndex: Int,
      ordinals: Array[Int]
  ): Unit =
    var query = 0
    while query < tokenCount do
      ordinals(query) = tokens(query)
      query += 1

    var minorCount = 0
    query = 0
    while query < tokenCount do
      if query != dominantIndex then
        var row = ordinals(query)
        while row >= 0 do
          tokens(minorCount) = row
          minorCount += 1
          row = nextRows(row)
      query += 1
    java.util.Arrays.sort(tokens, 0, minorCount)

    var dominantRow = ordinals(dominantIndex)
    var minor = 0
    var output = 0
    while dominantRow >= 0 && minor < minorCount do
      val minorRow = tokens(minor)
      if dominantRow <= minorRow then
        ordinals(output) = dominantRow
        dominantRow = nextRows(dominantRow)
      else
        ordinals(output) = minorRow
        minor += 1
      output += 1
    while dominantRow >= 0 do
      ordinals(output) = dominantRow
      output += 1
      dominantRow = nextRows(dominantRow)
    while minor < minorCount do
      ordinals(output) = tokens(minor)
      output += 1
      minor += 1

  def first(key: Int): Int =
    var slot = SecondaryIndexHash.mix(key) & mask
    while heads(slot) >= 0 && keys(slot) != key do slot = (slot + 1) & mask
    heads(slot)

  def next(token: Int): Int =
    nextRows(token)

  def row(token: Int): Int =
    token

  def select(key: Int): Int32RowSelection =
    val firstRow = first(key)
    if firstRow < 0 then Int32RowSelection.empty
    else
      val following = nextRows(firstRow)
      if following < 0 then Int32RowSelection.single(firstRow)
      else
        var matches = 2
        var row = nextRows(following)
        while row >= 0 do
          matches += 1
          row = nextRows(row)
        val ordinals = new Array[Int](matches)
        ordinals(0) = firstRow
        var output = 1
        row = following
        while row >= 0 do
          ordinals(output) = row
          output += 1
          row = nextRows(row)
        Int32RowSelection.fromOwned(ordinals)

  def close(): Unit =
    keys = Array.emptyIntArray
    heads = Array.emptyIntArray
    nextRows = Array.emptyIntArray

  private[frame4s] def add(key: Int, row: Int): Unit =
    var slot = SecondaryIndexHash.mix(key) & mask
    while heads(slot) >= 0 && keys(slot) != key do slot = (slot + 1) & mask
    if heads(slot) < 0 then keys(slot) = key
    nextRows(row) = heads(slot)
    heads(slot) = row

final private class CompactSortedBackend(
    private var keys: Array[Int],
    private var rows: Array[Int]
) extends Int32IndexBackend:
  val ownedBytes: Long =
    (keys.length.toLong + rows.length.toLong) * 4L

  override val balancedBatchStrategy: SecondaryIndexBatchStrategy =
    SecondaryIndexBatchStrategy.HeapMerge

  override def mergeBalancedStreams(
      tokens: Array[Int],
      tokenCount: Int,
      ordinals: Array[Int]
  ): Unit =
    var heapSize = tokenCount
    var parent = (heapSize >>> 1) - 1
    while parent >= 0 do
      siftRowsDown(tokens, parent, heapSize)
      parent -= 1

    var output = 0
    while heapSize > 0 do
      val token = tokens(0)
      ordinals(output) = rows(token)
      output += 1
      val following = token + 1
      if following < keys.length && keys(following) == keys(token) then tokens(0) = following
      else
        heapSize -= 1
        if heapSize > 0 then tokens(0) = tokens(heapSize)
      if heapSize > 1 then siftRowsDown(tokens, 0, heapSize)

  def first(key: Int): Int =
    var low = 0
    var high = keys.length
    while low < high do
      val middle = (low + high) >>> 1
      if keys(middle) < key then low = middle + 1
      else high = middle
    if low < keys.length && keys(low) == key then low else Int32SecondaryIndex.MissingRow

  def next(token: Int): Int =
    val following = token + 1
    if following < keys.length && keys(following) == keys(token) then following
    else Int32SecondaryIndex.MissingRow

  def row(token: Int): Int =
    rows(token)

  def select(key: Int): Int32RowSelection =
    val firstPosition = first(key)
    if firstPosition < 0 then Int32RowSelection.empty
    else
      var end = firstPosition + 1
      while end < keys.length && keys(end) == key do end += 1
      val matches = end - firstPosition
      if matches == 1 then Int32RowSelection.single(rows(firstPosition))
      else
        val ordinals = new Array[Int](matches)
        var input = firstPosition
        var output = 0
        while input < end do
          ordinals(output) = rows(input)
          input += 1
          output += 1
        Int32RowSelection.fromOwned(ordinals)

  def close(): Unit =
    keys = Array.emptyIntArray
    rows = Array.emptyIntArray

  private def siftRowsDown(tokens: Array[Int], root: Int, heapSize: Int): Unit =
    var parent = root
    var done = false
    while !done do
      val left = parent * 2 + 1
      if left >= heapSize then done = true
      else
        val right = left + 1
        val child =
          if right < heapSize && rows(tokens(right)) < rows(tokens(left)) then right
          else left
        if rows(tokens(parent)) <= rows(tokens(child)) then done = true
        else
          val previous = tokens(parent)
          tokens(parent) = tokens(child)
          tokens(child) = previous
          parent = child

sealed private[frame4s] trait Int32OrdinalStore:
  def apply(position: Int): Int
  def ownedBytes: Long
  def close(): Unit

final private class ZeroInt32OrdinalStore extends Int32OrdinalStore:
  def apply(position: Int): Int = 0
  val ownedBytes = 0L
  def close(): Unit = ()

final private class PackedInt32OrdinalStore(
    private var words: Array[Int],
    bitWidth: Int,
    mask: Int
) extends Int32OrdinalStore:
  val ownedBytes: Long = words.length.toLong * 4L

  def apply(position: Int): Int =
    val bitPosition = position.toLong * bitWidth.toLong
    val word = (bitPosition >>> 5).toInt
    val shift = (bitPosition & 31L).toInt
    val lower = words(word) >>> shift
    if shift + bitWidth <= 32 then lower & mask
    else
      val upper = words(word + 1) << (32 - shift)
      (lower | upper) & mask

  def close(): Unit =
    words = Array.emptyIntArray

private[frame4s] object Int32OrdinalStore:
  def from(ordinals: Array[Int], rowCount: Int): Int32OrdinalStore =
    val bitWidth = bitWidthFor(rowCount)
    if ordinals.isEmpty || bitWidth == 0 then new ZeroInt32OrdinalStore
    else
      val words = new Array[Int](wordCount(ordinals.length, bitWidth))
      val mask = (1 << bitWidth) - 1
      var position = 0
      while position < ordinals.length do
        val value = ordinals(position)
        val bitPosition = position.toLong * bitWidth.toLong
        val word = (bitPosition >>> 5).toInt
        val shift = (bitPosition & 31L).toInt
        words(word) |= value << shift
        if shift + bitWidth > 32 then words(word + 1) |= value >>> (32 - shift)
        position += 1
      new PackedInt32OrdinalStore(words, bitWidth, mask)

  private[frame4s] def ownedBytesFor(indexedRows: Int, rowCount: Int): Long =
    val bitWidth = bitWidthFor(rowCount)
    wordCount(indexedRows, bitWidth).toLong * 4L

  private def bitWidthFor(rowCount: Int): Int =
    if rowCount <= 1 then 0
    else 32 - java.lang.Integer.numberOfLeadingZeros(rowCount - 1)

  private def wordCount(length: Int, bitWidth: Int): Int =
    if length == 0 || bitWidth == 0 then 0
    else ((length.toLong * bitWidth.toLong + 31L) >>> 5).toInt

final private class PackedSortedBackend(
    private var keys: Array[Int],
    private var rows: Int32OrdinalStore
) extends Int32IndexBackend:
  val ownedBytes: Long =
    keys.length.toLong * 4L + rows.ownedBytes

  def first(key: Int): Int =
    var low = 0
    var high = keys.length
    while low < high do
      val middle = (low + high) >>> 1
      if keys(middle) < key then low = middle + 1
      else high = middle
    if low < keys.length && keys(low) == key then low else Int32SecondaryIndex.MissingRow

  def next(token: Int): Int =
    val following = token + 1
    if following < keys.length && keys(following) == keys(token) then following
    else Int32SecondaryIndex.MissingRow

  def row(token: Int): Int =
    rows(token)

  def select(key: Int): Int32RowSelection =
    val firstPosition = first(key)
    if firstPosition < 0 then Int32RowSelection.empty
    else
      var end = firstPosition + 1
      while end < keys.length && keys(end) == key do end += 1
      val matches = end - firstPosition
      if matches == 1 then Int32RowSelection.single(rows(firstPosition))
      else
        val ordinals = new Array[Int](matches)
        var input = firstPosition
        var output = 0
        while input < end do
          ordinals(output) = rows(input)
          input += 1
          output += 1
        Int32RowSelection.fromOwned(ordinals)

  def close(): Unit =
    keys = Array.emptyIntArray
    rows.close()
    rows = new ZeroInt32OrdinalStore

final private class FlatHashRowsBackend(
    private var keys: Array[Int],
    private var rows: Array[Int]
) extends Int32IndexBackend:
  private val RowOrdinalMask = (1 << 30) - 1
  private val HasFollowingMatch = 1 << 30
  private val buildCacheSize =
    if rows.isEmpty then 0
    else
      var size = 16
      while size < 4096 && size * 2 <= rows.length do size *= 2
      size
  private var cachedKeys = new Array[Int](buildCacheSize)
  private var cachedLastSlots =
    Array.fill(buildCacheSize)(Int32SecondaryIndex.MissingRow)

  val ownedBytes: Long =
    (keys.length.toLong + rows.length.toLong) * 4L

  def first(key: Int): Int =
    if rows.isEmpty then Int32SecondaryIndex.MissingRow
    else
      var slot = SecondaryIndexHash.initialSlot(key, rows.length)
      while rows(slot) >= 0 && keys(slot) != key do slot = following(slot)
      if rows(slot) >= 0 then slot else Int32SecondaryIndex.MissingRow

  def next(token: Int): Int =
    if (rows(token) & HasFollowingMatch) == 0 then Int32SecondaryIndex.MissingRow
    else
      val key = keys(token)
      var slot = following(token)
      while rows(slot) >= 0 && keys(slot) != key do slot = following(slot)
      if rows(slot) >= 0 then slot else Int32SecondaryIndex.MissingRow

  def row(token: Int): Int =
    rows(token) & RowOrdinalMask

  def select(key: Int): Int32RowSelection =
    val firstToken = first(key)
    if firstToken < 0 then Int32RowSelection.empty
    else
      val secondToken = next(firstToken)
      if secondToken < 0 then Int32RowSelection.single(rows(firstToken) & RowOrdinalMask)
      else
        var matches = 2
        var token = next(secondToken)
        while token >= 0 do
          matches += 1
          token = next(token)
        val ordinals = new Array[Int](matches)
        ordinals(0) = rows(firstToken) & RowOrdinalMask
        var output = 1
        token = secondToken
        while token >= 0 do
          ordinals(output) = rows(token) & RowOrdinalMask
          output += 1
          token = next(token)
        Int32RowSelection.fromOwned(ordinals)

  def close(): Unit =
    keys = Array.emptyIntArray
    rows = Array.emptyIntArray
    finishBuild()

  private[frame4s] def add(key: Int, row: Int): Unit =
    val cacheSlot = SecondaryIndexHash.mix(key) & (buildCacheSize - 1)
    val cachedLast = cachedLastSlots(cacheSlot)
    var previousMatch =
      if cachedLast >= 0 && cachedKeys(cacheSlot) == key then cachedLast
      else Int32SecondaryIndex.MissingRow
    var slot =
      if previousMatch >= 0 then following(previousMatch)
      else SecondaryIndexHash.initialSlot(key, rows.length)
    while rows(slot) >= 0 do
      if keys(slot) == key then previousMatch = slot
      slot = following(slot)
    if previousMatch >= 0 then rows(previousMatch) |= HasFollowingMatch
    keys(slot) = key
    rows(slot) = row
    cachedKeys(cacheSlot) = key
    cachedLastSlots(cacheSlot) = slot

  private[frame4s] def finishBuild(): Unit =
    cachedKeys = Array.emptyIntArray
    cachedLastSlots = Array.emptyIntArray

  private def following(slot: Int): Int =
    val next = slot + 1
    if next == rows.length then 0 else next

/** One hash slot per distinct key, with contiguous overflow rows only for duplicate groups.
  *
  * Main-table tokens are strictly below bit 30. Overflow tokens set bit 30 and carry an overflow
  * position in the lower bits. The final row in each overflow group also sets bit 30 in its stored
  * ordinal. [[Int32SecondaryIndex.MaxRows]] is strictly below `2^30`, so neither tag can collide
  * with a valid source ordinal; bit 31 remains available for the negative empty sentinel.
  */
final private class GroupedHashBackend(
    private var keys: Array[Int],
    private var entries: Array[Int],
    private var duplicateStarts: Array[Int],
    private var duplicateRows: Array[Int]
) extends Int32IndexBackend:
  import GroupedHashBackend.*

  val ownedBytes: Long =
    (
      keys.length.toLong +
        entries.length.toLong +
        duplicateStarts.length.toLong +
        duplicateRows.length.toLong
    ) * 4L

  def first(key: Int): Int =
    if entries.isEmpty then Int32SecondaryIndex.MissingRow
    else
      var slot = SecondaryIndexHash.initialSlot(key, entries.length)
      while entries(slot) >= 0 && keys(slot) != key do slot = following(slot)
      if entries(slot) >= 0 then slot else Int32SecondaryIndex.MissingRow

  def next(token: Int): Int =
    if isOverflowToken(token) then
      val position = payload(token)
      if isLastOverflowRow(duplicateRows(position)) then Int32SecondaryIndex.MissingRow
      else OverflowTag | (position + 1)
    else
      val entry = entries(token)
      if !isDuplicateEntry(entry) then Int32SecondaryIndex.MissingRow
      else
        val position = duplicateStarts(payload(entry))
        if isLastOverflowRow(duplicateRows(position)) then Int32SecondaryIndex.MissingRow
        else OverflowTag | (position + 1)

  def row(token: Int): Int =
    if isOverflowToken(token) then payload(duplicateRows(payload(token)))
    else
      val entry = entries(token)
      if isDuplicateEntry(entry) then payload(duplicateRows(duplicateStarts(payload(entry))))
      else entry

  def select(key: Int): Int32RowSelection =
    val slot = first(key)
    if slot < 0 then Int32RowSelection.empty
    else
      val entry = entries(slot)
      if !isDuplicateEntry(entry) then Int32RowSelection.single(entry)
      else
        val start = duplicateStarts(payload(entry))
        var end = start
        while !isLastOverflowRow(duplicateRows(end)) do end += 1
        val matches = end - start + 1
        val ordinals = new Array[Int](matches)
        var input = start
        var output = 0
        while input <= end do
          ordinals(output) = payload(duplicateRows(input))
          input += 1
          output += 1
        Int32RowSelection.fromOwned(ordinals)

  def close(): Unit =
    keys = Array.emptyIntArray
    entries = Array.emptyIntArray
    duplicateStarts = Array.emptyIntArray
    duplicateRows = Array.emptyIntArray

  private[frame4s] def addUnique(key: Int, row: Int): Unit =
    val slot = emptySlot(key)
    keys(slot) = key
    entries(slot) = row

  private[frame4s] def addDuplicateGroup(
      key: Int,
      group: Int,
      sourceRows: Array[Int],
      from: Int,
      until: Int,
      overflowStart: Int
  ): Unit =
    val slot = emptySlot(key)
    keys(slot) = key
    entries(slot) = OverflowTag | group
    duplicateStarts(group) = overflowStart
    var input = from
    var output = overflowStart
    while input < until do
      val lastTag = if input + 1 == until then OverflowTag else 0
      duplicateRows(output) = sourceRows(input) | lastTag
      input += 1
      output += 1

  private def emptySlot(key: Int): Int =
    var slot = SecondaryIndexHash.initialSlot(key, entries.length)
    while entries(slot) >= 0 do slot = following(slot)
    slot

  private def following(slot: Int): Int =
    val next = slot + 1
    if next == entries.length then 0 else next

private[frame4s] object GroupedHashBackend:
  private val OverflowTag = 1 << 30
  private val PayloadMask = OverflowTag - 1

  private def isOverflowToken(token: Int): Boolean =
    (token & OverflowTag) != 0

  private def isDuplicateEntry(entry: Int): Boolean =
    (entry & OverflowTag) != 0

  private def isLastOverflowRow(row: Int): Boolean =
    (row & OverflowTag) != 0

  private def payload(value: Int): Int =
    value & PayloadMask

private[frame4s] enum SecondaryIndexBatchStrategy:
  case Sort
  case HeapMerge
  case DominantMerge

private[frame4s] object SecondaryIndexBatchStrategy:
  private val MinimumMatches = 128
  private val MinimumStreams = 4

  def choose(
      matches: Int,
      streams: Int,
      longestStream: Int,
      workspace: Int,
      balancedStrategy: SecondaryIndexBatchStrategy,
      dominantStrategy: SecondaryIndexBatchStrategy
  ): SecondaryIndexBatchStrategy =
    val minorMatches = matches - longestStream
    if matches < MinimumMatches then SecondaryIndexBatchStrategy.Sort
    else if longestStream.toLong * 2L > matches.toLong && minorMatches <= workspace then
      dominantStrategy
    else if streams >= MinimumStreams && longestStream.toLong * 2L <= matches.toLong then
      balancedStrategy
    else SecondaryIndexBatchStrategy.Sort

final private[frame4s] class Int32SecondaryIndex private (
    boundSources: ReferenceSources,
    val reference: SourceRef,
    val schema: Schema,
    val columnIndex: Int,
    val rowCount: Int,
    val layout: SecondaryIndexLayout,
    private val backend: Int32IndexBackend
):
  private var closed = false

  val ownedBytes: Long = backend.ownedBytes

  def isClosed: Boolean = synchronized(closed)

  def validateBinding(
      sources: ReferenceSources,
      expectedReference: SourceRef,
      expectedSchema: Schema,
      expectedColumnIndex: Int
  ): Either[SecondaryIndexError, Unit] = synchronized:
    if closed then Left(SecondaryIndexError.Closed)
    else if !(boundSources eq sources) ||
      reference != expectedReference ||
      schema != expectedSchema ||
      columnIndex != expectedColumnIndex
    then Left(SecondaryIndexError.SourceMismatch)
    else Right(())

  def lookup(key: Int): Either[SecondaryIndexError, Int32RowSelection] = synchronized:
    if closed then Left(SecondaryIndexError.Closed)
    else Right(backend.select(key))

  def lookup(queryKeys: Array[Int]): Either[SecondaryIndexError, Int32RowSelection] =
    synchronized:
      if closed then Left(SecondaryIndexError.Closed)
      else
        val distinct = queryKeys.clone()
        val distinctCount = compactDistinctSorted(distinct)
        var matches = 0
        var streams = 0
        var longestStream = 0
        var longestStreamIndex = Int32SecondaryIndex.MissingRow
        var query = 0
        while query < distinctCount do
          var token = backend.first(distinct(query))
          val streamIndex = streams
          if token >= 0 then
            distinct(streamIndex) = token
            streams += 1
          var streamMatches = 0
          while token >= 0 do
            matches += 1
            streamMatches += 1
            token = backend.next(token)
          if streamMatches > longestStream then
            longestStream = streamMatches
            longestStreamIndex = streamIndex
          query += 1

        val ordinals = new Array[Int](matches)
        SecondaryIndexBatchStrategy.choose(
          matches,
          streams,
          longestStream,
          distinct.length,
          backend.balancedBatchStrategy,
          backend.dominantBatchStrategy
        ) match
          case SecondaryIndexBatchStrategy.HeapMerge =>
            backend.mergeBalancedStreams(distinct, streams, ordinals)
          case SecondaryIndexBatchStrategy.DominantMerge =>
            backend.mergeDominantStreams(
              distinct,
              streams,
              longestStreamIndex,
              ordinals
            )
          case SecondaryIndexBatchStrategy.Sort =>
            var output = 0
            query = 0
            while query < streams do
              var token = distinct(query)
              while token >= 0 do
                ordinals(output) = backend.row(token)
                output += 1
                token = backend.next(token)
              query += 1
            java.util.Arrays.sort(ordinals)
        Right(Int32RowSelection.fromOwned(ordinals))

  private[frame4s] def withView[A](
      sources: ReferenceSources,
      expectedReference: SourceRef,
      expectedSchema: Schema,
      expectedColumnIndex: Int
  )(operation: Int32SecondaryIndex => A): Either[SecondaryIndexError, A] = synchronized:
    validateBinding(
      sources,
      expectedReference,
      expectedSchema,
      expectedColumnIndex
    ).map(_ => operation(this))

  private[frame4s] def firstUnsafe(key: Int): Int =
    backend.first(key)

  private[frame4s] def nextUnsafe(token: Int): Int =
    backend.next(token)

  private[frame4s] def rowUnsafe(token: Int): Int =
    backend.row(token)

  def close(): Unit = synchronized:
    if !closed then
      closed = true
      backend.close()

  private def compactDistinctSorted(values: Array[Int]): Int =
    if values.isEmpty then 0
    else
      Sorting.quickSort(values)
      var distinct = 1
      var index = 1
      while index < values.length do
        if values(index) != values(distinct - 1) then
          values(distinct) = values(index)
          distinct += 1
        index += 1
      distinct

private[frame4s] object Int32SecondaryIndex:
  /** Physical hash-table sentinel. Valid source row ordinals are always non-negative. */
  private[frame4s] val MissingRow = -1

  private[frame4s] val MaxRows: Long = (1L << 30) * 3L / 4L

  def build(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema,
      columnIndex: Int,
      layout: SecondaryIndexLayout = SecondaryIndexLayout.FastHash
  ): Either[SecondaryIndexError, Int32SecondaryIndex] =
    if columnIndex < 0 || columnIndex >= schema.size then
      Left(SecondaryIndexError.InvalidColumnIndex(columnIndex, schema.size))
    else
      sources
        .borrowedBatches(reference, schema)
        .left
        .map:
          case ExecutionError.Storage(error) => SecondaryIndexError.Storage(error)
          case error                         => SecondaryIndexError.Source(error)
        .flatMap: batches =>
          val rows = batches.foldLeft(0L)(_ + _.rowCount.toLong)
          if rows > MaxRows then Left(SecondaryIndexError.TooManyRows(rows))
          else
            validatedColumns(batches, columnIndex).flatMap: columns =>
              layout match
                case SecondaryIndexLayout.FastHash =>
                  buildFastHash(
                    sources,
                    reference,
                    schema,
                    columnIndex,
                    batches,
                    columns,
                    rows.toInt
                  )
                case SecondaryIndexLayout.CompactSorted =>
                  buildSorted(
                    sources,
                    reference,
                    schema,
                    columnIndex,
                    batches,
                    columns,
                    rows.toInt,
                    SortedSecondaryIndexLayout.Compact
                  )
                case SecondaryIndexLayout.PackedSorted =>
                  buildSorted(
                    sources,
                    reference,
                    schema,
                    columnIndex,
                    batches,
                    columns,
                    rows.toInt,
                    SortedSecondaryIndexLayout.Packed
                  )
                case SecondaryIndexLayout.FlatHashRows =>
                  buildFlatHashRows(
                    sources,
                    reference,
                    schema,
                    columnIndex,
                    batches,
                    columns,
                    rows.toInt
                  )
                case SecondaryIndexLayout.GroupedHash =>
                  buildGroupedHash(
                    sources,
                    reference,
                    schema,
                    columnIndex,
                    batches,
                    columns,
                    rows.toInt
                  )

  private def validatedColumns(
      batches: Vector[RecordBatch],
      columnIndex: Int
  ): Either[SecondaryIndexError, Vector[Int32Array]] =
    val builder = Vector.newBuilder[Int32Array]
    var batch = 0
    var error: Option[SecondaryIndexError] = None
    while batch < batches.length && error.isEmpty do
      batches(batch).columns(columnIndex) match
        case column: Int32Array =>
          builder += column
        case column =>
          error = Some(
            SecondaryIndexError.UnsupportedColumn(
              columnIndex,
              column.dataType,
              column.encoding
            )
          )
      batch += 1
    error.toLeft(builder.result())

  private def buildFastHash(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema,
      columnIndex: Int,
      batches: Vector[RecordBatch],
      columns: Vector[Int32Array],
      rows: Int
  ): Either[SecondaryIndexError, Int32SecondaryIndex] =
    val capacity = capacityFor(rows)
    val backend = new FastHashBackend(
      new Array[Int](capacity),
      Array.fill(capacity)(MissingRow),
      Array.fill(rows)(MissingRow)
    )
    val index = new Int32SecondaryIndex(
      sources,
      reference,
      schema,
      columnIndex,
      rows,
      SecondaryIndexLayout.FastHash,
      backend
    )
    val batchStarts = new Array[Int](batches.length)
    var running = 0
    var batch = 0
    while batch < batches.length do
      batchStarts(batch) = running
      running += batches(batch).rowCount
      batch += 1

    var error: Option[SecondaryIndexError] = None
    batch = batches.length - 1
    while batch >= 0 && error.isEmpty do
      columns(batch)
        .withBorrowedValueBytes: (bytes, valuesStart, validity, logicalOffset, length) =>
          var row = length - 1
          while row >= 0 do
            val absolute = logicalOffset + row
            val valid = validity.forall: bitmap =>
              ((bitmap(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
            if valid then
              val value = readInt(bytes, valuesStart + absolute * 4)
              backend.add(value, batchStarts(batch) + row)
            row -= 1
        .left
        .map(SecondaryIndexError.Storage.apply) match
        case Left(value) => error = Some(value)
        case Right(())   => ()
      batch -= 1

    error match
      case Some(value) =>
        index.close()
        Left(value)
      case None => Right(index)

  private def buildSorted(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema,
      columnIndex: Int,
      batches: Vector[RecordBatch],
      columns: Vector[Int32Array],
      rows: Int,
      layout: SortedSecondaryIndexLayout
  ): Either[SecondaryIndexError, Int32SecondaryIndex] =
    val indexedRows = columns.foldLeft(0): (total, column) =>
      total + column.length - column.nullCount
    val keys = new Array[Int](indexedRows)
    val ordinals = new Array[Int](indexedRows)
    var batchStart = 0
    var output = 0
    var batch = 0
    var error: Option[SecondaryIndexError] = None
    while batch < batches.length && error.isEmpty do
      columns(batch)
        .withBorrowedValueBytes: (bytes, valuesStart, validity, logicalOffset, length) =>
          var row = 0
          while row < length do
            val absolute = logicalOffset + row
            val valid = validity.forall: bitmap =>
              ((bitmap(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
            if valid then
              keys(output) = readInt(bytes, valuesStart + absolute * 4)
              ordinals(output) = batchStart + row
              output += 1
            row += 1
        .left
        .map(SecondaryIndexError.Storage.apply) match
        case Left(value) => error = Some(value)
        case Right(())   => ()
      batchStart += batches(batch).rowCount
      batch += 1

    error match
      case Some(value) => Left(value)
      case None        =>
        stableSignedRadixSort(keys, ordinals)
        val backend = layout.backend(keys, ordinals, rows)
        Right(
          new Int32SecondaryIndex(
            sources,
            reference,
            schema,
            columnIndex,
            rows,
            layout.publicLayout,
            backend
          )
        )

  private def buildFlatHashRows(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema,
      columnIndex: Int,
      batches: Vector[RecordBatch],
      columns: Vector[Int32Array],
      rows: Int
  ): Either[SecondaryIndexError, Int32SecondaryIndex] =
    val indexedRows = columns.foldLeft(0): (total, column) =>
      total + column.length - column.nullCount
    val capacity = flatCapacityFor(indexedRows)
    val backend = new FlatHashRowsBackend(
      new Array[Int](capacity),
      Array.fill(capacity)(MissingRow)
    )
    val index = new Int32SecondaryIndex(
      sources,
      reference,
      schema,
      columnIndex,
      rows,
      SecondaryIndexLayout.FlatHashRows,
      backend
    )
    var batchStart = 0
    var batch = 0
    var error: Option[SecondaryIndexError] = None
    while batch < batches.length && error.isEmpty do
      columns(batch)
        .withBorrowedValueBytes: (bytes, valuesStart, validity, logicalOffset, length) =>
          var row = 0
          while row < length do
            val absolute = logicalOffset + row
            val valid = validity.forall: bitmap =>
              ((bitmap(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
            if valid then
              backend.add(
                readInt(bytes, valuesStart + absolute * 4),
                batchStart + row
              )
            row += 1
        .left
        .map(SecondaryIndexError.Storage.apply) match
        case Left(value) => error = Some(value)
        case Right(())   => ()
      batchStart += batches(batch).rowCount
      batch += 1

    error match
      case Some(value) =>
        index.close()
        Left(value)
      case None =>
        backend.finishBuild()
        Right(index)

  private def buildGroupedHash(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema,
      columnIndex: Int,
      batches: Vector[RecordBatch],
      columns: Vector[Int32Array],
      rows: Int
  ): Either[SecondaryIndexError, Int32SecondaryIndex] =
    val indexedRows = columns.foldLeft(0): (total, column) =>
      total + column.length - column.nullCount
    val keys = new Array[Int](indexedRows)
    val ordinals = new Array[Int](indexedRows)
    var batchStart = 0
    var output = 0
    var batch = 0
    var error: Option[SecondaryIndexError] = None
    while batch < batches.length && error.isEmpty do
      columns(batch)
        .withBorrowedValueBytes: (bytes, valuesStart, validity, logicalOffset, length) =>
          var row = 0
          while row < length do
            val absolute = logicalOffset + row
            val valid = validity.forall: bitmap =>
              ((bitmap(absolute >>> 3).toInt >>> (absolute & 7)) & 1) == 1
            if valid then
              keys(output) = readInt(bytes, valuesStart + absolute * 4)
              ordinals(output) = batchStart + row
              output += 1
            row += 1
        .left
        .map(SecondaryIndexError.Storage.apply) match
        case Left(value) => error = Some(value)
        case Right(())   => ()
      batchStart += batches(batch).rowCount
      batch += 1

    error match
      case Some(value) => Left(value)
      case None        =>
        stableSignedRadixSort(keys, ordinals)
        var distinctKeys = 0
        var duplicateGroups = 0
        var duplicateRows = 0
        var from = 0
        while from < keys.length do
          val key = keys(from)
          var until = from + 1
          while until < keys.length && keys(until) == key do until += 1
          distinctKeys += 1
          if until - from > 1 then
            duplicateGroups += 1
            duplicateRows += until - from
          from = until

        val backend = new GroupedHashBackend(
          new Array[Int](flatCapacityFor(distinctKeys)),
          Array.fill(flatCapacityFor(distinctKeys))(MissingRow),
          new Array[Int](duplicateGroups),
          new Array[Int](duplicateRows)
        )
        var group = 0
        var overflowStart = 0
        from = 0
        while from < keys.length do
          val key = keys(from)
          var until = from + 1
          while until < keys.length && keys(until) == key do until += 1
          if until - from == 1 then backend.addUnique(key, ordinals(from))
          else
            backend.addDuplicateGroup(
              key,
              group,
              ordinals,
              from,
              until,
              overflowStart
            )
            group += 1
            overflowStart += until - from
          from = until

        Right(
          new Int32SecondaryIndex(
            sources,
            reference,
            schema,
            columnIndex,
            rows,
            SecondaryIndexLayout.GroupedHash,
            backend
          )
        )

  private def capacityFor(rows: Int): Int =
    val target = math.max(16L, (rows.toLong * 4L + 2L) / 3L)
    var capacity = 16
    while capacity.toLong < target && capacity < (1 << 30) do capacity *= 2
    capacity

  private[frame4s] def flatCapacityFor(indexedRows: Int): Int =
    if indexedRows == 0 then 0
    else math.max(16L, (indexedRows.toLong * 4L + 2L) / 3L).toInt

  private def stableSignedRadixSort(keys: Array[Int], rows: Array[Int]): Unit =
    if keys.length > 1 then
      var sourceKeys = keys
      var sourceRows = rows
      var targetKeys = new Array[Int](keys.length)
      var targetRows = new Array[Int](rows.length)
      val offsets = new Array[Int](256)
      var pass = 0
      while pass < 4 do
        var bucket = 0
        while bucket < offsets.length do
          offsets(bucket) = 0
          bucket += 1

        val shift = pass * 8
        var index = 0
        while index < sourceKeys.length do
          bucket = radixBucket(sourceKeys(index), shift, pass == 3)
          offsets(bucket) += 1
          index += 1

        var running = 0
        bucket = 0
        while bucket < offsets.length do
          val count = offsets(bucket)
          offsets(bucket) = running
          running += count
          bucket += 1

        index = 0
        while index < sourceKeys.length do
          val key = sourceKeys(index)
          bucket = radixBucket(key, shift, pass == 3)
          val output = offsets(bucket)
          targetKeys(output) = key
          targetRows(output) = sourceRows(index)
          offsets(bucket) = output + 1
          index += 1

        val previousKeys = sourceKeys
        sourceKeys = targetKeys
        targetKeys = previousKeys
        val previousRows = sourceRows
        sourceRows = targetRows
        targetRows = previousRows
        pass += 1

  private def radixBucket(key: Int, shift: Int, signedByte: Boolean): Int =
    val raw = (key >>> shift) & 0xff
    if signedByte then raw ^ 0x80 else raw

  private def readInt(bytes: Array[Byte], offset: Int): Int =
    (bytes(offset).toInt & 0xff) |
      ((bytes(offset + 1).toInt & 0xff) << 8) |
      ((bytes(offset + 2).toInt & 0xff) << 16) |
      ((bytes(offset + 3).toInt & 0xff) << 24)
