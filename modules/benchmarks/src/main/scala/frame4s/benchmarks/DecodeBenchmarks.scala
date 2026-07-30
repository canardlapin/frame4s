package frame4s.benchmarks

import org.openjdk.jmh.annotations.*
import java.lang.invoke.{MethodHandles, VarHandle}
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Isolates the cost of frame4s' storage representation from the cost of its kernels.
  *
  * frame4s keeps every numeric column in an `Array[Byte]` and decodes each element with
  * masked loads and shifts. That is Arrow-compatible and platform-neutral, but it is also
  * the layer every kernel sits on, so its cost is charged to every workload in the court.
  *
  * This benchmark answers one question before any kernel is rewritten: how much does the
  * representation actually cost against a native primitive array, and does a `VarHandle`
  * recover it? The native rows are the ceiling, not a proposal -- frame4s cannot simply
  * become `Array[Double]` without giving up zero-copy Arrow interchange.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class DecodeBenchmarks:
  import DecodeBenchmarks.*

  @Benchmark
  def sumDoubleShift(state: DecodeState): Double =
    val bytes = state.bytes
    var total = 0.0
    var index = 0
    while index < state.length do
      total += java.lang.Double.longBitsToDouble(shiftLong(bytes, index * 8))
      index += 1
    total

  @Benchmark
  def sumDoubleVarHandle(state: DecodeState): Double =
    val bytes = state.bytes
    var total = 0.0
    var index = 0
    while index < state.length do
      total += doubleHandle.get(bytes, index * 8).asInstanceOf[Double]
      index += 1
    total

  @Benchmark
  def sumDoubleNative(state: DecodeState): Double =
    val values = state.doubles
    var total = 0.0
    var index = 0
    while index < state.length do
      total += values(index)
      index += 1
    total

  @Benchmark
  def sumIntShift(state: DecodeState): Long =
    val bytes = state.bytes
    var total = 0L
    var index = 0
    while index < state.length do
      total += shiftInt(bytes, index * 4)
      index += 1
    total

  @Benchmark
  def sumIntVarHandle(state: DecodeState): Long =
    val bytes = state.bytes
    var total = 0L
    var index = 0
    while index < state.length do
      total += intHandle.get(bytes, index * 4).asInstanceOf[Int]
      index += 1
    total

  @Benchmark
  def sumIntNative(state: DecodeState): Long =
    val values = state.ints
    var total = 0L
    var index = 0
    while index < state.length do
      total += values(index)
      index += 1
    total

object DecodeBenchmarks:
  private val intHandle: VarHandle =
    MethodHandles.byteArrayViewVarHandle(classOf[Array[Int]], ByteOrder.LITTLE_ENDIAN)
  private val doubleHandle: VarHandle =
    MethodHandles.byteArrayViewVarHandle(classOf[Array[Double]], ByteOrder.LITTLE_ENDIAN)

  /** The decode frame4s uses today, copied so the comparison is against real code. */
  private def shiftInt(bytes: Array[Byte], offset: Int): Int =
    (bytes(offset) & 0xff) |
      ((bytes(offset + 1) & 0xff) << 8) |
      ((bytes(offset + 2) & 0xff) << 16) |
      ((bytes(offset + 3) & 0xff) << 24)

  private def shiftLong(bytes: Array[Byte], offset: Int): Long =
    (shiftInt(bytes, offset).toLong & 0xffffffffL) |
      (shiftInt(bytes, offset + 4).toLong << 32)

  @State(org.openjdk.jmh.annotations.Scope.Benchmark)
  class DecodeState:
    @Param(Array("1000000"))
    var length: Int = 0

    var bytes: Array[Byte] = scala.compiletime.uninitialized
    var doubles: Array[Double] = scala.compiletime.uninitialized
    var ints: Array[Int] = scala.compiletime.uninitialized

    @Setup(Level.Trial)
    def setup(): Unit =
      bytes = new Array[Byte](length * 8)
      doubles = new Array[Double](length)
      ints = new Array[Int](length)
      var index = 0
      while index < length do
        val value = index.toDouble / 8.0
        doubles(index) = value
        ints(index) = index
        val bits = java.lang.Double.doubleToRawLongBits(value)
        var byteIndex = 0
        while byteIndex < 8 do
          bytes(index * 8 + byteIndex) = (bits >>> (byteIndex * 8)).toByte
          byteIndex += 1
        index += 1
