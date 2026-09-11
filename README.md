# wristwork

A personal, single-user integration layer between a Pixel Watch 5 and the things I want on my wrist.
Its first job is **emotional research and tracking** — a watch complication that tags my current
affective state (Panksepp's seven primaries plus canonical OTHER, now displayed as Neutral) to an append-only log, feeding the
same line of work as my other projects. Its second, standing job is to be **the place any of my apps
or machines can push a glanceable signal to the watch** — agent notifications, the compute rig's
load, the 3D printer's progress — over a lightweight [ntfy](https://ntfy.sh) message bus.

Not a product. No accounts, no Play Store, no companion app. Just my wrist, my server, and whatever
I decide to wire into it next.

Start here: [`AGENTS.md`](AGENTS.md) for agents, [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
for the system map, and [`docs/ROADMAP.md`](docs/ROADMAP.md) for proposed next work.
Spec: [`wristworkSpecs.md`](wristworkSpecs.md). Status: [`docs/STATE.md`](docs/STATE.md).
Full documentation index: [`docs/README.md`](docs/README.md).
Reusable emotion distribution:
[`EmotiveAutomaton/wristwork-emotion`](https://github.com/EmotiveAutomaton/wristwork-emotion).
Its handoff and synchronization design are recorded in
[`docs/NEW-WEARER-SETUP.md`](docs/NEW-WEARER-SETUP.md) and
[`docs/REUSABLE-EMOTION-APP.md`](docs/REUSABLE-EMOTION-APP.md).

## Layout

```
app/                 Wear OS app (Kotlin, Compose for Wear OS, complication data sources). minSdk 36.
tools/watch/         adb pairing/connect helpers (mDNS discovery, no address hunting)
tools/server/        Synology/Docker: ntfy container, labels.jsonl subscriber, nightly mirror
tools/rig/           Windows stats, prompt allocation, detector, health ingestion, mirrors and checks
tools/printer/       PrusaLink poller -> topic "printer"
tools/hooks/         secrets pre-commit guard; Claude Code notification hook fragment
docs/                current status, architecture, decisions, roadmap and infrastructure notes
.agents/skills/      focused workflows for watch changes, pipeline checks and research review
```

## Build

PowerShell on the workstation (JDK 21, Android SDK, Gradle wrapper):

```powershell
# Only on a fresh checkout without local configuration:
if (!(Test-Path config.properties)) { Copy-Item config.example.properties config.properties }
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Git Bash/Linux can use
`./gradlew :app:assembleDebug`; `bash tools/hooks/install.sh` installs the existing
pre-commit secrets guard. CI builds the APK and scans tracked files for secrets.

Config values (server URL, topic names) are read from the gitignored `config.properties` and compiled
into `BuildConfig`. Without one, the example placeholders are used — CI builds, but the app points at
nothing real. A locally configured APK contains private credentials and is not a public artifact.

## Laws

- Raw label data is immutable. Derived artifacts are versioned and recomputable.
- No secrets, topic names, or server hostnames in git history. The pre-commit hook enforces it.
- The app must comfortably survive a full wear-day: no foreground services, no alarms, no wake locks.
- Stale never renders as fresh — every channel signal shows its age.
- **The random stream is a permanent holdout.** Labels whose `source` is `random` are used for
  evaluation only — never for training, tuning, or threshold selection, ever. Every claim the
  detector makes about itself (lift over chance, classifier validation, the gate that lets it show
  a state on the wrist) is scored exclusively against them. Labels from the `signal`, `self` and
  `google` sources are trainable. One field carries the whole integrity of the system, so it is
  written at the moment of capture and never inferred afterwards.

  Historical note for anyone reading the archive: labels before 2026-08-26 use the earlier
  vocabulary and normalise as `manual` -> `self`, `timeline-retro` -> `self`, `fitbit-flag` ->
  `google`, `model-alert` -> `signal`. History is not rewritten; the mapping is applied at
  analysis time.

## Layout note for the detector

`wristwork-detector-design.md` is the design authority for the state detector. The allocator that
decides when to ask lives on the rig (`tools/rig/prompts.ps1`, daily) and posts prompts to the bus
ahead of time; the watch fires them when their moment comes. Nothing about a detected state is
ever shown on the wrist until it beats the time-of-day baseline on random-stream labels.
