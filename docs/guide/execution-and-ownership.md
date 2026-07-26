# Execution, streaming, collection, and ownership

`SourceBinding` and `Frame` are reusable descriptions. Each
`FrameRuntime.resource` acquisition validates and owns one invocation.

```scala mdoc:compile-only
import cats.effect.IO
import frame4s.*
import frame4s.fs2.*

type Row = (id: Int)

def binding(reference: SourceRef): SourceBinding[IO, Row] =
  InMemoryFrameSource.rowsBinding[IO, Row](
    reference,
    Vector((id = 1), (id = 2)),
    batchSize = 1
  )

def streamBatchSizes(source: SourceBinding[IO, Row]): IO[Vector[Int]] =
  FrameRuntime
    .resource(source)
    .use(runtime =>
      runtime
        .stream(source.frame)
        .map(_.rowCount)
        .compile
        .toVector
    )
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

Next, review the [semantic and explain](semantics-and-explain.md) guarantees
that every backend must preserve.
