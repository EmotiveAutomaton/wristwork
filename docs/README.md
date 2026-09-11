# Documentation map

- [../AGENTS.md](../AGENTS.md): current cross-agent operating guide and invariants.
- [../wristworkSpecs.md](../wristworkSpecs.md): living behavior and dated owner amendments;
  original runbook below its divider is historical.
- [STATE.md](STATE.md): dated implementation and verification records; use the newest entry.
- [ARCHITECTURE.md](ARCHITECTURE.md): component boundaries and where to find code.
- [ROADMAP.md](ROADMAP.md): ranked proposals and source-review findings, not authorization.
- [NEW-WEARER-SETUP.md](NEW-WEARER-SETUP.md): current pilot steps and the target
  handoff for another Pixel Watch wearer.
- [REUSABLE-EMOTION-APP.md](REUSABLE-EMOTION-APP.md): wristwork-emotion extraction and sync plan
  structure and extraction sequence; no fork has been created.
- [DECISIONS.md](DECISIONS.md): design rationale; early entries need their later amendments.
- [../HEALTH_DESIGN.md](../HEALTH_DESIGN.md): collection → prosthesis → scaffold, with
  later capture/UI changes in the living spec.
- [../wristwork-detector-design.md](../wristwork-detector-design.md): staged detector,
  permanent holdout and display gate, with later implementation amendments in the spec.
- [SERVER.md](SERVER.md): infrastructure notes; resolve real configuration locally.
- [VOICE-TO-PRINT.md](VOICE-TO-PRINT.md): historical feasibility and ownership rationale;
  current protocol behavior is in the living spec and `PrintLoop.kt`.

Keep private data, endpoints, device captures and operational reports outside the
tracked docs. Use `data/` for private local artifacts, preserving raw mirrors and
event records; keep derived reports separately identifiable and versioned.
