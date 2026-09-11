# wristwork-emotion

`wristwork-emotion` is a private-by-design Wear OS instrument for recording a
wearer's affective state and nearby physiology. It provides an emotion complication,
an explicit-confirmation label grid, an offline queue, passive sensor collection,
optional blinded prompts, and a small authenticated [ntfy](https://ntfy.sh) data
path. It is a personal self-report and research tool, not a diagnostic device.

The canonical labels are SEEK, RAGE, FEAR, LUST, CARE, GRIEF, PLAY, and OTHER.
OTHER is displayed as Neutral. A label is written only after Confirm or ECG. Raw
events are append-only: corrections add revisions and removals add tombstones.

Start with [AGENTS.md](AGENTS.md) when using a coding agent and
[docs/SETUP.md](docs/SETUP.md) for the wearer setup. The app builds with JDK 21,
Android SDK 36, and the included Gradle wrapper:

```powershell
Copy-Item config.example.properties config.properties
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

The example configuration deliberately points nowhere. A real `config.properties`
is ignored and compiled into the APK, so treat configured APKs as private. Data,
tokens, device serials, and setup receipts do not belong in Git.

## What is included

```text
app/                  Wear OS emotion and health application
backend/              optional private ntfy service and append-only archiver
docs/SETUP.md          agent and wearer setup path
docs/DATA.md           data, provenance, and holdout contract
docs/UPSTREAM.md       automatic sync and fork model
setup/                 local configuration generator and read-only doctor
.agents/skills/        focused setup and data-review instructions
```

The canonical repository is generated from a reviewed allowlist in
[`EmotiveAutomaton/wristwork`](https://github.com/EmotiveAutomaton/wristwork).
An hourly workflow imports eligible changes from its `main` branch without copying
that repository's personal integrations or history. See [docs/UPSTREAM.md](docs/UPSTREAM.md).
