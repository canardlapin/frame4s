# Read and write Arrow IPC on the JVM

Arrow IPC is optional and JVM-only. Add its dedicated artifact; it brings in
`frame4s-fs2` transitively:

```scala
libraryDependencies += "io.github.canardlapin" %% "frame4s-arrow" % "@VERSION@"
```

Start Arrow programs with this JVM option:

```text
--add-opens=java.base/java.nio=ALL-UNNAMED
```

The base `frame4s-fs2` JVM and Scala.js artifacts contain no Arrow dependency
and do not need that option.

The following public surface writes owned frame4s batches and reads the
resulting immutable byte snapshot. Both sides remain explicitly scoped:

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import frame4s.*
import frame4s.fs2.*

type Event = (id: Int, label: String, at: TimestampMicros)

val schema = summon[SchemaDescriptor[Event]].schema
val events = InMemoryFrameSource.rows[IO, Event](
  Vector(
    (id = 1, label = "arrow", at = TimestampMicros(1000L)),
    (id = 2, label = "東京", at = TimestampMicros(2000L))
  ),
  batchSize = 1
)

def encoded: IO[Either[SinkError, ArrowIpcWriteResult]] =
  new ArrowIpcFrameSink[IO]().write(
    schema,
    events.stream(events.frame)
  )

def source(bytes: Array[Byte]): Resource[IO, ArrowIpcFrameSource[IO]] =
  ArrowIpcFrameSource.resource[IO](bytes)
```

Constructing `ArrowIpcFrameSource.resource` immediately clones `bytes`.
Mutating the caller's array afterward cannot change what acquisition decodes.
Malformed input and unsupported schemas raise `SourceFailure` with a structured
`SourceError`; sink failures remain in `Either[SinkError, ...]`. Decoded batches
belong to the source resource and must not escape its scope.

In `0.1`, `TimestampMicros` is the complete typed timestamp contract. Arrow
fields must therefore be timezone-free and use microsecond precision. Second,
millisecond, nanosecond, and timezone-bearing timestamp fields are rejected
explicitly. No unit is silently rescaled or exposed as an unbindable dynamic
schema.

The release rehearsal compiles and runs a clean external Arrow consumer against
the staged `frame4s-arrow` coordinate with the same JVM option shown above.
