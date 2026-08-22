# tools/watch

- `connect.sh` (Phase 2) — finds the watch via adb mDNS (`_adb-tls-connect._tcp`) and connects.
  Pairing persists; only the connect port rotates. Detects "failed to authenticate" and says so.
  `--teardown` disables wireless debugging at session end.
- `install.sh` — `./gradlew :app:assembleDebug` then `adb install -r` to the connected watch.
