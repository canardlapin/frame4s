package frame4s.fs2

import cats.effect.Async
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.kernel.Outcome
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Pull
import fs2.Stream
import scala.NamedTuple
import frame4s.*

final case class ExecutionFailure(error: ExecutionError) extends RuntimeException(error.message)

enum RuntimeBindingError:
  case DuplicateSource(id: SourceId)
  case SingleSourceIdentityInMultiBinding(ids: Vector[SourceId])
  case MissingSource(id: SourceId)
  case ConflictingPlanSchema(id: SourceId, first: Schema, second: Schema)
  case SourceSchema(id: SourceId, expected: Schema, actual: Schema)
  case Source(id: SourceId, error: SourceError)

  def cause: Option[Throwable] = this match
    case Source(_, error) => error.cause
    case _                => None

  def message: String =
    AdapterMessage.bounded:
      this match
        case DuplicateSource(id) => s"source '${id.value}' is bound more than once"
        case SingleSourceIdentityInMultiBinding(ids) =>
          val rendered = ids.map(id => s"'${id.value}'").mkString(", ")
          s"single-source bindings $rendered cannot be combined; construct each source with an explicit SourceRef"
        case MissingSource(id)                        => s"no source is bound for '${id.value}'"
        case ConflictingPlanSchema(id, first, second) =>
          s"source '${id.value}' is used with conflicting schemas $first and $second"
        case SourceSchema(id, expected, actual) =>
          s"source '${id.value}' schema $actual does not match typed schema $expected"
        case Source(id, error) => s"source '${id.value}': ${error.message}"

  override def toString: String = message

final class RuntimeBindingFailure private (val error: RuntimeBindingError)
    extends RuntimeException(error.message, error.cause.orNull)

object RuntimeBindingFailure:
  def apply(error: RuntimeBindingError): RuntimeBindingFailure =
    new RuntimeBindingFailure(error)

  def unapply(failure: RuntimeBindingFailure): Some[RuntimeBindingError] = Some(failure.error)

private def runtimeSourceFailure(reference: SourceRef, error: SourceError): RuntimeBindingFailure =
  RuntimeBindingFailure(RuntimeBindingError.Source(reference.id, error))

private def runtimeSourceEffect[F[_], A](
    reference: SourceRef,
    effect: F[A],
    classify: Throwable => SourceError
)(using F: Async[F]): F[A] =
  AdapterFailureBoundary
    .sourceEffect(effect, classify)
    .handleErrorWith:
      case SourceFailure(error) => F.raiseError(runtimeSourceFailure(reference, error))
      case fatal                => F.raiseError(fatal)

private def runtimeSourceStream[F[_], A](
    reference: SourceRef,
    stream: Stream[F, A],
    classify: Throwable => SourceError
)(using F: Async[F]): Stream[F, A] =
  AdapterFailureBoundary
    .sourceStream(stream, classify)
    .handleErrorWith:
      case SourceFailure(error) => Stream.raiseError[F](runtimeSourceFailure(reference, error))
      case fatal                => Stream.raiseError[F](fatal)

private[fs2] enum BindingIdentity:
  case Explicit
  case SingleSource

/** One pure, typed source description.
  *
  * `frame` is an immutable logical value and acquiring it performs no I/O. The `Resource`
  * description is opened only by `FrameRuntime.resource`, where its inspected schema is checked
  * before any execution cursor is opened.
  */
final class SourceBinding[F[_], S <: NamedTuple.AnyNamedTuple] private[fs2] (
    val reference: SourceRef,
    private[fs2] val acquire: Resource[F, FrameSource[F]],
    private[fs2] val descriptor: SchemaDescriptor[S],
    private[fs2] val identity: BindingIdentity
):
  val frame: Frame[S] = Frame.scan[S](reference)(using descriptor)

  /** Collect through this binding while keeping the owned table inside `Resource`.
    *
    * This expands exactly to `FrameRuntime.resource(this).flatMap(_.collect(query))`.
    */
  def collect[O <: NamedTuple.AnyNamedTuple](
      query: Frame[O]
  )(using F: Async[F]): Resource[F, Table[O]] =
    FrameRuntime.resource(this).flatMap(_.collect(query))

  /** Stream through this binding while keeping runtime and batch ownership inside `Stream`.
    *
    * This expands exactly to
    * `Stream.resource(FrameRuntime.resource(this)).flatMap(_.stream(query))`.
    */
  def stream[O <: NamedTuple.AnyNamedTuple](
      query: Frame[O]
  )(using F: Async[F]): Stream[F, RecordBatch] =
    Stream.resource(FrameRuntime.resource(this)).flatMap(_.stream(query))

  /** Render a bounded detached string and close all owned storage before returning. */
  def render[O <: NamedTuple.AnyNamedTuple](
      query: Frame[O],
      options: TableRenderOptions = TableRenderOptions()
  )(using F: Async[F]): F[String] =
    collect(query).use: table =>
      F.fromEither(table.show(options).left.map(TableReadFailure.apply))

object SourceBinding:
  def apply[
      F[_],
      S <: NamedTuple.AnyNamedTuple,
      A <: FrameSource[F]
  ](
      reference: SourceRef,
      source: Resource[F, A]
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    create(reference, source, BindingIdentity.Explicit)

  private[fs2] def singleSource[
      F[_],
      S <: NamedTuple.AnyNamedTuple,
      A <: FrameSource[F]
  ](
      reference: SourceRef,
      source: Resource[F, A]
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    create(reference, source, BindingIdentity.SingleSource)

  private def create[
      F[_],
      S <: NamedTuple.AnyNamedTuple,
      A <: FrameSource[F]
  ](
      reference: SourceRef,
      source: Resource[F, A],
      identity: BindingIdentity
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    new SourceBinding(
      reference,
      source.map(value => value: FrameSource[F]),
      descriptor,
      identity
    )

final case class SourceExecutionReceipt(
    reference: SourceRef,
    inspection: SourceInspection,
    pushdown: PushdownReceipt
)

enum EngineId:
  case Reference
  case Columnar

enum EnginePolicy:
  case Auto
  case ReferenceOnly
  case RequireColumnar

enum EngineFallbackReason:
  case UnsupportedLogicalShape(nodeName: String)
  case CapabilityResidual(detail: String)
  case ColumnarDeclined(detail: String)

  def message: String = this match
    case UnsupportedLogicalShape(nodeName) => s"unsupported logical shape $nodeName"
    case CapabilityResidual(detail)        => s"columnar capability residual: $detail"
    case ColumnarDeclined(detail)          => s"columnar execution declined: $detail"

enum EnginePolicyError:
  case RequiredEngineUnavailable(required: EngineId, reason: EngineFallbackReason)

  def message: String = this match
    case RequiredEngineUnavailable(required, reason) =>
      s"required engine $required is unavailable: ${reason.message}"

final case class EnginePolicyFailure(error: EnginePolicyError)
    extends RuntimeException(error.message)

/** Which engine answered a materializing collect, and why. */
final case class EngineReceipt(
    engine: EngineId,
    physicalPlan: String,
    fallback: Option[EngineFallbackReason]
)

final case class ExecutionReceipt(
    sources: Vector[SourceExecutionReceipt],
    engine: Option[EngineReceipt] = None
):
  def accepted: Vector[(SourceRef, PushdownFeature)] =
    sources.flatMap(source => source.pushdown.accepted.map(source.reference -> _))

  def residual: Vector[(SourceRef, PushdownFeature)] =
    sources.flatMap(source => source.pushdown.residual.map(source.reference -> _))

final case class StreamedExecution[F[_]](
    receipt: ExecutionReceipt,
    batches: Stream[F, RecordBatch]
)

final case class MaterializedExecution[S <: NamedTuple.AnyNamedTuple](
    receipt: ExecutionReceipt,
    table: Table[S]
)

sealed private trait AcquiredBinding[F[_]]:
  def reference: SourceRef
  def expected: Schema
  def inspection: SourceInspection
  def plan(request: ScanRequest)(using F: Async[F]): F[PlannedBinding[F]]
  def materialize(request: ScanRequest)(using
      F: Async[F]
  ): Resource[F, MaterializedBinding]

final private class TypedAcquiredBinding[
    F[_],
    S <: NamedTuple.AnyNamedTuple
](
    binding: SourceBinding[F, S],
    source: FrameSource[F],
    val inspection: SourceInspection
) extends AcquiredBinding[F]:
  val reference: SourceRef = binding.reference
  val expected: Schema = binding.descriptor.schema

  private def plannedScan(request: ScanRequest)(using F: Async[F]): F[PlannedScan[F]] =
    runtimeSourceEffect(
      reference,
      source.plan(request),
      SourceError.Upstream.apply
    )
      .flatMap:
        case Left(error) =>
          F.raiseError(RuntimeBindingFailure(RuntimeBindingError.Source(reference.id, error)))
        case Right(scan) if scan.schema != expected =>
          F.raiseError(
            RuntimeBindingFailure(
              RuntimeBindingError.SourceSchema(reference.id, expected, scan.schema)
            )
          )
        case Right(scan) => F.pure(scan)

  def plan(request: ScanRequest)(using F: Async[F]): F[PlannedBinding[F]] =
    plannedScan(request).map: scan =>
      val batches = runtimeSourceStream(
        reference,
        scan.batches,
        SourceError.Upstream.apply
      )
      PlannedBinding(
        reference,
        batches,
        SourceExecutionReceipt(reference, inspection, scan.receipt)
      )

  def materialize(
      request: ScanRequest
  )(using F: Async[F]): Resource[F, MaterializedBinding] =
    Resource
      .makeFull[F, (Table[S], PushdownReceipt)](poll =>
        plannedScan(request).flatMap: scan =>
          Ref
            .of[F, Vector[RecordBatch]](Vector.empty)
            .flatMap: retained =>
              def closeRetained: F[Unit] =
                retained.get.flatMap(_.traverse_(batch => F.delay(batch.close())))

              val sourceBatches = runtimeSourceStream(
                reference,
                scan.batches,
                SourceError.Upstream.apply
              )
              val copy = sourceBatches.evalMap: batch =>
                F.uncancelable: _ =>
                  F.delay(batch.slice(0, batch.rowCount))
                    .flatMap:
                      case Right(value) => F.pure(value)
                      case Left(error)  =>
                        F.raiseError(
                          RuntimeBindingFailure(
                            RuntimeBindingError.Source(
                              reference.id,
                              SourceError.Storage(error)
                            )
                          )
                        )
                    .flatTap(value => retained.update(_ :+ value))

              poll(copy.compile.drain)
                .guaranteeCase:
                  case Outcome.Succeeded(_) => F.unit
                  case _                    => closeRetained
                .flatMap: _ =>
                  retained.get.flatMap: batches =>
                    Table.takeOwnership[S](batches)(using binding.descriptor) match
                      case Right(table) => F.pure((table, scan.receipt))
                      case Left(error)  =>
                        F.raiseError[(Table[S], PushdownReceipt)](
                          RuntimeBindingFailure(
                            RuntimeBindingError.Source(
                              reference.id,
                              SourceError.Storage(error)
                            )
                          )
                        )
      )((table, _) => F.delay(table.close()))
      .map: (table, receipt) =>
        MaterializedBinding(
          sources => sources.bind(reference, table),
          SourceExecutionReceipt(reference, inspection, receipt)
        )

final private case class MaterializedBinding(
    bind: ReferenceSources => ReferenceSources,
    receipt: SourceExecutionReceipt
)

final private case class PlannedBinding[F[_]](
    reference: SourceRef,
    batches: Stream[F, RecordBatch],
    receipt: SourceExecutionReceipt
)

final private case class PreparedExecution(
    sources: ReferenceSources,
    receipt: ExecutionReceipt
)

final private case class PreparedStream[F[_]](
    sources: Map[String, PlannedBinding[F]],
    receipt: ExecutionReceipt
)

sealed private trait CollectedBatches[F[_]]:
  def engine: EngineReceipt

final private case class OwnedCollectedBatches[F[_]](
    engine: EngineReceipt,
    batches: Vector[RecordBatch]
) extends CollectedBatches[F]

final private case class ScopedCollectedBatches[F[_]](
    engine: EngineReceipt,
    batches: Stream[F, RecordBatch]
) extends CollectedBatches[F]

/** Effectful execution boundary for pure `Frame` values.
  *
  * The normal public constructor is `FrameRuntime.resource`. It acquires every source once,
  * validates its inspected schema, and owns all execution cursors and materialized batches within
  * the returned `Resource`.
  *
  * `stream` emits scoped `RecordBatch` values. `collect` returns an owned `Table[S]` whose lifetime
  * is the returned resource. Do not retain a batch or table past that scope; decoded rows, cells,
  * and columns are detached Scala values and may outlive it.
  *
  * The `ReferenceSources` overload is retained solely for semantic-oracle fixtures; normal CSV and
  * in-memory workflows bind `FrameSource` values directly.
  */
final class FrameRuntime[F[_]] private (
    private[fs2] val bindings: Vector[AcquiredBinding[F]],
    legacySources: Option[ReferenceSources],
    enginePolicy: EnginePolicy
)(using F: Async[F]):
  private def executionFailure[A](result: Either[ExecutionError, A]): F[A] =
    F.fromEither(result.left.map(ExecutionFailure.apply))

  private def batches(cursor: ExecutionCursor): Stream[F, RecordBatch] =
    Stream
      .resource:
        Resource.make(
          F.delay(cursor.nextBatch()).flatMap(executionFailure)
        ):
          case Some(batch) => F.delay(batch.close())
          case None        => F.unit
      .repeat
      .unNoneTerminate

  private def referenceBatches(
      frame: Frame[?],
      sources: ReferenceSources
  ): Stream[F, RecordBatch] =
    Stream
      .bracket(
        F.defer(executionFailure(ReferenceInterpreter.prepare(frame.plan, sources).open()))
      )(cursor => F.delay(cursor.close()))
      .flatMap(batches)

  private def scopedBatch(acquire: F[RecordBatch]): Stream[F, RecordBatch] =
    Stream.bracket(acquire)(batch => F.delay(batch.close())).flatMap(Stream.emit)

  private def transformBatches(
      input: Stream[F, RecordBatch]
  )(
      transform: RecordBatch => Either[ExecutionError, RecordBatch]
  ): Stream[F, RecordBatch] =
    input.flatMap: batch =>
      scopedBatch(F.delay(transform(batch)).flatMap(executionFailure))

  private def limitBatches(
      input: Stream[F, RecordBatch],
      requested: Int
  ): Stream[F, RecordBatch] =
    def loop(
        remainingInput: Stream[F, RecordBatch],
        remainingRows: Long
    ): Pull[F, RecordBatch, Unit] =
      if remainingRows <= 0L then Pull.done
      else
        remainingInput.pull.uncons1.flatMap:
          case None                                                          => Pull.done
          case Some((batch, tail)) if batch.rowCount.toLong <= remainingRows =>
            Pull.output1(batch) >> loop(tail, remainingRows - batch.rowCount.toLong)
          case Some((batch, _)) =>
            val sliced = F
              .delay(batch.slice(0, remainingRows.toInt).leftMap(ExecutionError.Storage.apply))
              .flatMap(executionFailure)
            scopedBatch(sliced).pull.echo

    loop(input, requested.toLong).stream

  private def incrementalBatches(
      plan: LogicalPlan,
      sources: Map[String, PlannedBinding[F]]
  ): Stream[F, RecordBatch] =
    plan match
      case LogicalPlan.Source(reference, _) =>
        sources.get(reference.id.value) match
          case Some(source) => source.batches
          case None         =>
            Stream.raiseError[F](
              RuntimeBindingFailure(RuntimeBindingError.MissingSource(reference.id))
            )
      case LogicalPlan.Project(input, expressions, schema) =>
        transformBatches(incrementalBatches(input, sources)): batch =>
          ReferenceInterpreter.projectBatch(batch, expressions, schema)
      case LogicalPlan.Filter(input, predicate, schema) =>
        transformBatches(incrementalBatches(input, sources)): batch =>
          ReferenceInterpreter.filterBatch(batch, predicate, schema)
      case LogicalPlan.Limit(input, count, _) =>
        limitBatches(incrementalBatches(input, sources), count)
      case LogicalPlan.UnionAll(left, right, _) =>
        incrementalBatches(left, sources) ++ incrementalBatches(right, sources)
      case other =>
        Stream.raiseError[F](
          ExecutionFailure(ExecutionError.UnsupportedNode(s"incremental ${other.nodeName}"))
        )

  /** Batches for a materializing collect, from the optimized engine when it will take the plan.
    *
    * Only `collect` routes here. `stream` deliberately stays on the reference cursor: the optimized
    * engine materializes its whole result before yielding anything, so promoting it there would
    * quietly turn an incremental stream into materialize-then-emit, which is a change in what
    * callers are promised rather than an optimization.
    *
    * When the engine has no kernel for the plan or hits a capability residual it falls back to the
    * reference interpreter in-engine, so the plan executes exactly once and the receipt names the
    * reason. The residual `Left` channel — execution errors and results with no batch form —
    * re-runs the reference path here, which stays the definition of correct behaviour.
    */
  private def collectBatches(
      frame: Frame[?],
      sources: ReferenceSources
  ): F[CollectedBatches[F]] =
    def reference(
        fallback: Option[EngineFallbackReason]
    ): CollectedBatches[F] =
      ScopedCollectedBatches(
        EngineReceipt(
          EngineId.Reference,
          ReferenceInterpreter.prepare(frame.plan, sources).physicalExplain,
          fallback
        ),
        referenceBatches(frame, sources)
      )

    def reject(reason: EngineFallbackReason): F[CollectedBatches[F]] =
      F.raiseError(
        EnginePolicyFailure(
          EnginePolicyError.RequiredEngineUnavailable(EngineId.Columnar, reason)
        )
      )

    def typedFallback(collected: ColumnarCollect): Option[EngineFallbackReason] =
      (collected.receipt.fallback, collected.receipt.fallbackKind) match
        case (Some(_), Some(ColumnarFallbackKind.UnsupportedLogicalShape)) =>
          Some(EngineFallbackReason.UnsupportedLogicalShape(frame.plan.nodeName))
        case (Some(reason), Some(ColumnarFallbackKind.CapabilityResidual)) =>
          Some(EngineFallbackReason.CapabilityResidual(reason))
        case (Some(reason), None) => Some(EngineFallbackReason.ColumnarDeclined(reason))
        case _                    => None

    enginePolicy match
      case EnginePolicy.ReferenceOnly => F.pure(reference(None))
      case EnginePolicy.Auto          =>
        F.delay(ColumnarInterpreter.collect(frame.plan, sources))
          .flatMap:
            case Right(collected) =>
              val fallback = typedFallback(collected)
              val engine = fallback.fold(EngineId.Columnar)(_ => EngineId.Reference)
              F.pure(
                OwnedCollectedBatches(
                  EngineReceipt(engine, collected.receipt.physicalPlan, fallback),
                  collected.batches
                )
              )
            case Left(reason) =>
              val fallback = EngineFallbackReason.ColumnarDeclined(reason)
              F.pure(reference(Some(fallback)))
      case EnginePolicy.RequireColumnar =>
        F.delay(ColumnarInterpreter.collectRequired(frame.plan, sources))
          .flatMap:
            case Right(collected) =>
              F.pure(
                OwnedCollectedBatches(
                  EngineReceipt(EngineId.Columnar, collected.receipt.physicalPlan, None),
                  collected.batches
                )
              )
            case Left(RequiredColumnarFailure.UnsupportedLogicalShape(nodeName)) =>
              reject(EngineFallbackReason.UnsupportedLogicalShape(nodeName))
            case Left(RequiredColumnarFailure.CapabilityResidual(detail)) =>
              reject(EngineFallbackReason.CapabilityResidual(detail))
            case Left(RequiredColumnarFailure.ResultMaterialization(detail)) =>
              reject(EngineFallbackReason.ColumnarDeclined(detail))
            case Left(RequiredColumnarFailure.Execution(error)) =>
              F.raiseError(ExecutionFailure(error))

  private def prepare(frame: Frame[?]): Resource[F, PreparedExecution] =
    legacySources match
      case Some(sources) =>
        Resource.pure(PreparedExecution(sources, ExecutionReceipt(Vector.empty)))
      case None =>
        Resource
          .eval(F.fromEither(planSources(frame.plan).leftMap(RuntimeBindingFailure.apply)))
          .flatMap: required =>
            val available = bindings.map(binding => binding.reference.id.value -> binding).toMap
            required.foldLeft(
              Resource.pure[F, PreparedExecution](
                PreparedExecution(ReferenceSources.empty, ExecutionReceipt(Vector.empty))
              )
            ):
              case (prepared, source) =>
                prepared.flatMap: current =>
                  available.get(source.reference.id.value) match
                    case None =>
                      Resource.eval(
                        F.raiseError[PreparedExecution](
                          RuntimeBindingFailure(
                            RuntimeBindingError.MissingSource(source.reference.id)
                          )
                        )
                      )
                    case Some(binding) =>
                      val request = source.request
                      binding
                        .materialize(request)
                        .map: materialized =>
                          PreparedExecution(
                            materialized.bind(current.sources),
                            ExecutionReceipt(current.receipt.sources :+ materialized.receipt)
                          )

  private def prepareStream(frame: Frame[?]): Resource[F, PreparedStream[F]] =
    Resource
      .eval(F.fromEither(planSources(frame.plan).leftMap(RuntimeBindingFailure.apply)))
      .flatMap: required =>
        val available = bindings.map(binding => binding.reference.id.value -> binding).toMap
        Resource.eval:
          required.traverse: source =>
            available.get(source.reference.id.value) match
              case None =>
                F.raiseError[PlannedBinding[F]](
                  RuntimeBindingFailure(RuntimeBindingError.MissingSource(source.reference.id))
                )
              case Some(binding) => binding.plan(source.request)
      .map: planned =>
        PreparedStream(
          planned.map(source => source.reference.id.value -> source).toMap,
          ExecutionReceipt(planned.map(_.receipt))
        )

  /** Execute as a re-runnable stream and expose the accepted/residual pushdown receipt.
    *
    * Each acquisition opens a new invocation. The stream and its batches must be consumed inside
    * the returned resource.
    */
  def streamWithReceipt[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S]
  ): Resource[F, StreamedExecution[F]] =
    val shape = ReferenceInterpreter.prepare(frame.plan, ReferenceSources.empty).shape
    if legacySources.isDefined || !shape.streaming then
      prepare(frame).map: prepared =>
        StreamedExecution(
          prepared.receipt,
          referenceBatches(frame, prepared.sources)
        )
    else
      prepareStream(frame).map: prepared =>
        StreamedExecution(
          prepared.receipt,
          incrementalBatches(frame.plan, prepared.sources)
        )

  /** Convenience streaming path. Use `streamWithReceipt` when the negotiation receipt is needed. */
  def stream[S <: NamedTuple.AnyNamedTuple](frame: Frame[S]): Stream[F, RecordBatch] =
    Stream.resource(streamWithReceipt(frame)).flatMap(_.batches)

  /** Collect within a `Resource` and expose source pushdown negotiation.
    *
    * The returned table is closed when the resource exits. Decode values inside the scope when they
    * must be retained.
    */
  def collectWithReceipt[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S]
  ): Resource[F, MaterializedExecution[S]] =
    prepare(frame).flatMap: prepared =>
      collectPrepared(frame, prepared.sources).map: (engine, table) =>
        MaterializedExecution(prepared.receipt.copy(engine = Some(engine)), table)

  /** Collect into an owned typed table scoped by the returned `Resource`. */
  def collect[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S]
  ): Resource[F, Table[S]] =
    collectWithReceipt(frame).map(_.table)

  private def collectPrepared[S <: NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  ): Resource[F, (EngineReceipt, Table[S])] =
    Resource.makeFull[F, (EngineReceipt, Table[S])](poll =>
      Ref
        .of[F, Vector[RecordBatch]](Vector.empty)
        .flatMap: retained =>
          def closeRetained: F[Unit] =
            retained.get.flatMap(_.traverse_(batch => F.delay(batch.close())))

          collectBatches(frame, sources).flatMap:
            case OwnedCollectedBatches(engine, batches) =>
              Table.takeOwnership[S](batches)(using frame.descriptor) match
                case Right(table) => F.pure((engine, table))
                case Left(error)  =>
                  F.raiseError[(EngineReceipt, Table[S])](
                    ExecutionFailure(ExecutionError.Storage(error))
                  )
            case ScopedCollectedBatches(engine, engineBatches) =>
              val copyBatches = engineBatches.evalMap: batch =>
                F.uncancelable: _ =>
                  F.delay(batch.slice(0, batch.rowCount))
                    .flatMap(result =>
                      executionFailure(result.left.map(ExecutionError.Storage.apply))
                    )
                    .flatTap(copy => retained.update(_ :+ copy))
              poll(copyBatches.compile.drain)
                .guaranteeCase:
                  case Outcome.Succeeded(_) => F.unit
                  case _                    => closeRetained
                .flatMap: _ =>
                  retained.get.flatMap: batches =>
                    Table.takeOwnership[S](batches)(using frame.descriptor) match
                      case Right(table) => F.pure((engine, table))
                      case Left(error)  =>
                        F.raiseError[(EngineReceipt, Table[S])](
                          ExecutionFailure(ExecutionError.Storage(error))
                        )
    )(acquired => F.delay(acquired(1).close()))

  /** The physical reference route used by `stream`, including any blocking operators. */
  def streamPhysicalExplain[S <: NamedTuple.AnyNamedTuple](frame: Frame[S]): String =
    ReferenceInterpreter.prepare(frame.plan, ReferenceSources.empty).physicalExplain

  /** The physical plan a materializing collect would execute, including the engine decision.
    *
    * Plans the optimized engine declines report the reference plan they actually run on. Streaming
    * executions always use the reference cursor regardless of what this reports.
    */
  def physicalExplain[S <: NamedTuple.AnyNamedTuple](frame: Frame[S]): String =
    enginePolicy match
      case EnginePolicy.ReferenceOnly =>
        ReferenceInterpreter.prepare(frame.plan, ReferenceSources.empty).physicalExplain
      case EnginePolicy.Auto | EnginePolicy.RequireColumnar =>
        val columnar = ColumnarInterpreter.prepare(frame.plan, ReferenceSources.empty)
        try
          columnar.kernelName match
            case Some(_)                                   => columnar.physicalExplain
            case None if enginePolicy == EnginePolicy.Auto =>
              ReferenceInterpreter.prepare(frame.plan, ReferenceSources.empty).physicalExplain
            case None =>
              val reason = EngineFallbackReason.UnsupportedLogicalShape(frame.plan.nodeName)
              s"ColumnarExecution(operator=Unavailable, policy=RequireColumnar, reason=${reason.message})"
        finally columnar.close()

final private case class RequiredSource(
    reference: SourceRef,
    schema: Schema,
    request: ScanRequest
)

private def planSources(
    plan: LogicalPlan
): Either[RuntimeBindingError, Vector[RequiredSource]] =
  def loop(current: LogicalPlan): Vector[RequiredSource] =
    current match
      case LogicalPlan.Limit(LogicalPlan.Source(reference, schema), count, _) =>
        Vector(
          RequiredSource(
            reference,
            schema,
            ScanRequest(limit = Some(count), batchSize = Some(1024))
          )
        )
      case LogicalPlan.Filter(
            LogicalPlan.Source(reference, schema),
            predicate,
            _
          ) =>
        Vector(
          RequiredSource(
            reference,
            schema,
            ScanRequest(
              predicate = portablePredicate(predicate),
              batchSize = Some(1024)
            )
          )
        )
      case LogicalPlan.Source(reference, schema) =>
        Vector(
          RequiredSource(
            reference,
            schema,
            ScanRequest(batchSize = Some(1024))
          )
        )
      case other => other.children.flatMap(loop)

  loop(plan)
    .foldLeft[Either[RuntimeBindingError, Vector[RequiredSource]]](Right(Vector.empty)):
      case (result, source) =>
        result.flatMap: accumulated =>
          accumulated.find(_.reference.id == source.reference.id) match
            case None                                               => Right(accumulated :+ source)
            case Some(existing) if existing.schema != source.schema =>
              Left(
                RuntimeBindingError.ConflictingPlanSchema(
                  source.reference.id,
                  existing.schema,
                  source.schema
                )
              )
            case Some(existing) =>
              val mergedLimit = (existing.request.limit, source.request.limit) match
                case (Some(left), Some(right)) => Some(math.max(left, right))
                case _                         => None
              val mergedPredicate =
                if existing.request.predicate == source.request.predicate then
                  existing.request.predicate
                else None
              val merged = existing.copy(
                request = existing.request.copy(
                  predicate = mergedPredicate,
                  limit = mergedLimit
                )
              )
              Right(accumulated.updated(accumulated.indexOf(existing), merged))

private def portablePredicate(expression: ResolvedExpr): Option[PortablePredicate] =
  def columnLiteral(
      left: ResolvedExpr,
      right: ResolvedExpr
  ): Option[(String, LiteralValue, Boolean)] =
    (left.node, right.node) match
      case (ExprNode.Column(InputRef.Current, _, _, name, _), ExprNode.Literal(value)) =>
        Some((name, value, false))
      case (ExprNode.Literal(value), ExprNode.Column(InputRef.Current, _, _, name, _)) =>
        Some((name, value, true))
      case _ => None

  def comparison(
      operator: BinaryOperator,
      column: String,
      value: LiteralValue,
      reversed: Boolean
  ): Option[PortablePredicate] =
    val actual =
      if !reversed then operator
      else
        operator match
          case BinaryOperator.LessThan           => BinaryOperator.GreaterThan
          case BinaryOperator.LessThanOrEqual    => BinaryOperator.GreaterThanOrEqual
          case BinaryOperator.GreaterThan        => BinaryOperator.LessThan
          case BinaryOperator.GreaterThanOrEqual => BinaryOperator.LessThanOrEqual
          case other                             => other
    actual match
      case BinaryOperator.Equal           => Some(PortablePredicate.Equal(column, value))
      case BinaryOperator.NotEqual        => Some(PortablePredicate.NotEqual(column, value))
      case BinaryOperator.LessThan        => Some(PortablePredicate.LessThan(column, value))
      case BinaryOperator.LessThanOrEqual =>
        Some(PortablePredicate.LessThanOrEqual(column, value))
      case BinaryOperator.GreaterThan        => Some(PortablePredicate.GreaterThan(column, value))
      case BinaryOperator.GreaterThanOrEqual =>
        Some(PortablePredicate.GreaterThanOrEqual(column, value))
      case _ => None

  expression.node match
    case ExprNode.Unary(
          UnaryOperator.IsNull,
          ResolvedExpr(_, _, _, ExprNode.Column(InputRef.Current, _, _, name, _))
        ) =>
      Some(PortablePredicate.IsNull(name))
    case ExprNode.Unary(UnaryOperator.IsTrue, input) =>
      portablePredicate(input)
    case ExprNode.Binary(BinaryOperator.And, left, right) =>
      (portablePredicate(left), portablePredicate(right)).mapN(PortablePredicate.And.apply)
    case ExprNode.Binary(BinaryOperator.Or, left, right) =>
      (portablePredicate(left), portablePredicate(right)).mapN(PortablePredicate.Or.apply)
    case ExprNode.Binary(operator, left, right) =>
      columnLiteral(left, right).flatMap: (column, value, reversed) =>
        comparison(operator, column, value, reversed)
    case _ => None

object FrameRuntime:
  /** Acquire, inspect, and exactly validate all typed sources in one invocation scope.
    *
    * Duplicate source ids and schema mismatches raise [[RuntimeBindingFailure]] in `F`. Query
    * construction errors remain in [[FrameError]] before this boundary.
    */
  def resource[F[_]](
      first: SourceBinding[F, ? <: NamedTuple.AnyNamedTuple],
      rest: SourceBinding[F, ? <: NamedTuple.AnyNamedTuple]*
  )(using F: Async[F]): Resource[F, FrameRuntime[F]] =
    val requested = (first +: rest).toVector
    val singleSourceIds =
      requested.flatMap: binding =>
        binding.identity match
          case BindingIdentity.Explicit     => Vector.empty
          case BindingIdentity.SingleSource => Vector(binding.reference.id)
    val duplicate =
      requested
        .groupBy(_.reference.id.value)
        .collectFirst {
          case (_, values) if values.lengthCompare(1) > 0 => values.head.reference.id
        }

    Resource
      .eval:
        if requested.lengthCompare(1) > 0 && singleSourceIds.nonEmpty then
          F.raiseError[Unit](
            RuntimeBindingFailure(
              RuntimeBindingError.SingleSourceIdentityInMultiBinding(singleSourceIds)
            )
          )
        else
          duplicate match
            case Some(id) =>
              F.raiseError[Unit](
                RuntimeBindingFailure(RuntimeBindingError.DuplicateSource(id))
              )
            case None => F.unit
      .flatMap: _ =>
        requested
          .foldLeft(
            Resource.pure[F, Vector[AcquiredBinding[F]]](Vector.empty)
          ):
            case (acquired, binding) =>
              acquired.flatMap: current =>
                val acquiredSource = Resource
                  .makeCase(
                    runtimeSourceEffect(
                      binding.reference,
                      binding.acquire.allocatedCase,
                      SourceError.Open.apply
                    )
                  ): (allocated, exitCase) =>
                    runtimeSourceEffect(
                      binding.reference,
                      allocated(1)(exitCase),
                      SourceError.Close.apply
                    )
                  .map(_(0))
                acquiredSource.flatMap: source =>
                  Resource
                    .eval(
                      runtimeSourceEffect(
                        binding.reference,
                        source.inspect,
                        SourceError.Open.apply
                      )
                    )
                    .flatMap:
                      case Left(error) =>
                        Resource.eval(
                          F.raiseError[Vector[AcquiredBinding[F]]](
                            RuntimeBindingFailure(
                              RuntimeBindingError.Source(binding.reference.id, error)
                            )
                          )
                        )
                      case Right(inspection) if inspection.schema != binding.descriptor.schema =>
                        Resource.eval(
                          F.raiseError[Vector[AcquiredBinding[F]]](
                            RuntimeBindingFailure(
                              RuntimeBindingError.SourceSchema(
                                binding.reference.id,
                                binding.descriptor.schema,
                                inspection.schema
                              )
                            )
                          )
                        )
                      case Right(inspection) =>
                        Resource.pure(
                          current :+ typedAcquired(binding, source, inspection)
                        )
          .map(acquired => new FrameRuntime(acquired, None, EnginePolicy.Auto))

  /** Acquire sources with an explicit materializing-engine policy. */
  def resource[F[_]](
      policy: EnginePolicy,
      first: SourceBinding[F, ? <: NamedTuple.AnyNamedTuple],
      rest: SourceBinding[F, ? <: NamedTuple.AnyNamedTuple]*
  )(using F: Async[F]): Resource[F, FrameRuntime[F]] =
    resource(first, rest*).map(runtime => new FrameRuntime(runtime.bindings, None, policy))

  private def typedAcquired[
      F[_],
      S <: NamedTuple.AnyNamedTuple
  ](
      binding: SourceBinding[F, S],
      source: FrameSource[F],
      inspection: SourceInspection
  ): AcquiredBinding[F] =
    new TypedAcquiredBinding(binding, source, inspection)

  /** Semantic-oracle fixture constructor. Normal user workflows should use `resource`. */
  def apply[F[_]: Async](sources: ReferenceSources): FrameRuntime[F] =
    new FrameRuntime(Vector.empty, Some(sources), EnginePolicy.Auto)

  /** Semantic-oracle fixture constructor with an explicit materializing-engine policy. */
  def apply[F[_]: Async](sources: ReferenceSources, policy: EnginePolicy): FrameRuntime[F] =
    new FrameRuntime(Vector.empty, Some(sources), policy)
