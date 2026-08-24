# Health component — design record (owner meeting, 2026-08-24)

Output of the owner's design meeting (Cowork session, 2026-08-24). This file is the current
design authority for the health/state work. Precedence:

- **Absorbed** `HEALTH_INTEGRATION.md` (2026-08-24): its options ranking is superseded by the
  capture architecture below; its verified facts (sealed GSR, grantable skin-temp permission,
  API absences, data-source cost table) live on in the appendix at the bottom of this file.
- **Buries** `telltalehealthcontext.md` (the claude.ai carry-over, old project name "telltale").
  Owner ruling: early planning, to be burned down. Mine it for theory vocabulary only; none of its
  architecture binds. Its "owner is the bridge / don't intercept" v1 stance, its 75-minute
  staleness rule, and its no-retro-labeling hygiene are **explicitly overturned** below.
- `wristworkSpecs.md` stays the living spec: fold this file into it in the same pass that builds it.

## The program, as the owner frames it (phased)

**H1 — Collection.** "Accurate and competent data collection is our first bet." Capture everything
cheaply capturable, redundantly, into the append-only discipline. Expect the personal data to be
messy; prefer more data over less.

**H2 — Prosthesis.** Build the personal state model and prove, by error rate, that a wrist-delivered
best-guess-with-confidence is possible. Bar: an error rate the owner finds subjectively
satisfying; floor: beats a time-of-day-only baseline (kept from the spec). Existing gates hold:
tier-2 label subdivision at ≥50 labels, structure audit at ≥100, no model code before the counts
say so.

**H3 — Scaffold (long-term).** Only after H2 proves out: tune sensitivity and alerting to train
the owner's own interoceptive accuracy. Not designed now; recorded so H1/H2 don't foreclose it.
The order is the owner's correction of both prior planning passes: prosthesis first — you cannot
scaffold with a model that doesn't exist.

## Capture architecture — parallel by design

Owner ruling: "commit, and also connect to Fitbit as well as possible." Two families run side by
side; neither is the fallback for the other.

### 1. Fitbit-derived (their model, our archive)

- **Watch-side `NotificationListenerService`** catching Body Response pushes → flag events into a
  new `flags` stream. This is capture *and* instrument: pushes are known-intermittent on this unit
  (owner has observed both firings and silent misses; cause unknown). Listener log vs the Fitbit
  app's in-app Body Responses timeline = the measured push miss rate. `OWNER STEP` (as curious,
  not scheduled): eyeball the in-app timeline against our flag log occasionally; no API exposes it.
- **Canary, law-grade:** archive a daily count of all Fitbit-package notifications seen; zero
  across N consecutive days (default 4) posts an alert to `agents`. String-matching breaks
  silently; silence must never read as calm.
- **Cue:** on a caught flag, our own notification fires after a delay (default 30 min, config
  knob); tapping opens the grid pre-linked to the flag. Fitbit's own push already covered the
  moment itself.
- **Later (H1.5): Health Connect nightly ingestion** via the first phone-side reader — nightly HRV
  (RMSSD), resting HR, skin-temp delta, sleep, SpO2. Baselines and covariates.
- **Analysis stance:** Fitbit flags are *weak supervision* for the H2 model — a distillation prior
  from a large general model (owner's argument: trained on massive data, it will beat a cold-start
  personal model early, and the personal data will be messy). They are not ground truth: they
  inherit Fitbit's false negatives and carry no valence. Owner labels are ground truth.

### 2. Owned collection (our stream, survives Google's whims)

- Health Services **passive HR** (MCU-batched) + **skin-temperature** reads into a new raw stream,
  same bus → NAS archiver → jsonl pattern as labels.
- Collection starts in H1; **the detector it feeds is H2-gated.** Every week not collecting is
  training data lost forever — that asymmetry is why collection starts now.
- **HRV caveat — settled (probe run 2026-08-24):** no public beat-interval sensor exists on this
  watch; raw PPG and the ECG hardware are sealed behind the same Pixel-Watch-private permission
  as GSR. RMSSD-grade HRV is not derivable from what we can touch. Model features are HR +
  skin temp + BPM-variability approximations; nightly RMSSD arrives later via Health Connect
  (H1.5) as a baseline covariate only.

## The label object

One event schema across all sources:

    { ts_event,          // when the state was/is — what the label is ABOUT
      ts_entered,        // when the human entered it (latency = entered − event; analysis weights it)
      source,            // manual | fitbit-flag | timeline-retro | model-alert (H2+)
      primary,           // one of the eight, canonical Panksepp name
      secondaries: [],   // mixtures are first-class ("more decision data to be harvested")
      intensity?,        // optional slider value, if built
      noticed_before?,   // see open items
      note?,             // mic transcript, optional
      flag_ref? }        // the flag event that cued this, if any

- **Retro-labeling is a requirement, not a concession.** The owner has lived events worth labeling
  hours later. Any event is reopenable at any time; both timestamps are recorded; immediacy
  weighting happens in analysis, never as a UI ban.
- Canonical Panksepp vocabulary in the data, forever (the CLAUDE.md rule stands). Display layer below.

## State complication — UX contract (this is its design pass)

**Face (until H2 ships):** humane display name of the most recent label + auto-ticking time since
entered ("to remind me to keep up with it"). The face-wide age-counter ban is lifted for this
complication. No inference and no bar until a model exists; when H2 ships, the same slot gains the
model's best guess + a confidence bar (same visual contract, upgraded semantics).

**Display names — humane and deniable.** Canonical in data; on the face, words a stranger can read
without a raised eyebrow. Proposed transformations — owner strikes/replaces freely:

| Canonical | Face shows | Alternate |
|---|---|---|
| SEEK | Drive | Flow |
| RAGE | Vexed | Grate |
| FEAR | Tense | Fog |
| LUST | Heat | Spark |
| CARE | Warm | Soft |
| GRIEF | Heavy | Rain |
| PLAY | Light | Loose |
| OTHER | Odd | Else |

Grid buttons show the humane word with the canonical small beneath (or the inverse — build agent
picks for on-device legibility).

**Tap-frame:**

- **Timeline strip on top:** one unified event stream (flags, tags, later model alerts), the last
  2–3 events visible, tap to select; selecting opens that event for (re)labeling.
- **Long-press on the timeline creates a new event** at the release position (a retro event at an
  arbitrary time). Backing out without entering anything discards it — that is the whole undo.
- **Grid:** rows of three (3/3/2), buttons compacted from today's layout. Single tap = fast path
  (primary only, submit, auto-close — the two-tap discipline survives). Long-press (or double-tap;
  build agent picks the less error-prone on hardware) sets the **dominant**; subsequent taps
  toggle **secondaries**; confirm to submit.
- **Intensity slider:** optional elaborate-mode input; owner ambivalent. Build if cheap, skip if it
  crowds the frame.
- **Mic note at the very bottom.** Kept; purpose intentionally open.

## Laws changed and kept

- **Battery law replaced.** The <3%/day figure is disowned by the owner ("a ghost of a
  requirement" that got a name and stuck). New acceptance: **the watch must comfortably survive a
  full wear-day on one charge** (owner charges daily). Listener + passive collection are
  authorized; measure attribution during the H1 ramp and report the number — measured, not
  trusted. No gratuitous wake locks; platform-bound services (notification listener, passive
  callbacks) are fine.
- **Unchanged:** raw-data immutability (all streams append-only; derived artifacts versioned and
  recomputable); no secrets/topics/hostnames in git; staleness never renders as freshness — and,
  per the canary, *silence* never reads as calm; H2 gates as above; do not pre-subdivide SEEK.

## Open items (owner strikes or answers whenever; none block the build)

1. **`noticed_before`** — kept as a collected field by default, now defined plainly: *had you
   caught this state yourself before the cue/tagging moment?* One tap of cost, and H3 wants the
   longitudinal series, which cannot be collected retroactively. Strike it and the grid loses a
   toggle.
2. Display-name mapping: approve/strike per word.
3. Intensity slider: in or out.
4. Cue delay: 30 min is the owner's starting guess; config knob either way.
5. Canary threshold N (default 4 days).
6. **Build-agent amendment (pending owner strike/approval): append-only revisions.** The schema
   as written implies reopening an event edits it in place, which collides with the immutability
   law. Proposed reconciliation: every (re)label is a NEW appended row carrying `event_id` (stable
   per event) and `revises` (the prior row it supersedes); an event's current state is the latest
   row for its id, and full label history is retained for free. UI shows one event; the archive
   keeps every version. Costs nothing at entry time.

## Build order (H1, for the build agent)

1. Listener + `flags` stream + canary. First, because it observes while everything else builds —
   it is also the push-intermittency diagnostic.
2. Label schema v2 (mixtures, dual timestamps, sources, flag_ref). Schema before UI.
3. Grid v2 + timeline tap-frame + face rename/age (the UX contract above).
4. Passive HR + skin-temp collection streams; battery attribution check against the wear-day law.
5. Weekly review script v0, dumb counts only: labels by source, flags caught vs labeled, latency
   distribution, wear coverage. No model code (H2 gate).
6. (H1.5, when convenient) Health Connect phone-side nightly reader.

Sequencing respects the owner's September deadline: items sized in days, not weeks; anything not
listed here is parked.

---

# Appendix — verified facts and data-source reference

Absorbed from `HEALTH_INTEGRATION.md` (research pass, 2026-08-23) when the two files merged into
this single source of truth (2026-08-24). The options ranking that file carried is superseded by
the capture architecture above; the facts below stand and need no re-verification.

## The hard wall, verified

**Body Response events and all EDA data are not programmatically accessible. Anywhere.**
- No Health Connect datatype for EDA or stress events exists.
- Fitbit's Web API explicitly does not expose EDA/stress and it is
  [not on the roadmap](https://community.fitbit.com/t5/Web-API-Development/Is-there-any-web-api-available-for-EDA-and-Stress/td-p/5475259).
- The successor [Google Health API](https://developers.google.com/health/endpoints) (parity
  project for the Fitbit Web API) has no EDA/stress endpoints either.
- The cEDA sensor itself is sealed — **verified by sensor enumeration on this watch
  (2026-08-23)**: a `Galvanic Skin Response` sensor (TI, `com.google.sensor.gsr`) exists but
  requires `com.google.pixelwatch.permission.READ_PRIVATE_SENSORS`, a Pixel-Watch-private
  permission third-party apps cannot hold.
- EDA scan data explicitly [stays on the device](https://support.google.com/fitbit/answer/14237928).

So the flags cannot be pulled. They might, however, be *caught in flight* — see Option A.

The flags cannot be pulled; they are caught in flight by the notification listener (capture
architecture, family 1).

## Data sources, with costs

| Source | What it gives | How | Cost / constraint |
|---|---|---|---|
| **Fitbit body-response notification** | The flagged moment itself, as it happens | A `NotificationListenerService` **on the watch** catches the Fitbit prompt ("Body response detected…") and turns it into our own tag prompt + archive event | Event-driven ≈ zero battery. Needs notification-access permission (one settings toggle). Fragile to Fitbit wording/channel changes. *Unverified — needs a build-and-watch-one-fire test* |
| **[Health Services passive monitoring](https://developer.android.com/health-and-fitness/health-services)** (watch) | Continuous HR (batched), steps, calories; platform health events | `PassiveMonitoringClient`, batched off the MCU, delivered to our app in clumps | The one real battery cost. Google designed it MCU-batched specifically for low power; realistic low-single-digit %/day (*measure, don't trust*). Needs `BODY_SENSORS` + a passive listener — spec change |
| **Health Connect** (phone) | What Fitbit/Pixel sync writes: resting HR, **HRV (nightly RMSSD)**, respiratory rate, SpO2, **skin temperature** (nightly delta), sleep stages, exercise sessions | Phone-side reader (we have no phone app — could be a small companion or a scheduled reader on this workstation is NOT possible; Health Connect is on-device only) | Free battery-wise (data already collected), but **nightly/summary granularity** — useless for moment-flagging, good for baselines and context. Requires building our first phone-side component |
| **On-watch HR sensor, raw** | Moment-scale HR/HRV if we run our own detector | `MeasureClient` bursts or passive stream + our own anomaly detector | We own the algorithm (which is also the point: Fitbit's detector is theirs). Sustained sampling is the expensive mode; burst-on-schedule is cheaper |
| **On-watch skin-temperature sensor** | Moment-scale skin temp — one of Fitbit's four body-response inputs | Direct sensor read behind `android.permission.health.READ_SKIN_TEMPERATURE` — a normal, grantable health permission (**verified present in this watch's sensor list**, TI part, wake-up variant included) | Same battery discipline as HR; makes our own detector a 2-of-4-signal approximation of Fitbit's (missing only sealed GSR and deriving HRV ourselves) |
| **Manual (exists today)** | The human, tapping | The 2×4 grid | Zero cost, already shipped; loses the "unusual event" trigger entirely |

## Sources

- [Fitbit community: no Web API for EDA/stress, not on roadmap](https://community.fitbit.com/t5/Web-API-Development/Is-there-any-web-api-available-for-EDA-and-Stress/td-p/5475259)
- [Google Health API endpoints](https://developers.google.com/health/endpoints) · [release notes](https://developers.google.com/health/release-notes)
- [Health Services on Wear OS](https://developer.android.com/health-and-fitness/health-services) (passive monitoring, MCU batching)
- [How Body Response / cEDA works](https://blog.google/products/fitbit/measure-stress-fitbit-google-pixel-watch/) · [9to5google on the cEDA sensor](https://9to5google.com/2023/06/02/pixel-watch-2-stress-tracking-sensor/)
- [Google: EDA data stays on device](https://support.google.com/fitbit/answer/14237928)
