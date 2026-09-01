"""The detector's first pass: find unusual moments in the owned physiology stream.

Runs on the rig every fifteen minutes (Task Scheduler: "wristwork-detect"). Reads the health
stream off the bus, folds it into five-minute epochs, scores each epoch against a time-of-day
matched baseline of the owner's own recent days, and writes the result to a derived, recomputable
file. In SHADOW mode it stops there. In LIVE mode it also ASKS: when a recent epoch is stranger than
almost anything else in that time-of-day slot, it posts a prompt, and the watch raises it as the
same blinded question a random prompt raises. Owner, 2026-08-28: "find some way to arrange
unusual patterns and then ask what just happened" — the argument being that random sampling of a
mostly-ordinary life returns mostly neutral labels, which teach a model very little.

Three guards keep that honest. The budget is small and enforced per day. A refractory period stops
a single strange hour from producing a burst. And the RANDOM stream continues alongside at its own
budget, because a label the detector asked for cannot be used to measure whether the detector is
any good — only the unasked-for ones can.

Detector design, sections 3 and 4. Two things it deliberately is NOT:
  * it is not a classifier. It ranks strangeness, nothing more.
  * it does not learn from labels. When it eventually does (v2), the random-prompt stream still
    never enters training — that is the holdout rule, and it is the whole integrity of the project.

The prior-only "shadow guess" — a mixture over the eight states derived from the literature table
in the design, with NO learned parameters — is logged beside each epoch and never displayed. Its
running hit rate against real labels is a free test of whether those priors describe this person.

Two output files, and the split matters. data/detector.jsonl holds one line per epoch scored,
versioned by SCORER_VERSION — a derived artifact that can be deleted and recomputed at any time.
data/detector-asks.jsonl holds every prompt actually sent. That is not derived: it is a record of
something that happened in the world, it is what the daily budget and the quiet period are counted
against, and it must survive any recompute. (Learned immediately: recomputing the scores wiped the
firing history and the detector promptly asked again inside its own quiet period.)
"""
import json
import math
import os
import statistics as st
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stream_cache                                                    # noqa: E402

SCORER_VERSION = "v2-2026-09-01"   # light and pressure no longer select moments
EPOCH_MIN = 5              # epoch length
BASELINE_DAYS = 7          # how far back the time-of-day baseline reaches
TOD_BUCKET_MIN = 120       # baseline matches epochs from the same 2-hour slot of the day
MIN_BASELINE_N = 6         # fewer comparable epochs than this and we score nothing
WAKING_EPOCHS_PER_DAY = 192   # ~16 waking hours of five-minute epochs; sets the firing percentile
RECENT_WINDOW_MIN = 25        # only ask about something that happened in the last few minutes
FLOOR_Z = 2.0                 # never ask about a moment that is not at least this far out
INTERACTION_QUIET_MIN = 12    # minutes around a label entry that are ours, not the wearer's

# Recorded and available to the rules, but never z-scored: see the note in features().
#
# LIGHT AND PRESSURE JOINED THIS SET ON 2026-09-01, and the reason is the most important thing in
# this file. An audit of the first fourteen questions the detector asked found EIGHT of them were
# fired by the ambient light sensor, with z-scores of 77, 55, 30, 20, 13 and 12 -- walking
# outdoors, or a lamp coming on at 05:47 against a night-time baseline whose spread is nearly
# zero. Barometric pressure fired a ninth. Neither is physiology: they describe the room, not the
# person. Left in the firing rule they select the training set on illumination, which quietly
# turns the whole collection into a study of when the lights change. They stay in the raw stream
# and remain available to a model as CONTEXT -- knowing it was dark matters when interpreting a
# state -- but they may never again decide which moment is worth interrupting someone for.
NOT_SCORED = {"calories_sum", "light", "light_sd", "pressure", "pressure_sd"}

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def config():
    cfg = {}
    with open(os.path.join(ROOT, "config.properties"), encoding="utf-8") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def fetch(cfg, topic, since):
    url = "%s/%s/json?poll=1&since=%s" % (cfg["NTFY_BASE_URL"].rstrip("/"), topic, since)
    headers = {"User-Agent": "wristwork-detect/1.0"}
    if cfg.get("NTFY_TOKEN_SVC"):
        headers["Authorization"] = "Bearer " + cfg["NTFY_TOKEN_SVC"]
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        for line in r.read().decode("utf-8", "replace").splitlines():
            if not line.strip():
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            if o.get("event") != "message":
                continue
            try:
                yield o["time"], json.loads(o["message"])
            except Exception:
                continue


def epochs_from(stream):
    """Fold the raw stream into {epoch_start_epoch_s: {feature: [values]}}."""
    size = EPOCH_MIN * 60
    buckets = {}

    def put(t, key, value):
        b = buckets.setdefault(int(t) // size * size, {})
        b.setdefault(key, []).append(value)

    for msg_time, payload in stream:
        kind = payload.get("kind")
        if kind == "hr":
            for t, v in payload.get("samples", []):
                put(t, "hr", float(v))
        elif kind == "skin_temp":
            # Pre-2026-08-28 shape: skin temperature arrived as its own record rather than in the
            # context sweep. Both are read, so the baseline reaches back across the change.
            for t, v in payload.get("samples", []):
                put(t, "skin_temp", float(v))
        elif kind in ("steps", "calories", "distance", "floors", "elevation"):
            for t, v in payload.get("samples", []):
                put(t, kind, float(v))
        elif kind == "ctx":
            t = payload.get("t", msg_time)
            for key in ("skin_temp", "light", "pressure", "cadence", "offbody"):
                if key in payload:
                    put(t, key, float(payload[key]))
        elif kind == "activity":
            put(payload.get("t", msg_time), "activity", payload.get("state", ""))
    return buckets


def features(bucket):
    """One epoch's feature vector. Absent channels are absent, never zero."""
    f = {}
    hr = bucket.get("hr", [])
    if len(hr) >= 5:
        f["hr_mean"] = st.mean(hr)
        # The rate-variability proxy. NOT RMSSD: the watch reports a smoothed integer rate, so
        # this measures how much the RATE moves, which is a different (and honest) quantity.
        f["hr_sd"] = st.pstdev(hr)
        f["hr_range"] = max(hr) - min(hr)
        half = len(hr) // 2
        if half >= 3:
            f["hr_drift"] = st.mean(hr[half:]) - st.mean(hr[:half])
    # Calories is deliberately NOT a feature (researched 2026-08-28). The platform's calorie
    # channel is total expenditure: a per-person constant basal rate plus a TIERED function of
    # heart rate and motion — a quantised, lossy transform of two channels we already record at
    # far better resolution, and no wearable-affect literature uses estimated energy expenditure
    # as an arousal marker. Its step-shaped jumps would read as deviations that are really just
    # the tier changing. It stays in the raw stream (it is free, and it cross-checks motion) and
    # out of the model.
    for k in ("steps", "distance", "floors", "elevation"):
        if bucket.get(k):
            f[k + "_sum"] = sum(bucket[k])
    if bucket.get("calories"):
        f["calories_sum"] = sum(bucket["calories"])   # motion cross-check only, never scored
    for k in ("skin_temp", "light", "pressure", "cadence"):
        vals = bucket.get(k, [])
        # Cadence reports -1 when there is no walking to measure. That is a sentinel, not a
        # measurement, and averaging it into a baseline would be nonsense.
        if k == "cadence":
            vals = [v for v in vals if v >= 0]
        if vals:
            f[k] = st.mean(vals)
            if len(vals) >= 3:
                f[k + "_sd"] = st.pstdev(vals)
    return f


def usable(bucket):
    """A reading is about the wearer only if the watch was on the wrist and they were awake.

    The sensor is NAMED off-body detect but reports 1.0 for ON body and 0.0 for OFF (Android's
    own definition, and confirmed on-device: readings of 1.0 came with a live pulse and skin at
    35 C). This mask was written the other way round for a day, which would have thrown away every
    worn epoch and kept only the ones recorded on the charger — the exact inversion that ruins a
    dataset while looking like it is working."""
    off = bucket.get("offbody", [])
    if off and st.mean(off) < 0.5:
        return False, "offbody"
    # Sleep is a STATE that persists, not an event that happens. The activity channel reports
    # roughly once every forty minutes, so it lands in about one five-minute epoch in eight -- and
    # this test, reading only the epoch's own bucket, therefore passed almost every sleeping epoch
    # as usable. Six of the detector's first fourteen questions fired between 02:00 and 08:00
    # (2026-09-01 audit). The caller carries the last known state forward into `activity_held`.
    act = bucket.get("activity", []) or bucket.get("activity_held", [])
    if act and act[-1] == "USER_ACTIVITY_ASLEEP":
        return False, "asleep"
    return True, None


def score(now_features, baseline):
    """Symmetric z-scores against the time-of-day baseline; unusual FLATNESS counts too."""
    zs = {}
    for k, v in now_features.items():
        if k in NOT_SCORED:
            continue
        hist = baseline.get(k, [])
        if len(hist) < MIN_BASELINE_N:
            continue
        mu = st.mean(hist)
        sd = st.pstdev(hist)
        if sd < 1e-6:
            continue
        zs[k] = (v - mu) / sd
    if not zs:
        return None, {}, 0.0
    # Two numbers, because they answer different questions. The RMS rewards several channels
    # moving together. The MAX is the owner's rule — "exceeding standard deviation limits across
    # any metric would be enough to make an ask" — and it is what decides whether to interrupt,
    # because one channel doing something genuinely strange is worth a question even when
    # everything else is ordinary.
    combined = math.sqrt(sum(z * z for z in zs.values()) / len(zs))
    peak = max(abs(z) for z in zs.values())
    return combined, zs, peak


# The literature priors, transcribed from the design's table. Deliberately mass-spread: the
# evidence supports arousal and family-level patterning, not confident single answers.
def shadow_guess(f, zs):
    hr_up = zs.get("hr_mean", 0) > 1.0
    hr_down = zs.get("hr_mean", 0) < -1.0
    var_up = zs.get("hr_sd", 0) > 1.0
    var_down = zs.get("hr_sd", 0) < -1.0
    moving = f.get("steps_sum", 0) > 20 or zs.get("cadence", 0) > 1.0 or f.get("calories_sum", 0) > 3.0
    if hr_up and not moving:
        return {"SEEK": .30, "FEAR": .25, "RAGE": .25, "LUST": .10, "PLAY": .10}
    if hr_down and var_down:
        return {"OTHER": .35, "GRIEF": .30, "SEEK": .20, "CARE": .15}
    if hr_down and not moving:
        return {"CARE": .40, "OTHER": .30, "SEEK": .20, "GRIEF": .10}
    if var_up and moving:
        return {"PLAY": .35, "SEEK": .30, "RAGE": .20, "FEAR": .15}
    return {"OTHER": .40, "SEEK": .25, "CARE": .20, "GRIEF": .15}


def fire_threshold(peaks, per_day):
    """Self-calibrating sensitivity: the level only ~per_day epochs a day clear.

    Expressed as a percentile of the owner's own recent distribution rather than a fixed number,
    so it keeps meaning as the baseline shifts. The floor stops a very calm week from lowering the
    bar until ordinary moments qualify."""
    if not peaks:
        return None
    frac = max(0.0, 1.0 - (per_day / float(WAKING_EPOCHS_PER_DAY)))
    ordered = sorted(peaks)
    idx = min(len(ordered) - 1, int(frac * len(ordered)))
    return max(FLOOR_Z, ordered[idx])


def already_fired_today(rows, now):
    """(count today, epoch seconds of the most recent firing)."""
    today = time.strftime("%Y-%m-%d", time.localtime(now))
    count, last = 0, 0
    for r in rows:
        if not r.get("fired"):
            continue
        when = r.get("fired_at", r["epoch"])
        if time.strftime("%Y-%m-%d", time.localtime(when)) == today:
            count += 1
        last = max(last, when)
    return count, last


def label_times(cfg):
    """When labels were ENTERED, so the detector never asks about a moment it created itself.

    Answering a prompt means looking at a watch and thinking about feelings for a minute, which
    moves a heart rate all by itself. Those minutes are genuinely unusual and completely
    uninteresting, and without this the thing feeds on its own tail."""
    out = []
    try:
        for _, payload in stream_cache.rows(cfg, cfg.get("TOPIC_TAGS", "tags"), 2):
            ts = payload.get("ts_entered")
            if not ts:
                continue
            try:
                out.append(time.mktime(time.strptime(ts[:19], "%Y-%m-%dT%H:%M:%S")))
            except Exception:
                pass
    except Exception:
        pass
    return out


def post_prompt(cfg, epoch_center, now):
    """One prompt, worded by the WATCH. Nothing here describes what was detected: the copy is
    identical for random and signal prompts, and that blinding is what keeps the comparison
    between them meaningful."""
    body = json.dumps({
        "prompt_id": "s-%d" % epoch_center,
        "source": "signal",
        "deliver_at": int(now),
        "ts": int(epoch_center),
    }).encode()
    url = "%s/%s" % (cfg["NTFY_BASE_URL"].rstrip("/"), cfg.get("TOPIC_PROMPTS", "prompts"))
    headers = {"User-Agent": "wristwork-detect/1.0", "Priority": "min",
               "Content-Type": "application/json"}
    if cfg.get("NTFY_TOKEN_SVC"):
        headers["Authorization"] = "Bearer " + cfg["NTFY_TOKEN_SVC"]
    req = urllib.request.Request(url, data=body, headers=headers)
    urllib.request.urlopen(req, timeout=20).read()


def main():
    cfg = config()
    mode = cfg.get("DETECTOR_MODE", "shadow").strip().lower()
    out_path = os.path.join(ROOT, "data", "detector.jsonl")
    asks_path = os.path.join(ROOT, "data", "detector-asks.jsonl")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    # Through the local rolling copy, not straight off the bus: pulling seven days of physiology
    # every fifteen minutes exhausted the server's daily read allowance, after which it answered
    # 200 and cut the response short — silently costing five hours of scoring on 2026-08-31.
    buckets = epochs_from(stream_cache.rows(cfg, cfg.get("TOPIC_HEALTH", "health"), BASELINE_DAYS))
    if not buckets:
        print("no health data in window")
        return

    # Carry the activity state forward: the watch reports it far too rarely for a five-minute
    # epoch to contain one, and a guard that only knows the state one epoch in eight is off seven
    # eighths of the time.
    held = None
    for start in sorted(buckets):
        seen = buckets[start].get("activity")
        if seen:
            held = seen[-1]
        elif held is not None:
            buckets[start]["activity_held"] = [held]

    # Feature table for every epoch, then a time-of-day matched baseline per feature.
    table = {}
    for start, bucket in sorted(buckets.items()):
        ok, why = usable(bucket)
        f = features(bucket)
        if f:
            table[start] = {"f": f, "ok": ok, "why": why}

    def tod_bucket(t):
        lt = time.localtime(t)
        return (lt.tm_hour * 60 + lt.tm_min) // TOD_BUCKET_MIN

    already = set()
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as fh:
            for line in fh:
                try:
                    o = json.loads(line)
                    if o.get("scorer") == SCORER_VERSION:
                        already.add(o["epoch"])
                except Exception:
                    pass

    now = time.time()
    written = 0
    scored = []
    zs_by_epoch = {}        # what each epoch's deviations were, so a firing can name its cause          # (epoch, peak) for everything written this pass
    with open(out_path, "a", encoding="utf-8") as fh:
        for start in sorted(table):
            if start in already or start > now - EPOCH_MIN * 60:
                continue
            row = table[start]
            if not row["ok"]:
                continue
            baseline = {}
            for other, orow in table.items():
                if other == start or not orow["ok"]:
                    continue
                if other > start or start - other > BASELINE_DAYS * 86400:
                    continue
                if tod_bucket(other) != tod_bucket(start):
                    continue
                for k, v in orow["f"].items():
                    baseline.setdefault(k, []).append(v)
            combined, zs, peak = score(row["f"], baseline)
            if combined is None:
                continue
            scored.append((start, peak))
            zs_by_epoch[start] = zs
            fh.write(json.dumps({
                "epoch": start,
                "scorer": SCORER_VERSION,
                "mode": mode,
                "score": round(combined, 3),
                "peak_z": round(peak, 3),
                "z": {k: round(v, 2) for k, v in sorted(zs.items())},
                "f": {k: round(v, 3) for k, v in sorted(row["f"].items())},
                "guess": shadow_guess(row["f"], zs),
                "n_baseline": max((len(v) for v in baseline.values()), default=0),
            }) + "\n")
            written += 1

    print("scored %d new epochs (%d total in window), mode=%s" % (written, len(table), mode))

    # ---- ask, if the mode allows it and the moment earns it ----
    rows = []
    with open(out_path, encoding="utf-8") as fh:
        for line in fh:
            try:
                rows.append(json.loads(line))
            except Exception:
                pass
    asks = []
    if os.path.exists(asks_path):
        with open(asks_path, encoding="utf-8") as fh:
            for line in fh:
                try:
                    asks.append(json.loads(line))
                except Exception:
                    pass
    peaks = [r["peak_z"] for r in rows if "peak_z" in r]
    per_day = float(cfg.get("SIGNAL_PER_DAY", 3))
    refractory = float(cfg.get("SIGNAL_REFRACTORY_MIN", 90)) * 60
    threshold = fire_threshold(peaks, per_day)
    if threshold is None:
        return
    fired_today, last_fire = already_fired_today(asks, now)
    print("threshold peak-z %.2f (target %.0f/day); fired today %d, last %s"
          % (threshold, per_day, fired_today,
             time.strftime("%H:%M", time.localtime(last_fire)) if last_fire else "never"))
    if mode != "live":
        return
    if fired_today >= per_day or (last_fire and now - last_fire < refractory):
        return
    entered = label_times(cfg)
    candidates = [
        (e, pk) for e, pk in scored
        if pk >= threshold
        and now - e <= RECENT_WINDOW_MIN * 60
        and not any(abs(e - t) < INTERACTION_QUIET_MIN * 60 for t in entered)
    ]
    if not candidates:
        return
    epoch_start, peak = max(candidates, key=lambda c: c[1])
    epoch_center = epoch_start + EPOCH_MIN * 30      # the middle of the epoch, not its edge
    # WHICH CHANNEL FIRED THIS, recorded at the time. Reconstructing it after the fact took an
    # archaeology session and is what exposed the light-sensor problem (2026-09-01); a question
    # that cannot say what provoked it cannot be audited, and this is the file that must survive
    # a recompute.
    zs = zs_by_epoch.get(epoch_start) or {}
    driver = max(zs.items(), key=lambda kv: abs(kv[1]))[0] if zs else None
    try:
        post_prompt(cfg, epoch_center, now)
    except Exception as exc:
        print("prompt post FAILED: %s" % exc)
        return
    with open(asks_path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps({
            "epoch": int(epoch_center), "scorer": SCORER_VERSION,
            "prompt_id": "s-%d" % epoch_center,
            "fired": True, "fired_at": int(now), "peak_z": round(peak, 3),
            "threshold": round(threshold, 3),
            "driver": driver,
            "driver_z": round(zs[driver], 3) if driver else None,
            "z": {k: round(v, 2) for k, v in sorted(zs.items(), key=lambda kv: -abs(kv[1]))[:5]},
        }) + "\n")
    print("ASKED about %s (peak z %.2f)" % (time.strftime("%H:%M", time.localtime(epoch_center)), peak))


if __name__ == "__main__":
    sys.exit(main())
