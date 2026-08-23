# STATE — where each phase stands

Updated 2026-08-23.

| Phase | Status | Notes |
|---|---|---|
| 0 recon + scaffold | done | Tooling verified. Scaffolded; APK builds as com.emotiveautomaton.wristwork. Pushed public: github.com/EmotiveAutomaton/wristwork. |
| 1 bus online | done (mirror deferred) | ntfy + label archiver live on the NAS as restart-always containers; round trip publish→labels.jsonl verified <3 s. Claude Code Stop/Notification hooks installed and test-fired. Nightly mirror waits on the rig address. |
| 2 watch link | done | Paired + connected over Wi-Fi adb (second attempt; pairing dialog must stay open). connect.sh verified against the live device. Watch reports API 37. |
| 3 health complication | installed, awaiting slot | On the watch, service registered, watch→NAS ping 7–22 ms. Render pending owner placing it on the face. |
| 4 state complication | built + verified on device | Grid renders (2×4, toggle, mic), tap→Room→DataStore→POST→labels.jsonl round trip verified <10 s via adb-driven tap. Offline replay path (WorkManager network constraint) UNVERIFIED — will be exercised naturally when first tagged away from Wi-Fi. State complication awaiting a face slot. |
| 5 channel complications + feeders | done | All four complications slotted 2026-08-23. Fixed on-device crash (DataStore delegate scope) that blanked rig/printer. Stats task live (5 min, exit 0). Printer poller container live on the NAS, digest auth verified, posts `idle`. Printer complication invisible when idle BY DESIGN. |
| 6 interpretation | gated | build nothing |

Open design decisions are in [DECISIONS.md](DECISIONS.md).

## Known test artifacts in the label archive (append-only; annotate, never delete)
- `2026-08-22T12:03:00-07:00` state SEEK, `source:"provision-test"` — Phase 1 provisioning check.
- `2026-08-22T12:54:23.856046-07:00` state OTHER, `source:"manual"` — Phase 4 automated adb tap test (not a real label).
