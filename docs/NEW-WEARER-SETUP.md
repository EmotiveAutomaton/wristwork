# New wearer setup

Status: reusable repository available, 2026-09-11. Another wearer should fork
[`EmotiveAutomaton/wristwork-emotion`](https://github.com/EmotiveAutomaton/wristwork-emotion)
and follow its `docs/SETUP.md`. This file records the handoff boundary and acceptance
criteria from the personal wristwork side.

## What another wearer receives

The reusable app should provide the state complication, explicit-confirmation grid,
six-hour timeline, append-only offline queue, passive watch data collection, and
optional blinded prompts. Printer controls, workstation graphs, agent notifications,
Fetch, the original wearer's endpoints, and the original wearer's data are outside
that package.

This is personal affective and physiological data. The new wearer owns their bus,
credentials, archive, prompt budget, and deletion/retention decisions. Never copy
the original wearer's `config.properties`, APK, signing material, local `data/`,
server archive, OAuth credentials, topic names, or screenshots as starter material.

## A request the wearer can give an agent

Once the wristwork-emotion repository exists, this is enough context to start:

> Set up wristwork-emotion for my Pixel Watch 5. Read AGENTS.md and
> docs/SETUP.md first. Use my own private configuration and archive;
> do not ask for or reuse the original wearer's endpoints, tokens, data, OAuth
> client, prompt schedule, or APK. Do everything possible from the terminal, then
> give me one checklist for the watch menus and verify the installation and data
> path yourself. Do not publish test emotion labels; let me enter one real label.

The agent should first establish which repository/version it has and whether the
backend is already available. A fork or clone is code, not a deployed private data
service.

## Prerequisites

- Pixel Watch 5 on the supported Wear OS/API level, on the same Wi-Fi as the setup
  computer for wireless adb pairing.
- Android Studio or the Android SDK with platform 36+, platform-tools, JDK 21, and
  Git. Use the repository's Gradle wrapper.
- A private ntfy endpoint plus persistent append-only storage. For routine use away
  from home, it needs authenticated HTTPS reachability. A LAN-only endpoint is fine
  for a short home pilot but will go stale away from that network.
- A Windows/macOS/Linux machine that can run the optional prompt allocator and keep
  a private mirror. Google Health account ingestion is optional and should be set up
  with the new wearer's own consent and OAuth project only if they want it.

The current server scripts assume a Synology NAS and replace named wristwork
containers. They are reference implementations, not a portable installer. Do not
run them on another person's server until the state-only backend has been adapted
and its exact targets reviewed.

## Personal-source manual path

These steps describe the full personal wristwork repository and remain useful to its
maintainer. A new wearer should use wristwork-emotion's `setup` and `doctor` commands.

1. Fork or clone the code into the new wearer's account/computer. Confirm that
   `git status` contains no configuration, data, APK, key, or capture files.
2. In wristwork-emotion, run its config generator with the new wearer's endpoint and
   token. It creates ignored app/backend files and unique topic names. The emotion
   complication owns prompt scheduling; no personal channel complication is needed.
3. Build the debug APK:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
   ```

4. On the watch, enable Developer options by tapping Build number seven times,
   then enable ADB debugging and Wireless debugging. Keep “Pair new device” open
   while the agent runs `adb pair`; the pairing port and connection port differ.
5. Have the agent connect to the exact watch serial and install with `adb -s SERIAL
   install -r ...`. Installing with `-r` preserves existing app data. Never clear
   app data or uninstall as a setup shortcut once real labels have been entered.
6. Grant notifications, heart rate, background health data, activity recognition,
   and skin temperature when the platform offers them. Notification-listener access
   for Fitbit flags is a separate watch Settings toggle. The reusable build has a
   launcher entry and its setup guide includes targeted adb grants where needed.
7. Add the state complication to the watch face. Add the health complication during
   setup if retained in the reusable app; its refresh currently helps register
   passive collection.
8. Let the wearer enter and confirm one genuine state. The agent verifies the
   installed package, the local queue/drain result, and a new archive timestamp
   without printing the label contents. A build, an installed package, and an
   archived row are three separate checks.

## Behavior the new wearer should understand

- Tapping a state makes it secondary; holding it makes it primary. Secondary-only
  answers are valid. Nothing is recorded until Confirm or ECG is pressed.
- Back and switching timeline events discard unconfirmed edits. Corrections append
  a revision; clearing a saved personal event appends a tombstone.
- Swiping away a state question means “skip this one.” It creates no label and the
  face returns from NEW to the last recorded state. An unanswered marker also retires
  after it leaves the six-hour timeline.
- Random prompts form the permanent evaluation set. Their labels must never train
  or tune the model. Prompt volume and waking hours belong to the new wearer; the
  original wearer's values are not defaults merely because they are in history.
- The canonical eight-state vocabulary and Neutral display semantics are part of
  this instrument. A wearer who wants another vocabulary is defining another
  measurement system; record that decision before collecting data rather than
  silently relabeling old rows.

## Setup acceptance

A handoff is complete only when the agent has observed all applicable checks:

- the intended state-only variant built from a clean checkout;
- the exact watch is connected and the package is installed without wiping data;
- the state complication opens the grid and confirmed input updates the face;
- an intentionally dismissed prompt clears NEW without creating a label;
- a genuine wearer-entered label reaches the new wearer's archive once logically;
- passive data either reaches its archive or is explicitly disabled;
- offline queue replay, full-wear-day battery behavior, and remote reachability are
  reported as verified or still unverified, never assumed from configuration.

Keep a private setup receipt containing versions, package ID, enabled capabilities,
and verification dates. Do not put endpoints, topic names, serials, health samples,
or credentials in the public repository.
