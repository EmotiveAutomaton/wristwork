# wristwork

A personal, single-user integration layer between a Pixel Watch 5 and the things I want on my wrist.
Its first job is **emotional research and tracking** — a watch complication that tags my current
affective state (Panksepp's seven primaries plus an open "OTHER") to an append-only log, feeding the
same line of work as my other projects. Its second, standing job is to be **the place any of my apps
or machines can push a glanceable signal to the watch** — agent notifications, the compute rig's
load, the 3D printer's progress — over a lightweight [ntfy](https://ntfy.sh) message bus.

Not a product. No accounts, no Play Store, no companion app. Just my wrist, my server, and whatever
I decide to wire into it next.

Spec: [`wristworkSpecs.md`](wristworkSpecs.md). Status: [`docs/STATE.md`](docs/STATE.md).
Server it talks to: [`docs/SERVER.md`](docs/SERVER.md).

## Layout

```
app/                 Wear OS app (Kotlin, Compose for Wear OS, complication data sources). minSdk 36.
tools/watch/         adb pairing/connect helpers (mDNS discovery, no address hunting)
tools/server/        Synology/Docker: ntfy container, labels.jsonl subscriber, nightly mirror
tools/rig/           compute box: stats timer -> topic "rig"
tools/printer/       PrusaLink poller -> topic "printer"
tools/hooks/         secrets pre-commit guard; Claude Code notification hook fragment
docs/                STATE.md, DECISIONS.md, SERVER.md
```

## Build

```
cp config.example.properties config.properties   # fill in; gitignored
./gradlew :app:assembleDebug                       # APK at app/build/outputs/apk/debug/
bash tools/hooks/install.sh                        # pre-commit secrets guard
```

Config values (server URL, topic names) are read from the gitignored `config.properties` and compiled
into `BuildConfig`. Without one, the example placeholders are used — CI builds, but the app points at
nothing real.

## Laws

- Raw label data is immutable. Derived artifacts are versioned and recomputable.
- No secrets, topic names, or server hostnames in git history. The pre-commit hook enforces it.
- Battery attributable to the app < 3%/day: no foreground services, no alarms, no wake locks.
- Stale never renders as fresh — every channel signal shows its age.
