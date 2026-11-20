# Motion detection

Recording that starts by itself when something moves, instead of running for hours
of empty footage.

This page covers the trigger subsystem. The model that recognises *what* moved is
described in [on-device AI and machine learning](../ai/on-device-models.md), and the
evidence it keeps is described in [digital forensics](digital-forensics.md).

## What it does

Motion Lab analyses a downsized copy of the camera feed, decides whether the scene
is active, and starts or pauses the recording file accordingly. The camera and
encoder keep running throughout, so starting is instant and there is no re-open
flash.

## How a frame is analysed

1. The camera delivers a second output stream to an `ImageReader` at the configured
   analysis resolution.
2. The newest frame is handed to a dedicated analysis thread. If analysis is still
   busy, the waiting frame is replaced rather than queued — analysis always works on
   the newest frame, and no camera buffer is held.
3. A motion score is computed. Only the luma plane is read, so no colour conversion
   happens for the motion decision itself.
4. The score is smoothed asymmetrically: it rises quickly so a real event is not
   missed, and decays slowly so a brief dip does not end a recording.
5. The state machine turns that score into a start or stop decision, subject to the
   debounce and post-roll settings.

The analysis thread is separate from the camera callback on purpose. Running the
analysis between capture requests starves the encoder and shows up as frame gaps and
preview lag.

## Trigger modes

| Mode | Starts recording when |
|---|---|
| Any motion | The motion score crosses the start threshold. No model is involved. |
| Person confirmed | A person is detected with sufficient confidence, for a confirmed number of consecutive samples. |

Person-confirmed mode requires the RF-DETR detector. Any-motion mode does not, so it
costs no GPU time at all.

## Settings

| Setting | Range | Meaning |
|---|---|---|
| Sensitivity | 1–100 | Smallest movement that counts. Higher accepts smaller and farther subjects. |
| Trigger mode | any motion / person confirmed | See above. |
| Analysis rate | 1–15 fps | How often a frame is analysed. |
| Analysis resolution | 480p / 720p / 1080p | Height of the analysed frame; the width follows the camera aspect ratio. |
| Debounce | ms | How long a condition must hold before the state changes. |
| Post-roll | ms | How long recording continues after motion stops. |
| Pre-roll | seconds | Footage kept from before the trigger, so the start of the event is not lost. |
| Auto torch | on / off | Turns the light on while motion recording is active. |

The whole field of view is always analysed; only the sampling grid changes with the
resolution setting.

## State machine

```
IDLE -> PENDING -> RECORDING -> POST_ROLL -> IDLE
```

- `PENDING` waits for the debounce period, so a single noisy frame cannot start a
  recording.
- `RECORDING` requires the signal to be absent for the whole debounce period before
  moving to `POST_ROLL`.
- `POST_ROLL` keeps recording for the configured time, and returns immediately to
  `RECORDING` if motion resumes.
- A minimum clip length and a cooldown prevent rapid start/stop flapping.

A manual pause or resume re-aligns the state machine to the recorder's real state.
Without that, the machine kept running while the file was paused and its expired
post-roll timer stopped the recording again on the first frame after a manual
resume.

## Where the code lives

| Part | Location |
|---|---|
| Analysis loop and wiring | `RecordingService` |
| State machine | `motion/domain/state/MotionStateMachine` |
| Thresholds | `motion/domain/policy/MotionPolicy` |
| Settings model | `motion/domain/model` |
| Motion backend (MOG2) | `src/full`, `OpenCvMog2MotionDetector` |
| Settings screen | `src/full`, `motion/ui/MotionLabSettingsFragment` |

Motion detection is Full-only. Lite compiles neither the backend nor the screen.

## Cost

Motion analysis is CPU work proportional to the analysed resolution. 720p is the
default and is roughly half the work of 1080p.

Raising the analysis rate costs CPU without improving detection accuracy: the object
model runs on its own cadence regardless. If a long recording becomes hot or the
preview stutters, lower the analysis resolution first, then the rate.
