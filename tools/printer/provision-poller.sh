#!/usr/bin/env bash
# Deploy the PrusaLink poller container to the NAS. Run FROM THE LAPTOP, repo root.
# Reads RAID_SSH_HOST, NTFY_BASE_URL, TOPIC_PRINTER, PRINTER_HOST, PRINTER_API_KEY from
# config.properties (gitignored). Requires the D12 scoped docker sudoers rule on the NAS.
set -eu
cd "$(dirname "$0")/../.."
cfg() { sed -n "s/^$1=\([^#]*\).*/\1/p" config.properties | head -1 | xargs; }
HOST=$(cfg RAID_SSH_HOST); BASE=$(cfg NTFY_BASE_URL); TOPIC=$(cfg TOPIC_PRINTER)
PHOST=$(cfg PRINTER_HOST); PKEY=$(cfg PRINTER_API_KEY); STOKEN=$(cfg NTFY_TOKEN_SVC)
[ -n "$PHOST" ] && [ -n "$PKEY" ] || { echo "Set PRINTER_HOST and PRINTER_API_KEY in config.properties first."; exit 1; }

ssh -o BatchMode=yes "$HOST" 'mkdir -p /volume1/docker/wristwork/printer'
scp -O -q tools/printer/poller.sh "$HOST:/volume1/docker/wristwork/printer/poller.sh" 2>/dev/null \
  || cat tools/printer/poller.sh | ssh -o BatchMode=yes "$HOST" 'cat > /volume1/docker/wristwork/printer/poller.sh'
ssh -o BatchMode=yes "$HOST" "
  sudo -n /usr/local/bin/docker rm -f wristwork-printer >/dev/null 2>&1 || true
  sudo -n /usr/local/bin/docker run -d --name wristwork-printer --restart=always \
    --network wristwork-net \
    -v /volume1/docker/wristwork/printer:/app:ro \
    -e PRINTER_HOST='$PHOST' -e PRINTER_API_KEY='$PKEY' \
    -e NTFY_TOPIC_URL='http://wristwork-ntfy/$TOPIC' \
    -e NTFY_TOKEN='$STOKEN' \
    --entrypoint sh curlimages/curl:latest /app/poller.sh
  sleep 2
  sudo -n /usr/local/bin/docker ps --filter name=wristwork-printer --format '{{.Names}}  {{.Status}}'
"
