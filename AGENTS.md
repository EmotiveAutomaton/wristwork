# wristwork agent guide

## Start here

Read the shared Agent Core GPT contract supplied by the workstation, then this file,
[README.md](README.md), and the newest entry in [docs/STATE.md](docs/STATE.md).
After compaction, reload those sources and the records relevant to the active task.
Use [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) to find the implementation.

This is the cross-agent operating entry point, adopted 2026-09-06. It supersedes
the general agent boilerplate in [CLAUDE.md](CLAUDE.md); that file retains the
previous handoff and existing local edits. Current user/runtime instructions win.
The shared contract supplies collaboration and permission defaults; this public
repo does not duplicate its machine configuration or private paths.

For behavior, read the relevant dated owner decisions in
[wristworkSpecs.md](wristworkSpecs.md), then the code. The spec's original runbook
is historical. [HEALTH_DESIGN.md](HEALTH_DESIGN.md) establishes the collection,
prosthesis, scaffold sequence; [wristwork-detector-design.md](wristwork-detector-design.md)
establishes the holdout and model gates. Later owner decisions amend both. Code
shows what exists, not proof of deployment or permission to change the design.
Older conflicting instructions do not silently re-activate. Record unresolved
conflicts rather than presenting an inference as an owner decision.

## Purpose and boundaries

wristwork is a personal Wear OS instrument: trustworthy affective labels and
physiology first, a quiet interface to machines and agents alongside it. Collection
supports a future personal state model; scaffolding awareness comes after that
model earns its place. It is not a multi-user product or a household agent backend.
Fetch owns model search, slicing, and print execution; wristwork owns the wrist UI.

- Preserve raw streams, event identities, both event/entry timestamps, and provenance.
  Corrections append revisions or tombstones; analysis creates versioned derivatives.
  Never rewrite the archive to repair an interpretation. Preserve the detector asks
  log too: it records interruptions that actually happened and cannot be replayed.
- `random` labels are evaluation only, forever: no training, tuning, prior revision,
  threshold selection, or model selection using their outcomes. A reconstructed
  guess at provenance must never promote a label into the holdout.
- Keep canonical states and order: SEEK, RAGE, FEAR, LUST, CARE, GRIEF, PLAY, OTHER.
  OTHER displays as Neutral since 2026-08-28; preserve that semantic boundary when
  reading older rows. No front-end subdivisions. Secondary-only labels are valid.
- Labels require explicit Confirm or ECG confirmation. Back and switching events
  discard unconfirmed edits. Never restore the old save-on-exit behavior.
- Preserve the one-hour event protection and blinded prompt copy/timing. Random
  prompts defer under the 2026-09-02 decision; signal prompts lapse when crowded.
  Keep the cap, provenance, and manual-entry exception; budget changes are the
  owner's decision. See the research skill before changing or evaluating this path.
- The approved allocator and prior-only shadow scores already exist. Learned
  inference and display remain gated: respect the design's label-count milestones
  and require random-stream evidence beating the time-of-day baseline before display.
  A setup, review, or documentation task does not open those gates.
- Battery acceptance is a comfortable full wear-day on one charge (owner amendment
  in HEALTH_DESIGN.md), with measured attribution. The old 3% figure is superseded.
  Passive/listener callbacks and the existing WorkManager cadence are authorized;
  no foreground service, alarm, or wake-lock workaround for faster updates.
- Stale or missing data must not look fresh or healthy. Keep truthful ages and
  pending states, silent routine traffic, and visible failures of collection.
- Short press on a print candidate opens details; long press sends the choice,
  which can start a physical print. Preserve expiry, bed-clearance, and Fetch's
  execution limits. Never use a real choice/confirmation as a harmless test.
- Keep credentials, real endpoints, private transcripts, and personal streams out
  of this public repo. `config.properties` and `data/` stay ignored. The bus's
  "public-tier" integration boundary does not make physiological data public;
  Fetch's private-tier communications and derivatives must not cross it.

## Working and checking

Inspect Git status and existing edits first. Default to inline work; this guide
does not authorize subagents. Do routine reversible work within the user's scope;
do not infer deployment, publication, notifications, or physical actions from a
request for local setup. Bundle unavoidable hardware steps and verify their result.
For a substantial evidenced disagreement, pause that action, explain, ask one clear
question, and continue independent work.

Use PowerShell on this workstation, the Gradle wrapper, and the existing interpreter:

```powershell
.\gradlew.bat :app:assembleDebug
python -B tools/rig/healthcheck.py
```

The first builds locally and may need JDK/SDK/Gradle cache access; configuration is
compiled into the APK, so a locally configured APK is private. The second is an
operational check: it reads the bus/server and updates local rolling caches. It is
not an offline test and does not prove everything it labels green. Read
[tools/rig/README.md](tools/rig/README.md) before running rig scripts; several
publish, change credentials, or append operational records when executed.

For a code change, run appropriate build/targeted behavioral checks. CI currently
builds the APK and scans for secrets; there is no checked-in behavioral test suite.
For docs/skills, validate skills, links, whitespace, and confidentiality; no watch
install is necessary. Use synthetic fixtures for event/prompt/print tests. Preserve
LF line endings. Report built, installed, observed, and unverified separately.

Keep the owner-established Git freeze: no staging, committing, or pushing Monday
through Thursday, 06:30–16:00 workstation local time. Continue editing. The existing
`.claude/settings.json` hook is Claude-specific; do not claim it enforces Codex
commands. Read deletion lines before any authorized commit; preserve unrelated work.

## Continuity and skills

Update the living spec when behavior changes and add a dated result to STATE.
Record implementation decisions in [docs/DECISIONS.md](docs/DECISIONS.md).
[docs/ROADMAP.md](docs/ROADMAP.md) holds proposals, not standing authorization.
Announce instruction changes and their material effects in the associated reply.

Repository skills live in `.agents/skills/`; each contains a focused workflow:

- `wristwork-watch-change`: app changes and device verification.
- `wristwork-pipeline-check`: trace freshness and failures without publishing probes.
- `wristwork-research-review`: collection/provenance review and model-readiness gates.

Consulted Agent Core GPT's AGENTS, SECURITY, BOOTSTRAP and project template on
2026-09-06. Adopted the shared operating approach, retaining this repo's data,
hardware, research, and Git-freeze rules. No global harness settings or hooks were
installed by this handoff.
