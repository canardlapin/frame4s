package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

private[benchmarks] object ColumnarBenchmarkSupport:
  /** Execute and retain the detached result without walking its cells.
    *
    * Cross-runtime dataframe courts time eager result construction on the comparator side. Keeping
    * this path separate from `checksum` prevents the frame4s column from silently including a
    * serial validation scan that the comparator does not perform. The blackhole consumes the result
    * object before it is closed so JMH cannot eliminate construction.
    */
  def executionOnly(execution: ColumnarExecution, blackhole: Blackhole): Long =
    val run = execution.run()
    if run.receipt.fallback.nonEmpty then
      throw new IllegalStateException(
        s"benchmark unexpectedly fell back: ${run.receipt.fallback.getOrElse("unknown")}"
      )
    val result = run.result.fold(error => throw new IllegalStateException(error.message), identity)
    try
      blackhole.consume(result)
      result.rowCount
    finally result.close()

  def checksum(execution: ColumnarExecution): Long =
    val run = execution.run()
    if run.receipt.fallback.nonEmpty then
      throw new IllegalStateException(
        s"benchmark unexpectedly fell back: ${run.receipt.fallback.getOrElse("unknown")}"
      )
    val result = run.result.fold(error => throw new IllegalStateException(error.message), identity)
    try result.checksum.fold(error => throw new IllegalStateException(error.message), identity)
    finally result.close()

  def columnChecksum(column: ColumnArray): Long =
    ColumnarInterpreter
      .columnChecksum(column)
      .fold(error => throw new IllegalStateException(error.message), identity)

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class ColumnarBenchmarks:
  import ColumnarBenchmarkSupport.*

  @Benchmark
  def primitiveScan(state: ReferenceState): Long =
    checksum(state.columnarPrimitive)

  @Benchmark
  def nullableScan(state: ReferenceState): Long =
    checksum(state.columnarNullable)

  @Benchmark
  def utf8Scan(state: ReferenceState): Long =
    columnChecksum(state.utf8Column)

  @Benchmark
  def dictionaryScan(state: ReferenceState): Long =
    columnChecksum(state.dictionaryColumn)

  @Benchmark
  def filter(state: ReferenceState): Long =
    checksum(state.columnarFilter)

  @Benchmark
  def fusedFilterProjectArithmetic(state: ReferenceState): Long =
    checksum(state.columnarFused)

  @Benchmark
  def groupedLowCardinality(state: ReferenceState): Long =
    checksum(state.columnarGroupLow)

  @Benchmark
  def groupedLowCardinalitySumOnly(state: ReferenceState): Long =
    checksum(state.columnarGroupLowSum)

  @Benchmark
  def groupedHighCardinality(state: ReferenceState): Long =
    checksum(state.columnarGroupHigh)

  @Benchmark
  def joinOneToOne(state: ReferenceState): Long =
    checksum(state.columnarJoinOne)

  @Benchmark
  def joinOneToOneExecutionOnly(state: ReferenceState, blackhole: Blackhole): Long =
    executionOnly(state.columnarJoinOne, blackhole)

  @Benchmark
  def joinOneToMany(state: ReferenceState): Long =
    checksum(state.columnarJoinMany)

  @Benchmark
  def joinOneToManyExecutionOnly(state: ReferenceState, blackhole: Blackhole): Long =
    executionOnly(state.columnarJoinMany, blackhole)

  @Benchmark
  def joinSparse(state: ReferenceState): Long =
    checksum(state.columnarJoinSparse)

  @Benchmark
  def joinSparseExecutionOnly(state: ReferenceState, blackhole: Blackhole): Long =
    executionOnly(state.columnarJoinSparse, blackhole)

  @Benchmark
  def joinSkewed(state: ReferenceState): Long =
    checksum(state.columnarJoinSkew)

  @Benchmark
  def joinSkewedExecutionOnly(state: ReferenceState, blackhole: Blackhole): Long =
    executionOnly(state.columnarJoinSkew, blackhole)

  @Benchmark
  def distinctLowCardinality(state: ReferenceState): Long =
    checksum(state.columnarDistinctLow)

  @Benchmark
  def semiJoinSparse(state: ReferenceState): Long =
    checksum(state.columnarSemiSparse)

  @Benchmark
  def semiJoinSparseExecutionOnly(state: ReferenceState, blackhole: Blackhole): Long =
    executionOnly(state.columnarSemiSparse, blackhole)

  @Benchmark
  def antiJoinSparse(state: ReferenceState): Long =
    checksum(state.columnarAntiSparse)

  @Benchmark
  def antiJoinSparseExecutionOnly(state: ReferenceState, blackhole: Blackhole): Long =
    executionOnly(state.columnarAntiSparse, blackhole)

  @Benchmark
  def unionAll(state: ReferenceState): Long =
    checksum(state.columnarUnionAll)
