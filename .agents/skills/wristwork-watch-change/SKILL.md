---
name: wristwork-watch-change
description: Change or verify wristwork Wear OS screens, complications, capture, prompt delivery, or the watch half of voice-to-print. Use for app work and authorized device verification.
---

# Wristwork watch changes

Read [AGENTS.md](../../../AGENTS.md), the latest
[STATE](../../../docs/STATE.md), and the relevant dated amendment in the
[living spec](../../../wristworkSpecs.md). Paths here are relative to this skill
at `.agents/skills/wristwork-watch-change/`; run commands from the repo root.

Use [the architecture map](../../../docs/ARCHITECTURE.md) to trace the changed
behavior through the screen, data source, worker, and transport. Existing activity
comments can describe an older interaction: compare actual callbacks with the
latest owner decision. Read both sides before changing a shared bus payload.

Keep these non-obvious invariants in the affected path:

- A grid exit does not save. Confirm or ECG confirms; secondary-only labels remain
  valid; revisions and deletions append. Preserve event/entry times and prompt source
  through both notification and face entry. Do not destructively migrate Room data.
- Prompt source is blinded on the wrist. Preserve one-hour protection, the random
  deferral policy, and signal expiry. A timing change changes the instrument;
  consult the research workflow and current owner decisions before implementing it.
- Cached channel values retain their real age. A waiting prompt/offer is not a
  fresh measurement. Surface failed or incomplete transport responses honestly.
- Bus authorization must not propagate to printer or third-party image requests.
  Database-backed upload retries must not strand every later row behind one batch.
- Print tap inspects locally; long press sends a choice that can start the printer.
  Inspect the current Fetch protocol before changing choices, expiry or interlocks.
  Tests use synthetic offers and fake transports; no real choice/confirm publishes.
- Keep round-screen content within useful margins, and preserve full wear-day
  acceptance and the existing low-power refresh approach.

Build with `.\gradlew.bat :app:assembleDebug`. The local config is compiled into
the APK: keep it private. Add targeted behavioral tests when changing persistence,
prompt decisions, or print actions; a successful build alone does not exercise them.
No device install is needed for documentation or an isolated local-code task.

When device installation/verification is within the authorized task, read
[tools/watch/README.md](../../../tools/watch/README.md) and its helpers first.
Verify the selected device; do not disconnect unrelated adb devices (the connection
helper has duplicate cleanup behavior). Use the existing pairing and SDK. Prefer a
targeted `adb -s SERIAL install -r` after building when multiple devices exist.
Never uninstall or clear app data to get a clean test: queued personal records live
there. Ask only for physical menu/pairing actions that are actually unavailable.

Verify the installed package and relevant device behavior separately. For capture
tests, avoid synthetic labels in the permanent archive; use isolated fixtures or
observe a genuine owner-entered event. Report build, installation, interaction,
archive arrival, and battery evidence as distinct claims. Update STATE and the
living spec for changes, including what could not be observed.
