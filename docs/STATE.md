# STATE — where each phase stands

## Status, 2026-08-28

**Where it stands.** Collection is running on its own and has been for four days. The watch
records eleven kinds of thing, the server keeps every one of them forever, the detector watches
for unusual moments and asks about them, and the watch's own ECG now produces true beat-to-beat
heart data — the thing that was supposed to be impossible without buying a chest strap. What the
project does not yet have is *labels*, and that is now the only thing standing between here and a
model. Everything below is either running, or waiting on time passing.

### What is running, and how fresh it was when this was written

Each row is a stream of data. "Freshness" is how long ago the newest entry arrived.

| stream | what it carries | freshness |
|---|---|---|
| physiology from the wrist | heart rate every 2 s, skin temperature, on-wrist detection, ambient light, air pressure, steps, distance, floors, elevation, energy, awake/asleep/exercising | under a minute |
| workstation | processor, graphics, memory, temperatures | 2 minutes |
| prompts | the questions the watch asks | last one 10 hours ago |
| labels | what was answered | 6 minutes |
| detector | scores every five minutes of the day against your own past week | 17 minutes |
| the archive | every stream above, appended forever, on the server | 3,002 lines |

### What has been collected so far

| thing | count | note |
|---|---|---|
| five-minute windows scored | 1,139 | since 24 August |
| labelled moments | 11 | across 16 rows, because revisions are kept too |
| labels from a detector question | 2 | the stream that will train the model |
| labels from a random question | **0** | the stream that will TEST it — the clock has not started |
| ECG readings | 4 | 30 seconds each, 7,500 samples, real voltage |
| readings turned into true beat intervals | 3 | one rejected as contact noise, on purpose |
| nights of overnight variability and sleep | 14 | pulled from the health account |
| times the detector has asked on its own | 2 | plus one delivery test |

Two beat-to-beat measurements taken half an hour apart read 43.5 ms and 75.1 ms — a near doubling
that the ordinary heart-rate stream could not have seen at all. That difference is the kind of
thing this whole apparatus exists to explain.

### What is not yet true

- **The evaluation stream is empty.** Every claim the detector will eventually make about itself
  has to be scored against questions asked at random times, and none have been answered yet: the
  first few were never delivered because of a fault fixed yesterday. Until that stream fills,
  nothing can be said about whether the detector beats chance.
- **Nothing is being learned yet.** The detector ranks strangeness; it does not classify. No model
  exists and none should until there are labels to fit one to.
- **Battery cost is unmeasured.** It needs a stretch of ordinary wear-days to attribute.
- **The autostart for the workstation's temperature reader has never actually fired.** It waits for
  the next sign-in. If it fails, processor temperature quietly disappears and the overheating alarm
  can never fire.
- **About twenty duplicated overnight rows** are in the archive from a fault fixed yesterday.
  Harmless and identical; the analysis will collapse them.
- **Breathing rate** has no working name in the health interface. Four spellings were rejected and
  there is no way to list the valid ones. It is the least important of the set.

### Rough ETAs

"What moves it" is the single lever that would bring the date forward.

| milestone | what has to happen | rough date | what moves it |
|---|---|---|---|
| the health connection expires | nothing — it just lapses | **4 September** | one toggle: publish the consent screen |
| a battery figure | seven ordinary wear-days | ~5 September | wearing it |
| 50 training labels | detector questions answered at ~2–3/day | ~13 September | the daily question budget |
| enough ECG readings to calibrate | ~30 good readings across different states | ~12 September | pressing the button beside labels |
| 100 training labels — the structure audit | same rate continues | early October | the daily question budget |
| 50 random-stream labels — the first honest test | one random question a day, answered | **early November** | raising the random budget is the ONLY lever |
| a model that shows a state on the wrist | must first beat a time-of-day baseline on random-stream labels | not before November | everything above |

**The one number worth arguing about** is the last row's lever. At one random question a day, the
first honest measurement of whether any of this works lands in November. At two a day it lands in
early October. It is a burden question, not a technical one, which is why it is yours.

### Waiting on you

1. **Publish the consent screen** in the cloud console so the health connection stops expiring
   weekly. Until then it dies on 4 September and the ECG, sleep and overnight data stop arriving.
2. **Decide the random question budget** — one a day or two. It is the only thing that moves the
   November date.
3. **Press the ECG button beside labels** when you can. Every reading is a piece of ground truth,
   and they are the scarcest useful thing after labels themselves.

Updated 2026-08-23.

| Phase | Status | Notes |
|---|---|---|
| 0 recon + scaffold | done | Tooling verified. Scaffolded; APK builds as com.emotiveautomaton.wristwork. Pushed public: github.com/EmotiveAutomaton/wristwork. |
| 1 bus online | done (mirror deferred) | ntfy + label archiver live on the NAS as restart-always containers; round trip publish→labels.jsonl verified <3 s. Claude Code Stop/Notification hooks installed and test-fired. Nightly mirror waits on the rig address. |
| 2 watch link | done | Paired + connected over Wi-Fi adb (second attempt; pairing dialog must stay open). connect.sh verified against the live device. Watch reports API 37. |
| 3 health complication | installed, awaiting slot | On the watch, service registered, watch→NAS ping 7–22 ms. Render pending owner placing it on the face. |
| 4 state complication | built + verified on device | Grid renders (2×4, toggle, mic), tap→Room→DataStore→POST→labels.jsonl round trip verified <10 s via adb-driven tap. Offline replay path (WorkManager network constraint) UNVERIFIED — will be exercised naturally when first tagged away from Wi-Fi. State complication awaiting a face slot. |
| 5 channel complications + feeders | done | All four complications slotted 2026-08-23. Fixed on-device crash (DataStore delegate scope) that blanked rig/printer. Stats task live (5 min, exit 0). Printer poller container live on the NAS, digest auth verified. Printer complication is now ALWAYS VISIBLE (owner 2026-08-24): live percent while printing, otherwise the icon plus a ticking time-since-the-last-print-finished; tapping between prints shows that print's record (name, 100 %, clock time, duration). Poller v2 posts `done · name · dur · 100%` at completion (payloads verified offline against canned finish and cancel sequences; redeployed mid-print 2026-08-24 20:21). VERIFIED END TO END: the 08-24 print posted `done · obj_1_pentagon-fidget-keychain-v2(3)_0.4 · 5h 27m · 100%` at 22:27, and the installed face now shows the printer icon + `18H` — last print, 18 hours ago. |
| 6 interpretation | gated | build nothing |

Open design decisions are in [DECISIONS.md](DECISIONS.md).
Health/state design authority (owner design meeting + absorbed research): [../HEALTH_DESIGN.md](../HEALTH_DESIGN.md) (2026-08-24).


## Wave 2026-08-25 (built, awaiting install)

Agents line: staleness parentheses dropped wherever a ticking age is drawn, name budget +1.
Printer record: carries the print's own thumbnail, uploaded by the poller as an ntfy attachment
(bus container recreated with attachments enabled, 720 h expiry, 5 MB cap; cache and archives
untouched). Rig frame: a deliberately unwired `mic` button — speak, see the transcript, it is
dropped. APK builds clean; the watch was asleep at install time, so none of it is on the wrist yet.

## 2026-08-28: TRUE HRV, from the watch, without a chest strap

The health API is authorised and the ECG path works end to end. Three readings came back as
7,500 samples each — thirty seconds at 250 Hz of actual voltage — and beat detection on two of
them produced genuine beat-to-beat intervals:

    06:49:33   32 beats   RMSSD 43.5 ms   SDNN 47.7 ms   HR 64.0 (the watch said 63)
    07:21:01   31 beats   RMSSD 75.1 ms   SDNN 78.3 ms   HR 61.7 (the watch said 60)

The heart rate derived from our own R-peak detection agrees with the watch's own average to
within about one beat per minute, which is the check that the beats found were really beats. The
third reading was contact noise and was REJECTED rather than filed: it yielded four "beats" and an
RMSSD of a full second, and a bad trace does not fail loudly, it produces numbers. The gate is
disagreement with the watch's own average, plus physiological bounds.

Both readings sit beside labels the owner entered seconds earlier. That is the pairing this whole
build was walking toward, and the chest strap is now a nice-to-have rather than the only door.

Three bugs found and fixed getting here: the scopes are grouped rather than per-metric (the
consent screen named exactly which guesses were invalid); the deduplication key fell back to
Python's randomised hash, so every run re-filed everything it had already filed; and a 45 KB ECG
record silently became a file attachment on the bus instead of a JSON line, so the waveform is
now chunked the same way the watch chunks its own oversized batches.

KNOWN, AND ON A CLOCK: refresh tokens issued while an OAuth consent screen is in TESTING expire
after seven days. The fix is to set the consent screen's publishing status to "In production" —
an unverified production app still works for its own developer. Until then the pipeline goes
quiet in a week, and the puller now says so in plain words instead of dying with a stack trace.

## 2026-08-28 (later): the sealed sensors, answered with measurements

Asked on the device rather than argued about. The private-sensor permission cannot be granted —
the system calls it "not a changeable permission type", so it is signature-level and only a
Google-signed or system-installed app can hold it. Raw pulse, skin conductance and continuous ECG
are behind it. Skin conductance has no API at all and Fitbit have said it is not on the roadmap.

But the ECG APP IS INSTALLED on this watch, and its thirty-second readings come back through the
health API as raw waveform samples at 250 Hz. That is genuine beat-to-beat data — the calibrator
this project wanted — at no cost, no risk and no root. It is on-demand rather than continuous,
which makes it a calibrator rather than a stream.

Bootloader state as reported by the watch: verified boot green, flash locked, unlock-allowed
empty. Rooting would need fastboot over the charging pins with a modified cable, a wipe, and would
risk the Fitbit stack that supplies sleep, body-response and the ECG path itself. Deferred.

Printer: a print that pauses for filament or wants attention now buzzes, and one that ends early
buzzes harder than one that finishes (owner 2026-08-28). Routine progress stays silent.

## 2026-08-28: collection widened, grid v5, detector in shadow

Collection now covers every background channel this device offers (verified by asking the device:
heart rate, intraday steps/calories/distance/floors/elevation, awake-asleep-exercise state, plus a
per-wakeup sweep of skin temperature, off-body, light, pressure and cadence). The two-day backlog
from the 26th drained completely — the archive went from 150 lines to over 2,100 and is current.

Grid v5: sliders removed, meaning moved into the grid (Neutral primary = low intensity;
secondaries with no primary = low confidence, and that saves); the eighth state now reads NEUTRAL;
emptying an event you placed removes it via a tombstone; the rules live in small type at the
bottom; the timeline gained a magnifier on long-press with quarter-hour resolution. The state line
on the face shows NEW while a prompt waits and returns to an age on submission.

Detector v1 runs every fifteen minutes in shadow mode and has scored ~980 five-minute epochs from
the past week. Median strangeness 0.81, ninetieth percentile 1.76, maximum 13.7 — the top-ranked
moment was a 00:40 heart-rate excursion more than twenty standard deviations above its
time-matched baseline. It sends nothing to anyone.

Verified by screenshot: the grid renders with the new layout and Neutral in place. NOT verified:
the press interactions and the magnifier — injected taps and swipes are unreliable on-wrist, as
always, so those need the owner's fingers.

## The health stream had stopped, and said nothing (found 2026-08-26)

Symptom: 1,005 raw batches queued on the watch, oldest 2026-08-24 16:13, nothing reaching the
archive since. Nothing was lost — the queue is the queue — but the archive has a two-day hole that
fills in as the backlog drains, and NOTHING ANYWHERE SAID SO. A retrying queue looks exactly like
a working one.

Two causes, both structural:
1. **Oversized messages.** ntfy's default message-size limit is 4 KB; heart-rate batches run to
   18 KB. Those posts failed, the pass broke on the first one, and the backlog grew. Fixed on both
   sides: the server now allows 32 KB (and 500-burst request limits, so a backlog CAN drain), and
   the drain splits any payload over 12 KB into sample chunks before posting, so it never again
   depends on server configuration.
2. **The queue head-blocked itself.** The drain enqueued with APPEND_OR_REPLACE, so every new
   batch APPENDED behind a request that was in exponential backoff — capped by WorkManager at five
   hours. One failure therefore stalled the whole stream for hours, and each new batch politely
   queued up behind the stall. Now REPLACE: the worker drains whatever the database holds, so
   replacing a pending request loses nothing and resets the backoff clock.
Also: the drain now LOGS every failed post with its HTTP code. The silence was the real defect.

Related, same day: the capability probe marked itself done before it had succeeded, so one silent
failure retired it permanently. It now records success only after the record is queued.

## Detector D0 + D1 built 2026-08-26 (design approved, three decisions taken)

Owner's decisions, recorded: the detector runs on the RIG (not the NAS); the fifteen-minute prompt
delivery ceiling is accepted in exchange for battery; the eight grid states are never subdivided on
the front end — finer structure lives in the back end and informs one indication, and a moment that
is two things is expressed with secondaries. Google Health is out of v1 entirely.

D0 — provenance. Label schema v3 adds `promptId` and `promptTs` (additive migration; no history
rewritten). Source vocabulary is now `random | signal | self | google`; the legacy five map at
analysis time, documented in the README along with THE HOLDOUT RULE, which is now a repo law:
random-stream labels are evaluation only, forever.

D1 — the prompt path. The allocator (`tools/rig/prompts.ps1`, Task Scheduler `wristwork-prompts`,
daily 00:15) picks a uniform random time inside the waking window and posts the prompt to a new
`prompts` topic AHEAD OF TIME with a deliver-at stamp, at priority `min` so the ntfy app stays
silent. `PromptWorker` on the watch polls every fifteen minutes, fires prompts whose moment has
come, drops any older than forty-five minutes rather than asking a stale question, and opens the
grid bound to the prompt's own timestamp. Copy is built on the watch from the timestamp alone —
`State?` / `· 2:41p`, identical for random and signal — so the blinding cannot be broken by
anything the server sends. Verified: the periodic work is enqueued as unique work `prompt-poll`
(WorkManager diagnostics), the ACL grants both accounts the new topic, and today's prompt
allocated on the first run.

Reserved, not built: a `kind: "rr"` payload in the health stream for a future BLE chest strap —
the only route to true beat-to-beat HRV here, intended as an occasional calibrator for our
bpm-derived variability. The archive accepts the shape today so that no migration is needed later.

## QA pass 2026-08-26 (watch unlocked and connected)

Verified on the device: the printer frame shows the PICTURE of the last print above its record
(name, done 100 %, finished Mon 22:27, 1d 8h ago, took 5h 27m); the rig frame's `mic` button is
present below the process table; both frames pull live data through the public bus. The archivers
reconnected after the auth flip — one 403 line each at the moment of the switch and nothing since,
which is the proof, because a broken subscriber retries every five seconds forever. Rig stats task
last ran 07:05 result 0; nightly mirror 03:30 result 0.

Three defects found and fixed during the pass:
1. THE BUS TOKEN WAS BEING SENT TO THE PRINTER. The token interceptor lived on the shared HTTP
   client, so it stamped `Authorization: Bearer …` on PrusaLink calls too — replacing the digest
   header on the retry and breaking every printer request the moment the bus gained a token. The
   printer client now uses a credential-free client. (This is why the picture would not load.)
2. The name budget was wrong in BOTH directions, and the face was measured rather than guessed.
   The slot is a PIXEL width — about five and a half capitals with the people icon present. It
   rendered "GHO·5" and ellipsised the "H" away, so even the long-standing three-letter budget was
   over; the parentheses had been hiding it by being narrow characters. The budget is now two
   letters everywhere, which never ellipsises and still separates every project on this machine
   (GH, SO, WR, FE, HM, WE, LO, BU, SI). Owner decision pending: dropping the people icon would
   buy roughly one and a half more letters.
3. The record picture drew twice (once in the live-print slot, once in the record). The live slot
   now only draws while a print is running.

Known, unexercised: the LibreHardwareMonitor autostart task has never fired (at-logon trigger,
no logon since it was created). LHM is running from the manual launch and its own config has the
web server on at port 8085, so the chain should work — but it is untested until the next reboot,
and if it fails the CPU temperature silently vanishes from the rig payload and the temperature
alarm can never fire.

Not on our side: no Fitbit body-response NOTIFICATION has ever reached the watch, though the
owner reports one body-response event visible inside the Fitbit app. Other agents are chasing the
setting; our listener logs every Fitbit notification it sees and has captured three (syncs and a
Morning Brief), so the capture path is not the suspect.

## RESOLVED 2026-08-26: the bus is on the internet, behind auth

The owner added a published application route to the Cloudflare tunnel already running on the NAS
(the routes list was empty, which turned out to mean that tunnel was not carrying the website at
all — the site is served some other way and is still up, checked). The bus now answers on a
hostname over HTTPS, with ntfy `auth-default-access: deny-all` and two accounts, `wrist` and
`svc`, read-write on our seven topics and nothing else.

Verified, not assumed: unauthenticated read AND publish both return 403; token read returns 200;
message history survived every container recreation (44 printer / 71 agents / 768 rig / 150
health / 7 tags / 3 flags in cache); the rig stats task published through the tunnel; the printer
poller container authenticates from inside the NAS; the Claude Code hook published; and the WATCH
both read the bus (the agents frame filled with real finishes) and published to it (an ack landed
when the frame opened).

Two real defects surfaced while testing rather than inferring:
1. Cloudflare answers 403 to the default `Python-urllib` user agent. The hook was silently failing
   until it was given an ordinary agent string. Any future Python client on this bus needs one.
   A WAF skip rule on the hostname would be the durable fix; not done, dashboard work.
2. The ack POST had NEVER worked. Its URL was written with escaped dollars, so it was the literal
   string "${...}" — OkHttp threw and runCatching swallowed it. Consequence: opening the agents
   frame never re-armed notifications, so a project stayed silent forever after one unaddressed
   finish. Fixed; the round trip is now verified in both directions (priority 1 while unaddressed,
   priority 3 after an ack).

Flag pipeline is also proven now: three real Fitbit notifications (two syncs and a Morning Brief,
2026-08-25 and 08-26) travelled watch -> Room -> bus -> flags.jsonl. No body response yet.

## Superseded (2026-08-25): the bus was LAN-only

The owner left the house and every channel went stale. Cause, confirmed rather than guessed: the
watch is the Bluetooth/Wi-Fi model (`ro.product.name=wisteria_btwifi`, SIM state empty), so away
from home its only internet path is through the phone over Bluetooth — a path it does have. What
it does not have is anything answering at `192.168.0.2:8093`, which exists only inside the house.
Nothing was lost: labels queue in Room and the drain worker replays them on reconnect.

Proposed fix, prepared and awaiting the owner's go: publish the bus as a hostname on the
Cloudflare tunnel ALREADY running on the NAS for the portfolio site (no new account, no money, no
router ports opened), with ntfy switched to deny-all and two token accounts. Client-side plumbing
is done and inert until a token exists: `NTFY_TOKEN` in the gitignored config feeds BuildConfig,
the HTTP edge attaches `Authorization: Bearer …` when it is non-empty (timeouts raised 5 s -> 8 s
for the Bluetooth path), and the rig stats, canary, printer poller and Claude hook carry the same
header from `NTFY_TOKEN_SVC`. The server-side flip is one prepared script (`tools/server/publish-bus.sh`),
not yet run. PrusaLink stays LAN-only forever; off-network the printer frame shows the bus record.

## Known test artifacts in the label archive (append-only; annotate, never delete)
- `2026-08-24T05:10:58-07:00` SEEK (schema v2), `source:"manual"` — grid v2 adb fast-path test (not a real label).
- `2026-08-24T11:01:01-07:00` RAGE, `source:"manual"` — grid v3 adb fast-path test after the process-death commit fix (not a real label).
- `2026-08-22T12:03:00-07:00` state SEEK, `source:"provision-test"` — Phase 1 provisioning check.
- `2026-08-22T12:54:23.856046-07:00` state OTHER, `source:"manual"` — Phase 4 automated adb tap test (not a real label).
