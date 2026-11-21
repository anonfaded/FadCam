# Digital forensics

Evidence collected automatically while recording: what was seen, when, and a
cropped snapshot of it.

The detector that recognises objects is described in
[on-device AI and machine learning](../ai/on-device-models.md). The trigger that
starts a recording is described in [motion detection](motion-detection.md).

## What it records

While a motion recording is active, detections are turned into **events**. An event
is one subject staying in frame: it has a start time, an end time, a peak confidence,
a bounding box and a set of snapshot images.

Snapshots are stored under the app's external files directory:

```
FadCam/Forensics/Snapshots/<yyyyMMdd>/<mediaUid>/<eventUid>_<timelineMs>_<snapshotUid>.jpg
```

Each snapshot carries a visible capture stamp and a SHA-256 hash is stored alongside
it, so a file can be proven unmodified.

## Event continuity

A detection continues an existing event when it overlaps that event's box above an
IoU threshold. Two consequences matter:

- **One subject is one event**, even while it moves across the frame.
- Events are **not** split when the model changes its label. A detector can report
  `person`, then `skateboard`, then `person` for the same subject; matching by
  position keeps it as one event and stores the highest-confidence label. Keying on
  the label instead created a new event for every relabel, and each new event got its
  own first snapshot — one recording produced hundreds of images of the same scene.

## Capture policy

All throttling lives in `CaptureThrottlePolicy`, a pure class with no Android or
database dependency, so the rules are unit-tested directly.

| Rule | Value | Purpose |
|---|---|---|
| Per-event floor | 6 s | No change signal can bypass it. |
| Per-event budget | 15 | After this an event drops to a slow heartbeat. |
| Heartbeat | 12 s | A long-standing subject still gets periodic evidence. |
| Low-power interval | 60 s | Cadence once the budget is spent. |
| Recording-wide floor | 4 s | Bounds total evidence regardless of subject count. |
| IoU match | 0.25 | Overlap required to continue an event. |

The recorder also submits at most one sample per analysis pass, and never more often
than the recording-wide floor, so the number of JPEGs encoded matches the number of
images actually stored.

## Categories

| Category | Contents |
|---|---|
| `PERSON` | people |
| `VEHICLE` | cars, motorcycles, buses, trucks, trains, boats, aircraft |
| `PET` | dogs, cats, birds, horses, and other animals |
| `OBJECT` | everything else the model recognises |

Categories can be enabled individually, and capture scope can be limited to people,
objects, or both.

The recognised classes are fixed by the model: 80 COCO categories. The complete list
is in [on-device AI and machine learning](../ai/on-device-models.md#what-it-can-detect),
and that page is the single place to check whether a given object is supported.

## Live overlay

When enabled, detected boxes are drawn on the **preview** while recording, with the
class name and confidence. The overlay is a preview aid: it is not burned into the
saved video.

## Live view while paused

A paused recording normally freezes the preview and shows the paused overlay. When
the live stream is active, the camera and encoder keep running, so the preview stays
live and the paused overlay is suppressed — the service decides this, and the home
screen follows that decision rather than guessing from the paused state alone.

## Where the code lives

| Part | Location |
|---|---|
| Event recorder | `src/full`, `forensics/service/DigitalForensicsEventRecorder` |
| Capture policy | `util/CaptureThrottlePolicy` (shared, testable) |
| Database | `src/full`, `forensics/data/local` |
| Screens | `src/full`, `forensics/ui` |

Digital forensics is Full-only. Lite compiles none of it.

## Evidence image orientation

Snapshots are stored in the same orientation as the recorded video. The analysis
buffer is rotated once, using the platform transform, and the crop is then taken from
that upright image. Cropping first and rotating afterwards produced very wide images
that did not match the object they were supposed to show.

## Slow devices

Detection cost varies enormously between GPUs (about 146 ms on a Tensor G3 against
about 1548 ms on a Mali-G57). Evidence capture needs detections, so the detector is not
disabled on slow hardware; the cadence adapts instead. When the measured cost is too
high, the Digital Forensics screen says so, with the measured figure, so the delay
between an event starting and being captured is expected rather than surprising.

## Cost

Each stored snapshot means decoding a frame, cropping it, drawing a stamp, encoding
JPEG and writing a file. The throttle exists to keep that off the recording hot path:
without a recording-wide floor the cost scales with how many objects the model can
find, which is exactly when the device is already busiest.
