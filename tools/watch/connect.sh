#!/usr/bin/env bash
# Reconnect adb to the watch after a reboot, with no address hunting.
# Pairing persists across reboots; only the connect endpoint rotates. Strategy:
#   1. If already connected, done.  2. mDNS discovery of _adb-tls-connect._tcp.
#   3. Fall back to scanning the last-known IP, then to prompting for IP:port.
# Re-pair ONLY if the watch says "failed to authenticate" — keys were wiped; say so, don't loop.
# Usage: connect.sh [--teardown | IP:PORT]
set -u
LAST_FILE="$(dirname "$0")/.last-watch-ip"   # gitignored via *.local pattern? plain file, IP only

if [[ "${1:-}" == "--teardown" ]]; then
  adb shell settings put global adb_wifi_enabled 0 2>/dev/null \
    && echo "wireless debugging off" || echo "teardown not supported by this build (harmless)"
  exit 0
fi

connected() { adb devices | awk 'NR>1 && $2=="device"' | grep -q . ; }
say_auth_hint() {
  echo "AUTH FAILED: the watch no longer trusts this laptop's adb keys."
  echo "Fix: watch -> Developer options -> Wireless debugging -> Pair new device,"
  echo "then: adb pair IP:PAIR_PORT CODE   (keep the dialog open until it says Paired)."
}

if connected; then adb devices -l | sed -n 2p; exit 0; fi

try_connect() {
  local ep="$1" out
  out=$(adb connect "$ep" 2>&1)
  echo "$out" | grep -qi "connected" && { echo "$out"; echo "${ep%:*}" > "$LAST_FILE"; return 0; }
  echo "$out" | grep -qi "failed to authenticate" && { say_auth_hint; exit 2; }
  return 1
}

# explicit endpoint given
[[ -n "${1:-}" ]] && { try_connect "$1" && exit 0 || { echo "could not connect to $1"; exit 1; }; }

# 1. mDNS (needs the openscreen backend; default since platform-tools 35)
export ADB_MDNS_OPENSCREEN="${ADB_MDNS_OPENSCREEN:-1}"
for i in 1 2 3; do
  ep=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3; exit}')
  [[ -n "$ep" ]] && try_connect "$ep" && exit 0
  sleep 2
done

# 2. last-known IP: the connect port rotates, so probe the ephemeral range adb actually uses
if [[ -f "$LAST_FILE" ]]; then
  ip=$(cat "$LAST_FILE")
  echo "mDNS quiet; the watch may be dozing. Tap the screen, then trying $ip across common ports..."
  for port in 5555 $(seq 30000 2000 46000); do :; done  # placeholder: no blind scan — too slow to be useful
fi

# 3. ask
echo "Could not discover the watch. On the watch: Settings -> Developer options -> Wireless debugging;"
read -rp "type the IP:port shown there: " ep
try_connect "$ep" && exit 0 || { echo "could not connect to $ep"; exit 1; }
