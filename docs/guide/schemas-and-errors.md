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
  frame.filter(row =>
    (row.col("score") > Expr.literal(Some(0.0): Option[Double])).isTrue
  )

def exactBinding(dynamic: DynamicFrame): Either[FrameError, Frame[Person]] =
  dynamic.typed[Person]
```

The compiler rejects a misspelled column before the query can run:

```scala mdoc:fail
def misspelledColumn(frame: Frame[Person]) =
  frame.drop("naem")
```

The repository's compile-time court separately protects the concise diagnostic
wording used by the public API.

Next, compose these expressions into
[relational operations](relational-operations.md).
