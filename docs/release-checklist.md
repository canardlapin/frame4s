# 0.1.0 release checklist

The release manager runs this checklist from a clean checkout of the exact
candidate commit. Configured workflows are not called green until the remote
jobs pass on that commit.

## Candidate identity

- [ ] Record the full commit, `0.1.0` version, `v0.1.0` tag target, JDK, Node,
      sbt, Scala, and Scala.js versions in the release receipt.
- [ ] Confirm `git status --short` is empty and no artifact or receipt contains
      a local absolute path, secret, snapshot dependency, `.mote`, `vendor`, or
      `target` payload.
- [ ] Confirm release notes, checksums, tag, POMs, and GitHub release name the
      same version and commit.

## Required court

```sh
sbt formatCheck docsCheck apiDocs versionPolicyCheck compileAll testAll benchmarkSmoke
bash scripts/release-rehearsal.sh
```

- [ ] Core, FS2, Arrow, laws, documentation, formatting, staged consumers,
      benchmark smoke, and diagnostic coverage jobs pass remotely.
- [ ] The JVM, Scala.js, and Arrow staged consumers resolve only candidate
      artifacts and print their success markers. The Arrow consumer uses the
      documented production JVM opening.
- [ ] External 32-, 48-, 128-, 256-, and 512-column JVM and Scala.js consumers
      compile without inherited `-Xmax-inlines`, run every advertised typed
      operation, and retain actionable negative diagnostics. Record per-task
      compile/run time; record peak compiler memory only if it was isolated.
- [ ] Full JVM JMH and Scala.js receipts are attached and disclose losses,
      environment, completed work, checksums, and allocation/ownership counts.
- [ ] Every release workload ends with zero active owners and views.

Scala 3.7.4 currently emits `Flag -classpath set repeatedly` from the Scaladoc
tool even for a warning-clean source set. FS2/JVM documentation also reports
one inherited Cats Effect `IOApp.Simple.run` link that cannot be resolved from
dependency TASTy; `show fs2JVM/Compile/doc/sources` must confirm that only
frame4s sources enter the task. These exact external/toolchain diagnostics are
recorded. Any unresolved frame4s link, project-source warning, or additional
diagnostic fails the candidate.

## API, semantics, and documentation

- [ ] Public API changes are frozen and listed in the release notes.
- [ ] `0.1.0` remains the first compatibility baseline; no pre-release artifact
      is configured as a predecessor.
- [ ] Executable guides and README examples use only public, total APIs and
      distinguish `Auto` materializing collection, typed reference fallback,
      `ReferenceOnly`/`RequireColumnar`, and the reference streaming route.
- [ ] Public-path specimens assert the selected engine, absence or presence of
      fallback, and source columns read; streaming courts prove early emission,
      limit short-circuiting, blocking boundaries, and exact cleanup.
- [ ] Constitution, operation map, ownership contract, explain output, known
      limitations, and deferred scope match the artifacts.

## Publication, security, and continuity

- [ ] POM metadata, JVM/Scala.js/Arrow binary jars, source jars, Scaladoc jars,
      signatures, and checksums pass rehearsal.
- [ ] `frame4s-fs2_3` and `frame4s-fs2_sjs1_3` contain no Apache Arrow
      dependency; `frame4s-arrow_3` contains the expected optional dependency
      edge.
- [ ] The tag-triggered workflow runs the publication gate with sbt 1.12.11,
      routes stable artifacts through Central Portal local staging, and exposes
      `sonaRelease`; the normal supported court remains pinned to sbt 1.10.5.
- [ ] `PROVENANCE.md` matches the resolved candidate dependency graph and copied
      source boundary.
- [ ] GitHub private vulnerability reporting is enabled and independently
      visible; `SECURITY.md` response expectations are current.
- [ ] The owner completes the offline recovery and clean-machine rehearsal in
      `maintainer-continuity.md` through
      `scripts/release-owner-preflight.sh`; its non-secret receipt is attached.
- [ ] Signed public coordinates resolve from clean external JVM, Scala.js, and
      Arrow consumers before broader announcement.

Publication stops on any unchecked item. A failed publication is corrected and
reverified before an announcement or performance claim.
