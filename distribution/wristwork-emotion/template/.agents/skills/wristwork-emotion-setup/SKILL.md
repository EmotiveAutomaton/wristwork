---
name: wristwork-emotion-setup
description: Set up or diagnose wristwork-emotion for a new Wear OS wearer, including private ntfy configuration, build, wireless adb installation, permissions, and read-only pipeline checks.
---

# Set up wristwork-emotion

Read [AGENTS.md](../../../AGENTS.md) and the complete
[setup guide](../../../docs/SETUP.md). Establish whether the wearer will use an
existing private ntfy service or the bundled backend before creating configuration.

Use only that wearer's endpoint, token, topics, archive, device, and consent choices.
Never copy another wearer's config, configured APK, data, topic names, prompt schedule,
OAuth material, or setup receipt. Keep generated `config.properties`, `backend/.env`,
archives, and device identifiers outside Git.

Run the config generator, targeted tests, and APK build. Pair or connect only the
intended watch serial. Install with `adb -s SERIAL install -r`; never uninstall or
clear application data to recover setup. Bundle unavoidable watch-menu work into one
checklist and verify each permission or setting from the device where possible.

Run `setup/doctor.ps1` for read-only endpoint, topic, adb, and package checks. It does
not prove capture or archive arrival. Let the wearer enter one genuine verification
label, then verify its arrival without echoing its emotional content. Do not publish
a synthetic label or prompt into their permanent stream. Report build, install,
on-watch interaction, archive arrival, passive collection, offline replay, remote
reachability, and battery evidence separately.
