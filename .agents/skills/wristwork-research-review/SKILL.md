---
name: wristwork-research-review
description: Review wristwork label provenance, prompt sampling and burden, collection quality, or readiness for a personal state model. Use for research-integrity reviews and detector work; retain the permanent random holdout and existing model gates.
---

# Wristwork collection and research review

Read [AGENTS.md](../../../AGENTS.md), the relevant current amendments in
[the living spec](../../../wristworkSpecs.md), and
[the detector design](../../../wristwork-detector-design.md). Read the phased goal
and battery amendment in [HEALTH_DESIGN.md](../../../HEALTH_DESIGN.md) when scope
or acceptance is at issue. Paths assume `.agents/skills/wristwork-research-review/`.

Distinguish the question being asked: collection reliability, allocator usefulness,
learned classification, or eventual awareness scaffolding. The allocator and
prior-only shadow guesser exist; that does not authorize learning or display.
Retain the recorded label-count milestones and baseline/display gates. Do not
launch a literature campaign or model training from an ordinary status review.

For an authorized local review, inspect the actual capture schema and relevant
analysis code, including [reconcile_labels.py](../../../tools/rig/reconcile_labels.py)
before running it. Keep raw files unchanged. Work in a versioned private derived
directory; include code/scorer version, input window, and exclusion reasons without
copying personal samples into tracked documentation.

Preserve these distinctions:

- Raw rows versus unique events versus latest non-tombstoned event revisions.
  Keep event time, entry time, and response delay. Handle known provisioning/test
  artifacts from STATE explicitly; never delete them from the archive.
- Captured `random | signal | self | google` provenance versus derived reconstruction.
  Normalize legacy vocabulary at analysis time. A random-looking timestamp is not
  sufficient to create a holdout label; require captured provenance and a real prompt.
- Random labels are evaluation only, forever. Do not train, tune, revise priors,
  select thresholds or choose models using their outcomes. Use trainable data for
  development and a fixed evaluation for the existing gate. If historical provenance
  is ambiguous, report it separately rather than guessing it into the holdout.
- The spec marks the two pre-September-2 random labels unblinded. Preserve that
  metadata and make cohort handling explicit before interpreting an evaluation.
- Scheduled, delivered, deferred, expired, and answered prompts are different
  denominators. Preserve actual asked-about time when random prompts move.
  Deferral makes observed times depend on prior events; do not call that uniformly
  sampled whole-day experience without evidence. Matching lag options alone does
  not establish matching delivery distributions. Keep the owner's policy until an
  authorized decision changes it; evaluate coverage/blinding separately from outcomes.
- Smoothed BPM variability is not beat-to-beat HRV. ECG-derived intervals need their
  quality checks; vendor flags are weak supervision, not owner ground truth.
  Light and pressure remain context, not anomaly drivers selecting training labels.
- OTHER/Neutral changed meaning on 2026-08-28. Preserve mixtures, secondary-only
  labels, and the canonical eight-state vocabulary rather than silently simplifying.

A collection review can report per-source event counts, delivery/response rate,
latency, wear coverage, and interruption burden without fitting a model. Budgets
remain the owner's choice; do not change them to meet an estimated label deadline.
Use synthetic known-answer cases to verify transforms before interpreting private
data. Keep proposals in ROADMAP or DECISIONS and measured outcomes in STATE.
