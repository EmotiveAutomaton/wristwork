# wristwork — living spec

**This file is the core spec sheet and is kept current as the design evolves.** The original
build runbook (below the divider) stands as written history; where this section disagrees with
it, this section wins. Owner sets design at a high level; implementation detail lives in
`docs/DECISIONS.md`; phase status in `docs/STATE.md`.

## Current design (updated 2026-08-24)

**Name:** wristwork (runbook said "telltale"; owner renamed 2026-08-22).

**System as built:** Pixel Watch 5 complications <- ntfy bus on the Synology NAS (:8093) <- feeders
(rig stats from the owner's workstation, PrusaLink poller container, Claude Code hooks). Raw labels
append to `labels.jsonl` on the NAS, mirrored nightly to the workstation. All laws from the runbook
hold (immutable raw data, no secrets in git, battery < 3%/day, no services/alarms/wake locks).

**Shared frame look (2026-08-23):** every tap-frame renders in the watch face's own font
(Google Sans via the device font family, silent fallback to system default; monospace stays for
aligned tables) through one shared theme (`app/.../ui/Theme.kt`).


**Fetch interop (2026-08-24):** the sibling project `../Fetch` is becoming the household's
agentic front-end hub and names this bus as its watch seam (its spec: "near-term the bus is the
seam"). Standing declarations from our side, which Fetch may build against:
- **The wristwork bus is PUBLIC-TIER transport only.** LAN-plaintext, archived, nightly-mirrored.
  Nothing from Fetch's private/HIPAA tier (Melissa's texts/emails or derivatives) may ever be
  published to any wristwork topic. This is a law, matching Fetch's structural-boundary law.
- **Topic payloads are a contract once Fetch consumes them**: `tags`/`flags`/`health` carry the
  JSON schemas in this spec; `agents` carries `done: {project}[: {summary}]` / `needs input:
  {project}` plus alert texts (canary, rig temperature); `rig`/`printer` carry the feeder formats
  above. Changes to these are spec changes.
- **ACLs**: the bus runs open on the LAN today. If Fetch's D-decisions add ntfy auth, every
  wristwork client reads credentials from the gitignored config — a config change, not a redesign.
- Deeper migration of the bus/feeders under Fetch's umbrella is an owner decision parked with
  Fetch's open worksheet (D12-D18); nothing here preempts it.

**Faces (v2, in flight — one-by-one design passes):**
- All numeric readings are integer percent of the machine's maximum, capped at 99.
- Age counters are OFF face-wide for now; reconsidered per complication in its pass. Staleness
  (>2 h) still marks itself: payload renders lowercased in parens. The face owns all styling
  (font/size/color); we control characters, icons, and complication type. Rich visuals live in
  tap-frames, which are fully ours.
- Every complication tap opens a single full-screen frame (back-swipe closes); one frame, no
  navigation tree.

**rig** (iteration 7, 2026-08-24): the face G glyph is driven by max(gpu, vram). Temperature
alarm replaces the whole line with `!!!!!!` — per-component thresholds (researched: Ryzen 7000
parks at its 95 C TjMax BY DESIGN, so cpu alarms only >= 97 = above-TjMax anomaly; NVIDIA cores
throttle 83-90, so gpu alarms >= 90); the feeder's phone alert uses the same thresholds.
Earlier iteration (2026-08-23, iteration 6): graph colors remapped by owner — cpu green,
gpu blue sharing one chart with vram orange (label rendered per-word in its series color with a
blended middle dot; both current values on the right in their colors), ram purple; the table's
number tints follow. The temp chart shows colorized current values only (no series names). Temp chart: label bright red like the warning line,
temperature lines full-opacity in their resource colors (gpu blue). CPU temperature flows via
LibreHardwareMonitor (portable, `~/Tools/LibreHardwareMonitor`, elevated, web server :8085; the
feeder walks its sensor tree — Ryzen Package/Tctl). NOTE: the 7900X rides 95 C by design under
sustained load, so the CPU alert fires once at the start of long heavy jobs — expected, not an
emergency. LHM needs its logon task registered or CPU temp silently drops after reboot. Face renders
circle-fill usage — `c◔ g◑ r◕`,
one glyph per resource at quintile resolution (○◔◑◕ empty->three-quarter; the block glyphs sat
below the text baseline and looked janky), the top quintile becoming `!` with its letter
uppercased (this face force-uppercases everything, so the case signal only shows on faces that
respect case). Space-separated — the owner's slot renders ~8+ chars despite the API's 7-char
contract. A monochrome CPU-chip icon rides the
line: plain normally, one exclamation badge when any resource > 90%, three when a temperature is
critical. RANGED_VALUE gauge (busiest resource) still offered. **The 7-char question, settled:**
SHORT_TEXT text is capped at 7, but the slot also has a separate TITLE field, and the owner's
face draws `title - text` on one visual line — that is where the earlier extra room came from, not
an overflow. Frame title is the machine name ("black pearl"). Tap-frame: five stacked 6 h charts — cpu / gpu / ram / vram percent, then all
temperatures superimposed on one 0-100 degC chart: label white, each line half-transparent in its
matching resource color (gpu green, cpu blue), and a faint red dotted warning line at 90 degC
with tiny "!!!" at its right end — crossing it is what fires the phone alert — then the top-10 processes
with a wider name column and c/g/r number columns tinted white->series-color as each value climbs
to 99. Charts sit inside the round margins; extra black below clears the arc. Feeder posts every
5 min: `{"cpu,ram,gpu,vram,tg,tc, procs:[[name,c,g,r] x10]}`; per-process GPU from Windows GPU
Engine counters; bursty GPU sampled twice, max kept. **Temperature alert:** gpu >= 90 C or
cpu >= 95 C posts a high-priority message to the `agents` topic, once per excursion, re-armed on
cooldown. Scheduled feeders run through a windowless VBScript wrapper (`tools/rig/run_hidden.vbs`)
so no console flashes. Known jitter: per-process CPU is a 1 s window, can read low vs the total.

**agents** (2026-08-23, iteration 5): face shows a two-person icon then `WRI·18M` — the most
recent finished project, middle dot, auto-ticking age. The name budget adapts to the age's
width: a short age ("7M", "2H", "3D") buys a fourth letter; two-digit ages leave three. (With an
icon this face drops the title field entirely, leaving one ~7-char text field — anything longer
ellipsizes with the age falling off first.) The Stop hook now appends a one-line summary of what
the session finished ("done: project: summary" — extracted from the transcript's final assistant
message); the tap-frame shows it as each chip's second line, with `project · age` as the first.
Frame window is 24 h but NEVER renders empty: if the day is quiet it falls back to the single
most recent finish however old (the bus cache was raised from 24 h to 30 days for this). The M/H/D case lives inside the platform's auto-ticking time format and
cannot be styled; the alternative (caseless superscript ᵐʰᵈ) would cost the auto-tick, going
stale up to 15 min between refreshes — not taken. "needs input" still shows INPUT; "needs input" still shows
INPUT. Tap-frame: the latest finish per project from the last 24 h, deduped (three SoundingLine
finishes collapse to the newest), newest first, as chips showing name + relative age; tapping a
chip asks the paired phone to open Claude Code (claude.ai/code — the Claude app intercepts if
installed; no public per-session deep links exist). This topic also carries the rig temperature
alerts. 2026-08-25 (owner: "it opens with a parenthesis sometimes, and I want the extra letter"):
the slot was MEASURED on 2026-08-26 — it fits about five and a half capitals with the people icon,
which is why "GHO·5H" lost its "H" to an ellipsis; the name budget is two letters everywhere now,
enough to separate every project here, and dropping the icon would buy about one and a half more.
The staleness parentheses are gone from any face that draws a ticking age — the age states
staleness better than brackets do, and the brackets were eating two of the seven characters. The
name budget went up one across the board; the evidence that it fits is the face itself, which had
been rendering "(GH·11H)" — eight characters, no ellipsis. Non-Claude agents: anything that can run a curl can post here (Codex CLI's notify hook
qualifies); the ChatGPT consumer app exposes no hooks or history API and cannot be wired.

**remote reachability — DONE 2026-08-26:** the bus is published on a hostname through the
Cloudflare tunnel that was already running on the NAS, with ntfy deny-all and two token accounts
(`wrist` for the watch and the phone app, `svc` for rig stats, canary, poller, hooks, archivers).
Everything carries `Authorization: Bearer …` from the gitignored config; the pre-commit hook
blocks `tk_…` strings and both token keys. Cost: nothing. Router ports opened: none. Two findings
worth keeping: Cloudflare 403s the default Python-urllib user agent, so the Claude hook now sends
its own; and the ack POST had never worked (escaped-dollar literal in the URL), which had left
notification re-arming silently dead — fixed and verified in both directions. Attachment storage
is on (720 h, 5 MB cap) for print thumbnails. Original problem statement follows.

**remote reachability (2026-08-25, owner: "how do we wire this up to be a permanent fixture"):**
the bus address compiled into the app is a LAN address, so every complication silently ages out
the moment the watch leaves home Wi-Fi. The watch is the Bluetooth/Wi-Fi model (no LTE, verified
on-device), so its off-home path is the phone over Bluetooth — a real path with nothing at the
far end. Decision pending: publish ntfy as a hostname on the Cloudflare tunnel already running on
the NAS for the portfolio site, with ntfy `auth-default-access: deny-all` and two accounts
(`wrist` for the watch and the phone app, `svc` for rig stats, hooks, poller, archivers). Tokens
live in the gitignored config and nowhere else; the pre-commit hook now blocks `tk_…` strings.
Rejected: Tailscale (no Wear OS client exists; sideloading a phone VPN onto the watch is
unsupported and would cost battery), ntfy.sh hosted (third-party custody of emotional-state
labels and heart rate), port-forwarding with DDNS (opens the home router for no gain over a
tunnel that is already up). PrusaLink is never exposed — off-network the printer frame falls back
to the bus record, which is what the 2026-08-24 pass made possible.

**printer** (2026-08-24, third pass — ALWAYS VISIBLE, owner): the face never disappears. While a
print runs it shows the printer icon + live progress percent (watched it materialize when a print
started). Between prints it shows the icon + a bare ticking age, which reads as "the last print
finished that long ago" — the completion record is posted at the moment of completion, so the
message's own timestamp is the finish time and the age needs no payload. Tapping between prints
shows the record of that print: THE PICTURE OF IT (owner 2026-08-25), its name, a full bar at
100 %, the clock time it finished, how long ago that was, and how long it took. The picture is
the job's own embedded thumbnail, uploaded by the poller as an ntfy attachment on the completion
message, so it outlives the job on the printer and travels off the LAN with the record; the
message text rides in an RFC 2047 encoded-word because a raw UTF-8 header turns the separator dot
into U+FFFD (measured against the live server). Attachments are kept as long as the message cache
(720 h) and capped at 5 MB; a thumbnail is ~20 kB. If the upload fails the plain record still
posts — a picture is never allowed to cost us the record. The record is read off the BUS, not the printer:
PrusaLink forgets a job the moment the completion screen is dismissed, and the watch must still
answer "when did it finish" hours later with the printer asleep. Poller payload contract (now a
contract, since Fetch may consume it): `{n}%` while printing, `paused`/`ATTN` on transitions,
`done · {name} · {dur} · 100%` at completion, `stopped · {name} · {dur} · {n}%` when a print ends
early. Plain idle posts nothing at all — an `idle` post at container start would age like a
phantom print, and staleness must never read as freshness; pre-2026-08-24 `idle` messages still
render as the word, with no age claim. Frame v2: thumbnail, print name, `state · NN%` headline using PrusaLink's detailed
state text when it differs ("absorbing heat" — a known non-error stall state, no alert wanted),
a timeline bar (start clock-time left, total expected duration right, "Xm left" trailing the
fill; appears once the printer reports time estimates), one combined temperature line
(nozzle/bed with targets), and the loaded material (from the legacy /api/printer endpoint).
Dropped by owner: z-height, speed, fans, elapsed. Tap-frame talks to PrusaLink directly over the LAN (digest auth on-watch):
the job's own embedded thumbnail — the picture of what's printing — then print name, state,
progress, remaining/elapsed, nozzle and bed temps with targets, z-height, speed, both fans,
refreshing every 5 s while open. Printer host + API key ride the gitignored config into
BuildConfig (private debug APK only; never in git).

**printer notifications (2026-08-26, owner):** the printer line updates SILENTLY. Every routine
post — progress steps, paused, attention — goes out at ntfy priority `min`: delivered, cached,
read by the complication on its next refresh, but no sound, no buzz, no pop. Only the end-of-print
record posts at normal priority. The poller's post helper now defaults to `min`, so a new call
site has to ask to be allowed to interrupt. OPEN with the owner: `stopped` and `ATTN` are silent
under the letter of the instruction, which means a print that dies waiting for filament says
nothing; one word from the owner flips those two to a normal notification.

**printer picture (2026-08-26):** the record frame shows the last print's picture. Two sources,
in order: the thumbnail the poller attaches to the completion message (works anywhere, survives
the printer forgetting the job), and failing that a direct lookup on the printer's own storage
listing matched by name prefix (LAN only, once per record — a miss must not re-ask every five
seconds). The live-print slot only draws while a print is actually running, or the same picture
appears twice. LESSON KEPT: the shared HTTP client must never carry bus credentials — the token
interceptor clobbered PrusaLink's digest header and silently broke every printer call.

**rig frame mic (2026-08-25, owner):** a `mic` button at the bottom of the rig frame, wired to
nothing on purpose — press it, speak, the transcript appears and is then dropped. No audio is
recorded by us and no text is stored, queued or posted (owner: "we don't even have to store the
damn microphone information because we'll just be transcribing it and tossing it immediately").
It is a placeholder for future print requests spoken from the wrist; the back end is deliberately
unbuilt and its destination undecided. Transcription is the platform recognizer, the same one the
grid's mic note uses.

**what the wrist can actually measure (measured 2026-08-26, not assumed):** the heart-rate stream
is one INTEGER sample every 2 s, and it is a smoothed estimate, not per-beat instantaneous rate —
in 3,519 archived samples, 42 % of consecutive values are identical, the median change is 1 bpm
and the 90th percentile is 2. Beat-to-beat HRV (RMSSD, pNN50) is therefore NOT recoverable:
differencing this stream measures the watch's filter. What IS real is variability of the rate
itself — the SD of bpm over 60 s windows runs 1.2-5.6 (median 2.05) against a quantisation floor
near 0.5, and it rises with heart rate (1.78 in the lowest-HR third, 2.64 in the highest). That
feature goes in the model under a name that cannot be mistaken for RMSSD. Health Services offers
no HRV, RR-interval, skin-temperature, SpO2 or sleep data type at all (checked against the API
source); skin temperature comes from the raw sensor list, which is also where anything else will
come from. True beat-to-beat HRV needs an external BLE chest strap, best used as an occasional
calibrator rather than a daily wearable. Runtime inventory of this device's actual capabilities
(`SensorInventory`) files itself into the health stream as a `kind: "inventory"` record.

**grid v5 (owner, 2026-08-28) — the sliders are gone.** Intensity and confidence are no longer
numbers on a scale; they are expressed in the grid itself, which is both fewer motions and, in the
owner's judgement, a better instrument for reporting under alexithymia — a forced 1-to-5 invents
precision that was never felt. Low intensity is NEUTRAL as primary with secondaries carrying the
flavour. Low confidence is secondaries only, with no primary at all — and such a label SAVES; it
is data, not an unfinished form. The eighth state is displayed as NEUTRAL rather than "other"
(canonical word in the archive stays OTHER; only the meaning on the wrist changed, and the change
is dated here so old rows are never read as the new thing). An event emptied completely is
REMOVED by appending a tombstone — that is how the owner deletes something they placed themselves;
flags and prompts placed by the system are not labels and cannot be removed. The rules sit in
small type at the bottom of the grid until they are second nature. Timeline: a tap jumps to an
event, a LONG-PRESS opens a magnifier — a ninety-minute window, ticked every quarter hour, panned
by dragging — for placing a label on a particular moment. The face's state line shows NEW while a
prompt is waiting, and returns to the age of the most recent event the moment anything is
submitted.

**collection widened 2026-08-28** to everything the device actually offers in the background,
asked of the device rather than the documentation: heart rate, intraday steps, calories, distance,
floors and elevation gain, the platform's own awake/asleep/exercise state, and a short sweep of
the cheap sensors on each existing wakeup — skin temperature, off-body detection, ambient light,
barometric pressure, cadence. Off-body matters more than it looks: a watch on a charger produces
numbers that are not about the wearer, and a detector that cannot tell would happily learn from
them.

**detector v1 running in shadow (2026-08-28).** Five-minute epochs, scored against a time-of-day
matched baseline of the owner's own past week, symmetric so unusual flatness counts as much as a
spike, ranked by the RMS of the per-feature z-scores. It posts NOTHING and will not until the
baseline has warmed and the scores have been judged against what actually happened. The
prior-only shadow guess from the design's literature table rides along, logged and never shown.
Output is a derived, recomputable file; raw data is untouched.

**queue discipline (learned the hard way 2026-08-26):** two failure modes that both present as
silence. A message larger than the server's limit poisons the head of the queue — every pass dies
on it forever — so payloads are chunked client-side (12 KB) as well as allowed server-side (32 KB).
And a unique work request enqueued with APPEND_OR_REPLACE stacks new work BEHIND a request that is
in backoff, which WorkManager caps at five hours; REPLACE is correct for any worker that drains
state from a database rather than from its own input. Every failed post now logs its HTTP code,
because a retrying queue is indistinguishable from a working one until someone counts rows.

**detector D0/D1 built 2026-08-26.** Decisions: rig (not NAS); fifteen-minute delivery ceiling
accepted; grid never subdivided on the front end; Google Health dropped from v1. Provenance
(`source` in {random, signal, self, google} plus `prompt_id`/`prompt_ts`) is written at capture,
never inferred; the holdout rule is now a README law. Prompts are allocated a day ahead by the rig
and posted to the `prompts` topic at priority `min`; the watch's own poller fires them and builds
the blinded copy locally from the timestamp. A `kind: "rr"` health payload is reserved for a
future BLE chest strap (calibrator for the bpm-derived variability, not a daily wearable).

**detector (H2) — planning only, 2026-08-26:** `wristwork-detector-design.md` at the repo top
level is the design authority for the detector/allocator, superseding the telltale-era draft. A
planning pass ran 2026-08-26; nothing is built. Two corrections to the design carried into that
pass: HRV is a SLEEP-ONLY covariate on this hardware (Fitbit measures RMSSD during main sleep of
at least three hours, and even its intraday endpoints only return values inside sleep), which
removes most of the reason to depend on the Google Health API at all; and the prompt delivery
path has a fifteen-minute floor because WorkManager is the only scheduler the battery law allows.
The design's finer guesser vocabulary (SEEK keyed/collapsed, FEAR active/freeze, GRIEF
protest/despair) does not conflict with the standing "do not pre-subdivide SEEK" law as long as
the GRID stays eight states — the subdivision lives inside the guesser, never on the wrist.

**state / health (H1 built; grid v3 pass 2026-08-24):** ROUND-SCREEN DESIGN RULE (owner): each
scroll-screen centers its main artifact mid-screen; nothing load-bearing in the pinched top
corners. Grid frame v3: dead space up top, then the TIMELINE as screen 1's artifact — a 6 h
horizontal line, arrow markers per event (red = unlabeled flag), tap a marker for that event's
primary/secondaries/intensity/confidence + relabel arming, "+ past" for retro events (15-min
tap-arrows; position-scrubbing rejected as imprecise). Grid 3/3/3: eight states + CLEAR (discards the current edit).
INTERACTION MODEL v4 (owner, 2026-08-24): nothing commits on tap. LONG-PRESS a state = primary;
TAP = toggle secondary; sliders 1-5 with 0 = n/a; BACK SAVES whatever is dirty (an edit with no
primary is discarded — that is the undo); selecting a timeline event saves the current edit then
loads that event; event-to-event editing of the whole 6 h window is free. The mix button is gone.
noticed? is compact beside the mic button. Display names rolled back to canonical words EXCEPT
RAGE->Vexed, FEAR->Tense, LUST->Heat (canonical small beneath just those three). Auxiliary
inputs at the bottom of the scroll: intensity + confidence sliders (fast path includes them when
set), compact noticed-before, mic note. Fast path unchanged: one tap commits. Commits are
SYNCHRONOUS before finish() — a fire-and-forget coroutine raced Wear's fast process kill and
sometimes lost the row (found on-device). Face shows humane name + ticking age (verified:
VEXED·3M). Flag listener enabled, awaiting its first real Fitbit notification; passive HR
registration required the granular Wear OS 6 health permissions (READ_HEART_RATE +
READ_HEALTH_DATA_IN_BACKGROUND — legacy BODY_SENSORS was failing silently), now registers OK,
first batches pending wear. Design authority: design authority is `HEALTH_DESIGN.md` (top level — owner
design meeting 2026-08-24 + absorbed research appendix). Program: H1 collection -> H2 prosthesis
(model with confidence on the wrist; gates: >=50 labels tier-2, >=100 structure audit) -> H3
scaffold (not designed). Parallel capture: Fitbit Body Response flags via watch notification
listener (+ silence canary) alongside owned passive HR + skin-temp streams. Label schema v2:
dual timestamps, mixtures first-class, sources, retro-labeling as a requirement. Face shows a
humane display name + time-since-entered (age ban lifted for this face). BATTERY LAW REPLACED by
the owner: "comfortably survives a full wear-day" — the <3%/day figure is disowned; measure and
report attribution during H1. Printer face is always visible (2026-08-24; it was invisible while idle until then). State face currently shows the state code alone; tap opens the 2x4 tag grid
(SEEK RAGE / FEAR LUST / CARE GRIEF / PLAY OTHER + "already noticed?" toggle + optional mic note).

---

# telltale — original build runbook (historical)

You are the build agent for **telltale**: Wear OS complications for a Pixel Watch 5 riding an ntfy bus, plus the server-side plumbing behind them. This file is the entire spec. The owner dropped it into an empty folder and expects you to drive.

## Operating contract

- **You do everything reachable from a terminal or by writing code**: repo scaffold, CI, provisioning the RAID server over SSH, builds, adb installs, scripts, service units. Do not narrate options — act, then report.
- **The owner is hardware hands only.** Batch physical steps: at each phase boundary emit ONE short checklist titled `OWNER STEPS`, wait for "done," then **verify yourself** (curl the server, `adb devices`, publish a test message) instead of asking the owner whether it worked.
- Interrupt only for: credentials/secrets, physical device menus, anything destructive, anything that costs money. Otherwise proceed on your own judgment and note decisions in commits.
- Secrets live in `config.properties` (gitignored). Scaffold `config.example.properties` with every key documented. Never let a topic name, tailnet hostname, or API key into git history; add a pre-commit grep.
- **Raw label data is immutable.** Every derived artifact is versioned and recomputable. This is a law, not a preference.
- Repo is public. Write it like someone will read it.

## Environment inventory (verify in Phase 0, assume until contradicted)

| Thing | Assumption |
|---|---|
| Laptop | adb/platform-tools probably present. Check `adb version`. If multiple adb installs exist (Android Studio's vs package manager's), consolidate to one — separate installs keep separate key stores, which masquerades as "pairing keeps breaking." |
| RAID server | Always-on, SSH-reachable (owner supplies alias/creds), Docker available, Tailscale maybe. Hosts: ntfy, label archive, printer poller, nightly mirror job. |
| Rig | Compute box, SSH-reachable. Hosts: stats timer, later the interpretation layer. Receives the nightly `labels.jsonl` mirror. |
| Watch | Pixel Watch 5, Wear OS 6/7. Owner taps menus. |
| Phone | Android, on Tailscale (verify). Owner installs the ntfy app. |
| Printer | Prusa CORE One, PrusaLink in stock firmware. API key comes off the printer's own screen (owner). |

## Phase 0 — recon and scaffold (no owner)

Verify laptop tooling. Scaffold: Wear OS app module (Kotlin, Compose for Wear OS, `watchface-complications-data-source-ktx`, min SDK = this device only, no backcompat), `tools/{watch,server,rig,printer,hooks}/`, GitHub Actions building a debug APK artifact on push. `.gitignore` covers config, keys, local props.

## Phase 1 — bus online

Over SSH to the RAID server: confirm Tailscale; run ntfy (`binwiederhier/ntfy` container or bare binary, cache file mounted, restart-always); install a systemd unit subscribing topic `tags` and appending JSON lines to `labels.jsonl`; add a nightly cron mirroring that file to the rig (owner calls this box janky; the labels are the only irreplaceable bytes in the system). Tailnet-only access is the v1 auth model — skip ntfy ACLs.

`OWNER STEPS`: install the ntfy app on the phone, point it at the server URL, subscribe `agents`.

Also in this phase: write `tools/hooks/settings-fragment.json` — Claude Code `Stop` → "done: {project}" and `Notification` → "needs input", both curling topic `agents` — plus a rendered preview of the owner's merged `~/.claude/settings.json`. The owner already runs PreToolUse hooks; **propose the merge, apply only on approval, back up the original with a timestamp.**

Verify: publish a test message to `agents`; the phone buzzing is self-evidencing. Confirm a line landed in `labels.jsonl` via a test publish to `tags`.

## Phase 2 — watch link (the one unavoidably manual phase)

`OWNER STEPS` (single checklist, watch on charger): join watch to home Wi-Fi (Settings → Connectivity → Wi-Fi — adb does not ride the Bluetooth proxy); Settings → System → About → tap Build number ×7; Developer options → ADB debugging ON, Wireless debugging ON; open Wireless debugging → Pair new device → paste the code and both `IP:port` values (pair port and the different connect port on the main screen) into chat.

You run `adb pair` and `adb connect`. Then write **`tools/watch/connect.sh`** so this never hurts again:

- Pairing persists across reboots; only the *connect* endpoint rotates. The script uses adb's mDNS discovery (`adb mdns services`, `_adb-tls-connect._tcp`) to find the watch's current IP:port and connect — no address hunting, no static IP needed. Set the mDNS backend env var if the platform needs it; fall back to prompting for a port if mDNS is blocked on the LAN.
- Re-pair only if keys were wiped — the script should detect "failed to authenticate" and say so plainly rather than looping.
- Optional teardown flag: attempt `adb shell settings put global adb_wifi_enabled 0` at session end; harmless if the build ignores it.

## Phase 3 — hello complication / network probe

Build and `adb install -r` a SHORT_TEXT complication that fetches `{server}/v1/health` and renders up/down + latency. `OWNER STEPS`: long-press face → Edit → put it in a slot; report what it shows. A latency number validates the entire architecture end-to-end. Down over tailnet → the phone-proxy question just answered itself: switch config to ntfy.sh with ≥24-char random topics (they are effectively passwords), commit the config-example change, proceed. That fallback is a config edit, not a redesign.

## Phase 4 — Component A: the state complication

- `ComplicationDataSourceService`, SHORT_TEXT (SMALL_IMAGE variant later). Face shows current state code + elapsed (`SEEK 43m`). Rendering is deliberately cryptic; privacy lives in the renderer.
- Tap → full-screen Compose grid, 2×4: **SEEK, RAGE, FEAR, LUST, CARE, GRIEF, PLAY, OTHER** — Panksepp's seven primaries plus the open-world hatch. One toggle: **"already noticed?"** (default unset; this is the experiment's dependent variable). Two taps, auto-close.
- Optional slow path: mic button → watch voice input → transcript rides as `note`. Never required.
- Event: `{ts, state, noticed, note?, source:"manual"}` → local cache (Room/DataStore, offline-safe, replay on reconnect) → POST to `tags`. Push a complication update immediately on tag.
- Do not pre-subdivide SEEK. Tier-2 (locked vs flat) happens at ≥50 labels, driven by counts.

## Phase 5 — Component B: channel complications + feeders

- One generic provider parameterized by topic + formatter; three manifest instantiations: `agents`, `rig`, `printer`.
- Poll `GET {server}/{topic}/json?poll=1&since={last}` on the platform's scheduled refresh. Accept the ~15-minute floor; **no alarms, no foreground services, no wake locks** — battery budget < 3%/day is an acceptance criterion. Push upgrade path (phone-side listener) is documented, unbuilt, v2.
- Render `{payload} · {age}`; stale >2h dims; staleness must never read as freshness.
- Printer formatter renders NO_DATA when idle/stale — the complication disappears between prints.
- `tools/rig/`: systemd timer, 5 min — CPU/RAM/GPU/load JSON to `rig`, sent on threshold-change or every 15 min, whichever first.
- `tools/printer/`: poller on the RAID server, 60 s against `http://{printer}/api/v1/status`, HTTP digest auth, user `maker`, password = API key (`OWNER STEP`: printer Settings → Network → PrusaLink → Reset once to generate, read it off the screen). Use `/status`, not `/job` — it returns state even when idle. Post on state transitions and each 5% step; FINISHED/idle posts `idle` once. Prusa Connect cloud is never used.

## Phase 6 — gated future (build none of it now)

Component C, interpretation layer, rig-side: LLM pass converting raw taps + notes into **mixture vectors over the seven primaries** (soft labels; anger may resolve RAGE-dominant with SEEK admixture; LUST/SEEK blends are data, not noise). Stance for the prompt: primaries are the basis set, construction happens at the labeling layer, mixtures are first-class. Derived vectors stored beside raw with model+prompt versions stamped. Structure audit at ≥100 labels: decomposition/clustering over sensor windows and label vectors — does empirical structure match the basis, rotate it, or exceed it (the OTHER pile feeds this). Exploratory only; n=1 autocorrelated series, no inferential claims. Any classifier comes after the audit and ships only if it beats a time-of-day-only baseline.

## Acceptance

- Tag round-trip wrist → `labels.jsonl` < 10 s; works offline with replay.
- Battery attributable to the app < 3%/day; zero foreground services.
- Age always visible on channel complications; stale never renders fresh.
- `connect.sh` reconnects after a watch reboot with no address hunting.
- No secrets or topic names anywhere in git history.

## Non-goals (v1)

Component C. Phone companion app. Tiles. Play Store. Any UI beyond one grid and four slots.
