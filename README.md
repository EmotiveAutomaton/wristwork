# telltale

Wear OS complications for a Pixel Watch 5 riding an [ntfy](https://ntfy.sh) bus, plus the server-side
plumbing behind them. One complication tags the wearer's current affective state (Panksepp's seven
primaries plus OTHER) to an append-only label log; three more show what the agents, the compute rig,
and the 3D printer are doing, with age always visible.

Spec: [`wristworkSpecs.md`](wristworkSpecs.md). Status: [`docs/STATE.md`](docs/STATE.md).

## Layout

```
app/                 Wear OS app (Kotlin, Compose for Wear OS, complication data sources). minSdk 36.
tools/watch/         adb pairing/connect helpers (mDNS discovery, no address hunting)
tools/server/        RAID server: ntfy container, labels.jsonl subscriber unit, nightly mirror
tools/rig/           compute box: stats timer -> topic rig
tools/printer/       PrusaLink poller -> topic printer
tools/hooks/         secrets pre-commit guard; Claude Code notification hook fragment
docs/                STATE.md, DECISIONS.md
```

## Build

```
cp config.example.properties config.properties   # fill in; gitignored
./gradlew :app:assembleDebug                       # APK at app/build/outputs/apk/debug/
bash tools/hooks/install.sh                        # pre-commit secrets guard
```

Config values are compiled into `BuildConfig`; without a `config.properties` the example placeholders
are used, so CI builds but the app points at nothing.

## Laws

- Raw label data is immutable. Derived artifacts are versioned and recomputable.
- No secrets, topic names, or tailnet hostnames in git history. The hook enforces it.
- Battery attributable to the app < 3%/day: no foreground services, no alarms, no wake locks.
- Stale never renders as fresh.
