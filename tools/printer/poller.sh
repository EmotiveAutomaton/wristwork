#!/bin/sh
# PrusaLink poller — runs INSIDE a curl-capable container on the NAS (see provision-poller.sh).
# Env: PRINTER_HOST, PRINTER_API_KEY, NTFY_TOPIC_URL (full URL of the printer topic).
# Polls /api/v1/status every 60 s (digest auth, user `maker`). Posts on state transitions and
# each 5% progress step; posts `idle` exactly once when a print ends or the printer sits idle.
# Uses /status, not /job — it answers even when idle. Prusa Connect cloud is never used.
set -u
last_state=""
last_bucket=-1
idle_posted=0

post() {
    curl -s -m 10 -d "$1" "$NTFY_TOPIC_URL" >/dev/null 2>&1
}

while true; do
    body=$(curl -s -m 15 --digest -u "maker:${PRINTER_API_KEY}" "http://${PRINTER_HOST}/api/v1/status" 2>/dev/null)
    if [ -n "$body" ]; then
        state=$(printf '%s' "$body" | sed -n 's/.*"state" *: *"\([A-Za-z]*\)".*/\1/p')
        progress=$(printf '%s' "$body" | sed -n 's/.*"progress" *: *\([0-9]*\).*/\1/p')
        case "$state" in
            PRINTING|PAUSED|ATTENTION|ERROR)
                idle_posted=0
                bucket=$(( ${progress:-0} / 5 ))
                if [ "$state" != "$last_state" ] || [ "$bucket" -ne "$last_bucket" ]; then
                    case "$state" in
                        PRINTING) post "${progress:-0}%";;
                        PAUSED)   post "paused";;
                        *)        post "ATTN";;
                    esac
                    last_state="$state"; last_bucket="$bucket"
                fi
                ;;
            FINISHED|IDLE|READY|STOPPED|*)
                if [ "$idle_posted" -eq 0 ]; then
                    post "idle"
                    idle_posted=1; last_state="$state"; last_bucket=-1
                fi
                ;;
        esac
    fi
    # No response: post nothing. The complication's age display carries the staleness signal.
    sleep 60
done
