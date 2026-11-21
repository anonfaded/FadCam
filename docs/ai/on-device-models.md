# On-device AI and machine learning

Everything FadCam runs on the phone itself to understand a camera frame: which
model, how it is executed, what it can recognise, how it is started and stopped,
and where it is used.

Nothing here calls a cloud service. Detection happens on the device GPU.

Related pages:

- [Motion detection](../features/motion-detection.md) — the trigger subsystem and
  forensic evidence capture that consume these detections.
- [Digital forensics](../features/digital-forensics.md) — what evidence is stored.
- [Animation components](../architecture/animations.md) — the illustrations used in
  Motion Lab and onboarding.

## Models in the app

| Model | Purpose | Runtime | Where |
|---|---|---|---|
| RF-DETR Nano | Recognises 80 object categories (people, vehicles, animals, objects) | LiteRT GPU | `app/src/full` only |
| OpenCV MOG2 | Answers "did the pixels change?" | CPU (native OpenCV) | `app/src/full` only |

Lite builds compile neither model. Lite has no motion triggers and no evidence
collection, so an object detector there would have no consumer.

## RF-DETR Nano

A transformer (DETR-family) detector. Chosen over the previous EfficientDet-Lite1
because it is substantially more accurate for the same or lower inference cost.

| | |
|---|---|
| Source | <https://huggingface.co/litert-community/RF-DETR-Nano-LiteRT> |
| Upstream | <https://github.com/roboflow/rf-detr> |
| License | Apache-2.0 |
| Accuracy | 48.4 COCO mAP@0.5:0.95 |
| Input | 384×384 RGB, upright, ImageNet mean/std, NCHW |
| Runtime | LiteRT `CompiledModel` GPU (ML Drift) |
| Graphs | `rfdetr_nano_graph_a.tflite`, `rfdetr_nano_graph_b.tflite` |

Assets live in `app/src/full/assets/models/`.

### Why two graph files

The two-stage query selection uses `TOPK`/`GATHER`, which has no mobile GPU
kernel. The published conversion splits the network at exactly that point so both
transformer halves stay fully on the GPU, with a small host step between them:

```
image 384x384 (upright)
  → graph A → enc_class[576,91], enc_coord[576,4], memory[576,256]
  → host: top-300 queries by best class score, gather their boxes
  → graph B → boxes[300,4] (cxcywh), logits[300,91]
  → host: sigmoid → threshold → cxcywh→xyxy → per-class NMS
```

This is one model split in two, not two models.

### Preprocessing: the frame must be upright

The camera sensor delivers a frame in its own orientation. In portrait that is
sideways relative to the scene. The model was trained on upright photographs, so
feeding it a rotated frame produces confident nonsense — a face in the corner can
come back as `skateboard`.

The analyzer therefore passes the rotation that makes the frame upright
(`RecordingService#getUprightRotationDegrees`, the same angle the encoder uses),
and the detector samples the frame through that rotation in one pass. Detected
boxes are rotated back into sensor coordinates before they leave the detector, so
the overlay, the forensic crop and the event tracker all work in the same space.

### What it can detect

Motion detection, forensic evidence and the live overlay recognise **exactly 80
classes** — the complete COCO category list this model was trained on, and nothing
else. Those 80 names live in one table in `RfDetrNanoDetector`; the list below is
that table, verbatim.

The count is 80 rather than 90 because COCO category ids are not contiguous: the
logits have 91 slots, id 0 and eleven gaps are unused, and the remaining 80 are the
named categories.

| Group | Count | Classes |
|---|---|---|
| `PERSON` | 1 | person |
| `VEHICLE` | 8 | airplane, bicycle, boat, bus, car, motorcycle, train, truck |
| `PET` | 10 | bear, bird, cat, cow, dog, elephant, giraffe, horse, sheep, zebra |
| `OBJECT` | 61 | apple, backpack, banana, baseball bat, baseball glove, bed, bench, bottle, bowl, book, broccoli, cake, carrot, cell phone, chair, clock, couch, cup, dining table, donut, fire hydrant, fork, frisbee, hair drier, handbag, hot dog, keyboard, kite, knife, laptop, microwave, mouse, orange, oven, parking meter, pizza, potted plant, refrigerator, remote, sandwich, scissors, sink, skateboard, skis, snowboard, spoon, sports ball, stop sign, suitcase, surfboard, teddy bear, tennis racket, tie, toaster, toilet, toothbrush, traffic light, tv, umbrella, vase, wine glass |

The four groups exist for the forensic event categories (`PERSON`, `VEHICLE`, `PET`,
`OBJECT`) and the capture-scope filter. They do not change detection: the class name
and the score always come from the model. An animal is a `PET` everywhere in the
app; there is no second mapping table.

Detection is not limited to people. Any of the 80 classes above can produce a
forensic event, subject to the capture scope chosen in Digital Forensics.

Anything outside this list is not recognised at all. A face seen close up, a hand,
or an object type COCO never contained will either go undetected or be mapped to the
nearest class it does know — that is a property of the trained model, not a setting.

### Class labels

The model does not carry text labels. Graph B returns a 91-way logit vector; index 0 is
unused and indexes 1–90 are COCO category ids. Names come from the model's own
training mapping, `src/rfdetr/assets/coco_classes.py` in the upstream repository, and
the table in this app is a byte-for-byte transcription of it (verified: 0 differences
across all 91 slots).

Without that table the output would be numbers only, and there is no way to invent or
"improve" it — a category with no training data has no logit, so a model cannot be
asked to name more than it was trained on.

### Thresholds

| Setting | Value | Why |
|---|---|---|
| Score threshold | 0.45 | The reference configuration for this model family. |
| Per-class NMS IoU | 0.60 | Removes duplicate boxes for the same class. |
| Max detections | 12 | Bounds per-frame work; a home scene rarely has more. |
| Min box side | 0.003 | Rejects degenerate boxes. |

A low threshold is the classic cause of "it labels everything". Around 0.2, a large
number of weak hypotheses pass; 0.45–0.50 is the production range.

## Lifecycle

The model is **not** tied to a recording. It is loaded when a feature that needs it is
enabled, and released when none do.

`ObjectDetectionRuntime` owns the single process-wide instance:

- Started at app launch and whenever a setting that needs detection changes.
- Needed when motion is enabled **and** either the person-confirmed trigger or a
  forensic feature (evidence collection or live overlay) is on.
- Released when none of those need it.
- Loading runs on its own thread. The camera, encoder and UI never wait for it.
- Frames analysed before the model is ready are dropped, not queued.
- Only one inference is ever in flight; a slow device cannot build a backlog.

Because the model loads at feature-enable time, starting a recording — even
immediately after launch — never waits for it.

### Measured device performance

At load the runtime runs a short warm-up and keeps the **median** of five runs. The
first call includes shader compilation, so a single sample would misreport the device.

| Device | GPU | Steady-state inference |
|---|---|---|
| Pixel 7 Pro | Tensor G3 | ~146 ms |
| TECNO KG5k | Mali-G57 | ~1548 ms |

That is a tenfold spread, and it is a property of the hardware, not of the model or
the app.

### Detection interval

How often objects are recognised is a **user setting**, Motion Lab → Detection
interval:

| Value | Behaviour |
|---|---|
| Automatic (default) | Derived from the measured inference cost, kept to roughly a third of wall time so a slow GPU is not occupied continuously. |
| 200–10000 ms | Used exactly as entered. No hidden rounding or override. |

The value is read on every analysis pass, so a change applies immediately without
restarting the recording.

Because evidence capture needs detections, the detector is never switched off: a slow
device still stores evidence, it just samples less often. That is surfaced to the user
in the Digital Forensics screen rather than silently degraded.

### Frame orientation

Boxes are reported in the orientation the model analysed, and the overlay converts them
with the same angle the video is rotated by. One rotation, one definition.

### GPU requirement

LiteRT's GPU accelerator is required. There is **no CPU path**: the CPU runtime for
this model is far too slow to analyse a live camera feed, so instead of silently
producing a degraded experience the detector reports itself unavailable with the
real reason.

`AiObjectDetector#unavailableReason()` carries a user-facing message;
`isAvailable()` is false. Motion detection keeps working; only recognition is
unavailable.

### Frame orientation

The camera delivers a sideways buffer in portrait. The detector is trained on upright
images, so it is fed the frame sampled through the rotation that makes it upright, and
its boxes are converted back with `FrameRotation` — one pure, unit-tested class used by
both directions.

The rotation must be the one the **video** is rotated by, because that is the
orientation of the pixels on screen. Getting this wrong does not fail loudly: the model
still detects the subject, but every box is drawn somewhere else.

## Cost and tuning

| | EfficientDet-Lite1 (previous) | RF-DETR Nano (current) |
|---|---|---|
| Accuracy | 30.6 mAP | 48.4 mAP |
| Model size | 6 MB | 56 MB |
| Runtime | CPU | GPU |
| Inference | ~49 ms | 146 ms on a Pixel 7 Pro |

Detection is sampled, not continuous: at most one inference per second while
recording, two per second when idle, and only on frames that contain motion. The motion
score itself is computed on every analysed frame and does not depend on the model.

GPU options are tuned for a camera app:

- **Program cache** — the compiled GPU program is persisted in `codeCacheDir`, so a
  later process start restores it instead of recompiling. Requires the directory, a
  stable per-graph key and the toggle together; any one missing silently disables
  caching.
- **FP16** — matches the shipped weights and the accelerator's default on capable
  hardware.
- **Priority** is left at the engine default. Setting it low measured *slower*
  (224 ms against ~146 ms), because it lowers the GPU clock for the whole context
  rather than merely yielding to the camera.

### Recommended settings when the device struggles

1. Keep the analyzer resolution at 720p. 1080p roughly doubles motion work for no
   detection benefit at this model's 384 px input.
2. Keep the analysis rate at the default. Raising it costs CPU without improving
   detection, which is sampled on its own cadence.
3. Turn off the forensic live overlay when it is not being watched; it redraws a
   full-resolution overlay texture.
4. Use the any-motion trigger when person confirmation is not needed — it costs no
   GPU time at all.

## Where the model is used

- **Motion trigger** — `person_confirmed` mode requires a confident `person`
  detection before recording starts.
- **Forensic evidence** — detections become events with cropped snapshots.
- **Forensic live overlay** — detected boxes are drawn onto the recorded video.

## Replacing the model

The model files are the only model-specific assets. Replacing them means updating:

- the two `.tflite` files in `app/src/full/assets/models/`
- the input size, class count and label table in `RfDetrNanoDetector`
- the cache keys passed to `gpuOptions` so stale GPU programs are not reused
- the provenance links in `app/build.gradle.kts`

Nothing else in the app is model-specific.
