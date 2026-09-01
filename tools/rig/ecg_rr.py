"""Turn an ECG reading into real beat-to-beat intervals, and file them as the `rr` record.

This is the piece the whole HRV argument was about. The watch's ordinary heart-rate stream is a
smoothed integer rate — differencing it measures the firmware, not the heart. A thirty-second ECG
reading is 7,500 samples at 250 Hz of actual voltage, so the beats can be found individually and
the intervals between them measured to within four milliseconds. That is genuine RMSSD.

The output uses the `rr` payload shape that was reserved for a Bluetooth chest strap, because it
is the same thing measured a different way — and the watch turns out to be able to do it, so the
strap is a nice-to-have rather than the only door.

    python tools/rig/ecg_rr.py            file the intervals for any reading not done yet
    python tools/rig/ecg_rr.py --show     print what it finds without publishing

Beat detection is a compact Pan-Tompkins: difference, square, integrate over a moving window, then
take peaks above a fraction of the running maximum with a refractory period. That is enough for a
clean resting single-lead trace; it is NOT a diagnostic tool and nothing here is medical advice.
"""
import importlib.util
import json
import os
import statistics as st
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STATE = os.path.join(ROOT, "data", "ecg-rr-state.json")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stream_cache                                                    # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "health_pull", os.path.join(ROOT, "tools", "rig", "health_pull.py"))
hp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(hp)


def gather(cfg):
    """Reassemble whole readings from the chunked records on the bus, newest window first."""
    readings = {}
    for _, msg in stream_cache.rows(cfg, cfg.get("TOPIC_HEALTH", "health"), 30):
        if msg.get("kind") != "ecg":
            continue
        point = msg.get("point") or {}
        ecg = point.get("electrocardiogram") or {}
        key = point.get("name") or ecg.get("interval", {}).get("startTime")
        if not key:
            continue
        r = readings.setdefault(key, {
            "start": ecg.get("interval", {}).get("startTime"),
            "hz": ecg.get("samplingFrequencyHertz", 250),
            "scale": ecg.get("millivoltsScalingFactor"),
            "bpm": ecg.get("beatsPerMinuteAvg"),
            "classification": ecg.get("resultClassification"),
            "parts": {},
        })
        r["parts"][msg.get("part", 0)] = ecg.get("waveformSamples") or []
    for key, r in readings.items():
        r["samples"] = [v for i in sorted(r["parts"]) for v in r["parts"][i]]
        del r["parts"]
    return readings


def hp_fetch(cfg, topic, since):
    """The health stream, decoded — same transport as everything else."""
    import urllib.request
    url = "%s/%s/json?poll=1&since=%s" % (cfg["NTFY_BASE_URL"].rstrip("/"), topic, since)
    headers = {"User-Agent": "wristwork-ecg-rr/1.0"}
    if cfg.get("NTFY_TOKEN_SVC"):
        headers["Authorization"] = "Bearer " + cfg["NTFY_TOKEN_SVC"]
    req = urllib.request.Request(url, headers=headers)
    out = []
    with urllib.request.urlopen(req, timeout=120) as resp:
        for line in resp.read().decode("utf-8", "replace").splitlines():
            if not line.strip():
                continue
            try:
                o = json.loads(line)
                if o.get("event") == "message":
                    out.append(json.loads(o["message"]))
            except Exception:
                continue
    return out


def r_peaks(samples, hz):
    """Sample indices of the R waves."""
    if len(samples) < hz * 5:
        return []
    # difference and square: emphasise the steep QRS slope over slow drift and low-frequency noise
    diff = [samples[i + 1] - samples[i] for i in range(len(samples) - 1)]
    sq = [d * d for d in diff]
    # integrate over a window a little shorter than a QRS complex
    w = max(3, int(0.12 * hz))
    run, integ = 0.0, []
    for i, v in enumerate(sq):
        run += v
        if i >= w:
            run -= sq[i - w]
        integ.append(run / w)
    peak_ref = sorted(integ)[int(0.995 * len(integ))]
    refractory = int(0.28 * hz)          # no two beats closer than ~210 bpm
    # One threshold does not fit every trace: a reading taken with poor skin contact has a much
    # flatter R wave relative to its noise. Walk the threshold down until the beat count implies
    # a plausible human heart rate, and take the first setting that does.
    best_peaks = []
    for frac in (0.35, 0.25, 0.18, 0.12, 0.08):
        threshold = frac * peak_ref
        peaks, i = [], 0
        while i < len(integ):
            if integ[i] > threshold:
                j = min(len(integ), i + refractory)
                peaks.append(max(range(i, j), key=lambda k: integ[k]))
                i = j
            else:
                i += 1
        seconds = len(samples) / float(hz)
        bpm = len(peaks) * 60.0 / seconds if seconds else 0
        if 40 <= bpm <= 150:
            return peaks
        if len(peaks) > len(best_peaks):
            best_peaks = peaks
    return best_peaks


def intervals(peaks, hz):
    """RR intervals in milliseconds, with implausible ones dropped."""
    rr = [(b - a) * 1000.0 / hz for a, b in zip(peaks, peaks[1:])]
    return [x for x in rr if 300 <= x <= 2000]


def measures(rr):
    if len(rr) < 3:
        return None
    diffs = [b - a for a, b in zip(rr, rr[1:])]
    return {
        "beats": len(rr) + 1,
        "mean_rr_ms": round(st.mean(rr), 1),
        "hr_from_rr": round(60000.0 / st.mean(rr), 1),
        "rmssd_ms": round((sum(d * d for d in diffs) / len(diffs)) ** 0.5, 1),
        "sdnn_ms": round(st.pstdev(rr), 1),
        "pnn50": round(sum(1 for d in diffs if abs(d) > 50) / len(diffs), 3),
    }


def main():
    cfg = hp.config()
    show_only = "--show" in sys.argv
    state = {"done": []}
    if os.path.exists(STATE):
        state = json.load(open(STATE, encoding="utf-8"))
    done = set(state.get("done", []))

    readings = gather(cfg)
    if not readings:
        print("no ECG readings on the bus yet")
        return 0

    filed = 0
    for key, r in sorted(readings.items(), key=lambda kv: kv[1]["start"] or ""):
        if key in done and not show_only:
            continue
        peaks = r_peaks(r["samples"], r["hz"])
        rr = intervals(peaks, r["hz"])
        m = measures(rr)
        if not m:
            print("%s  too few beats found (%d samples)" % (r["start"], len(r["samples"])))
            continue
        # A quality gate, because a bad trace does not fail loudly — it produces numbers. The
        # watch reports its own average rate for the same thirty seconds, so disagreement is the
        # cheapest possible check that the beats found were really beats. One reading in three
        # here was contact noise that yielded four "beats" and an RMSSD of a full second.
        watch_bpm = float(r.get("bpm") or 0)     # the API sends it as a string
        drift = abs(m["hr_from_rr"] - watch_bpm) / float(watch_bpm) if watch_bpm else 1.0
        if drift > 0.15 or m["rmssd_ms"] > 250 or m["beats"] < 15:
            print("%s  REJECTED as noise: %d beats, HR %.1f against the watch's %s, RMSSD %.0f ms"
                  % (r["start"], m["beats"], m["hr_from_rr"], watch_bpm, m["rmssd_ms"]))
            done.add(key)          # do not keep retrying a trace that will never improve
            continue
        print("%s  %d beats | RMSSD %5.1f ms | SDNN %5.1f ms | HR %.1f (watch said %s) | %s"
              % (r["start"], m["beats"], m["rmssd_ms"], m["sdnn_ms"], m["hr_from_rr"],
                 r["bpm"], r["classification"]))
        if show_only:
            continue
        hp.publish(cfg, {
            "kind": "rr",
            "source": "watch-ecg",
            "start": r["start"],
            "hz": r["hz"],
            "classification": r["classification"],
            "rr_ms": [round(x, 1) for x in rr],
            **m,
        })
        done.add(key)
        filed += 1

    if not show_only:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        json.dump({"done": sorted(done)}, open(STATE, "w", encoding="utf-8"), indent=1)
        print("filed %d new interval records" % filed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
