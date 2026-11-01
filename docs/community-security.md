# Repository contribution and security administration

This records the 2026-10-13 setup and the remaining rollout work (PR and branch
numbers below are that day's snapshot). Repository settings
are live independently of the files on the configuration branch. Forms and community
documents become the defaults only after a reviewed merge into `plus`.

## Baseline and applied settings

- Public personal-account fork; default and production branch: `plus`.
- Existing open contributor PRs: #22–25. Do not merge, close, edit, or rerun them as
  part of this setup. Existing feature branches and releases are outside its scope.
- Existing community files: bug and feature forms, form configuration, PR template,
  and README contribution/localization guidance. No CONTRIBUTING.md or SECURITY.md.
  The former bug form referenced a nonexistent `triage` label and an old version
  dropdown; the replacement uses `needs-triage` and free-text version entry.
- Issues, private vulnerability reporting, Dependabot alerts, and the dependency
  graph are enabled. Secret scanning and push protection were already enabled and
  are preserved. No paid security products are enabled.
- All 10 original labels remain unchanged. Added: `security`, `android`, `ui/ux`,
  `playback`, `podcasts`, `downloads`, `needs-triage`, `needs-info`.
- Actions retain read-only default token permissions, cannot approve PRs, and keep
  the existing first-time-contributor run approval policy. Auto-merge remains off.
- No branch protection or rulesets existed. Active ruleset `plus-history-protection`
  targets only `refs/heads/plus`, blocks deletion and non-fast-forward pushes, and
  has an empty bypass list. It does not restrict ordinary pushes or tag publication.

## CI and security checks

Existing workflows are unchanged. `Build APK` has check context `main`; translation
validation has context `update_translations`. Both passed on the inspected `plus`
head `80e6838311d172d04cc4ba79fa2f7280690bd39f`. Neither is currently required.
The inherited iOS workflow is manually disabled and remains so.

The new `Dependency review` workflow runs on PRs targeting `plus`, with no path
filter. It uses GitHub's `actions/dependency-review-action`, pinned to reviewed v4
commit `2031cfc080254a8a887f58cffee85186f0e49e48`. The action metadata, configuration
loader, and comparison flow were inspected: inline inputs use the dependency graph
comparison API without checking out or executing PR code. It has only `contents:
read`, no repository secrets, no PR comments, no external scorecard requests, and
no new license policy. It fails on newly introduced known vulnerabilities of low
severity or greater; it is not a required branch check yet.

Dependabot alerts cover the existing recognized dependency inventory. Dependency
review covers changes, not every latent vulnerability in existing code. Gradle
dependencies not recognized by the graph may need a separately reviewed dependency
submission workflow. Automatic dependency-update PRs remain disabled to avoid
unsolicited application changes; maintainers should triage existing alerts.
CodeQL/default code scanning remains unconfigured pending a scoped Android/JS plan;
the inherited iOS code must not accidentally become a required build target.

## Proposed PR ruleset — not applied

A second ruleset for `refs/heads/plus` should eventually enforce:

- Pull requests with one maintainer approval, stale approvals dismissed after new
  commits, all review threads resolved, and approval of the most recent reviewable
  push by someone other than that pusher.
- Code-owner review of workflows and release-sensitive files. Add a CODEOWNERS file
  naming the approved maintainer for `.github/workflows/`, `.github/CODEOWNERS`,
  `android/app/build.gradle`, Gradle build/configuration files, and
  `docs/release-process.md` before enabling that rule. CODEOWNERS alone does not
  protect a path; the review rule must be active.
- Required GitHub Actions checks `main` and `update_translations`, bound to the
  GitHub Actions integration, with the branch up to date before merging. Add
  `Dependency review` only after verifying it on a real PR and confirming coverage.
- No blanket administrator bypass. Keep history protection in its separate
  no-bypass ruleset. Do not require iOS or signed-debug distribution checks.

Before activating these rules, make these exact workflow/process changes in a
separate reviewed change:

1. Remove the top-level `paths-ignore` filters from `build-apk.yml` and the
   `pull_request.paths` filter from `i18n-check.yml`, or introduce always-running
   gate jobs with stable check names that explicitly succeed for irrelevant paths.
   Otherwise README/iOS-only or non-translation PRs can wait forever for skipped
   required workflows. Verify the final contexts on both docs-only and app PRs.
2. Move Claude Code's release-version preparation, locally merged changes, and
   post-publication ledger updates onto topic branches and reviewed PRs. Merge the
   validated PR through GitHub, then build/sign locally from the exact merged commit,
   verify the artifact, create the normal release tag, and publish under the existing
   release process. Submit the ledger update in another PR and ensure the next
   release waits for that ledger to land. Preserve all existing version, signing,
   and release-asset conventions.
3. Resolve the author/reviewer identity first. Only `ADD-OCD` was listed as a
   collaborator during preflight. GitHub does not allow a PR author to approve their
   own PR; Claude using the owner's login cannot satisfy an owner-review rule. Use
   an explicitly approved separate release-agent GitHub App identity with narrowly
   scoped permissions and human maintainer approval, or add an approved independent
   maintainer. Do not grant either access as part of this setup. The agent can merge
   after review and checks without a bypass. If a different exception is essential,
   design and approve a narrowly scoped PR-only exception rather than an unrestricted
   admin bypass; verify all checks and review requirements still hold.
4. Rehearse the complete release and ledger path before enabling the PR ruleset.
   Existing signed releases and ongoing development must stay usable throughout.

## Credentials and workflow boundaries

Production signing is local according to `release-process.md`; this setup does not
read, move, rotate, or change signing credentials. Existing automatic PR workflows
use `pull_request` with read-only permissions. From 2026-10-14 until the Actions cost reduction, `Build APK` also had a separate
`debug-apk` job for pushes to `plus`; it was removed, so `Build APK` no longer reads any
signing secret. Production signing secrets are not referenced by any workflow. There is no privileged
`pull_request_target` or `workflow_run` execution path.

The existing manually dispatched debug APK workflow uses separate test-signing
secrets, allows repository branches, and has no environment approval gate. A branch
condition is not a trust guarantee: a maintainer must inspect code before dispatching
it, and must not copy unreviewed contributor code into a privileged build. Its build
steps precede signing in the same job, which remains a credential-exposure risk if
untrusted code is run. A future reviewed change should isolate signing from build
execution and protect its environment/refs with maintainer approval. Existing
signing secrets and this workflow are unchanged here.

Existing Actions policy permits all actions and existing workflows use mutable tags,
including the third-party Audiobookshelf translation action. Review and pin those
actions separately before narrowing the repository allowlist; a blanket policy
change now could break established checks. The new dependency check is SHA-pinned.

## Rollout and limitations

Review the configuration branch, its rendered forms, and CI before any merge. After
an approved merge, verify all three forms in Issues → New issue, the private security
contact link, the Security policy page, and the PR template. No merge is part of this
setup. Public reporting remains public even with a privacy warning; GitHub's private
advisory channel is the place for sensitive reports. Scanning is not a guarantee that
every secret or vulnerability is detected, and no response-time SLA is promised.

References: [GitHub rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets),
[issue-form syntax](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms),
[private reports](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately),
and the [dependency-review action](https://github.com/actions/dependency-review-action/tree/2031cfc080254a8a887f58cffee85186f0e49e48).
