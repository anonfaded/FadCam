# Digital Forensics Detector Model

Required model filename:

`efficientdet_lite1.tflite`

Expected path in app assets:

`app/src/main/assets/models/efficientdet_lite1.tflite`

## Recommended source

Use TensorFlow Lite EfficientDet-Lite1 (metadata) from TF Hub.

TF Hub page:
- https://tfhub.dev/tensorflow/lite-model/efficientdet/lite1/detection/metadata/1

Direct TFLite download:
- https://tfhub.dev/tensorflow/lite-model/efficientdet/lite1/detection/metadata/1?lite-format=tflite

## Notes

- Keep filename exactly `efficientdet_lite1.tflite`.
- This is the only supported runtime detector in the current architecture.
- COCO class mapping is used for event typing (`PERSON`, `VEHICLE`, `PET`, `OBJECT`).
