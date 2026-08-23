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

**Faces (v2, in flight — one-by-one design passes):**
- All numeric readings are integer percent of the machine's maximum, capped at 99.
- Age counters are OFF face-wide for now; reconsidered per complication in its pass. Staleness
  (>2 h) still marks itself: payload renders lowercased in parens. The face owns all styling
  (font/size/color); we control characters, icons, and complication type. Rich visuals live in
  tap-frames, which are fully ours.
- Every complication tap opens a single full-screen frame (back-swipe closes); one frame, no
  navigation tree.

**rig** (design pass 2026-08-23, iterating): three renderings, the slot decides —
SHORT_TEXT `c44g89` (7-char hard cap: cpu+gpu only; a title-line experiment for ram truncated
badly on the owner's face and was reverted), LONG_TEXT `c44-g89-r65` (full triple, hyphenated,
on slots that support it), RANGED_VALUE gauge (busiest resource's percent as an arc).
Tap-frame: three stacked 6 h percent graphs (cpu blue / gpu green / ram orange, ~5 min
resolution, read straight from the bus cache — nothing stored), then the top-5 processes with
aligned c/g/r columns (each process's own percent of the whole machine) under a header, extra
black below so the round screen clips nothing. Processes rank by their busiest resource, so a
GPU-bound job with idle CPU still surfaces. Feeder posts every 5 min flat:
`{"cpu":n,"ram":n,"gpu":n,"procs":[["name",c,g,r],...]}`; per-process GPU comes from Windows
GPU Engine counters (Task Manager's numbers — nvidia's own per-process view is blocked on WDDM);
bursty GPU is sampled twice, max kept. Known jitter: per-process CPU is a 1 s window and can
read low against the total.

**state / agents / printer:** awaiting their design passes. Printer face stays invisible while
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
