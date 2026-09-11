---
name: wristwork-pipeline-check
description: Diagnose wristwork collection freshness, missing prompts, silent feeds, health-account ingestion, archives, or scheduled jobs. Use for operational checks; it does not authorize publishing test events or changing live jobs.
---

# Trace a quiet wristwork pipeline

Read [AGENTS.md](../../../AGENTS.md), the newest
[STATE](../../../docs/STATE.md), [rig README](../../../tools/rig/README.md),
and the script for the affected leg. Paths assume this skill is installed at
`.agents/skills/wristwork-pipeline-check/`; commands run from the repo root.

Trace the symptom along its actual path: device queue → authenticated bus → durable
archive → local mirror/cache → detector or face. An old STATE receipt is historical
evidence; a scheduled exit code or cached row alone does not prove today's path.

Start with local file metadata and task registration/results. Inspect only needed
personal records and report aggregates. For live reads, use the existing local
configuration without printing credentials or endpoints. Never upload private
samples to a connector, issue, or public report.

`python -B tools/rig/healthcheck.py` is an operational check, not an offline test:
it reads the bus/server and refreshes a local rolling cache. Use it when those
reads are in scope. Check its current implementation rather than inheriting these
2026-09-06 limitations forever:

- Token presence is not proof of a successful refresh or recent ECG/overnight pull.
- Empty task-query output can look green. Check command success and presence of
  expected jobs independently; include last-run time and artifact freshness.
- HTTP 200 can contain an in-band truncation/error envelope. Treat counts from an
  incomplete read as lower bounds; do not report an empty tail as no new events.
- Bus availability, archival progress, local mirror freshness, and watch delivery
  are separate checks. A published prompt is not proof it was shown or answered.

Read [stream_cache.py](../../../tools/rig/stream_cache.py) before investigating
backlogs; repeated full-history network reads previously exhausted the read allowance.
Prefer incremental reads. Keep raw archives/mirrors and `detector-asks.jsonl` intact.
Rolling caches and derived scores are not substitutes for those records.

Do not execute `detect.py`, the allocator, health puller, mirror/canary, stats feeder,
or provisioning scripts as generic probes: they can publish, append operational
records, change credentials or affect jobs. If repair is already authorized, inspect
the exact change and job ownership, perform it within that scope, then verify it;
otherwise finish the diagnosis and make the proposed repair concrete.

If a device step is needed, request the specific physical action, then verify the
result yourself. Avoid reset/re-pair loops and app-data deletion. Report each leg
as observed working, failed, or unknown with a timestamp and relevant evidence.
Put a concise dated outcome in STATE without private payloads or unverified claims.
