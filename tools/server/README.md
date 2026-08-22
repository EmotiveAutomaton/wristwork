# tools/server — RAID server (Phase 1)

Provisioned over SSH. Contents, once written:
- `provision.sh` — idempotent: checks Tailscale, runs `binwiederhier/ntfy` (cache file mounted,
  restart-always), installs the subscriber unit, installs the nightly mirror cron.
- `ntfy-subscribe-tags.service` — systemd unit: subscribes topic `tags`, appends JSON lines to `labels.jsonl`.
- `mirror-labels.sh` — nightly rsync of `labels.jsonl` to the rig. Raw labels are immutable; this is the only
  irreplaceable file in the system.
