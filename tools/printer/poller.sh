#!/bin/sh
# PrusaLink poller — runs INSIDE a curl-capable container on the NAS (see provision-poller.sh).
# Env: PRINTER_HOST, PRINTER_API_KEY, NTFY_TOPIC_URL (full URL of the printer topic).
# Polls /api/v1/status every 60 s (digest auth, user `maker`). Uses /status, not /job — it answers
# even when idle. Prusa Connect cloud is never used.
#
# Payload contract (the watch face and tap-frame parse these; Fetch may consume them too):
#   "{n}%"                              while printing, on each 5 % step
#   "paused" / "ATTN"                   state transitions
#   "done · {name} · {dur} · 100%"      a print finished  <- the message TIME is the finish time
#   "stopped · {name} · {dur} · {n}%"   a print was cancelled or errored out
# The completion message carries the job's own thumbnail as an ntfy attachment, so the picture of
# the last print outlives the job on the printer and travels off the LAN with the record. The
# message text rides in an RFC 2047 encoded-word: a raw UTF-8 header turns the separator dot into
# U+FFFD (measured against the live server 2026-08-25), and base64 avoids URL-encoding entirely.
# Nothing is posted for plain idle (owner 2026-08-24): the face now shows time-since-last-finish,
# so an idle post at container start would age like a phantom print and read as freshness.
# The pre-2026-08-24 bare `idle` payload still renders, as the word, with no age claim.
#
# NOTIFICATIONS (owner 2026-08-26): the printer line updates SILENTLY. Everything except the
# completion record posts at ntfy priority `min`, which the phone and watch deliver without a
# sound, a buzz or a pop — the complication still reads it on its next refresh, because the face
# polls the topic and priority is a delivery hint, not a filter. Only the end-of-print record
# posts at normal priority and is allowed to interrupt.
set -u
DOT='·'          # field separator of the payload contract (UTF-8, byte-matched downstream)
last_state=""
last_bucket=-1
printing=0
job_name=""
job_secs=0
started=0
last_pct=0

# The bus requires a token once it is published to the internet; empty = LAN-only mode.
# $2 = ntfy priority; defaults to `min` so that every routine update is silent by construction —
# a new call has to ASK to be allowed to buzz.
post() {
    _prio=${2:-min}
    if [ -n "${NTFY_TOKEN:-}" ]; then
        curl -s -m 10 -H "Authorization: Bearer $NTFY_TOKEN" -H "Priority: $_prio" \
            -d "$1" "$NTFY_TOPIC_URL" >/dev/null 2>&1
    else
        curl -s -m 10 -H "Priority: $_prio" -d "$1" "$NTFY_TOPIC_URL" >/dev/null 2>&1
    fi
}

# Same message, with the thumbnail attached. Returns non-zero if the upload fails, so the caller
# can fall back to a plain post rather than losing the record over a picture.
post_file() {
    _enc=$(printf '%s' "$1" | base64 | tr -d '\n')
    _prio=${3:-default}
    if [ -n "${NTFY_TOKEN:-}" ]; then
        curl -sf -m 30 -T "$2" -H "Authorization: Bearer $NTFY_TOKEN" \
            -H "Filename: preview.png" -H "Message: =?UTF-8?B?${_enc}?=" -H "Priority: $_prio" \
            "$NTFY_TOPIC_URL" >/dev/null 2>&1
    else
        curl -sf -m 30 -T "$2" \
            -H "Filename: preview.png" -H "Message: =?UTF-8?B?${_enc}?=" -H "Priority: $_prio" \
            "$NTFY_TOPIC_URL" >/dev/null 2>&1
    fi
}

# The job's name and its embedded thumbnail, asked for once per print (status carries neither).
# Any separator dot in the name becomes a hyphen so the payload stays parseable; 40 chars is
# plenty for the frame. The thumbnail is written to $THUMB, deleted first so that a print whose
# picture cannot be fetched never inherits the previous print's picture.
THUMB=/tmp/wristwork-thumb.png
fetch_job() {
    _job=$(curl -s -m 15 --digest -u "maker:${PRINTER_API_KEY}" "http://${PRINTER_HOST}/api/v1/job" 2>/dev/null)
    job_name=$(printf '%s' "$_job" | sed -n 's/.*"display_name" *: *"\([^"]*\)".*/\1/p' | head -1 \
        | tr -d '\\' | sed "s/$DOT/-/g" | cut -c1-40)
    rm -f "$THUMB"
    _ref=$(printf '%s' "$_job" | sed -n 's/.*"thumbnail" *: *"\([^"]*\)".*/\1/p' | head -1)
    [ -n "$_ref" ] || return 0
    curl -sf -m 20 --digest -u "maker:${PRINTER_API_KEY}" "http://${PRINTER_HOST}${_ref}" \
        -o "$THUMB" 2>/dev/null || rm -f "$THUMB"
}

fmt_dur() {
    _s=${1:-0}
    _h=$(( _s / 3600 ))
    _m=$(( (_s % 3600) / 60 ))
    if [ "$_h" -gt 0 ]; then printf '%dh %dm' "$_h" "$_m"; else printf '%dm' "$_m"; fi
}

while true; do
    body=$(curl -s -m 15 --digest -u "maker:${PRINTER_API_KEY}" "http://${PRINTER_HOST}/api/v1/status" 2>/dev/null)
    if [ -n "$body" ]; then
        state=$(printf '%s' "$body" | sed -n 's/.*"state" *: *"\([A-Za-z]*\)".*/\1/p')
        progress=$(printf '%s' "$body" | sed -n 's/.*"progress" *: *\([0-9]*\).*/\1/p')
        elapsed=$(printf '%s' "$body" | sed -n 's/.*"time_printing" *: *\([0-9]*\).*/\1/p')
        case "$state" in
            PRINTING|PAUSED|ATTENTION|ERROR)
                if [ "$printing" -eq 0 ]; then
                    printing=1
                    started=$(date +%s)
                    fetch_job
                fi
                # Elapsed comes from the printer when it offers it, so a poller restart mid-print
                # still reports the real duration; wall clock is only the fallback.
                [ -n "${elapsed:-}" ] && job_secs=$elapsed
                [ -n "${progress:-}" ] && last_pct=$progress
                bucket=$(( ${progress:-0} / 5 ))
                if [ "$state" != "$last_state" ] || [ "$bucket" -ne "$last_bucket" ]; then
                    # Progress is silent; the face reads it on its next refresh. A print that
                    # has STOPPED NEEDING YOU TO NOT KNOW is allowed to interrupt (owner
                    # 2026-08-28): paused for filament, or wanting attention, buzzes.
                    case "$state" in
                        PRINTING) post "${progress:-0}%" min;;
                        PAUSED)   post "paused" high;;
                        *)        post "ATTN" high;;
                    esac
                    last_state="$state"; last_bucket="$bucket"
                fi
                ;;
            *)
                if [ "$printing" -eq 1 ]; then
                    # A FINISHED status still carries time_printing; prefer it over the last
                    # value seen while printing (which is one poll short).
                    secs=${elapsed:-}
                    [ -n "$secs" ] || secs=${job_secs:-0}
                    [ "$secs" -gt 0 ] || secs=$(( $(date +%s) - started ))
                    dur=$(fmt_dur "$secs")
                    name=${job_name:-?}
                    # FINISHED is the clean end. A printer that jumps straight to IDLE inside one
                    # poll still counts as finished if progress had all but arrived; anything else
                    # ended early and says so rather than claiming 100 %.
                    if [ "$state" = "FINISHED" ] || [ "${last_pct:-0}" -ge 95 ]; then
                        msg="done $DOT $name $DOT $dur $DOT 100%"
                    else
                        msg="stopped $DOT $name $DOT $dur $DOT ${last_pct}%"
                    fi
                    # The end of a print interrupts either way; one that ended EARLY is worth
                    # more attention than one that finished.
                    _prio=default
                    case "$msg" in stopped*) _prio=high;; esac
                    if [ -s "$THUMB" ]; then
                        post_file "$msg" "$THUMB" "$_prio" || post "$msg" "$_prio"
                    else
                        post "$msg" "$_prio"
                    fi
                    rm -f "$THUMB"
                    printing=0; last_state="$state"; last_bucket=-1; last_pct=0; job_secs=0; job_name=""
                fi
                ;;
        esac
    fi
    # No response: post nothing. The complication's age display carries the staleness signal.
    sleep 60
done
