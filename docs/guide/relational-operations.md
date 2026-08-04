# Joins, grouping, sorting, union, and distinct

```scala mdoc:compile-only
import frame4s.*

type Person = (id: Int, name: String, score: Option[Double])
type Grouped = (name: String, n: Long, mean: Option[Double])

def grouped(input: Frame[Person]): Frame[Grouped] =
  input
    .groupBy(row => Tuple1(row.col("name")))
    .aggregate(row =>
      (
        Aggregate.count.as("n"),
        Aggregate.mean(row.col("score")).as("mean")
      )
    )

def ordered(input: Frame[Person]): Frame[Person] =
  input.sortBy(
    row => SortKey(row.col("name")),
    row => SortKey(row.col("score")).descending.nullsFirst
  )

def combined(left: Frame[Person], right: Frame[Person]): Frame[Person] =
  left.unionAll(right).distinct
```

Join output names are never silently suffixed:

```scala mdoc:compile-only
import frame4s.*

type Left = (id: Int, label: String)
type Right = (key: Int, amount: Double)

def existing(left: Frame[Left], right: Frame[Right]): Frame[Left] =
  left.semiJoin(right)((lhs, rhs) => lhs.col("id") === rhs.col("key"))

def missing(left: Frame[Left], right: Frame[Right]): Frame[Left] =
  left.antiJoin(right)((lhs, rhs) => lhs.col("id") === rhs.col("key"))
```

Using joins resolve each key by name on both sides, even when the physical
field order differs:

```scala mdoc:compile-only
import frame4s.*

type Visits = (personId: Int, session: Int, label: String)
type Measures = (session: Int, personId: Int, value: Double)
type Joined = (personId: Int, session: Int, label: String, value: Double)

def joined(
    visits: Frame[Visits],
    measures: Frame[Measures]
): Frame[Joined] =
  visits.innerJoinUsing(
    measures,
    (UsingKey("personId"), UsingKey("session"))
  )
```

See the
[operation map](https://github.com/canardlapin/frame4s/blob/main/docs/operations.md)
for exact output schemas, null
behavior, lowering, and order guarantees.

Next, use [dynamic inspection and exact typed binding](dynamic-schemas.md) when
a schema is available only at runtime.
