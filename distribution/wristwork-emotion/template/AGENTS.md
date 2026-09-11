# wristwork-emotion agent guide

Read this file, [README.md](README.md), [docs/SETUP.md](docs/SETUP.md), and
[docs/DATA.md](docs/DATA.md) before changing or installing the app.

This repository is a single-wearer emotion self-report and physiology instrument.
Keep its measurement and privacy boundaries intact:

- Preserve raw event identities, event and entry timestamps, prompt provenance,
  and append-only archives. Corrections add revisions or tombstones.
- `random` labels are a permanent evaluation holdout. Never use their outcomes for
  training, tuning, threshold selection, prior revision, or model selection.
- Keep canonical order SEEK, RAGE, FEAR, LUST, CARE, GRIEF, PLAY, OTHER. OTHER is
  displayed as Neutral. Secondary-only labels are valid.
- Labels require explicit Confirm or ECG confirmation. Back and event switching
  discard unconfirmed edits.
- Swiping away a prompt retires that exact prompt without creating a label. Preserve
  blinded prompt text, truthful ages, and one-hour event protection.
- Keep credentials, configured APKs, device identifiers, health samples, archives,
  and private topic names outside Git.
- Preserve full-wear-day battery behavior: no foreground service, alarm, or wake lock
  workaround for faster updates.

Inspect `git status` before editing. Build with the Gradle wrapper and use synthetic
fixtures for prompt or archive checks. Installing with `adb install -r` preserves
data; never uninstall or clear app data as a setup shortcut. A build, installation,
on-watch interaction, and archived row are separate verification claims.

The canonical repository is generated. Shared app changes belong in the upstream
`wristwork` source and its export allowlist; repository-specific setup, backend, and
documentation live in that export template. The sync workflow runs only in the
canonical `EmotiveAutomaton/wristwork-emotion` repository. Personal forks receive
updates through the usual upstream-fork merge process.
