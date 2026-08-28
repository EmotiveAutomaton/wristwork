#!/bin/sh
# Turn the LAN-only bus into an authenticated, internet-reachable one. Run ON the NAS, once,
# AFTER the tunnel hostname exists (see OWNER STEPS in docs/STATE.md, 2026-08-25).
#
# Why this exists: complications call NTFY_BASE_URL directly, and a 192.168.x.x address only
# exists inside the house — off home Wi-Fi the watch still HAS internet (through the phone over
# Bluetooth, or LTE) but there is nothing at that number, so every channel silently ages out.
#
# What it changes:
#   * the bus container gains an auth database and `deny-all` default access, so publishing it
#     to the internet does not publish its contents. Nothing reads or writes without a token.
#   * two accounts: `wrist` (the watch app and the phone/watch ntfy apps) and `svc` (rig stats,
#     Claude hooks, printer poller, the three archivers). Both read-write on our topics only.
#   * the three archiver containers are recreated carrying svc's token.
# What it does NOT touch: the cache database, labels.jsonl / flags.jsonl / health.jsonl, or any
# container outside wristwork-* (the website tunnel keeps running untouched).
#
# Env (required): PUBLIC_URL=https://bus.example.com
# Env (optional): WRIST_PASS, SVC_PASS (generated if unset), DATA_ROOT, NTFY_PORT
set -eu
BIN=/usr/local/bin/docker
[ -x "$BIN" ] || BIN=$(command -v docker)
if [ "$(id -u)" -eq 0 ]; then DOCKER="$BIN"; else DOCKER="sudo -n $BIN"; fi
: "${PUBLIC_URL:?set PUBLIC_URL=https://your-bus-hostname}"
NTFY_PORT="${NTFY_PORT:-8093}"
DATA_ROOT="${DATA_ROOT:-/volume1/docker/wristwork}"
TOPICS="tags agents rig printer acks flags health"
rand() { head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
WRIST_PASS="${WRIST_PASS:-$(rand)}"
SVC_PASS="${SVC_PASS:-$(rand)}"

mkdir -p "$DATA_ROOT/ntfy-auth" "$DATA_ROOT/ntfy-attach"

# 1. The bus, with auth. Cache mount unchanged, so no message history is lost.
$DOCKER rm -f wristwork-ntfy >/dev/null 2>&1 || true
$DOCKER run -d --name wristwork-ntfy --restart=always --network wristwork-net \
  -p "${NTFY_PORT}:80" \
  -v "$DATA_ROOT/ntfy-cache:/var/cache/ntfy" \
  -v "$DATA_ROOT/ntfy-auth:/var/lib/ntfy" \
  -e NTFY_BASE_URL="$PUBLIC_URL" \
  -e NTFY_CACHE_FILE=/var/cache/ntfy/cache.db \
  -e NTFY_CACHE_DURATION=720h \
  -v "$DATA_ROOT/ntfy-attach:/var/lib/ntfy-attachments" \
  -e NTFY_ATTACHMENT_CACHE_DIR=/var/lib/ntfy-attachments \
  -e NTFY_ATTACHMENT_EXPIRY_DURATION=720h \
  -e NTFY_ATTACHMENT_FILE_SIZE_LIMIT=5M \
  -e NTFY_MESSAGE_SIZE_LIMIT=32K \
  -e NTFY_VISITOR_REQUEST_LIMIT_BURST=500 \
  -e NTFY_VISITOR_REQUEST_LIMIT_REPLENISH=1s \
  -e NTFY_AUTH_FILE=/var/lib/ntfy/user.db \
  -e NTFY_AUTH_DEFAULT_ACCESS=deny-all \
  binwiederhier/ntfy serve
sleep 3

# 2. Accounts. Idempotent: an existing user is left alone, its access re-applied.
for U in wrist svc; do
    P=$WRIST_PASS; [ "$U" = svc ] && P=$SVC_PASS
    $DOCKER exec -e NTFY_PASSWORD="$P" wristwork-ntfy ntfy user add --ignore-exists "$U" >/dev/null
    for T in $TOPICS; do
        $DOCKER exec wristwork-ntfy ntfy access "$U" "$T" rw >/dev/null
    done
done

# 3. One token per account for code paths (the phone/watch apps use user+password instead).
WRIST_TOKEN=$($DOCKER exec wristwork-ntfy ntfy token add --label wrist-app wrist \
    | sed -n 's/.*\(tk_[A-Za-z0-9]*\).*/\1/p' | head -1)
SVC_TOKEN=$($DOCKER exec wristwork-ntfy ntfy token add --label services svc \
    | sed -n 's/.*\(tk_[A-Za-z0-9]*\).*/\1/p' | head -1)

# 4. Archivers, now authenticating. Same append-only subscribe loop as provision.sh.
for PAIR in "labels:tags" "flags:flags" "health:health"; do
    NAME=${PAIR%%:*}; STREAM=${PAIR##*:}
    FILE=$NAME; [ "$NAME" = labels ] && FILE=labels
    $DOCKER rm -f "wristwork-$NAME" >/dev/null 2>&1 || true
    $DOCKER run -d --name "wristwork-$NAME" --restart=always --network wristwork-net \
      -v "$DATA_ROOT/labels:/labels" \
      -e NTFY_TOKEN="$SVC_TOKEN" \
      --entrypoint sh binwiederhier/ntfy \
      -c "while true; do ntfy subscribe --token \"\$NTFY_TOKEN\" http://wristwork-ntfy/$STREAM >> /labels/$FILE.jsonl 2>>/labels/$FILE.err; sleep 5; done"
done

sleep 2
$DOCKER ps --filter name=wristwork --format "{{.Names}}  {{.Status}}"
cat <<EOF

--- put these in config.properties (gitignored) and NOWHERE else ---
NTFY_BASE_URL=$PUBLIC_URL
NTFY_TOKEN=$WRIST_TOKEN          # the watch app
NTFY_TOKEN_SVC=$SVC_TOKEN        # rig stats, Claude hooks, printer poller
--- phone / watch ntfy app login ---
user: wrist   password: $WRIST_PASS
user: svc     password: $SVC_PASS
EOF
