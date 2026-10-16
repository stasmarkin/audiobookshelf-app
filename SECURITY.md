# Security policy

## Supported versions

Security fixes target the **latest stable Audiobookshelf+ Android release** and the
`plus` development branch. At the time this policy was introduced, that release was
Audiobookshelf+ **v0.18.2**, published under tag `v0.14.2-beta-plus-v0.18.2`.
Use the [latest release](https://github.com/ADD-OCD/audiobookshelfplus-app/releases/latest)
for the current supported version.

Older releases and development/test builds have no guaranteed security backports.
The inherited iOS project is not a supported Audiobookshelf+ release target.
Reports about older versions are still useful, especially if the latest release
may be affected. Audiobookshelf server and upstream app support policies are
managed by their respective projects.

## Report privately

Use [GitHub private vulnerability reporting](https://github.com/ADD-OCD/audiobookshelfplus-app/security/advisories/new)
in this repository (Security → Report a vulnerability). Sign in to GitHub if prompted.
Reports are shared privately with maintainers and invited advisory collaborators.

**Do not disclose unpatched sensitive vulnerabilities in public Issues, PRs,
Discussions, public forks, or chat channels.** The `security` Issue label is for
publicly safe hardening work only and provides no confidentiality.

Include what you can safely provide:

- Affected Audiobookshelf+ version/build or commit, Android version, and device model.
- A concise impact description, affected component, and any prerequisites.
- Minimal reproduction steps or a proof of concept using synthetic data and a test
  environment you control; say whether streaming or downloaded media is involved.
- Expected versus observed behavior and relevant sanitized logs or screenshots.
- Suggested mitigation, if known, and whether the latest stable release is affected.

Never send live passwords, tokens, private keys, signing material, private server
URLs, or unnecessary personal information. Use placeholders and redact attachments.
Coordinate any additional sensitive evidence within the private report.

If the private reporting form becomes unavailable, keep the details confidential
and request an approved private contact channel from the maintainer without
describing the vulnerability publicly. No alternative security email is designated.

## Coordination and disclosure

Maintainers will assess reports and coordinate investigation, fixes, and disclosure
through the private advisory. Keep details confidential until a fix or mitigation
is available and disclosure has been coordinated. Do not publish exploit details or
an unpatched sensitive issue while that process is ongoing.

There are **no guaranteed response or remediation times**. Severity, reproducibility,
and maintainer availability affect handling. Reporter credit can be coordinated
with your consent and preferred public attribution; anonymity is welcome.