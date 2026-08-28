# wristwork — detector design v2 (supersedes telltale-detector-design.md)

Global rename: the project is **wristwork**. Find-replace `telltale` in CLAUDE.md and the health-context doc when carting this over; ntfy topic names are config, rename or keep at will.

## 0. Identity, staged

This component is a **state classifier built in stages, wearing a prompt allocator as its first stage.**

- **v1 — allocator.** Decides when to ask. Bar: lift over the random stream at matched budget. May be wrong most of the time and still succeed.
- **v1.5 — shadow guesser (starts day one).** A prior-only model (no learned parameters) maps each epoch's features to a **mixture prior over the eight grid states**, logged server-side with a version stamp, **never displayed**. Costs nothing, and its running hit-rate against incoming labels is a free n=1 test of the literature priors in §3.
- **v2 — learned classifier.** Supervised on accumulated labels (mixture-vector regression per CLAUDE.md Component C), replacing the shadow guesser.
- **v3 — display.** The inference complication renders a state only after v2 beats the time-of-day baseline **on random-stream labels** (see §5 holdout rule).

## 1. Prompt sources and budgets (corrected)

| source | budget | role |
|---|---|---|
| `random` | **1/day max at launch** (owner-set; ramp knob in config, revisit ~week 4) | Unbiased backbone; the permanent evaluation stream. Fully random time within waking window, jittered daily. |
| `signal` | ≤2/day, refractory ≥90 min | Detector enrichment. |
| `self` | unlimited | Owner-initiated; `noticed_before=true` by construction. |
| `google` | whatever fires | Auto-captured via listener when their pushes work; courtesy stream. |

**Volume math, stated so nobody is surprised:** at 1 random/day, 50 random-stream labels take ~7 weeks assuming perfect response. Every evaluation gated on the random stream (lift, classifier validation) inherits that clock. The ramp knob is the remedy when the owner's tolerance data comes in; the budget is his call, the arithmetic is physics.

**Prompt copy, uniform across `random` and `signal` (blinding preserved):** `State? · 2:41p` — neutral, timestamped, no assertion about what was detected ("unknown state" register, per owner). Tap opens the grid **bound to the prompt's timestamp**, so the tag attaches to the flagged moment, not the tap moment. Identical wording for both sources is what keeps the blind intact and the lift measurement honest; never ship copy that asserts arousal (prior-injection — the false-cardiac-feedback lesson).

## 2. Placement (ratified)

Server-side on the rig against the Google Health API covariate stream (intraday HR ~5 s, intraday HRV), polled every 10–15 min. Watch pays zero battery. Week-one task unchanged: measure watch→cloud sync latency; p50 > ~45 min demotes live triggering to the end-of-day annotator. The OAuth auth spike is first and load-bearing; September cutover looms.

## 3. Research-informed priors — the Ekman→Panksepp bridge

The autonomic-specificity literature is organized around Ekman-style discrete emotions, so priors route through it into the Panksepp basis. Ground truth on the ground truth: Kreibig's (2010, *Biol Psychol*) review of 134 studies finds **considerable specificity at the level of emotion subtypes**; Siegel et al.'s (2018, *Psych Bull*) meta-analysis finds weak *consistent* fingerprints with huge heterogeneity. Read together: **arousal is robust, family-level patterning is real but noisy, within-family valence is largely unrecoverable from HR/HRV alone.** The mixture design already assumes this; the guesser must too.

Wearable-visible signatures per grid state (features: HR level, HR-motion residual, HRV/RMSSD, recovery slope, variance). Confidence: ▲ solid, ■ moderate, ▽ weak.

| state | expected signature | conf | anchors |
|---|---|---|---|
| **SEEK (keyed/engaged)** | HR mildly ↑, HRV mildly ↓, sustained, motion-flat; appetitive anticipation | ■ | Kreibig 2010 (anticipatory/interest); flow shows *moderate* arousal, inverted-U (Peifer et al. 2014) — HRV findings mixed ▽ |
| **SEEK (collapsed/flat)** | low arousal *variance*, HR ↓/↔, sluggish reactivity across hours | ▽ | deactivated-sadness analog; mostly our extrapolation — flag as such |
| **RAGE** | HR ↑ (moderate), HRV ↓, sustained pressor pattern; slow recovery | ■ | Levenson et al. 1983; Kreibig 2010 (anger: DBP/TPR-dominant — invisible to us, so anger≈fear here) |
| **FEAR (active)** | HR ↑↑ (largest accelerations), HRV ↓ | ■ | Kreibig et al. 2007; Kreibig 2010 |
| **FEAR (freeze/orienting)** | transient HR **deceleration** — bradycardia under threat immobility | ■ | Roelofs 2017, *Phil Trans B* |
| **LUST** | HR ↑, arousal indistinguishable from stress in this feature set; context-dependent | ▽ | Kreibig 2010; disambiguation is the human's job, full stop |
| **CARE (contentment/warmth)** | HR ↓, HRV ↑ — relaxation-like pattern | ■ | Kreibig 2010 (contentment ≈ relaxation response); Behnke et al. 2022 positive-emotion meta |
| **GRIEF/PANIC (protest)** | activated: HR ↑, agitation in motion channel | ■ | Kreibig 2010 (crying sadness); Panksepp & Watt 2011 (protest phase) |
| **GRIEF/PANIC (despair)** | deactivated: HR ↓, HRV ↓/↔, low variance | ■ | Kreibig 2010 (non-crying sadness bifurcation); despair phase |
| **PLAY** | HR ↑ bursts with irregular motion/respiratory signature (laughter), fast recovery | ▽ | Behnke et al. 2022; amusement HR often ↓ vs fear/anger (Cacioppo et al. 1997) — messy, keep weak |
| **OTHER** | anything persistently unlike baseline that fits none of the above; disgust-type parasympathetic dips land here | — | by construction |

Guesser output shape: mass-spread mixtures honest to the table — e.g., sustained {HR-residual ↑, HRV ↓} → `{SEEK-keyed .30, FEAR .25, RAGE .25, LUST .10, PLAY .10}`, never a confident singleton. Bradycardia transient → `{FEAR-freeze .5, OTHER .3, CARE .2}`. Low-variance afternoon → `{SEEK-flat .5, GRIEF-despair .3, CARE .2}`. The two GRIEF/PANIC modes and two FEAR modes exist in the guesser's vocabulary even though the grid collapses them — the LLM layer can use the finer guess later.

## 4. Trigger mechanism (corrected: quantile → model thresholds)

- **v1:** top-k most anomalous eligible epochs/day (k = signal budget) over a combined score of §3 features, z-scored against the time-of-day-matched rolling baseline. Symmetric: unusually low variance is anomalous too. Self-calibrating, ~150 lines.
- **v2:** once a real generative model of his baseline exists (BOCPD — Adams & MacKay 2007 — or the learned mixture model), the trigger converts to **model-based thresholds**: fire on surprise under the baseline model or posterior mass on non-neutral states exceeding threshold. The quantile stage's job is to keep the budget honest until the model earns thresholds.
- Shadow mode weeks 1–3 regardless (baseline warm-up); first free evaluation = co-occurrence of shadow events with `self` tags and Google's timeline.

## 5. Labels may enter the model; the holdout never does

Correction absorbed: the trigger/classifier **will** become label-informed at v2 — that's the point. The circularity guard therefore moves from "unsupervised forever" to a **permanent holdout rule**: the `random` stream's labels are never used for training, tuning, or threshold selection — evaluation only, forever. Lift, classifier validation, and the v3 display gate all score exclusively against random-stream labels. Signal-, self-, and google-stream labels are trainable. This is the entire integrity of the system in one sentence; it goes in the repo README.

## 6. Fine-tuning loop and readouts

Biweekly (stretched to match the 1/day random cadence): per-source counts, response rate, %physical, %OTHER, `noticed_before` rate; guesser hit-rate (top-mass state vs. label, and mixture cross-entropy) as the running test of §3's table. Decisions: signal keeps its budget only on lift; guesser priors get hand-revised only at review, never mid-week; ramp knob revisited with burden data. The scientific readout stands: `noticed_before` conditional on source — detector prompts landing on unnoticed states = the instrument finding blind spots; flat `noticed_before` with rising subjective awareness = ITPE tripwire, stop.

## 7. Pre-mortem deltas

Unchanged from v1 (sync latency → annotator fallback; Sept API cutover → auth spike first; fatigue → hard caps + week-6 burden review; scope seduction → v2 boxes labeled). One addition: **at 1 random/day the evaluation clock is the scarcest resource in the project** — every skipped prompt now costs ~2% of the first validation set. Respond to the ones that fire.
