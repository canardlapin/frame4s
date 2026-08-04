# Schemas, nullability, expressions, and errors

```scala mdoc
import frame4s.*

type Person = (id: Int, name: String, score: Option[Double])

val schema = summon[SchemaDescriptor[Person]].schema
schema.fields.map(field => (field.name, field.dataType, field.nullable))
```

`Option[A]` is the only nullable typed-column representation. A missing column,
incompatible operator, duplicate output name, or expression from another frame
is rejected while compiling the typed API. Dynamic construction returns the
corresponding structured `FrameError`.

```scala mdoc:compile-only
def totalPredicate(frame: Frame[Person]): Frame[Person] =
  frame.filter(row => (row.col("score") > Some(0.0)).isTrue)

def exactBinding(dynamic: DynamicFrame): Either[FrameError, Frame[Person]] =
  dynamic.typed[Person]
```

The compiler rejects a misspelled column before the query can run:

```scala mdoc:fail
def misspelledColumn(frame: Frame[Person]) =
  frame.drop("naem")
```

Raw `null` is not a typed column value. Use `None` for a nullable column:

```scala mdoc:fail
def rawNull(frame: Frame[Person]) =
  frame.filter(row => row.col("name") === null)
```

Projects compiled without explicit nulls can still place raw null inside a
value declared as `String`, or inside `Some(null)`. frame4s checks those values
when it constructs a literal. The direct typed syntax stops immediately with
`InvalidValueFailure`; it does not return an expression or plan. Use the total
constructor when the value came from Java or another untrusted boundary:

```scala mdoc
val checked = Expr.literalChecked(null: String)
checked.left.map(_.message)
```

Dynamic literals and storage scalars use the same boundary through
`LiteralValue.utf8` and `ScalarValue.utf8`. Their UTF-8 cases store an opaque
`Utf8Value`, so a successfully constructed case cannot contain raw null.
`None` remains the only supported representation of a missing typed value.

The repository court protects both the compile-time `null` diagnostic and the
runtime boundary for an ascribed null or `Some(null)`.

Planning, storage, execution, and adapter failures use separate structured
ADTs. In particular, source acquisition and streams raise `SourceFailure`, a
runtime binding adds its `SourceId` in `RuntimeBindingError.Source`, and sinks
return `Left(SinkError)` for ordinary failures. Their public messages are
bounded and redact rejected values and exception text. Inspect `cause` only in
an explicit debugging path. The complete repository decision is recorded in
`docs/design/adr-0009-structured-adapter-failures.md`.

Next, compose these expressions into
[relational operations](relational-operations.md).
