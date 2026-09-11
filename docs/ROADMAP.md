# Proposed next work

2026-09-06 source-review recommendations. These are proposals, not owner-approved
changes to prompt budgets, model gates, deployments, or physical actions. Current
operational health has not been checked in this handoff.

The implied goal is a personal instrument you can trust and live with: it captures
what happened, asks sparingly, and eventually offers a state estimate that earns
your confidence. The wrist controls for agents and printing serve the same wish
to spend less attention managing machines. This is an interpretation of the README
and health design, not an additional research mandate.

## New-wearer workstream (owner request, 2026-09-11)

The clean [`wristwork-emotion`](https://github.com/EmotiveAutomaton/wristwork-emotion)
upstream now exists with fresh history, an explicit privacy allowlist, portable
private backend/archive, setup and doctor tooling, a launcher entry, and focused
skills. Its hourly sync from `wristwork/main` was exercised through export, secrets
scan, tests, build, commit, and push; a final no-change run also preserved the
target-owned workflows and required hidden files. The boundary and update model are
in
[REUSABLE-EMOTION-APP.md](REUSABLE-EMOTION-APP.md); the wearer path is in
[NEW-WEARER-SETUP.md](NEW-WEARER-SETUP.md).

Next, run the first friend's setup as the product test. Record permission friction,
archive arrival, passive collection, prompt dismissal, offline replay, remote
reachability, and full-wear-day battery evidence, then improve the shared template.

## Recommended order

Each row names a useful increment, the evidence motivating it, and its completion test.

| Order | Build next | Why now | Done when |
|---|---|---|---|
| 1 | Trustworthy collection-health report | `healthcheck.py` treats a present refresh token as authorization; an empty scheduled-task result can pass; historical STATE warned of a Sep 4 expiry | Missing expected jobs, failed task queries, stale health-account ingestion, missing mirrors, and truncated responses report failed or unknown; fixtures cover each; a current read-only operational check dates the result |
| 2 | Durable prompt delivery record and rule tests | The allocator records allocation, while the watch holds firing/deferral in preferences and logs; scheduling is not delivery | Allocation, actual delivery, asked-about time, deferral, expiry, answer, and reason can be joined by ID; tests exercise the one-hour boundary, six-hour cap, repeated polls, and preserved source |
| 3 | Private collection-quality review | Early labels include revisions, test artifacts, delayed answers, and a documented blinding change | A versioned local report distinguishes raw rows from unique events, reports response/latency/coverage by source, accounts for tombstones and known artifacts, and never exports personal rows |
| 4 | Offline replay and wear-day validation | Latest detailed STATE still calls battery attribution and parts of offline replay unverified | A controlled device session proves queued records arrive once logically after reconnect; ordinary wear-day measurements establish comfort on one charge; install/build evidence stays separate |
| 5 | Complete the voice-to-print seam | Voice itself remained untested in the Aug 31 record; local code has since changed to hold-to-print | Synthetic contract tests cover missing/malformed expiry, closed/old offers, partial slicer fields, failed posts, and repeat choices; authorized device validation proves tap is local and hold is deliberate |
| 6 | Codex-aware agent status and return links | Existing hook fragments and `AgentsDetailActivity` assume Claude; the user is now using Codex | A versioned producer-neutral event carries project, status, safe summary, and a supported return target; existing messages still render; notifications are configured and verified only within explicit scope |
| Later | Learned personal model in shadow, then possible display | The model is the goal, but collection and independent evidence are prerequisites | The recorded label milestones are met; training uses eligible non-random labels; a fixed evaluation meets the existing baseline/display gate without tuning on the holdout |

Start with item 1, then item 2. They protect every later feature and make quiet
failure distinguishable from a quiet day. Item 5 is the most direct next visible
feature if the owner prefers immediate wrist utility.

## Specific review findings to carry forward

- **Health checks can overstate confidence.** In `tools/rig/healthcheck.py`, token
  presence is the entire ECG/overnight authorization check. The scheduled-task
  subprocess's return code is not checked; zero returned tasks yields no bad tasks.
  This is source evidence of a diagnostic gap, not evidence that today's jobs failed.
- **The intended deferral cap needs a boundary test.** In `PromptWorker`, the cap is
  checked inside the crowded branch. A deferred prompt can reach the clear branch
  after six hours and be delivered. Reproduce this in an isolated test before a fix.
- **The watch's crowding check is narrower than the broad spec wording.**
  `PromptWorker.crowded()` reads recent labels and body-response flags, not a durable
  history of delivered prompts. Test two due prompts in the same poll before claiming
  the one-hour rule covers every event across all sources.
- **Random scheduling and random observation are different.** Moving a random
  question until the timeline clears makes its delivered time depend on earlier
  events. Shared lag choices alone also do not prove matching observed lag
  distributions. Preserve the owner's deferral decision; report what population the
  resulting holdout samples and measure blinding before claiming unbiased whole-day
  performance. Do not change scheduling or use holdout outcomes to repair this.
- **READY needs strict offer validation.** `PrintLoop.liveOffer()` currently accepts
  missing/unparseable expiry and checks for grams alone. That is weaker than the
  spec's unexpired offer with real slicer numbers. Reproduce with synthetic offers;
  a misleading READY is distinct from proof that Fetch would execute a stale request.
- **Some readers still ignore bus error envelopes.** `PromptWorker` and
  `PrintLoop.latest()` skip non-message lines; `stream_cache.py` explicitly rejects
  an in-band error inside HTTP 200. Review the watch readers against that same
  failure case rather than assuming the historical fix reached every consumer.

## Keep the structure proportional

Add tests at the rules that can damage data or trigger a physical action. A small
`tests/` for pure rig logic and Android unit tests for extracted Kotlin decisions
would earn their cost. Room schema export/migration fixtures are another useful
increment before the next database change. Avoid a second app module, a framework
for one-off scripts, or a broad rewrite merely to tidy the tree.

Owner choices remain prompt burden, subjective model usefulness, and any change
to the instrument's sampling or interaction rules. No new choice is needed just
to implement the local diagnostics and tests described above when commissioned.
