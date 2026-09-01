"""Read a bus topic incrementally, keeping a local rolling copy.

WHY THIS EXISTS (2026-08-31). The detector re-downloaded the whole seven-day physiology history
every fifteen minutes, and the beat-interval job re-downloaded thirty days every hour: together
about 800 MB of reads a day against the server's 500 MB daily allowance. Past that point the bus
answered `HTTP 200` and then cut the response off with

    {"code":42905,"http":429,"error":"limit reached: daily bandwidth reached"}

as its final line. Nothing raised. Every reader simply saw fewer messages than exist, the detector
scored zero new epochs because the newest hours were the part that got cut, and the scheduled task
exited successfully. Five hours of scoring were lost before a health check caught it — the same
shape of failure as the two-day upload backlog in August, and for the same reason: a truncated
answer is indistinguishable from a short one unless something checks.

Two rules follow, and both live here so no caller can forget them:

  1. **Never re-download what we already have.** The bus is asked only for messages after the last
     one we hold (ntfy accepts a message id as `since`). A fifteen-minute run pulls fifteen minutes.
  2. **A truncated answer is a failure, out loud.** The limit line is detected and raised, never
     quietly dropped as an unparseable row.

The cache under `data/` is a derived artifact in the project's sense: delete it and the next run
rebuilds it from the bus, and the permanent archive on the server remains the record of truth.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CACHE_DIR = os.path.join(ROOT, "data")

# Keep a little more than any caller asks for, so a window boundary never trims live data.
PRUNE_MARGIN_H = 6

# topic -> the nightly mirror that holds the same envelopes, where the names differ.
MIRROR_NAMES = {"tags": "labels"}


class BusTruncated(RuntimeError):
    """The server stopped mid-answer. Callers must not treat the partial result as complete."""


def _paths(topic):
    return (os.path.join(CACHE_DIR, "cache-%s.jsonl" % topic),
            os.path.join(CACHE_DIR, "cache-%s.state.json" % topic))


def _poll(cfg, topic, since):
    """One poll of a topic. Returns raw envelope lines; raises if the answer was cut short."""
    url = "%s/%s/json?poll=1&since=%s" % (cfg["NTFY_BASE_URL"].rstrip("/"), topic, since)
    headers = {"User-Agent": "wristwork-stream-cache/1.0"}      # Cloudflare 403s Python's default
    if cfg.get("NTFY_TOKEN_SVC"):
        headers["Authorization"] = "Bearer " + cfg["NTFY_TOKEN_SVC"]
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        body = r.read().decode("utf-8", "replace")

    lines = []
    for line in body.splitlines():
        if not line.strip():
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        # The limit notice arrives as an ordinary line in an otherwise successful response.
        if o.get("http") and o.get("error"):
            raise BusTruncated("bus cut the answer short after %d messages: %s (code %s)"
                               % (len(lines), o.get("error"), o.get("code")))
        if o.get("event") == "message":
            lines.append((o, line))
    return lines


def refresh(cfg, topic, days):
    """Bring the local copy of `topic` up to date, then prune it to `days`. Returns the count added.

    First run bootstraps with a time window; every run after that asks only for what is new.
    """
    path, state_path = _paths(topic)
    os.makedirs(CACHE_DIR, exist_ok=True)
    state = {}
    if os.path.exists(state_path):
        try:
            state = json.load(open(state_path, encoding="utf-8"))
        except Exception:
            state = {}

    # Two callers share one cache with different appetites — the detector wants a week, the
    # beat-interval job a month. Retention is the widest anyone has asked for, or the detector's
    # weekly prune would silently destroy the month the other one depends on.
    keep = max(int(state.get("keep_days") or 0), days)

    if not os.path.exists(path):
        seeded = _seed_from_mirror(topic, path)
        if seeded:
            state = _restate(path, state)
            print("seeded the local copy of %s with %d rows from the nightly mirror" % (topic, seeded))

    since = state.get("last_id") or ("%dh" % (days * 24))
    try:
        fresh = _poll(cfg, topic, since)
    except BusTruncated:
        # A truncated INCREMENTAL poll still tells us something real; a truncated bootstrap does
        # not, because the missing part is the recent end. Either way the caller must hear it.
        raise

    if fresh:
        with open(path, "a", encoding="utf-8") as fh:
            for _, raw in fresh:
                fh.write(raw + "\n")
        last = fresh[-1][0]
        state.update({"last_id": last["id"], "last_time": last["time"]})
    state["keep_days"] = keep
    state["refreshed"] = int(time.time())
    json.dump(state, open(state_path, "w", encoding="utf-8"), indent=1)

    _prune(path, keep)
    return len(fresh)


def _seed_from_mirror(topic, path):
    """Bootstrap from the nightly mirror rather than the bus, when one exists.

    The mirror holds the same raw envelope lines the bus would send, so a first run costs nothing
    against the read allowance — which matters most on exactly the day the allowance ran out.
    """
    # The nightly mirror files are named for what they hold, not for the topic they came from:
    # the label stream arrives on a topic called tags and is mirrored as labels.jsonl.
    mirror = os.path.join(CACHE_DIR, "%s.jsonl" % MIRROR_NAMES.get(topic, topic))
    if not os.path.exists(mirror):
        return 0
    n = 0
    with open(mirror, encoding="utf-8") as src, open(path, "w", encoding="utf-8") as dst:
        for line in src:
            try:
                o = json.loads(line)
            except Exception:
                continue
            if o.get("event") == "message" and o.get("id") and o.get("time"):
                dst.write(line.rstrip("\r\n") + "\n")
                n += 1
    return n


def _restate(path, state):
    """Point the incremental cursor at the newest row a seeded cache holds."""
    last = None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except Exception:
                continue
            if last is None or o["time"] >= last["time"]:
                last = o
    if last:
        state = dict(state, last_id=last["id"], last_time=last["time"])
    return state


def _prune(path, days):
    """Drop rows older than the window. Rewrites via a temporary file so a crash cannot truncate."""
    if not os.path.exists(path):
        return
    cutoff = time.time() - (days * 86400 + PRUNE_MARGIN_H * 3600)
    tmp = path + ".tmp"
    kept = dropped = 0
    with open(path, encoding="utf-8") as src, open(tmp, "w", encoding="utf-8") as dst:
        for line in src:
            try:
                t = json.loads(line)["time"]
            except Exception:
                continue
            if t >= cutoff:
                dst.write(line)
                kept += 1
            else:
                dropped += 1
    if dropped:
        os.replace(tmp, path)
    else:
        os.remove(tmp)


def rows(cfg, topic, days, refresh_first=True):
    """The decoded stream for `topic` over the last `days`, newest included.

    Yields (message_time, payload) exactly as the old direct-from-bus readers did, so callers only
    change where the data comes from, never what it looks like.
    """
    if refresh_first:
        try:
            refresh(cfg, topic, days)
        except (BusTruncated, urllib.error.URLError, OSError) as exc:
            # Work with what is on disk rather than doing nothing — but SAY the window is short,
            # on stderr, every time. A quiet fallback to stale data is the exact failure this
            # module exists to prevent, and the health check still measures scoring freshness.
            st = status(topic)
            newest = (time.strftime("%Y-%m-%d %H:%M", time.localtime(st["newest"]))
                      if st["newest"] else "nothing")
            print("BUS READ FAILED for %s: %s -- working from the local copy, which ends at %s"
                  % (topic, exc, newest), file=sys.stderr)
    path, _ = _paths(topic)
    if not os.path.exists(path):
        return
    cutoff = time.time() - days * 86400
    seen = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except Exception:
                continue
            if o.get("event") != "message" or o["time"] < cutoff:
                continue
            if o["id"] in seen:       # a bootstrap overlapping an earlier cache cannot double-count
                continue
            seen.add(o["id"])
            try:
                yield o["time"], json.loads(o["message"])
            except Exception:
                continue


def status(topic):
    """What the local copy holds, for the health check."""
    path, state_path = _paths(topic)
    if not os.path.exists(path):
        return {"rows": 0, "newest": None, "bytes": 0}
    newest, n = 0, 0
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            try:
                t = json.loads(line)["time"]
            except Exception:
                continue
            n += 1
            newest = max(newest, t)
    return {"rows": n, "newest": newest, "bytes": os.path.getsize(path)}
