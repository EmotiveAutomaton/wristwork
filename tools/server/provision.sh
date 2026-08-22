#!/bin/sh
# wristwork Phase 1 provisioning — runs ON the NAS, as root (sudo bash provision.sh).
# Idempotent: safe to re-run; replaces only wristwork-* containers, never touches data volumes.
# Parameters come from the environment or these defaults:
#   NTFY_PORT       host port to publish ntfy on            (default 8093)
#   NTFY_BASE_URL   external URL clients will use           (default http://<this-host-lan-ip>:PORT)
#   DATA_ROOT       where cache + labels live               (default /volume1/docker/wristwork)
#   OWNER_USER      LAN user who should own the data files  (default the sudo caller)
set -eu
D=/usr/local/bin/docker
[ -x "$D" ] || D=$(command -v docker)
NTFY_PORT="${NTFY_PORT:-8093}"
DATA_ROOT="${DATA_ROOT:-/volume1/docker/wristwork}"
OWNER_USER="${OWNER_USER:-${SUDO_USER:-root}}"
LAN_IP=$(ip route get 1 2>/dev/null | sed -n 's/.*src \([0-9.]*\).*/\1/p')
NTFY_BASE_URL="${NTFY_BASE_URL:-http://${LAN_IP}:${NTFY_PORT}}"

mkdir -p "$DATA_ROOT/ntfy-cache" "$DATA_ROOT/labels"
$D network inspect wristwork-net >/dev/null 2>&1 || $D network create wristwork-net
$D pull -q binwiederhier/ntfy:latest

# The bus. Message cache persists on the volume across container replacement.
$D rm -f wristwork-ntfy >/dev/null 2>&1 || true
$D run -d --name wristwork-ntfy --restart=always --network wristwork-net \
  -p "${NTFY_PORT}:80" \
  -v "$DATA_ROOT/ntfy-cache:/var/cache/ntfy" \
  -e NTFY_BASE_URL="$NTFY_BASE_URL" \
  -e NTFY_CACHE_FILE=/var/cache/ntfy/cache.db \
  -e NTFY_CACHE_DURATION=24h \
  binwiederhier/ntfy serve

# The label archiver: every message on topic `tags` becomes one JSON line in labels.jsonl.
# A restart-always container instead of a systemd unit: on Synology, hand-installed units
# do not reliably survive DSM updates; the Docker daemon's restart policy does.
$D rm -f wristwork-labels >/dev/null 2>&1 || true
$D run -d --name wristwork-labels --restart=always --network wristwork-net \
  -v "$DATA_ROOT/labels:/labels" \
  --entrypoint sh binwiederhier/ntfy \
  -c 'while true; do ntfy subscribe http://wristwork-ntfy/tags >> /labels/labels.jsonl 2>>/labels/subscriber.err; sleep 5; done'

chown -R "$OWNER_USER" "$DATA_ROOT"
sleep 2
$D ps --filter name=wristwork --format "{{.Names}}  {{.Status}}"
echo "base_url=$NTFY_BASE_URL"
