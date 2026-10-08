# Music Haptics — Design & Implementation

Implemented for v1.13, branch `windows-desktop`.

Music Haptics drives the phone's vibration motor in time with the music, the way
Apple Music does on iPhone. This document records what the Android platform
actually allows, why the implementation is shaped the way it is, and what its
honest limits are.

---

## 1. What the platform gives you, and what it does not

The single most important fact: **Android has no sample-accurate vibration
scheduling API.**

`Vibrator` offers three ways to make a pattern:

| API | Shape | Scheduling |
| --- | --- | --- |
| `createWaveform(long[] timings, int repeat)` | off/on pairs alternating by index | whole waveform starts *now* |
| `createWaveform(long[] timings, int[] amplitudes, int repeat)` | independent duration + strength per step | whole waveform starts *now* |
| `VibrationEffect.Composition` + `addPrimitive` | predefined motor primitives (tick, click, thud) | whole composition starts *now* |

None of them accept a start time. `Composition.addPrimitive` takes a scale and a
delay, but the delay is *after* the primitive, not a future start time, so it
cannot be used to schedule a strike that has not been reached yet. Verified
against the AOSP `VibrationEffect.java` source and `javap` on the
`compileSdk 37` `android.jar`.

**Consequence:** vibration cannot be *predicted* and handed to the OS. It has to
be submitted in short chunks, just ahead of the playback position, and each
submission replaces the last one. That replacement semantics is what makes the
refill gate in §4 load-bearing rather than an optimisation.

Verified API details the implementation relies on:

- The 3-argument `createWaveform` has **no parity constraint**. It only requires
  `timings.length == amplitudes.length`; a step with amplitude `0` is the motor
  being off, and a timing of `0` makes the pair be ignored. A single-element
  array is a valid one-shot. (The 2-argument form *does* impose the
  even-index-is-off convention, which is why the engine uses the 3-argument
  form.)
- `VibrationAttributes.USAGE_MEDIA` (API 33+) is the correct usage for
  media-driven haptics. `USAGE_TOUCH` is what the app's UI haptics use.
- `hasAmplitudeControl()` is **not** guaranteed. Some devices on API 31+
  return `false` and silently ignore the amplitude array, rendering every step
  at full strength. The engine detects this and degrades to a coarser pattern
  rather than emitting 200-step full-power waveforms.

---

## 2. Why the vibration is *derived*, not measured

The obvious implementation is to tap the audio chain and run a live
onset/envelope detector, then vibrate on what it finds. That was rejected.

The app already analyses every track offline. `TrackAnalyzer` decodes the audio
and `TrackAnalysis` — stored one JSON document per track in `AnalysisStore` —
already contains exactly what music haptics needs:

- `beatInterval` (seconds per beat, measured directly)
- `beatConfidence` (0..1)
- `downbeats`, `phraseBoundaries`
- `firstBeat`, `contentEndTime`
- `lowEnergyCurve` (the low band, which is what a hand on a speaker feels)

Deriving from that instead of measuring has three concrete advantages:

1. **Nothing is added to the audio thread.** `PrecisionAudioSink` documents that
   with its three processors idle the audio block leaves byte-identical. A
   fourth live processor would put a new failure mode in the playback path for a
   cosmetic feature.
2. **The whole track is known in advance**, so the vibration is beat-locked and
   accent-aware rather than reacting a few milliseconds late to an onset it has
   already heard.
3. **It is testable.** `HapticScore` is pure — no I/O, no Android, no clock —
   so the entire derivation is covered by plain JVM unit tests.

The trade-off, stated plainly: **only tracks whose whole-track analysis has
completed vibrate.** A track that is still analysing, or one played straight
from a stream before analysis, stays still until the analysis lands.

---

## 3. Deriving the pattern — `HapticScore`

`HapticScore.of(analysis)` produces the pulses, or `EMPTY` when the analysis
does not describe a track that can be felt. It bails out on:

- `status != "ready"` or `bpm <= 0`
- `beatConfidence < 0.25`
- `beatInterval` outside `0.15 – 3.0` s (a corruption guard; the analyzer only
  emits 40–220 bpm)
- fewer than 4 beats in the resulting grid

### 3.1 The beat grid is re-anchored at every downbeat

Walking a fixed interval across a whole track is the obvious approach and the
wrong one: a measured interval carries a little error, and a three-minute track
gives that error a few hundred beats to accumulate over. The grid would start
locked and finish audibly late.

So `beatGrid()` re-anchors at every downbeat. Each segment is walked at *its
own* interval — measured from the two anchors that bound it — rather than at the
track's average. Drift is bounded by one bar instead of by the whole track.

Two invariants hold this together:

- **The content end is emitted.** Every interior anchor becomes the first beat of
  the segment that follows it, so only the final anchor — the content end — has
  nothing to emit it. It is added explicitly. Without that the grid stops one
  beat early, which is a beat the listener would still have tapped.
- **Anchors closer than 90 ms are dropped.** Two strikes that close cannot both
  be rendered anyway.

Note that `BeatTracker` already snaps every downbeat onto the nearest beat
("keeps the bar grid a strict subset of the beat grid"), so a downbeat that
anchors a segment lands on the grid exactly. The accent tolerance in §3.2 is
not there for that case — it is there for the case where the spacing guard folds
a downbeat into the anchor before it.

### 3.2 Strength

```
base  = INTENSITY_FLOOR + (1 - INTENSITY_FLOOR) * level
gain  = 1.32 if downbeat, 1.15 if phrase boundary, else 1.0
level = lowEnergyCurve value at that beat, normalised against 1.0
```

- The **low band** drives the level, not the broadband curve: a kick drum *is*
  the low band. The broadband curve is the fallback for analyses the analyzer
  band-split nothing for, and a constant `0.55` is the fallback for analyses
  stored before either curve existed.
- The **floor of 0.16** means a quiet passage still produces strikes. Without it
  the rhythm — the entire point — fades out exactly when the music drops.
- Accents are matched **by tolerance** (`0.22 × interval`), not equality,
  because a bar line can land between grid points.

### 3.3 Curve lookup must be by nearest time, never by index

The native analyzer caps a curve at **240 points** and strides whatever it
measured to fit:

```
curve_stride = max(1, (levels.size() + 239) / 240)
```

On a four-minute track that commonly means consecutive samples **a second
apart**, and the spacing is not part of the contract. Indexing by second would
read the wrong sample — or past the end — on exactly the tracks where it
mattered. `nearestSample()` does a binary search for nearest time.

---

## 4. Submitting the pattern — `MusicHaptics`

The engine runs one coroutine ticking every `TICK_MS = 45ms`. On each tick it
looks at the current position, converts the pulses in the next
`LOOKAHEAD_MS = 400ms` into a waveform, and submits it.

### 4.1 The refill gate

Every chunk ends with trailing silence, because the lookahead window is longer
than the gap to the next beat most of the time. The gate is what makes the
chunking safe:

```kotlin
val outstanding = emittedThroughMs - now
if (emittedThroughMs > 0L && outstanding > REFILL_AHEAD_MS) return
```

**This is not an optimisation.** A `VibrationEffect` replaces whatever is
currently running. The first 40 ms of every chunk is `LATENCY_LEAD_MS` of
deliberate silence, compensating for the motor's spin-up. Without the gate, the
engine would submit a fresh 400 ms chunk every 45 ms tick — each one beginning
with silence — so the motor would be reset onto silence before it ever finished
playing a pattern. It would look correct in code review and produce **no
vibration at all** on the device. Cutting the outstanding tail 30 ms short is
audibly free, because the tail is silence anyway.

### 4.2 Timing model of a chunk — `HapticWaveform`

A chunk's total duration is exactly `leadMs + windowMs`, and
`timings.sum()` equals it. The cursor is anchored at `-lead` rather than `0`,
which makes every gap fall out of the same subtraction:

- first gap  = `leadMs + onsetMs`
- later gaps = `onsetMs - (previousOnsetMs + pulseMs)`

Anchoring at `-lead` is what keeps the lead from being lost, and from being
double-counted, in the same expression.

Other rules:

- A gap of `0` is a zero-length step and is skipped; a gap shorter than
  `MIN_GAP_MS = 6ms` is folded into the strike before it, because the motor
  cannot render a pause that short anyway.
- A chunk with no audible step returns `null` rather than a silent waveform.
- **Coarse mode** (`buildCoarse`) is the `hasAmplitudeControl() == false`
  fallback: strikes are picked from accented-or-even beats, capped at
  `COARSE_MAX_PULSE_MS = 60ms` so consecutive strikes stay distinct, and run at
  full power.

### 4.3 Anchoring

Wall-clock time and playback position drift apart, so the engine keeps an anchor
(`positionMs` paired with an elapsed-realtime reading) and re-reads it every
`ANCHOR_REFRESH_MS = 250ms`. `onPositionDiscontinuity()` — a seek, a track skip,
a jump — drops the anchor and cancels the motor rather than letting it continue
against a stale position.

Constants that may need device tuning, and are called out here rather than
buried:

| Constant | Value | Purpose |
| --- | --- | --- |
| `TICK_MS` | 45 | reschedule granularity |
| `LOOKAHEAD_MS` | 400 | how far ahead a chunk reaches |
| `REFILL_AHEAD_MS` | 30 | tail-silence safety margin (see §4.1) |
| `LATENCY_LEAD_MS` | 40 | motor spin-up compensation |
| `ANCHOR_REFRESH_MS` | 250 | position/realtime re-sync |
| `ANALYSIS_POLL_MS` | 400 | how often to look for a finished analysis |

---

## 5. Settings

`AppSettings.musicHapticsMode` is a `MusicHapticsMode` with four values:

| Mode | Scale | Notes |
| --- | --- | --- |
| `OFF` | 0.0 | **default** |
| `LOW` | 0.55 | |
| `MEDIUM` | 0.8 | |
| `HIGH` | 1.0 | |

Default is `OFF` deliberately. The motor runs continuously while the feature is
on, which is a real and noticeable battery cost, and vibration during music is
not universally wanted. The setting lives in the Settings sheet under
`music_haptics`, and its strings are translated into all 16 supported locales —
`LocaleStringsTest` fails the build if a key is missing from any of them.

Wiring in `PlaybackService`: a lazy engine accessor, `syncMusicHaptics()` called
from `onIsPlayingChanged`, `onPositionDiscontinuity`, `onTrackBecameCurrent` and
`onCreate`, and `release()` in `onDestroy`.

---

## 6. Honest limits

These are stated here because they are properties of the platform and the
approach, not bugs to be fixed later.

1. **Not sample-accurate.** This is a ~400 ms schedule-ahead approximation. It
   is beat-locked and accent-aware; it is not Apple's Taptic Engine. There is no
   Android API that would make it otherwise.
2. **Whole-track analysis is a prerequisite.** A track still being analysed, or
   played from a stream before analysis completed, does not vibrate.
3. **Battery cost is real.** The motor runs continuously while enabled.
4. **Amplitude control is device-dependent.** Where it is missing, the pattern
   is coarser and always at full strength.
5. **The timing constants are not device-verified.** `LATENCY_LEAD_MS` in
   particular compensates for motor spin-up, which varies by device. These
   values are reasoned, not measured on hardware, and may need tuning.

---

## 7. Tests

- `HapticScoreTest` — the derivation against hand-written analyses: grid walk
  and re-anchoring, content end versus track duration, accents and their
  tolerance, the spacing guard, strided-curve lookup by nearest sample, quiet
  and silent passages, and the corrupt analyses the guards exist for.
- `HapticWaveformTest` — the timing model: `timings.sum() == lead + span`, the
  lead's placement, gap merging, coarse mode's pulse cap, and the empty-window
  and long-lead cases.

Both are pure JVM tests. Full suite at v1.13: **975 tests, 0 failures.**
