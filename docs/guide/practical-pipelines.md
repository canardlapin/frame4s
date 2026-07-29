# Filter, derive, select, and summarise

frame4s uses familiar dataframe operations while keeping each query as an
immutable, typed `Frame`. `withColumn` appends a derived column; `replace`
changes an existing column in the result schema. Neither operation mutates the
input frame.

## Filter rows and derive columns

This pipeline filters two text columns, derives metric height and body-mass
index, and selects the five columns needed by its caller:

```scala mdoc:compile-only
import frame4s.*

type Person = (
    name: String,
    species: Option[String],
    sex: Option[String],
    skin: String,
    eyes: String,
    height: Option[Double],
    mass: Option[Double]
)

type Metrics = (
    name: String,
    species: Option[String],
    sex: Option[String],
    heightM: Option[Double],
    bmi: Option[Double]
)

def metrics(input: Frame[Person]): Frame[Metrics] =
  val filtered = input.filter: row =>
    (row.col("skin") === "light") && (row.col("eyes") === "brown")

  val withHeight = filtered.withColumn("heightM"): row =>
    row.col("height") / Some(100.0)

  withHeight
    .withColumn("bmi"): row =>
      row.col("mass") / (row.col("heightM") * row.col("heightM"))
    .select: row =>
      (
        row.col("name"),
        row.col("species"),
        row.col("sex"),
        row.col("heightM"),
        row.col("bmi")
      )
```

`select` infers the exact `Metrics` schema. Raw column expressions retain their
existing names; a computed expression needs `.as("name")` when it appears
directly in a selection.

`height` and `mass` are `Option[Double]`, so their arithmetic operands are
explicitly nullable. Missing height or mass produces a missing derived value.
frame4s does not install arithmetic for ordinary Scala `Option` values or
silently lift a non-null operand.

## Replace an existing column

Use `replace` when the output should retain a column's position and name:

```scala mdoc:compile-only
import frame4s.*

type Measurement = (id: Int, value: Option[Double])

def rescale(input: Frame[Measurement]): Frame[Measurement] =
  input.replace("value"): row =>
    row.col("value") * Some(0.001)
```

`withColumn("value")` would be rejected because `value` already exists. That
distinction makes an accidental overwrite a compile-time error.

## Select before grouping

A grouped query can first project away unused input columns, then compute
several summaries:

```scala mdoc:compile-only
import frame4s.*

type Observation = (
    species: Option[String],
    sex: Option[String],
    height: Option[Double],
    mass: Option[Double],
    note: String
)

type Selected = (
    species: Option[String],
    sex: Option[String],
    height: Option[Double],
    mass: Option[Double]
)

type Summary = (
    species: Option[String],
    sex: Option[String],
    height: Option[Double],
    mass: Option[Double]
)

def summaries(input: Frame[Observation]): Frame[Summary] =
  val selected: Frame[Selected] = input.select: row =>
    (
      row.col("species"),
      row.col("sex"),
      row.col("height"),
      row.col("mass")
    )

  selected
    .groupBy(row => (row.col("species"), row.col("sex")))
    .aggregate: row =>
      (
        Aggregate.mean(row.col("height")).as("height"),
        Aggregate.mean(row.col("mass")).as("mass")
      )
```

Null grouping keys coalesce. `mean` ignores missing observations and returns
`None` for an empty or all-missing group.

## Operation names

The public names describe immutable Scala operations:

| Task | frame4s operation |
| --- | --- |
| Keep matching rows | `filter` |
| Choose or compute an output schema | `select` |
| Append a derived column | `withColumn` |
| Recompute an existing column | `replace` |
| Group and calculate summaries | `groupBy(...).aggregate(...)` |

The practical benchmark court exercises the first and third examples as
complete pipelines rather than isolated operators. Its
[versioned receipt](https://github.com/canardlapin/frame4s/blob/main/docs/benchmarks/receipts/2026-07-29-dplyr-practical/dplyr/summary.md)
compares an internal columnar candidate with dplyr after checking both outputs
against the semantic reference backend. Public `FrameRuntime` still selects
the reference backend; the receipt does not claim otherwise.

Next, read [schemas and errors](schemas-and-errors.md) for compile-time column
checking and nullable predicate rules.
