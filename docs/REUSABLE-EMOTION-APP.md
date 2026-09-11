# wristwork-emotion extraction and synchronization

Implemented 2026-09-11. The clean public repository is
[`EmotiveAutomaton/wristwork-emotion`](https://github.com/EmotiveAutomaton/wristwork-emotion).
It has fresh history and is the upstream another wearer should fork. It is a generated
distribution, not a direct GitHub fork of wristwork.

## Why it is a generated repository

A direct fork would preserve every tracked file and the complete wristwork history.
Ignored `config.properties` and `data/` would stay out, but printer, rig, Fetch,
agent-dashboard, Synology assumptions, and owner-specific design history would remain.
Deleting them in a later commit would not remove them from history.

`tools/export-wristwork-emotion.ps1` instead copies an explicit reviewed allowlist
plus the public template under `distribution/wristwork-emotion/template/`. The result
contains no wristwork Git history. Adding an ordinary file to wristwork cannot make it
cross the distribution boundary by accident; the exporter itself must be changed.

## Included boundary

The distribution keeps:

- emotion and health complications;
- the label grid, timeline, revisions/tombstones, explicit confirmation, notes, and
  offline Room queue;
- passive watch collection, sensor inventory, Fitbit notification capture/cues;
- prompt delivery, dismissal, provenance, and permanent-random-holdout rules;
- generic authenticated ntfy transport and a portable append-only archiver;
- configuration and read-only doctor scripts, CI, setup docs, and focused skills.

It excludes agent, rig, and printer surfaces; Fetch and voice-to-print; machine
feeders and printer pollers; the original server paths, OAuth state, schedules,
mirrors, data, credentials, and historical status records. The public project keeps
the canonical Panksepp vocabulary because it defines the instrument, while making no
diagnostic or medical claim.

## Automatic update path

The target repository runs `.github/workflows/sync-upstream.yml` at minute 17 of
every hour and on manual dispatch. It checks out `wristwork/main`, exports the
allowlist, replaces the generated target tree, and commits only when content changes.
Before committing it runs the secrets guard, five prompt-state tests, and a complete
debug APK build. The first exercised sync successfully imported a real documentation
change and pushed it using only the workflow's repository-scoped `contents: write`
permission.

The sync job is guarded to the canonical `EmotiveAutomaton/wristwork-emotion` repo.
A friend's fork receives updates through GitHub's normal **Sync fork** or an upstream
merge, so their code changes are not overwritten hourly. Their private config and
data remain ignored and never belong in pull requests.

## Implemented repository shape

```text
app/                    Wear OS emotion/health app, separate application ID
backend/                authenticated ntfy + append-only NDJSON archiver
setup/                  config generator, ACL helper, and read-only doctor
docs/SETUP.md            human/agent onboarding and acceptance path
docs/DATA.md             ownership, provenance, and holdout rules
docs/UPSTREAM.md         generation and fork update model
.agents/skills/          setup and private data-review workflows
config.example.properties
```

Prompt scheduling now belongs to the emotion complication rather than relying on a
personal channel complication. The target also has a launcher entry so a new wearer
can open it during onboarding. A clean exported tree builds with placeholders, its
two skills pass the Skill Creator validator, Docker Compose resolves, and the config
generator was executed with disposable values.

## Next handoff step

The friend's first setup is the remaining product test. Follow the target repository's
`docs/SETUP.md`: connect or deploy their private ntfy service, generate their ignored
configuration, build, pair, install, grant permissions, and let them enter one genuine
verification label. Record archive arrival, passive collection, prompt dismissal,
offline replay, remote reachability, and full-wear-day battery behavior as separate
observations. Improve the upstream template wherever that pilot finds avoidable setup
friction.
