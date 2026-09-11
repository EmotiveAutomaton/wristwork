# Data and research contract

The wearer owns the configuration, transport, archive, and every resulting record.
This project stores affective labels and optional physiological streams, so the
default is a private authenticated endpoint and an archive controlled by the wearer.

## Labels

Each uploaded label contains a stable `event_id`, `ts_event`, `ts_entered`, a
primary label (which may be empty for a secondary-only response), zero or more
secondaries, and `source`. It may also contain a note, flag reference, prompt
identity, and revision pointer. The canonical states are:

```text
SEEK RAGE FEAR LUST CARE GRIEF PLAY OTHER
```

OTHER is shown as Neutral in the interface. Do not rewrite older rows to change
vocabulary or interpretation. Derivations should be versioned and reproducible.

The source values are `self`, `google`, `signal`, and `random`. The `random` stream
is evaluation-only forever. Its outcomes cannot inform training, features, priors,
thresholds, model selection, or decisions about which model to display. Missing or
reconstructed provenance never qualifies a row for the holdout.

## Append-only behavior

The watch keeps pending uploads in Room. Editing an existing event inserts a new
row with the same event identity and a `revises` pointer. Clearing it inserts a
tombstone. Transport acknowledgements can update local upload bookkeeping, but
archive content is never edited to repair an interpretation.

The optional backend archiver stores the complete ntfy message envelope as NDJSON,
one file per topic. Back up `backend/data/` privately. The archiver filters duplicate
ntfy message IDs after reconnects; this is transport deduplication, not mutation of
the recorded events.

## Prompts and physiology

Random and signal prompts use identical on-watch copy and preserve their identity,
source, and asked-about time. Dismissing one records no emotion label. Prompt burden,
waking hours, and whether prompts are enabled are wearer decisions.

Health batches may include heart-rate, skin-temperature, activity-state, or sensor
inventory records depending on device support and permission. Capability and missing
data must be reported as observed rather than inferred from the model name. This
repository makes no medical or diagnostic claim.
