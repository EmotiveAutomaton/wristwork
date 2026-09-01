"""Reconstruct, from evidence, where each label actually came from.

WHY. The owner's recollection (2026-09-01) is that nearly every label was given in answer to a
question, with a single manual test early on. The record disagrees, and the disagreement is worth
keeping rather than smoothing over, because "which labels answered a question" decides what may
enter the evaluation set — the only measurement that can ever say whether the detector beats
chance. A relabelling done from memory would put guesses in that set, and a contaminated holdout
cannot be uncontaminated later.

So this reconstructs provenance from timestamps, which are not a memory:

  carried          the row names a question, and that question was really published. Certain.
  likely-prompted  no question named, but one had been delivered and was still unanswered, and the
                   label was entered within LATE_WINDOW_MIN of that delivery. This is exactly what
                   happened when the grid was opened from the face instead of the notification —
                   the fault fixed on 2026-09-01, which until then dropped the attribution.
  unprompted       no question was outstanding. Genuinely self-initiated.
  pre-prompt-era   entered before any question had ever been published. Cannot have been prompted.

THE LAW THIS OBEYS: labels.jsonl is never touched. This writes a derived, versioned, recomputable
file beside it, and the analysis joins on event_id.

THE RULE THIS ENFORCES: a reconstructed attribution may never place a label in the evaluation
stream. Only `carried` rows naming a random question count as evaluation data. A likely-prompted
row is usable for TRAINING, where a wrong guess costs a little accuracy, and is barred from the
holdout, where a wrong guess costs the whole measurement.

    python tools/rig/reconcile_labels.py            write the provenance file
    python tools/rig/reconcile_labels.py --show     print the reconstruction without writing
"""
import datetime
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stream_cache                                                    # noqa: E402

VERSION = "v1-2026-09-01"
OUT = os.path.join(ROOT, "data", "labels-provenance-%s.jsonl" % VERSION)

# How long after a question was delivered a label may still be counted as its answer. The watch
# drops an undelivered question after 45 minutes; once delivered, the notification sits there, and
# the observed answering delays run from 25 minutes to over five hours. Three hours keeps the
# plausible cases and refuses the ones where the person had plainly moved on.
LATE_WINDOW_MIN = 180


def config():
    cfg = {}
    with open(os.path.join(ROOT, "config.properties"), encoding="utf-8") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def epoch(iso):
    try:
        return datetime.datetime.fromisoformat(iso).timestamp()
    except Exception:
        return None


def main():
    cfg = config()
    show = "--show" in sys.argv
    labels = [m for _, m in stream_cache.rows(cfg, cfg.get("TOPIC_TAGS", "tags"), 30)]
    prompts = [m for _, m in stream_cache.rows(cfg, cfg.get("TOPIC_PROMPTS", "prompts"), 30)]
    if not labels:
        print("no labels on the bus")
        return 1

    first_prompt = min((p["deliver_at"] for p in prompts if p.get("deliver_at")), default=None)
    named = {m.get("prompt_id") for m in labels if m.get("prompt_id")}
    published = {p.get("prompt_id"): p for p in prompts if p.get("prompt_id")}

    out, counts = [], {}
    for m in labels:
        entered = epoch(m.get("ts_entered", "") or "")
        moment = epoch(m.get("ts_event", "") or "")
        pid = m.get("prompt_id")
        kind, evidence = "unprompted", {}

        if pid and pid in published:
            kind = "carried"
            evidence = {"prompt_id": pid, "source": published[pid].get("source")}
        elif pid:
            # Names a question that was never published: a bug, not a provenance.
            kind = "carried-unknown-question"
            evidence = {"prompt_id": pid}
        elif entered is None or (first_prompt and entered < first_prompt):
            kind = "pre-prompt-era"
        else:
            # The best unanswered question that had been delivered before this was entered.
            best = None
            for p in prompts:
                d = p.get("deliver_at")
                if not d or p.get("prompt_id") in named:
                    continue                      # somebody else already answered that one
                if not (0 <= entered - d <= LATE_WINDOW_MIN * 60):
                    continue
                if best is None or d > best.get("deliver_at", 0):
                    best = p
            if best:
                kind = "likely-prompted"
                evidence = {
                    "prompt_id": best["prompt_id"], "source": best.get("source"),
                    "delivered_min_before": round((entered - best["deliver_at"]) / 60),
                }

        counts[kind] = counts.get(kind, 0) + 1
        out.append({
            "version": VERSION,
            "event_id": m.get("event_id"),
            "ts_event": m.get("ts_event"),
            "ts_entered": m.get("ts_entered"),
            "source_as_filed": m.get("source"),
            "provenance": kind,
            "evidence": evidence,
            # THE GATE. Only a label that names a real random question may measure the detector.
            "evaluation_eligible": bool(
                kind == "carried" and evidence.get("source") == "random"),
            # How long after the moment it describes the answer was actually given. A label
            # recalled three hours later is a memory, not an observation, and the analysis should
            # be able to weight or exclude on this without recomputing it.
            "answer_delay_min": (round((entered - moment) / 60)
                                 if entered and moment else None),
        })

    print("labels reconciled: %d" % len(out))
    for k in sorted(counts):
        print("   %-28s %d" % (k, counts[k]))
    print("   %-28s %d" % ("evaluation-eligible", sum(1 for o in out if o["evaluation_eligible"])))
    delays = [o["answer_delay_min"] for o in out if o["answer_delay_min"] is not None]
    if delays:
        delays.sort()
        print("   answer delay, minutes: median %d, worst %d, over an hour: %d of %d"
              % (delays[len(delays) // 2], delays[-1],
                 sum(1 for d in delays if d > 60), len(delays)))
    if show:
        return 0
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        for o in out:
            fh.write(json.dumps(o) + "\n")
    print("wrote %s" % os.path.relpath(OUT, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
