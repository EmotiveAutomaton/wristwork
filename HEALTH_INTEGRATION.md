# Health integration — context for the planning/analyst agent

Written 2026-08-23 by the build agent, for the owner's planning pass. Everything below the
"verified" markers was checked against fetched documentation or tested on the hardware this week;
items marked *unverified* are believed-true and need a probe before load-bearing use.
Read `wristworkSpecs.md` (living spec) and `docs/STATE.md` first for the system this bolts onto.

## Where the state system stands today (verified on hardware)

- One tap on the wrist → `{ts, state, noticed, note?, source:"manual"}` → offline-safe queue →
  ntfy bus on the NAS → append-only `labels.jsonl` → nightly mirror to the workstation.
  Round trip < 10 s. The grid is Panksepp's seven primaries + OTHER; "already noticed?" is the
  dependent variable. The archive currently holds two test rows and zero real labels.
- Nothing reads any physiological signal. No health permissions are held. The interpretation
  layer (Phase 6) is spec-gated behind ≥ 50 labels and remains unbuilt.
- Battery law as written: app < 3 %/day, no services/alarms/wake-locks. **The owner has ruled
  (2026-08-23) that physiological collection is the watch's primary purpose — the law bends for
  it if there is no way around the cost.** That is a spec change waiting for a number.

## The goal, in the owner's words

Fitbit's mental-wellbeing section flags **Body Responses** — possible stress moments detected
on-device from cEDA (continuous electrodermal activity) + HR + HRV + skin temperature. The owner
wants to "steal" those flagged events: capture them, tag them with our labels, and later do math
pairing physiology-flagged moments against self-reported states.

## The hard wall, verified

**Body Response events and all EDA data are not programmatically accessible. Anywhere.**
- No Health Connect datatype for EDA or stress events exists.
- Fitbit's Web API explicitly does not expose EDA/stress and it is
  [not on the roadmap](https://community.fitbit.com/t5/Web-API-Development/Is-there-any-web-api-available-for-EDA-and-Stress/td-p/5475259).
- The successor [Google Health API](https://developers.google.com/health/endpoints) (parity
  project for the Fitbit Web API) has no EDA/stress endpoints either.
- The cEDA sensor itself is not exposed to third-party apps on the watch (*unverified but
  near-certain; no public sensor type exists — worth one SensorManager enumeration probe*).
- EDA scan data explicitly [stays on the device](https://support.google.com/fitbit/answer/14237928).

So the flags cannot be pulled. They might, however, be *caught in flight* — see Option A.

## Data sources actually available, with costs

| Source | What it gives | How | Cost / constraint |
|---|---|---|---|
| **Fitbit body-response notification** | The flagged moment itself, as it happens | A `NotificationListenerService` **on the watch** catches the Fitbit prompt ("Body response detected…") and turns it into our own tag prompt + archive event | Event-driven ≈ zero battery. Needs notification-access permission (one settings toggle). Fragile to Fitbit wording/channel changes. *Unverified — needs a build-and-watch-one-fire test* |
| **[Health Services passive monitoring](https://developer.android.com/health-and-fitness/health-services)** (watch) | Continuous HR (batched), steps, calories; platform health events | `PassiveMonitoringClient`, batched off the MCU, delivered to our app in clumps | The one real battery cost. Google designed it MCU-batched specifically for low power; realistic low-single-digit %/day (*measure, don't trust*). Needs `BODY_SENSORS` + a passive listener — spec change |
| **Health Connect** (phone) | What Fitbit/Pixel sync writes: resting HR, **HRV (nightly RMSSD)**, respiratory rate, SpO2, **skin temperature** (nightly delta), sleep stages, exercise sessions | Phone-side reader (we have no phone app — could be a small companion or a scheduled reader on this workstation is NOT possible; Health Connect is on-device only) | Free battery-wise (data already collected), but **nightly/summary granularity** — useless for moment-flagging, good for baselines and context. Requires building our first phone-side component |
| **On-watch HR sensor, raw** | Moment-scale HR/HRV if we run our own detector | `MeasureClient` bursts or passive stream + our own anomaly detector | We own the algorithm (which is also the point: Fitbit's detector is theirs). Sustained sampling is the expensive mode; burst-on-schedule is cheaper |
| **Manual (exists today)** | The human, tapping | The 2×4 grid | Zero cost, already shipped; loses the "unusual event" trigger entirely |

## Options, ranked by the build agent

**A. Catch the Fitbit notification (recommended first move).** The Body Response alert fires on
the watch itself. A watch-side notification listener sees it, timestamps it, posts a
`source:"fitbit-body-response"` event to `tags`, and can light the state complication or pop the
grid for immediate labeling. This is literally the owner's ask — the flag, captured and tagged —
without touching sealed data. Risks: Google could reword/rechannel the notification (brittle
string-matching); the alert must actually be enabled and firing on this watch. One day of build,
one real body-response to verify.

**B. Passive HR/HRV collection + our own flagger (the durable path).** Collect the batched
passive stream into the same append-only discipline (`hr.jsonl` beside `labels.jsonl`), and later
build our own "notable moment" detector on it — replacing Fitbit's black box with an owned,
versioned one. This is the path that survives Google's whims and feeds Phase 6 math directly.
Cost: the battery number (measure a week), `BODY_SENSORS`, a passive listener service (spec
change, owner pre-authorized in principle).

**C. Health Connect nightly ingestion (context layer, later).** Baselines: nightly HRV, resting
HR, skin-temp delta, sleep. Not momentary. Needs our first phone-side piece; defer until A/B
exist and the analysis wants baselines.

**D. Do nothing automatic** — keep manual tagging and let the human be the detector. Already
works; it is what the "already noticed?" toggle was designed around, and A/B make it better
rather than replacing it.

**Recommended composite:** A now (it is the ask, and cheap), B started in parallel as
collection-only (no detector until the labels earn it, mirroring the Phase 6 gate), C later,
D forever.

## Open questions for the analyst

1. Does the body-response notification reliably surface in the watch's notification stream, and
   with what exact package/channel/text? (One instrumented listener + one stressful afternoon.)
2. What is the measured battery delta of passive HR on this watch? (One week A/B on the
   battery-attribution numbers; the acceptance law's new number falls out of this.)
3. Where do physiology windows live? Proposal: same bus, new topic (e.g. raw batched posts),
   NAS-archived like labels — keeps the immutability law uniform.
4. Does tagging *at* a flagged moment need a different UI than tagging spontaneously? (The
   "already noticed?" toggle was built for exactly this distinction — a fitbit-triggered tag
   arguably sets it by definition.)
5. Sample-rate/retention budget: passive HR batches arrive irregularly; what resolution does the
   Phase-6 math actually need?

## Sources

- [Fitbit community: no Web API for EDA/stress, not on roadmap](https://community.fitbit.com/t5/Web-API-Development/Is-there-any-web-api-available-for-EDA-and-Stress/td-p/5475259)
- [Google Health API endpoints](https://developers.google.com/health/endpoints) · [release notes](https://developers.google.com/health/release-notes)
- [Health Services on Wear OS](https://developer.android.com/health-and-fitness/health-services) (passive monitoring, MCU batching)
- [How Body Response / cEDA works](https://blog.google/products/fitbit/measure-stress-fitbit-google-pixel-watch/) · [9to5google on the cEDA sensor](https://9to5google.com/2023/06/02/pixel-watch-2-stress-tracking-sensor/)
- [Google: EDA data stays on device](https://support.google.com/fitbit/answer/14237928)
