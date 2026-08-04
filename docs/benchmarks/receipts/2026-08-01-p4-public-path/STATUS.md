# Status: invalidated

Invalidated on 2026-08-02 during the release-quality review.

`source-files.sha256` does not match the current source tree and did not match
the source state inspected at the receipt's introducing change. The committed
metrics therefore cannot be tied to the implementation they are claimed to
measure. They remain in this directory only as historical, non-admissible
output.

A replacement court must:

1. run from the final candidate after functional and formatting gates;
2. record an explicit exact-set `source-files.list`;
3. generate `source-files.sha256` from that list;
4. pass `scripts/verify-source-manifest.sh`; and
5. record the typed engine receipt assertions used by every public iteration.

The admissible replacement is the
[2026-08-02 P4 public-path court](../2026-08-02-p4-public-path/summary.md).
