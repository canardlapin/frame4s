# Dynamic inspection and exact typed binding

A dynamic source may be inspected and transformed without inventing a type.
Promotion to `Frame[S]` checks field count, ordered names, data types, and
nullability.

```scala mdoc
import frame4s.*

type Expected = (id: Int, label: String)

val dynamic = DynamicFrame.source(
  "runtime-input",
  Vector(
    DynamicFrame.field("id", DataType.Int32),
    DynamicFrame.field("label", DataType.Utf8)
  )
)

val typed: Either[FrameError, Frame[Expected]] =
  dynamic.flatMap(_.typed[Expected])

typed.map(_.schema.fields.map(_.name))
```

Schema inference, when supplied by a future adapter or external tool, is a
suggestion that produces an explicit schema value. It is never ambient proof of
a compile-time named-tuple schema.

Next, read the [execution and ownership](execution-and-ownership.md) contract
before streaming or retaining results.
