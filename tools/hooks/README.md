# tools/hooks

- `secret-grep.sh` — pre-commit + CI guard against secrets/topic names/hostnames in git. `install.sh` wires it up.
- `settings-fragment.json` (Phase 1) — Claude Code `Stop` → "done: {project}", `Notification` → "needs input",
  both curling topic `agents`. Merged into `~/.claude/settings.json` only on owner approval, original backed up.
