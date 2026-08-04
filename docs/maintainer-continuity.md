# Maintainer continuity and break-glass rehearsal

frame4s `0.1.0` has one release-capable maintainer, `canardlapin`. This is a
disclosed bus-factor risk. A second maintainer is desirable, but the release
court does not pretend one exists.

The first release candidate is blocked until the owner completes this
procedure from a clean machine or isolated user profile and commits a
non-secret receipt. The rehearsal must never print or store recovery codes,
private keys, passphrases, access tokens, or credential values.

## Owner-only rehearsal

Use a clean checkout of the exact candidate commit in an isolated machine or
user profile.

1. Recover the GitHub account with an offline recovery factor. Authenticate
   the isolated CLI profile with
   `tools/github/gh-repo auth login --hostname github.com --web --git-protocol ssh --skip-ssh-key`.
   Run `tools/github/doctor`, then confirm repository administration without
   changing public state. Do not change the machine-default GitHub account.
2. Recover the artifact-signing key from offline backup and import it into the
   isolated profile. Record its full public fingerprint; do not export the
   private key into the checkout.
3. Sign in to the Central Publisher Portal through the recovered account.
   Confirm that the `io.github.canardlapin` namespace is verified and recover or
   rotate a current Portal user token. This is a read-only account check; do
   not upload a deployment.
4. Confirm that the release workflow contains the four required Actions secret
   names: `PGP_PASSPHRASE`, `PGP_SECRET`, `SONATYPE_USERNAME`, and
   `SONATYPE_PASSWORD`. Listing names is safe; never print values.
5. Confirm from a non-maintainer browser session that the repository exposes
   **Report a vulnerability**. This check is distinct from the maintainer's
   administrative view.
6. Mark the three manual checks only after completing them, then run the
   automated preflight:

   ```sh
   export FRAME4S_GITHUB_RECOVERY=pass
   export FRAME4S_CENTRAL_PORTAL_RECOVERY=pass
   export FRAME4S_PRIVATE_REPORTING_VIEW=pass
   export FRAME4S_SIGNING_FINGERPRINT=<full-public-fingerprint>
   scripts/release-owner-preflight.sh target/release-owner-preflight
   ```

The script refuses a dirty checkout. It verifies JDK 21, Node.js 24, the
repository-pinned sbt 1.10.5 court, the sbt 1.12.11 Central Portal release
runner, repository administration, Actions secret names, private-reporting
status, and a detached signature made by the recovered production key. It then
runs the full court and isolated JVM, Scala.js, and Arrow artifact rehearsal.

Git transport and GitHub API authentication are deliberately separate. Commits
and pushes use the repo-local `github-canardlapin` SSH route. GitHub
administration uses the isolated `gh-canardlapin` profile through
`tools/github/gh-repo`; an expired API token therefore cannot silently redirect
Git operations to another account.

Inspect `target/release-owner-preflight/status.properties` and its
`artifact-rehearsal` directory before copying them to the release receipt. The
receipt contains public facts and pass/fail statuses only. A failed run names
the failed step and records that production publication was not exercised.

If a signing key is lost or suspected compromised, revoke it, generate a new
key, publish the revocation/replacement record, rotate the CI secret, and
rehearse again. If GitHub or publishing recovery fails, do not tag or publish.

## Public receipt template

```text
status=pass
performed.by=canardlapin
performed.at=<UTC timestamp>
candidate.commit=<full commit>
github.recovery=pass
repository.admin=pass
signing.recovery=pass
signing.public.fingerprint=<public fingerprint>
detached.signature.roundtrip=pass
central.portal.credential.recovery=pass
central.portal.toolchain=sbt-1.12.11
central.portal.routing=local-staging
clean.machine.court=pass
artifact.rehearsal=pass
private.security.channel=pass
private.security.channel.nonmaintainer.view=pass
production.publication=not-exercised
remaining.bus.factor=1
```

The current non-secret status remains `pending-owner` until the owner performs
these steps. The repository-side artifact rehearsal and explicit stable-route
probe do not prove GitHub recovery, Portal account access, or possession of the
production signing key.
