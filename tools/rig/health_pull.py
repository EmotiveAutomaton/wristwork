"""Pull what the wrist cannot give us directly: ECG waveforms, sleep, overnight HRV.

The watch's own sensors are sealed to third-party apps, but the readings the Fitbit stack produces
come back through Google's health API — and among them are the thirty-second ECG readings at
250 Hz, which are the only true beat-to-beat data this hardware will ever hand us. Everything this
script fetches lands in the same append-only health stream as the wrist data, so one archive holds
the whole picture and nothing needs joining across systems later.

Fetched, in order of how much they matter here:
  * electrocardiogram   the waveform of a reading taken with the ECG button beside a label
  * heart-rate-variability   overnight RMSSD — sleep-only on this hardware, useful as a baseline
  * sleep               stages and duration, the covariate that explains half of a strange morning
  * oxygen-saturation, respiratory-rate   nightly, cheap, occasionally revealing

AUTHORISATION is a one-time thing the owner does, because it needs their Google account:
    python tools/rig/health_pull.py --auth
prints a URL, they approve, they paste the code back. The refresh token is written to the
gitignored config and never leaves this machine.

Run with no arguments to fetch. Idempotent: a state file records what has already been filed, so
running it hourly cannot duplicate anything.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONFIG = os.path.join(ROOT, "config.properties")
STATE = os.path.join(ROOT, "data", "health-pull-state.json")

API = "https://health.googleapis.com/v4/users/me/dataTypes/%s/dataPoints"
TOKEN_URL = "https://oauth2.googleapis.com/token"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
REDIRECT = "urn:ietf:wg:oauth:2.0:oob"          # installed-app flow: paste the code back

# (data type on the wire, the kind it is filed under, the scope it needs)
WANTED = [
    ("electrocardiogram", "ecg", "ecg"),
    ("heart-rate-variability", "hrv", "heart_rate_variability"),
    ("sleep", "sleep", "sleep"),
    ("oxygen-saturation", "spo2", "oxygen_saturation"),
    ("respiratory-rate", "breathing", "respiratory_rate"),
]
SCOPES = ["https://www.googleapis.com/auth/googlehealth.%s.readonly" % s for _, _, s in WANTED]


def config():
    cfg = {}
    with open(CONFIG, encoding="utf-8") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg


def config_set(key, value):
    """Write one key back into the gitignored config, replacing any existing line."""
    with open(CONFIG, encoding="utf-8") as f:
        lines = f.read().splitlines()
    out, done = [], False
    for line in lines:
        if line.split("=")[0].strip() == key:
            out.append("%s=%s" % (key, value))
            done = True
        else:
            out.append(line)
    if not done:
        out.append("%s=%s" % (key, value))
    with open(CONFIG, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")


def post_form(url, fields):
    data = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(url, data=data, headers={
        "User-Agent": "wristwork-health-pull/1.0",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def authorise(cfg):
    cid = cfg.get("GHEALTH_CLIENT_ID")
    secret = cfg.get("GHEALTH_CLIENT_SECRET")
    if not cid or not secret:
        print("Put GHEALTH_CLIENT_ID and GHEALTH_CLIENT_SECRET in config.properties first.")
        print("They come from a Google Cloud OAuth client of type 'Desktop app'.")
        return 1
    params = {
        "client_id": cid, "redirect_uri": REDIRECT, "response_type": "code",
        "scope": " ".join(SCOPES), "access_type": "offline", "prompt": "consent",
    }
    print("\n1. Open this in a browser and approve:\n")
    print(AUTH_URL + "?" + urllib.parse.urlencode(params))
    print("\n2. Paste the code it gives you here and press enter:")
    code = sys.stdin.readline().strip()
    if not code:
        print("no code given")
        return 1
    tok = post_form(TOKEN_URL, {
        "code": code, "client_id": cid, "client_secret": secret,
        "redirect_uri": REDIRECT, "grant_type": "authorization_code",
    })
    if "refresh_token" not in tok:
        print("no refresh token came back; response was: %s" % tok)
        return 1
    config_set("GHEALTH_REFRESH_TOKEN", tok["refresh_token"])
    print("\nAuthorised. The refresh token is in config.properties, which git ignores.")
    return 0


def access_token(cfg):
    tok = post_form(TOKEN_URL, {
        "client_id": cfg["GHEALTH_CLIENT_ID"],
        "client_secret": cfg["GHEALTH_CLIENT_SECRET"],
        "refresh_token": cfg["GHEALTH_REFRESH_TOKEN"],
        "grant_type": "refresh_token",
    })
    return tok["access_token"]


def get_json(url, token, params=None):
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "User-Agent": "wristwork-health-pull/1.0",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def publish(cfg, payload):
    body = json.dumps(payload).encode()
    url = "%s/%s" % (cfg["NTFY_BASE_URL"].rstrip("/"), cfg.get("TOPIC_HEALTH", "health"))
    headers = {"User-Agent": "wristwork-health-pull/1.0", "Priority": "min",
               "Content-Type": "application/json"}
    if cfg.get("NTFY_TOKEN_SVC"):
        headers["Authorization"] = "Bearer " + cfg["NTFY_TOKEN_SVC"]
    req = urllib.request.Request(url, data=body, headers=headers)
    urllib.request.urlopen(req, timeout=30).read()


def load_state():
    if os.path.exists(STATE):
        with open(STATE, encoding="utf-8") as f:
            return json.load(f)
    return {"seen": {}}


def save_state(state):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w", encoding="utf-8", newline="\n") as f:
        json.dump(state, f, indent=1)


def point_id(kind, dp):
    """A stable identity for a data point, so an hourly run cannot file it twice."""
    for key in ("name", "id", "dataPointId", "startTime", "time"):
        if isinstance(dp, dict) and dp.get(key):
            return "%s:%s" % (kind, dp[key])
    return "%s:%s" % (kind, hash(json.dumps(dp, sort_keys=True)[:400]))


def main():
    cfg = config()
    if "--auth" in sys.argv:
        return authorise(cfg)
    if not cfg.get("GHEALTH_REFRESH_TOKEN"):
        print("not authorised yet — run:  python tools/rig/health_pull.py --auth")
        return 0

    token = access_token(cfg)
    state = load_state()
    seen = state.setdefault("seen", {})
    filed = 0

    for wire, kind, _scope in WANTED:
        try:
            # The exact paging and range parameters are not published; ask for a recent slice and
            # filter locally, and keep the raw response shape so the first real run tells us what
            # the fields are actually called rather than what we assumed.
            resp = get_json(API % wire, token, {"pageSize": 20})
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:200]
            print("%-22s HTTP %s %s" % (wire, e.code, body))
            continue
        except Exception as e:
            print("%-22s failed: %s" % (wire, e))
            continue

        points = resp.get("dataPoints") or resp.get("dataPoint") or []
        fresh = [dp for dp in points if point_id(kind, dp) not in seen]
        for dp in fresh:
            publish(cfg, {
                "kind": kind,
                "t": int(time.time()),
                "source": "google-health",
                "point": dp,
            })
            seen[point_id(kind, dp)] = int(time.time())
            filed += 1
        print("%-22s %d returned, %d new" % (wire, len(points), len(fresh)))

    # The seen-set only has to outlive the fetch window; keep it from growing without bound.
    if len(seen) > 5000:
        for k in sorted(seen, key=seen.get)[:1000]:
            del seen[k]
    save_state(state)
    print("filed %d new records into the health stream" % filed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
