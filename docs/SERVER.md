# The server (sanitized)

wristwork's server side runs on a home NAS ("the RAID server" in the spec): an always-on Synology
box running Docker. It hosts, or will host:

| Role | What it is |
|---|---|
| ntfy | the message bus every topic rides on |
| label archive | a subscriber appending every `tags` message to `labels.jsonl` — the only irreplaceable bytes in the system |
| nightly mirror | cron copying `labels.jsonl` to the compute rig |
| printer poller | 60 s loop against the Prusa's PrusaLink API, posting transitions to topic `printer` |

Addresses, usernames, key paths, and volume layout are deliberately **not in this repository**
(public repo; the pre-commit hook enforces it). They live in the gitignored `config.properties`
and in a local operations document kept outside the repo. If you are an agent working here and
need them, ask the owner or read that local document; do not reconstruct them into git.
