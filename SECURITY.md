# Security policy

frame4s has not yet published a supported release. After `0.1.0`, the latest
patch release in the `0.1.x` line will receive correctness and security fixes;
older `0.1.x` releases will not be maintained in parallel. Supported lines will
be updated here when that changes.

Do not open a public issue for an undisclosed vulnerability. This public
repository does not currently have private vulnerability reporting enabled,
so there is no safe private reporting path and publication is blocked. Before
the first release candidate is approved, the owner must enable and test
**Security → Report a vulnerability**. This policy will then link directly to
that live private form.

The maintainer will:

- acknowledge a private report within three business days;
- provide an initial severity and affected-version assessment within seven
  business days when reproducible;
- provide an update at least every fourteen calendar days until resolution;
- coordinate disclosure after a fix or mitigation is available.

Please include the affected version or commit, platform, impact, reproduction,
and any proposed embargo. Never include credentials, personal data, or
production secrets in a report.

Ordinary correctness, type-safety, resource-ownership, and performance bugs may
be reported through public issues once the repository is published. A report
that may cross a confidentiality, integrity, availability, sandbox, or
dependency-supply-chain boundary belongs in the private channel.
