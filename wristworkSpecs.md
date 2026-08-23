# wristwork — living spec

**This file is the core spec sheet and is kept current as the design evolves.** The original
build runbook (below the divider) stands as written history; where this section disagrees with
it, this section wins. Owner sets design at a high level; implementation detail lives in
`docs/DECISIONS.md`; phase status in `docs/STATE.md`.

## Current design (updated 2026-08-23)

**Name:** wristwork (runbook said "telltale"; owner renamed 2026-08-22).

**System as built:** Pixel Watch 5 complications <- ntfy bus on the Synology NAS (:8093) <- feeders
(rig stats from the owner's workstation, PrusaLink poller container, Claude Code hooks). Raw labels
append to `labels.jsonl` on the NAS, mirrored nightly to the workstation. All laws from the runbook
hold (immutable raw data, no secrets in git, battery < 3%/day, no services/alarms/wake locks).

**Shared frame look (2026-08-23):** every tap-frame renders in the watch face's own font
(Google Sans via the device font family, silent fallback to system default; monospace stays for
aligned tables) through one shared theme (`app/.../ui/Theme.kt`).

**Faces (v2, in flight — one-by-one design passes):**
- All numeric readings are integer percent of the machine's maximum, capped at 99.
- Age counters are OFF face-wide for now; reconsidered per complication in its pass. Staleness
  (>2 h) still marks itself: payload renders lowercased in parens. The face owns all styling
  (font/size/color); we control characters, icons, and complication type. Rich visuals live in
  tap-frames, which are fully ours.
- Every complication tap opens a single full-screen frame (back-swipe closes); one frame, no
  navigation tree.

**rig** (design pass 2026-08-23, iteration 6): graph colors remapped by owner — cpu green,
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
alerts. Non-Claude agents: anything that can run a curl can post here (Codex CLI's notify hook
qualifies); the ChatGPT consumer app exposes no hooks or history API and cannot be wired.

**printer** (2026-08-23, second pass — verified against two live prints): face shows a printer
icon + progress percent, appears only while printing (watched it materialize when a print
started). Frame v2: thumbnail, print name, `state · NN%` headline using PrusaLink's detailed
state text when it differs ("absorbing heat" — a known non-error stall state, no alert wanted),
a timeline bar (start clock-time left, total expected duration right, "Xm left" trailing the
fill; appears once the printer reports time estimates), one combined temperature line
(nozzle/bed with targets), and the loaded material (from the legacy /api/printer endpoint).
Dropped by owner: z-height, speed, fans, elapsed. Tap-frame talks to PrusaLink directly over the LAN (digest auth on-watch):
the job's own embedded thumbnail — the picture of what's printing — then print name, state,
progress, remaining/elapsed, nozzle and bed temps with targets, z-height, speed, both fans,
refreshing every 5 s while open. Printer host + API key ride the gitignored config into
BuildConfig (private debug APK only; never in git).

**state:** awaiting its design pass. The owner's direction (2026-08-23): it will interact with
the health layer — capturing Fitbit Body Response flags and tagging them. Research, data-source
costs, and ranked options live in `HEALTH_INTEGRATION.md` (top level), written for the owner's
planning agent; the battery law is owner-authorized to bend for physiological collection if
unavoidable (number pending measurement). Printer face stays invisible while
idle (by design). State face currently shows the state code alone; tap opens the 2x4 tag grid
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
