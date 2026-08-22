# Design decisions

Each entry: what was decided, why, what would change it. Made by the build agent unless marked owner.

## D1 — minSdk 36 (2026-08-22, proposed)
Pixel Watch 5 ships Wear OS 6 = Android 16 = API 36. Spec: "min SDK = this device only, no backcompat."
Reverts to 35 if the watch reports `ro.build.version.sdk` = 35 in Phase 2.

## D2 — config compiled into BuildConfig at build time (2026-08-22, proposed)
Server URL and topic names are read from gitignored `config.properties` and baked into the APK.
Alternative was an on-watch settings screen. Rejected for v1: the spec forbids UI beyond one grid and
four slots, and the ntfy.sh fallback in Phase 3 is "a config edit" — rebuild + reinstall is that edit.
Cost: changing a topic means a rebuild. Acceptable for one device, one owner.

## D3 — local event cache is Room, not DataStore (2026-08-22, proposed)
Offline tags need an ordered queue with per-row acknowledgement on successful POST. Room gives that
directly; DataStore would mean hand-rolling a list. DataStore is used only for small scalars
(last-seen message id per topic, current state + since-timestamp for the face).

## D4 — channel polling on the complication update cycle, no WorkManager periodic jobs (2026-08-22, proposed)
The platform already wakes the data source on its ~15-minute schedule (`UPDATE_PERIOD_SECONDS`).
Polling inside that callback adds no scheduler of our own and nothing that could become a wake lock.
WorkManager is a dependency in the build for one purpose: a one-shot, network-constrained job to replay
the offline tag queue when connectivity returns. If that proves unnecessary it comes out.

## D5 — one generic channel service, three manifest entries (spec) — via subclasses
Android needs a distinct `<service>` class per data source, so the generic provider is an abstract
base and `AgentsComplicationService` / `RigComplicationService` / `PrinterComplicationService` are
three-line subclasses supplying topic + formatter.

## D6 — package `com.emotiveautomaton.telltale`, repo folder `wristwork`
The spec names the project telltale; the folder was pre-named. Repo on GitHub will be `telltale`
unless the owner says otherwise.

## D7 — no launcher activity (2026-08-22, proposed)
The app exposes complications only. The tag grid (Phase 4) is an activity launched from the
complication tap action, not from the app drawer. Keeps the non-goal "any UI beyond one grid and four slots."
