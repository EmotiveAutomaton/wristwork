#!/bin/sh
# wristwork Phase 1 provisioning — run ON the NAS. Idempotent; replaces only wristwork-*
# containers, never touches data volumes. Works two ways:
#   as root:            sudo bash provision.sh
#   as the owner user:  bash provision.sh   (uses the scoped NOPASSWD docker sudoers rule, D12)
# Parameters (env): NTFY_PORT (8093), NTFY_BASE_URL (http://<lan-ip>:PORT),
#                   DATA_ROOT (/volume1/docker/wristwork), OWNER_UIDGID (calling user)
set -eu
BIN=/usr/local/bin/docker
[ -x "$BIN" ] || BIN=$(command -v docker)
if [ "$(id -u)" -eq 0 ]; then DOCKER="$BIN"; else DOCKER="sudo -n $BIN"; fi
NTFY_PORT="${NTFY_PORT:-8093}"
DATA_ROOT="${DATA_ROOT:-/volume1/docker/wristwork}"
OWNER_UIDGID="${OWNER_UIDGID:-$(id -u):$(id -g)}"
LAN_IP=$(ip route get 1 2>/dev/null | sed -n 's/.*src \([0-9.]*\).*/\1/p')
NTFY_BASE_URL="${NTFY_BASE_URL:-http://${LAN_IP}:${NTFY_PORT}}"

$DOCKER network inspect wristwork-net >/dev/null 2>&1 || $DOCKER network create wristwork-net
$DOCKER pull -q binwiederhier/ntfy:latest

mkdir -p "$DATA_ROOT/ntfy-cache" "$DATA_ROOT/labels"   # Synology daemon refuses to auto-create bind dirs

# The bus. Cache persists across container replacement.
$DOCKER rm -f wristwork-ntfy >/dev/null 2>&1 || true
$DOCKER run -d --name wristwork-ntfy --restart=always --network wristwork-net \
  -p "${NTFY_PORT}:80" \
  -v "$DATA_ROOT/ntfy-cache:/var/cache/ntfy" \
  -e NTFY_BASE_URL="$NTFY_BASE_URL" \
  -e NTFY_CACHE_FILE=/var/cache/ntfy/cache.db \
  -e NTFY_CACHE_DURATION=720h \
  binwiederhier/ntfy serve

# The label archiver: every message on topic `tags` becomes one JSON line in labels.jsonl.
# A restart-always container instead of a systemd unit: hand-installed units do not reliably
# survive DSM updates; the Docker daemon's restart policy does (decision D9).
$DOCKER rm -f wristwork-labels >/dev/null 2>&1 || true
$DOCKER run -d --name wristwork-labels --restart=always --network wristwork-net \
  -v "$DATA_ROOT/labels:/labels" \
  --entrypoint sh binwiederhier/ntfy \
  -c 'while true; do ntfy subscribe http://wristwork-ntfy/tags >> /labels/labels.jsonl 2>>/labels/subscriber.err; sleep 5; done'

# Flag + health archivers: every message on `flags` / `health` becomes a JSON line, same
# pattern as labels (append-only law covers all streams).
for STREAM in flags health; do
  $DOCKER rm -f "wristwork-$STREAM" >/dev/null 2>&1 || true
  $DOCKER run -d --name "wristwork-$STREAM" --restart=always --network wristwork-net     -v "$DATA_ROOT/labels:/labels"     --entrypoint sh binwiederhier/ntfy     -c "while true; do ntfy subscribe http://wristwork-ntfy/$STREAM >> /labels/$STREAM.jsonl 2>>/labels/$STREAM.err; sleep 5; done"
done

# Make the data readable by the calling user (chown via helper container: root work stays in docker).
$DOCKER run --rm -v "$DATA_ROOT:/d" --entrypoint sh binwiederhier/ntfy -c "chown -R $OWNER_UIDGID /d"

sleep 2
$DOCKER ps --filter name=wristwork --format "{{.Names}}  {{.Status}}"
echo "base_url=$NTFY_BASE_URL"
