"""Is the whole thing actually running? One command, one answer per leg.

Written because every failure this project has had looked identical from the outside: a queue that
retries forever, a prompt that is never delivered, a mask with its polarity inverted, an archiver
that reconnected but stopped writing. None of them announced themselves, and all of them would
have been caught in seconds by counting rows and looking at the newest timestamp.

Run it whenever something feels quiet:  python tools/rig/healthcheck.py
Exit code is the number of failures, so it can be scheduled and alerted on later.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stream_cache                                                    # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FAILURES = []
TRUNCATED = []      # topics where the server stopped answering part-way through


def config():
    cfg = {}
    with open(os.path.join(ROOT, "config.properties"), encoding="utf-8") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def say(ok, label, detail):
    mark = "  ok  " if ok else " FAIL "
    print("[%s] %-26s %s" % (mark, label, detail))
    if not ok:
        FAILURES.append(label)


def fetch(cfg, topic, since):
    url = "%s/%s/json?poll=1&since=%s" % (cfg["NTFY_BASE_URL"].rstrip("/"), topic, since)
    headers = {"User-Agent": "wristwork-healthcheck/1.0"}
    if cfg.get("NTFY_TOKEN_SVC"):
        headers["Authorization"] = "Bearer " + cfg["NTFY_TOKEN_SVC"]
    req = urllib.request.Request(url, headers=headers)
    out = []
    with urllib.request.urlopen(req, timeout=30) as r:
        for line in r.read().decode("utf-8", "replace").splitlines():
            if not line.strip():
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            # The server signals "I stopped early" as an ordinary line inside a 200 response —
            # a daily read allowance, a rate limit. Dropping it as unparseable is how a health
            # check reports green while every reader is quietly seeing a partial stream
            # (2026-08-31). A check that cannot see its own blindfold is worse than none.
            if o.get("http") and o.get("error"):
                TRUNCATED.append("%s: %s" % (topic, o.get("error")))
                break
            if o.get("event") == "message":
                out.append(o)
    return out


def cached(cfg, topic, days):
    """The local rolling copy, in the same envelope shape `fetch` returns."""
    path, _ = stream_cache._paths(topic)
    stream_cache.refresh(cfg, topic, days)
    out, cutoff = [], time.time() - days * 86400
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except Exception:
                continue
            if o.get("event") == "message" and o["time"] >= cutoff:
                out.append(o)
    return out


def ago(epoch_s):
    mins = (time.time() - epoch_s) / 60.0
    if mins < 90:
        return "%d min ago" % mins
    if mins < 60 * 48:
        return "%.1f h ago" % (mins / 60)
    return "%.1f days ago" % (mins / 1440)


def main():
    cfg = config()

    # --- the bus itself ---
    try:
        # The user agent is not optional: Cloudflare refuses urllib's default outright, which is
        # exactly how the notification hook failed silently for half a day.
        probe = urllib.request.Request(
            cfg["NTFY_BASE_URL"].rstrip("/") + "/v1/health",
            headers={"User-Agent": "wristwork-healthcheck/1.0"})
        with urllib.request.urlopen(probe, timeout=20) as r:
            say(r.status == 200, "bus reachable", "over the public hostname")
    except Exception as e:
        say(False, "bus reachable", str(e))
        return len(FAILURES)

    # --- the streams, and how fresh each one is ---
    for topic_key, label, stale_min in (
        ("TOPIC_HEALTH", "physiology arriving", 30),
        ("TOPIC_RIG", "rig stats arriving", 20),
        ("TOPIC_PROMPTS", "prompts posted", 60 * 30),
        ("TOPIC_TAGS", "labels arriving", 60 * 24 * 3),
    ):
        topic = cfg.get(topic_key)
        try:
            # The physiology stream is by far the largest thing on the bus; asking for two days of
            # it twice per check is what a read allowance is spent on. Everything else is small
            # enough to ask the server directly.
            msgs = (cached(cfg, topic, 2) if topic_key == "TOPIC_HEALTH"
                    else fetch(cfg, topic, "48h"))
        except Exception as e:
            say(False, label, "fetch failed: %s" % e)
            continue
        if not msgs:
            say(False, label, "nothing in 48 h")
            continue
        newest = max(m["time"] for m in msgs)
        fresh = (time.time() - newest) / 60.0 <= stale_min
        say(fresh, label, "%d in 48 h, newest %s" % (len(msgs), ago(newest)))

    # --- what the physiology stream actually contains ---
    try:
        msgs = [m for m in cached(cfg, cfg["TOPIC_HEALTH"], 2)
                if m["time"] > time.time() - 6 * 3600]
        kinds = {}
        for m in msgs:
            try:
                kinds[json.loads(m["message"]).get("kind")] = 1 + kinds.get(
                    json.loads(m["message"]).get("kind"), 0)
            except Exception:
                pass
        essential = {"hr", "ctx"}
        say(essential.issubset(kinds), "channels present",
            ", ".join("%s=%d" % kv for kv in sorted(kinds.items())) or "none")
    except Exception as e:
        say(False, "channels present", str(e))

    # --- the detector ---
    det = os.path.join(ROOT, "data", "detector.jsonl")
    asks = os.path.join(ROOT, "data", "detector-asks.jsonl")
    if os.path.exists(det):
        rows = sum(1 for _ in open(det, encoding="utf-8"))
        newest = 0
        for line in open(det, encoding="utf-8"):
            try:
                newest = max(newest, json.loads(line).get("epoch", 0))
            except Exception:
                pass
        say(rows > 0 and (time.time() - newest) < 3 * 3600,
            "detector scoring", "%d epochs, newest %s" % (rows, ago(newest)))
    else:
        say(False, "detector scoring", "no scores written yet")
    n_asks = sum(1 for _ in open(asks, encoding="utf-8")) if os.path.exists(asks) else 0
    say(True, "detector asks on record", "%d (this file must survive a recompute)" % n_asks)

    # --- the scheduled work on this machine ---
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-ScheduledTask -TaskName 'wristwork*' | ForEach-Object { "
             "$i = Get-ScheduledTaskInfo $_.TaskName; "
             "'{0}={1}' -f $_.TaskName, $i.LastTaskResult }"],
            capture_output=True, text=True, timeout=60).stdout
        tasks = dict(
            line.strip().split("=") for line in out.splitlines() if "=" in line
        )
        bad = [k for k, v in tasks.items() if v not in ("0", "267011")]
        say(not bad, "scheduled jobs",
            "%d registered%s" % (len(tasks), (", failing: " + ", ".join(bad)) if bad else ""))
    except Exception as e:
        say(False, "scheduled jobs", str(e))

    # --- the archive on the server ---
    host = cfg.get("RAID_SSH_HOST")
    if host:
        try:
            out = subprocess.run(
                ["ssh", "-n", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", host,
                 "cd /volume1/docker/wristwork/labels && wc -l *.jsonl | tail -5"],
                capture_output=True, text=True, timeout=60).stdout.strip()
            say(bool(out), "archive on the server", out.replace("\n", " | "))
        except Exception as e:
            say(False, "archive on the server", str(e))

    # --- the one thing that is not wired yet ---
    say(bool(cfg.get("GHEALTH_REFRESH_TOKEN")), "ECG / sleep / overnight HRV",
        "authorised" if cfg.get("GHEALTH_REFRESH_TOKEN")
        else "NOT authorised yet — python tools/rig/health_pull.py --auth")

    # Reported last and on its own, because it invalidates everything above it: a truncated read
    # makes every count in this report a lower bound rather than a measurement.
    say(not TRUNCATED, "bus served whole answers",
        "yes" if not TRUNCATED else "NO — %s. Every count above is a floor, not a figure."
        % "; ".join(sorted(set(TRUNCATED))))

    print()
    if FAILURES:
        print("%d thing(s) need attention: %s" % (len(FAILURES), ", ".join(FAILURES)))
    else:
        print("everything green")
    return len(FAILURES)


if __name__ == "__main__":
    sys.exit(main())
