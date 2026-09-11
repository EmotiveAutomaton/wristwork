# Upstream synchronization

This repository has fresh history and does not contain the full history of the
personal `wristwork` project. It is a generated distribution rather than a GitHub
fork of that project.

The canonical repository's hourly `sync-upstream.yml` workflow checks out
`EmotiveAutomaton/wristwork@main`, runs its explicit emotion-export allowlist, and
commits only when the generated tree changes. GitHub's repository-scoped token can
write only to `wristwork-emotion`; no cross-repository personal token is required.
The workflow also supports a manual run. It runs the secrets guard, unit tests, and
APK build before committing, so a failed export leaves the target branch unchanged.

`.github/` is target-managed and excluded from automated replacement. GitHub's
repository token cannot modify workflow files without a broader workflow-management
credential. Workflow updates are therefore reviewed and pushed manually by the
repository owner; ordinary app, backend, docs, skills, and ignore-rule changes remain
automatic.

The allowlist is deliberate. New files in `wristwork` do not enter this repository
until the exporter is updated, so printer controls, agent status, machine telemetry,
private infrastructure, local configuration, data, and the original history remain
outside this project.

The exporter includes dot directories on Linux and stops if the generated `.github`,
`.agents`, `.gitignore`, or backend `.env.example` files are missing. This prevents a
cross-platform sync from silently removing the workflow or its privacy guardrails.

The scheduled workflow is guarded to run only when `github.repository` is
`EmotiveAutomaton/wristwork-emotion`. A wearer can fork this clean repository and
keep private configuration outside Git. Updates then arrive through GitHub's normal
**Sync fork** action or an upstream merge, giving the wearer a chance to reconcile
their own code changes. Do not enable the canonical mirroring workflow in a personal
fork by removing that guard unless replacing the fork's files is actually intended.

Shared app fixes should be made in `wristwork` and included by
`tools/export-wristwork-emotion.ps1`. Distribution-only docs, backend files, and
skills are maintained in `distribution/wristwork-emotion/template` there. Workflow
changes start there too, then require the manual target update described above.
