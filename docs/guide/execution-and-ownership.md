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

`Table` is a materialized read view, not a second query algebra. Use its typed
`row`, `cell`, and `column` decoders or its bounded `show` methods inside the
resource scope; decoded values are detached immutable Scala values and may be
retained. Keep transformations on `Frame`. Reading the table after its resource
closes returns `TableReadError.Closed`.

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
an execution receipt or backend selection.

Next, review the [semantic and explain](semantics-and-explain.md) guarantees
that every backend must preserve.
