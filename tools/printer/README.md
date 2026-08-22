# tools/printer — PrusaLink poller (Phase 5), runs on the RAID server

Polls `http://{printer}/api/v1/status` every 60 s (HTTP digest, user `maker`, password = API key).
Posts to topic `printer` on state transitions and each 5% progress step; posts `idle` once on FINISHED/idle.
Prusa Connect cloud is never used.
