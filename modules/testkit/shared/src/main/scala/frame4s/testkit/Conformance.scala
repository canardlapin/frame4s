package frame4s.testkit

import frame4s.*
import scala.NamedTuple

/** A detached result suitable for comparing execution backends after owned buffers are closed. */
final case class ObservedTable(
    schema: Schema,
    rows: Vector[Vector[ScalarValue]],
    order: OrderGuarantee
):
  def equivalentTo(other: ObservedTable): Boolean =
    schema == other.schema &&
      order == other.order &&
      rows.map(_.map(ObservedTable.scalarFingerprint)) ==
      other.rows.map(_.map(ObservedTable.scalarFingerprint))

object ObservedTable:
  private[testkit] def scalarFingerprint(value: ScalarValue): String = value match
    case ScalarValue.Null            => "null"
    case ScalarValue.Bool(actual)    => s"bool:$actual"
    case ScalarValue.Int32(actual)   => s"i32:$actual"
    case ScalarValue.Int64(actual)   => s"i64:$actual"
    case ScalarValue.Float32(actual) =>
      if actual.isNaN then "f32:nan"
      else s"f32:${java.lang.Float.floatToRawIntBits(actual)}"
    case ScalarValue.Float64(actual) =>
      if actual.isNaN then "f64:nan"
      else s"f64:${java.lang.Double.doubleToRawLongBits(actual)}"
    case ScalarValue.Utf8(actual)            => s"utf8:$actual"
    case ScalarValue.Timestamp(actual, unit) => s"timestamp:$unit:$actual"

  private[testkit] def detach[S <: NamedTuple.AnyNamedTuple](
      table: Table[S],
      order: OrderGuarantee
  ): Either[StorageError, ObservedTable] =
    val output = Vector.newBuilder[Vector[ScalarValue]]
    var error: Option[StorageError] = None
    val batchIterator = table.batches.iterator
    while batchIterator.hasNext && error.isEmpty do
      val batch = batchIterator.next()
      var rowIndex = 0
      while rowIndex < batch.rowCount && error.isEmpty do
        val row = Vector.newBuilder[ScalarValue]
        val columnIterator = batch.columns.iterator
        while columnIterator.hasNext && error.isEmpty do
          columnIterator.next().scalar(rowIndex) match
            case Right(value) => row += value
            case Left(value)  => error = Some(value)
        if error.isEmpty then output += row.result()
        rowIndex += 1
    error match
      case Some(value) => Left(value)
      case None        => Right(ObservedTable(table.schema, output.result(), order))

enum BackendCapability:
  case Scan
  case Project
  case Filter
  case Aggregate
  case Join
  case Sort
  case Limit
  case Union

final case class BackendReceipt(
    backend: String,
    physicalPlan: String,
    fallback: Option[String]
)

enum BackendAttempt:
  case Completed(
      result: Either[ExecutionError, ObservedTable],
      receipt: BackendReceipt
  )
  case Residual(
      capability: BackendCapability,
      reason: String,
      receipt: BackendReceipt
  )

/** Deliberately small backend boundary used by the reusable oracle-conformance laws. */
trait ExecutionBackend:
  def name: String

  def execute[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): BackendAttempt

object ReferenceBackend extends ExecutionBackend:
  val name = "reference"

  def execute[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): BackendAttempt =
    val execution = ReferenceInterpreter.prepare(frame.plan, sources)
    val result = execution
      .collect[S]
      .flatMap: table =>
        try ObservedTable.detach(table, frame.plan.order).left.map(ExecutionError.Storage.apply)
        finally table.close()
    BackendAttempt.Completed(
      result,
      BackendReceipt(name, execution.physicalExplain, fallback = None)
    )

/** Candidate backend used to admit optimized kernels against the semantic oracle. */
object ColumnarBackend extends ExecutionBackend:
  val name = "columnar"

  def execute[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): BackendAttempt =
    val run = ColumnarInterpreter.prepare(frame.plan, sources).run()
    val result = run.result.flatMap: table =>
      try
        table.rows.map: rows =>
          ObservedTable(table.schema, rows, table.order)
      finally table.close()
    BackendAttempt.Completed(
      result,
      BackendReceipt(name, run.receipt.physicalPlan, run.receipt.fallback)
    )

enum BackendComparison:
  case Equivalent(oracle: BackendReceipt, candidate: BackendReceipt)
  case Mismatch(
      oracle: Either[ExecutionError, ObservedTable],
      candidate: Either[ExecutionError, ObservedTable],
      oracleReceipt: BackendReceipt,
      candidateReceipt: BackendReceipt
  )
  case Residual(
      capability: BackendCapability,
      reason: String,
      receipt: BackendReceipt
  )

object BackendConformance:
  def compare[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources,
      candidate: ExecutionBackend
  )(using SchemaDescriptor[S]): BackendComparison =
    (ReferenceBackend.execute(frame, sources), candidate.execute(frame, sources)) match
      case (
            BackendAttempt.Completed(oracle, oracleReceipt),
            BackendAttempt.Completed(actual, candidateReceipt)
          ) =>
        val same = (oracle, actual) match
          case (Left(left), Left(right))   => left == right
          case (Right(left), Right(right)) => left.equivalentTo(right)
          case _                           => false
        if same then BackendComparison.Equivalent(oracleReceipt, candidateReceipt)
        else BackendComparison.Mismatch(oracle, actual, oracleReceipt, candidateReceipt)
      case (_, BackendAttempt.Residual(capability, reason, receipt)) =>
        BackendComparison.Residual(capability, reason, receipt)
      case (BackendAttempt.Residual(capability, reason, receipt), _) =>
        BackendComparison.Residual(capability, reason, receipt)
