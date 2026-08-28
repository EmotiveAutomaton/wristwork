# Speaking a print into existence — feasibility and plan

Owner's ask, 2026-08-28: speak into the watch; an agent goes and finds a model; it slices it and
sends it to the printer; the watch shows a picture and asks yes or no; on yes it prints. Also
asked: is this Fetch's job rather than wristwork's?

**Short answer: viable, with one genuinely hard step in the middle, and yes — it belongs to Fetch.**
The watch is the microphone and the yes/no button. Everything between those two is somebody else's
work, and pretending otherwise would put a household-automation engine inside a watch app.

## What is easy, what is hard

| Step | Difficulty | Why |
|---|---|---|
| Speak, transcribe, send the text | **Easy, mostly built** | The watch already transcribes with the platform recogniser and already publishes to the bus. This is a new topic and a button. |
| Find a model matching the words | **Medium** | Printables and Thingiverse both have public search APIs; MakerWorld does not, officially. An agent can search, rank and pick candidates. Licence and printability filtering is the fiddly part. |
| Decide it is the RIGHT model | **Hard — the real problem** | "A bracket for the shelf" is under-specified. This is where a wrong answer wastes six hours of filament. Mitigated by asking, with a picture, before anything moves. |
| Slice it | **Medium-hard** | PrusaSlicer has a command-line mode that takes a model and a printer/filament profile and emits gcode. Runs on the rig, unattended. The failure mode is silent: a slice that "succeeds" but produces a part that needs supports it did not get. |
| Preview picture | **Easy** | The slicer emits a thumbnail; the watch already knows how to show one from the bus — that path shipped for the printer complication. |
| Ask yes/no on the wrist | **Easy** | A notification with two actions, or the existing frame pattern with two buttons. |
| Send to the printer | **Easy** | Upload gcode over the local printer API and start the job. Already authenticated, already working. |

## Why it is Fetch's, not wristwork's

wristwork's job is the wrist: sensors, glanceable lines, and a message bus. The moment something
searches the internet, evaluates licences, drives a slicer and manages a queue of pending
proposals, it is a household agent — which is exactly what Fetch is for. The compartmentalisation
the owner suspected is the right one, and it also keeps the boundary that already exists: the
wristwork bus is public-tier transport only.

**The seam:** two new topics on the existing bus.
* `requests` — wristwork publishes `{text, spoken_at}` when the owner speaks. Fetch subscribes.
* `proposals` — Fetch publishes `{proposal_id, title, source_url, print_time, filament_g, thumbnail}`
  with the picture attached. wristwork raises the confirmation and publishes the answer back.

wristwork's entire contribution is: a button that records, a notification that asks, and two
payload shapes. Everything else lives in Fetch and can be rewritten without touching the watch.

## The three things that will bite

1. **Under-specification.** Voice gives you a sentence, and a sentence rarely determines a model.
   Expect the first useful version to propose two or three candidates rather than one, and to be
   answered with "none of these" often. Design the confirmation for *choosing*, not just approving.
2. **Silent slicing failures.** A slice that completes is not a slice that will print. At minimum,
   refuse to propose anything whose sliced result needs supports unless the request said so, and
   show estimated time and filament in the confirmation — those two numbers catch most disasters.
3. **The confirmation must expire.** A yes tapped four hours later, after the printer has been
   used for something else, must not start a print. Proposals get a deadline; a stale one asks
   again rather than acting.

## Suggested build order (for Fetch, when it gets there)

1. Text in, candidates out. No slicing, no printing — just prove that spoken requests produce
   sensible model candidates, reviewed on a screen.
2. Add slicing and the preview picture. Still no printing: the output is a proposal the owner
   looks at.
3. Add the wrist confirmation and the print start, with the expiry rule and a hard daily cap.
4. Only then consider letting it propose without being asked.

## What wristwork should do now

Nothing, beyond what already exists. The microphone button on the rig frame is the placeholder the
owner asked for, and the payload contracts above are written down so Fetch can build against them.
When Fetch is ready, wristwork's side is roughly a day of work — a topic, a notification with two
actions, and a reply.
