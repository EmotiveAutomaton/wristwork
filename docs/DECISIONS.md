# Design decisions

Each entry: what was decided, why, what would change it. Made by the build agent unless marked owner.

## D1 — minSdk 36 (2026-08-22, proposed)
Pixel Watch 5 ships Wear OS 6 = Android 16 = API 36. Spec: "min SDK = this device only, no backcompat."
Reverts to 35 if the watch reports `ro.build.version.sdk` = 35 in Phase 2.

## D2 — config compiled into BuildConfig at build time (2026-08-22, proposed)
Server URL and topic names are read from gitignored `config.properties` and baked into the APK.
Alternative was an on-watch settings screen. Rejected for v1: the spec forbids UI beyond one grid and
four slots, and the ntfy.sh fallback in Phase 3 is "a config edit" — rebuild + reinstall is that edit.
Cost: changing a topic means a rebuild. Acceptable for one device, one owner.

## D3 — local event cache is Room, not DataStore (2026-08-22, proposed)
Offline tags need an ordered queue with per-row acknowledgement on successful POST. Room gives that
directly; DataStore would mean hand-rolling a list. DataStore is used only for small scalars
(last-seen message id per topic, current state + since-timestamp for the face).

## D4 — channel polling on the complication update cycle, no WorkManager periodic jobs (2026-08-22, proposed)
The platform already wakes the data source on its ~15-minute schedule (`UPDATE_PERIOD_SECONDS`).
Polling inside that callback adds no scheduler of our own and nothing that could become a wake lock.
WorkManager is a dependency in the build for one purpose: a one-shot, network-constrained job to replay
the offline tag queue when connectivity returns. If that proves unnecessary it comes out.

## D5 — one generic channel service, three manifest entries (spec) — via subclasses
Android needs a distinct `<service>` class per data source, so the generic provider is an abstract
base and `AgentsComplicationService` / `RigComplicationService` / `PrinterComplicationService` are
three-line subclasses supplying topic + formatter.

## D6 — project name is `wristwork` (owner decision, 2026-08-22)
The spec's runbook called it "telltale"; the owner chose the folder name **wristwork** as the durable
name ("something I can always come back to"). Package `com.emotiveautomaton.wristwork`, public GitHub
repo `wristwork` under EmotiveAutomaton. "telltale" survives only inside `wristworkSpecs.md`.

## D7 — no launcher activity (2026-08-22, proposed)
The app exposes complications only. The tag grid (Phase 4) is an activity launched from the
complication tap action, not from the app drawer. Keeps the non-goal "any UI beyond one grid and four slots."

## D8 — LAN-first, no Tailscale anywhere yet (2026-08-22)
Verified: no Tailscale on the laptop, no Tailscale package on the NAS. The watch lives on home
Wi-Fi, so v1 talks straight to the NAS over the LAN; away-from-home tags queue offline and replay
(already a spec requirement). The spec's tailnet assumption is an upgrade path, not a prerequisite;
the ntfy.sh fallback (Phase 3) remains the escape hatch if LAN reachability disappoints.

## D9 — label subscriber is a restart-always container, not a systemd unit (2026-08-22)
Deviation from the spec's wording ("install a systemd unit"). On Synology DSM, hand-installed
systemd units do not reliably survive DSM updates; the Docker daemon's restart policy does, and it
is the box's native idiom (everything else on it runs that way). Same guarantee, sturdier host fit.
The nightly mirror is deferred until the rig's address exists; interim risk is bounded because the
label file sits on redundant RAID storage.

## D10 — ntfy published on host port 8093 (2026-08-22)
80/443/5000/5001/8080/9000 are taken by DSM and existing tenants; 8093 was free and is now the
port baked into the app config. Changing it is a config edit + reprovision, not code.

## D11 — cleartext HTTP allowed app-wide (2026-08-22)
`usesCleartextTraffic="true"`: the server is plain http on a private LAN in v1. Scoping it to a
network-security-config domain list would hardcode the IP into a committed XML resource — worse for
the no-hostnames-in-git law than allowing cleartext globally in a single-purpose personal app.
Revisit if the server ever gets TLS via DSM reverse proxy.

## D12 — passwordless docker via a scoped sudoers line (proposed, awaiting owner)
Future sessions need `docker` on the NAS without a password prompt. Proposal: a one-line
`/etc/sudoers.d/` entry limited to the docker binary for the owner's user. Until it exists, any
docker-touching step needs the owner to run one command themselves.

## D13 — the "rig" is this workstation (2026-08-22)
The spec assumed a separate always-on compute box. None exists; the machine matching the rig's
description (GPU, local models, runs the research projects) is the owner's Windows workstation.
Consequences: the nightly `labels.jsonl` mirror pulls TO this machine (`tools/rig/mirror_labels.ps1`,
Task Scheduler entry "wristwork-labels-mirror", 03:30); it misses nights the machine is off, which
is acceptable because the archive is append-only and every pull is a full catch-up. The Phase 5
stats feeder will publish from this machine on the same reasoning.
