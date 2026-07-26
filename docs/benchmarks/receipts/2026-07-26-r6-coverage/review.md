# R6 JVM coverage diagnostic

Court: `coreJVM/test` with scoverage 2.4.4 and Scala 3.7.4

Initial result before the diagnostic-message follow-up:

- 89 tests passed;
- statement coverage: 72.07% (3,641 of 5,052 statements);
- branch coverage: 57.67%;
- raw scoverage XML SHA-256:
  `17e67969c533f0faa9bd8d4f54c961737dcee930fdfa2d81cf8704ab533337c5`;
- raw Cobertura XML SHA-256:
  `84ad4f3057c3adf49f42b8e206316874e9704fe2bbfe87f9b4807ececdd89db7`.

Final result after the cross-platform diagnostic-message tests:

- 94 tests passed;
- statement coverage: 74.72%;
- branch coverage: 63.92%;
- raw scoverage XML SHA-256:
  `7ad5e86df49be4d69a2f626c0b4d53c07578fa911838624f6677bbaf788c6224`;
- raw Cobertura XML SHA-256:
  `68f20fb6c86074b26234f7f837c8cd5976ef862eb9eca093821e0804e0f29460`.

Review:

- The report identified public error-rendering branches as the clearest
  user-facing gap. `ErrorMessageSuite` now exercises every branch in
  `SchemaError`, `BindingIssue`, `FrameError`, `StorageError`,
  `ExecutionError`, and `TableReadError`, with exact assertions for
  provenance, duplicate names, decoding, overflow, and dictionary failures.
- Ownership paths remain governed by explicit buffer counters and
  success/error/early-termination/cancellation tests rather than a percentage.
- Sparse normalizer branches are covered primarily by cross-platform
  normalization laws; raw line coverage is not used to weaken or replace those
  laws.
- Union cursor boundary/close behavior and specialized timestamp/storage
  branches remain priority areas when future coverage work is undertaken.

Release CI regenerates the report from the candidate commit. No percentage is
a release threshold.
