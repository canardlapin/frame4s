# ADR-0008: Non-null UTF-8 values and private expression identity

Status: accepted and implemented; release certification pending

Date: 2026-08-03

Decision issue: `bd-01KZ4K330BNM087JPKM7ZKSJS3`

## Context

frame4s represents a nullable typed column as `Option[A]`. The runtime must
therefore distinguish `None` from every value of `A`. That guarantee did not
hold for strings. Under Scala's default nullability mode, both of these calls
compiled:

```scala
Expr.literal(null: String)
Expr.literal(Some(null): Option[String])
```

The public `LiteralValue.Utf8` and `ScalarValue.Utf8` enum cases also accepted a
raw `String`. A null could consequently enter a non-nullable expression or
storage scalar and fail later in comparison, encoding, or rendering code.

Expression identity had a separate representation problem. An identifier
contained the rendered literal, and a unary or binary identifier contained the
complete identifiers of its children. Reusing an expression on both sides of a
binary operator therefore doubled the identifier at each level. A depth-20
court produced a 33,554,421-character identifier. The same text appeared in
`explain` and execution errors, including literal strings and control
characters.

These are boundary defects. They do not require a new expression algebra or a
different execution model.

## Decision

### Store validated UTF-8 values

`Utf8Value` is an opaque wrapper whose only public constructor checks for raw
null and returns `Either[ValueError, Utf8Value]`. Its underlying `String` is
readable, but callers cannot manufacture the wrapper from an unchecked
`String`.

`LiteralValue.Utf8` and `ScalarValue.Utf8` store `Utf8Value`, not `String`.
Their public companion methods accept a `String` and return `Either`. Internal
decoders may use a package-scoped constructor only after they have established
the same non-null condition. This makes the following invariant structural:

> Every `LiteralValue.Utf8` and `ScalarValue.Utf8` contains a non-null string.

`ColumnArray.utf8`, row encoding, CSV decoding, and Arrow decoding continue to
report errors at their existing structured boundaries. A null in a valid UTF-8
slot is an error; an invalid slot represents absence and its placeholder is
never inspected.

### Check typed literal boundaries immediately

`Expr.literalChecked` is the total constructor for a value received from an
untrusted or non-explicit-null caller. It returns `Left(ValueError.NullUtf8)` for
both an ascribed null string and `Some(null)`.

`Expr.literal` and exact scalar operands remain direct constructors because
they are the ordinary typed algebra used inside `select`, `filter`, joins, and
aggregations. They perform the same validation. If a caller has placed raw
null inside a value whose static type excludes null, construction stops
immediately with `InvalidValueFailure(ValueError.NullUtf8)`. No expression or
logical plan is returned. The failure is deliberate, bounded, and independent
of the JVM operation that would otherwise encounter the null.

This runtime guard is necessary for downstream projects compiled without
explicit nulls and for Java interoperation. The compile-time `Null` overloads
remain because they give a better diagnostic for an un-ascribed `null`.
ADR-0004's earlier statement that frame4s would not diagnose an ascribed null
is superseded by this decision.

### Separate structural identity from display

Each expression receives a fixed-size, deterministic fingerprint computed from
its operator, physical type, literal value, column identity, and child
fingerprints. Constructing a unary or binary identity is constant in the size
of its children. A compact structural witness accompanies the fingerprint so
that equality compares structure when fingerprints collide; the fingerprint
alone never establishes identity.

The fingerprint and collision witness are internal. `ExprId.toString`, error
messages, logs, and receipts do not render either one. Scalar and literal
diagnostics render a bounded type description and never render the stored
value.

`LogicalPlan.explain` assigns `e1`, `e2`, and so on in deterministic traversal
order. A linear expression legend retains operator and input structure while
rendering literals only by physical type. The same expression reused in a plan
receives the same local ordinal. The ordinal is meaningful only within that
explanation; it is not a serialized identifier or a cache key.

## Consequences

- Existing typed numeric and Boolean expressions keep their direct syntax.
- Code that constructs dynamic UTF-8 literals or storage scalars from a raw
  `String` must handle an `Either`.
- Pattern matches on a UTF-8 literal or scalar receive `Utf8Value`; `.value`
  returns the underlying non-null `String`.
- Explain output changes before 0.1.0 from recursively rendered expressions to
  bounded local ordinals.
- Error messages identify the failing operation and physical type but do not
  reproduce user data.
- Fingerprints are implementation details. No compatibility promise covers
  their algorithm or value.

## Rejected alternatives

Keeping `String` in the enum cases and adding scattered null checks would leave
the core invariant dependent on every future caller remembering a check.

Returning `Either` from every typed expression operator would be total, but it
would turn ordinary arithmetic and predicates into a second error-propagating
algebra. The malformed state exists only when a caller has already violated
the static `String` or `Option[String]` contract, so an immediate structured
boundary failure is narrower.

Using the fingerprint in explain output would bound the text but still expose
an internal correlation key. It would also make a private hash algorithm look
like public API. Plan-local ordinals communicate expression reuse without
making that promise.

## Required court

The JVM and Scala.js court must prove:

- raw null, ascribed null, and `Some(null)` cannot produce a UTF-8 value,
  expression, row, or plan;
- dynamic and storage smart constructors return `ValueError.NullUtf8`;
- valid and invalid UTF-8 storage slots preserve the Option-only null model;
- expression construction remains bounded for a deeply reused DAG;
- forced fingerprint collisions do not merge different structures;
- explain output is deterministic and contains only plan-local ordinals; and
- explain and error messages do not contain secret literals, long values,
  control characters, or fingerprints.
