# Admission decision: rejected

This full court was not admitted. Direct UTF-8 scan measured 0.0313354 ms/op,
above the immutable 0.030 ms ceiling, although its 70,245 B/op allocation was
within the 128 KiB ceiling.

The miss was not rounded away and the threshold was not changed. It led to an
allocation-free UTF-8-to-UTF-16 hash implementation with exact Java
`String.hashCode` semantics and a malformed-input decoding fallback. The
cross-platform Unicode tests and the succeeding full court are recorded in
[2026-07-26-r5-columnar-admitted-final](../2026-07-26-r5-columnar-admitted-final/admission.md).
