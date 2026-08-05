# Execution, streaming, collection, and ownership

`SourceBinding` and `Frame` are reusable descriptions. A one-source program can
collect or stream directly from its binding without hiding ownership.

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import frame4s.*
import frame4s.fs2.*

type Row = (id: Int)

val source = InMemoryFrameSource.rows[IO, Row](
  Vector((id = 1), (id = 2)),
  batchSize = 1
)

val positive = source.frame.filter(row => row.col("id") > 0)

def collectedIds: IO[Vector[Int]] =
  source.collect(positive).use: table =>
    IO.fromEither(
      table.column("id").left.map(TableReadFailure.apply)
    )

def streamBatchSizes: IO[Vector[Int]] =
  source.stream(positive).map(_.rowCount).compile.toVector
```

Collection returns `Resource[F, Table[S]]`; streaming scopes every emitted
batch. Completion, structured failure, early termination, and cancellation
close cursor and batch leases exactly once. A borrowed input remains owned by
its caller; an owning source closes its input in its source resource.

The same scope gives source failures one route. Acquisition, inspection,
planning, batch-stream, and finalizer failures become
`RuntimeBindingFailure(RuntimeBindingError.Source(id, error))`. The nested
`SourceError` distinguishes open, read, malformed UTF-8, upstream, and close
failures, and the wrapper retains the original throwable as `getCause` without
rendering it in the public message. Cancellation is still cancellation.

Materializing collection and streaming are separate physical contracts. Under
the default `EnginePolicy.Auto`, `collectWithReceipt` selects an admitted
in-process columnar kernel or records a typed reference fallback.
`physicalExplain` describes that collection decision. `streamWithReceipt`
always uses the reference cursor, and `streamPhysicalExplain` describes its
pull and blocking behavior. Use the receipt ADTs, rather than parsing explain
text, when program logic needs the engine or fallback decision.

Source, filter, project, `withColumn`, limit, and `unionAll` plans pull batches
on demand. A satisfied limit stops source pulls, including before the right
branch of a union when the left branch supplies enough rows. Aggregate, join,
and sort are blocking in the semantic runtime: `stream` materializes their
required inputs inside its resource before it emits a result batch. Use
`runtime.streamPhysicalExplain(frame)` to inspect this distinction; it reports
`mode=streaming` or `mode=blocking` and names the blocking operators.

`Table` is a materialized read view, not a second query algebra. Use its typed
`row`, `cell`, and `column` decoders or its bounded `show` methods inside the
resource scope; decoded values are detached immutable Scala values and may be
retained. Keep transformations on `Frame`. Reading the table after its resource
closes returns `TableReadError.Closed`.

When constructing a table from existing batches, ownership is named rather
than inferred. `Table.takeOwnership[S](batches)` transfers every batch to the
table; callers must not reuse or close them afterward, and a failed validation
closes every supplied batch. `Table.retainFrom[S](batches)` instead creates
independent retained views: callers keep ownership of the inputs and may close
them immediately. A failed retain closes only views created by that call.
`Table.fromRows` owns the batches it builds and converts nonfatal iterator or
row-encoding failures into `TableReadError.InputFailure`, including the failed
stage and logical row.

External columnar adapters can call `table.retainBatches` while the table is
open. The result contains independently owned full-batch views in source order;
the caller must close every returned `RecordBatch`. Those views share storage
with the table but may outlive it, and closing them does not invalidate the
table. Acquisition returns `TableReadError.Closed` for a closed table and
releases every partially retained view if a later batch cannot be retained.
Copying through `ColumnArray.copyPhysicalBuffers` remains an explicit adapter
operation; retained views alone are not a detached-buffer or zero-copy export
claim.

Source identity becomes a real choice when one runtime binds several inputs.
Use the explicit-reference constructors in that case:

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import frame4s.*
import frame4s.fs2.*

type Left = (id: Int, label: String)
type Right = (key: Int, amount: Double)
type Joined = (id: Int, label: String, key: Int, amount: Double)

def joined(
    leftRef: SourceRef,
    rightRef: SourceRef
): Resource[IO, MaterializedExecution[Joined]] =
  val left = InMemoryFrameSource.rowsBinding[IO, Left](
    leftRef,
    Vector((id = 1, label = "a"))
  )
  val right = InMemoryFrameSource.rowsBinding[IO, Right](
    rightRef,
    Vector((key = 1, amount = 2.5))
  )
  val query = left.frame.innerJoin(right.frame): (lhs, rhs) =>
    lhs.col("id") === rhs.col("key")

  FrameRuntime
    .resource(left, right)
    .flatMap(_.collectWithReceipt(query))
```

`SourceRef` values are explicit here because they identify inputs in the plan,
pushdown receipt, and errors. frame4s rejects an attempt to combine
single-source convenience bindings and directs the caller to these
constructors. `FrameRuntime` also remains the public route when a caller needs
an execution receipt or engine selection. The convenience route uses
`EnginePolicy.Auto`. Select a stricter policy at the resource boundary:

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import frame4s.fs2.*

type BoundRow = (id: Int, label: String)

def columnarOnly(
    binding: SourceBinding[IO, BoundRow]
): Resource[IO, FrameRuntime[IO]] =
  FrameRuntime.resource(EnginePolicy.RequireColumnar, binding)
```

`ReferenceOnly` bypasses the optimized engine. `RequireColumnar` fails with
`EnginePolicyFailure` when the plan is not admitted. Read `EngineReceipt.engine`
and `EngineReceipt.fallback` as ADTs; `physicalPlan` is explanatory text and
must not be parsed for control flow.

Each source receipt reports accepted and residual pushdown plus
`columnsRead`. The general runtime receipt does not report rows read or peak
retained bytes. Cross-platform streaming tests instrument a one-column source
to prove early emission, exact pull counts, limit short-circuiting, and cleanup;
their byte peak covers only active required-`Int32` value buffers, not total
allocator or heap use.

Next, review the [semantic and explain](semantics-and-explain.md) guarantees
that every backend must preserve.
