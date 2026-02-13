# Motion Lab Person Model

Place your model file here as:

`person_detector.tflite`

Expected path in app assets:

`app/src/main/assets/models/person_detector.tflite`

## Recommended open-source model

Use TensorFlow Lite EfficientDet-Lite0 (metadata) from TF Hub.

TF Hub page:
- https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1

Direct TFLite download:
- https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite

## Notes

- Keep filename exactly `person_detector.tflite`.
- Motion Lab falls back to `Any Motion` if model is missing/unavailable.
- For best accuracy, pair with COCO labels where person class is index 0 or 1 depending exporter.
