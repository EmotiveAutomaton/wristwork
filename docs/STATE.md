# STATE — where each phase stands

Updated 2026-08-22.

| Phase | Status | Notes |
|---|---|---|
| 0 recon + scaffold | done | Tooling verified. Scaffolded; APK builds as com.emotiveautomaton.wristwork. Pushed public: github.com/EmotiveAutomaton/wristwork. |
| 1 bus online | staged, one owner command from live | Key auth to the NAS works via the local ssh alias. Provision script staged in the NAS home dir; needs one sudo run by the owner (or the D12 sudoers line). Hooks fragment + notify helper written, merge pending owner approval. App rebuilt against the live LAN config. |
| 2 watch link | not started | Owner steps: Wi-Fi, developer options, wireless debugging pair code. |
| 3 health complication | code written, never installed | Compiled against the live server URL; cleartext HTTP enabled (D11). |
| 4 state complication | not started | |
| 5 channel complications + feeders | not started | |
| 6 interpretation | gated | build nothing |

Open design decisions are in [DECISIONS.md](DECISIONS.md).
