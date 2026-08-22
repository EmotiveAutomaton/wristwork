# tools/rig — compute box (Phase 5)

- `stats.sh` + systemd timer (5 min): CPU/RAM/GPU/load JSON → topic `rig`, sent on threshold change or every 15 min.
- Receives the nightly `labels.jsonl` mirror. Phase 6 (interpretation layer) lives here later; not built now.
