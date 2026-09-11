---
name: wristwork-emotion-data-review
description: Review wristwork-emotion label provenance, collection quality, prompt burden, or model-readiness while protecting the permanent random holdout and append-only record.
---

# Review wristwork-emotion data

Read [AGENTS.md](../../../AGENTS.md) and [the data contract](../../../docs/DATA.md).
Work on private local records only unless the wearer explicitly chooses another
destination. Report counts and timestamps without copying raw affective or physiology
rows into public issues, commits, or chat.

Preserve raw envelopes and event identities. Apply corrections as versioned analysis
or append-only revisions; never rewrite the archive to repair an interpretation.
Normalize legacy vocabulary only in derived analysis and record the mapping.

Treat every `source=random` outcome as evaluation-only forever. Do not inspect it to
choose features, priors, thresholds, prompt policies, models, or report variants.
Rows with missing or reconstructed provenance do not enter that holdout. Training
and tuning may use only eligible non-random rows, while display claims require a
fixed evaluation that beats the declared baseline without holdout-driven iteration.

Separate raw rows, logical events, revisions, tombstones, prompts delivered, prompts
dismissed, and answers. Report missingness and latency by source. A configured job or
present file is not proof of fresh collection; date the newest observed record on
each pipeline leg and call unavailable evidence unknown.
