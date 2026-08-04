# ADR 0012: Complete admitted typed operations and flatten wide-schema derivation

Status: accepted for the 0.1 candidate

## Context

The original typed surface exposed the right logical operations but stopped at
arbitrary limits: `renameAll` and `dropAll` handled exactly two fields,
`joinUsing` handled one key, and typed sorting applied one direction and null
policy to every key. Those limits were not semantic. They forced users back to
manual projections or the dynamic surface for ordinary forms of operations the
logical algebra already supported.

The first external width specimen exposed a second kind of accidental limit.
A 32-column consumer using default Scala compiler settings exceeded the
successive-inline limit during final-column lookup. The repository's own
`-Xmax-inlines:64` option had hidden that first-contact failure. Once lookup was
flattened, recursive schema-descriptor and row-codec evidence became the next
limits at 512 columns.

The release contract requires both properties to be true before signatures
freeze: ordinary admitted operations must have no arity dead end, and the
documented schema-width envelope must come from downstream artifacts rather
than repository compiler privileges.

## Decision

### Atomic projection requests

Typed multiple-field rename and drop accept one non-empty tuple:

```scala
frame.renameAll(
  (
    RenameRequest("oldA", "newA"),
    RenameRequest("oldB", "newB"),
    RenameRequest("oldC", "newC")
  )
)

frame.dropAll(
  (DropRequest("temporaryA"), DropRequest("temporaryB"))
)
```

Evidence is tuple-recursive over the request tuple, not implemented as an
overload ladder. Every rename source is resolved against the original schema;
all names change in one `Project`, which makes swaps well-defined. Sources and
targets must each be unique, and a target may not collide with an unrenamed
field. Drop requests are unique and lower to one `Project` that preserves the
relative order of retained fields.

### Multikey using joins

Typed using joins accept one non-empty tuple of `UsingKey` values. Each key is
resolved by name independently on both inputs, so the physical positions may
differ. The corresponding typed-column representations must be identical, and
duplicate or missing requests fail during compilation. Request order defines
comparison order. The result schema is every left field followed by right
non-key fields; the operation lowers to the existing `Join` node.

### Per-key sorting

The primary typed sorting form accepts callbacks that return `SortKey` values.
Each value carries its expression, direction, and null placement:

```scala
frame.sortBy(
  row => SortKey(row.col("account")),
  row => SortKey(row.col("score")).descending.nullsFirst
)
```

The keys retain callback order and lower together to one stable `Sort` node.
The earlier uniform-policy overload remains as a direct lowering for callers
that intentionally share one policy.

### Constant-depth schema evidence

Field lookup uses one compile-time traversal that emits a constant integer
index and preserves the exact missing-column diagnostic. `SchemaDescriptor`
uses one compile-time traversal to emit ordered `Field` values from the sealed
`ColumnType` witnesses. `RowCodec` similarly emits one flat ordered program
from sealed `ScalarCodec` witnesses. These derivations do not introduce a new
runtime type system, accept downstream evidence, widen column types, or add a
second dataframe algebra.

The row codec retains the single audited representation bridge between a named
tuple and its ordinary value tuple. It returns the first field error by schema
order and keeps `Option[A]` as the only nullable representation.

## Evidence and support boundary

The staged court publishes `frame4s-core` to an isolated Maven repository and
compiles external JVM and Scala.js consumers. Those projects use public symbols
only, default consumer compiler settings, and no inherited `-Xmax-inlines`.
Widths 32, 48, 128, 256, and 512 exercise:

- schema descriptor derivation and lookup of the final field;
- projection, three-way atomic rename, and three-way atomic drop;
- grouping and aggregation;
- a two-key using join whose corresponding fields occupy different positions;
- heterogeneous sort direction and null policy; and
- row encoding and decoding.

The 32-column external specimen also checks missing, duplicate, and mistyped
multikey diagnostics. In a focused warm H9 development run, 256 columns
completed in about six seconds per platform; the final exact-typed,
balanced-codec 512 tasks took 217 seconds on the JVM and 198 seconds on
Scala.js. A stronger isolated rehearsal then published candidate coordinates
and started each width in a fresh sbt process: 256 columns took 35 seconds on
the JVM and 31 seconds on Scala.js; 512 columns took 254 and 275 seconds. Peak
compiler memory was not isolated, so no memory figure is claimed.

Accordingly, 256 columns is the practical 0.1 envelope. A 512-column schema is
a verified stress tier, not an ordinary compile-cost promise. The exact release
SHA must rerun the complete isolated ladder before tagging. No final library
width failure was observed through 512; widths above 512 were not tested, so no
first failing width is claimed.

## Rejected alternatives

- Adding three-, four-, and five-field overloads would merely move the dead end
  and multiply signatures.
- Accepting raw `(String, String)` pairs would discard singleton names before
  the compiler could prove source presence, target uniqueness, and output
  shape.
- Making users raise `-Xmax-inlines` would transfer an implementation defect
  into every downstream build and leave deeper implicit recursion untouched.
- Treating 512 as ordinary merely because it passes would hide a roughly
  forty-fold task-time increase over 256 in the observed court.

## Consequences

The public surface now matches the admitted algebra without redesigning it.
Conveniences remain pure, exact-schema plan construction. Wide-schema evidence
is faster and no longer depends on field-count-sized implicit chains, but very
wide named tuples still carry substantial compiler cost. That cost is a
documented support boundary and a permanent downstream regression court rather
than an unqualified scalability claim.
