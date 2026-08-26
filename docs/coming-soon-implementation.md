# Production feature implementation

All 13 former Coming Soon production tools are implemented as real, usable tools and are wired at runtime through `ProductionFeatureUnlocker` into `ProductionFeatureHubDialog`.

- Thermal Guardian — live battery-temperature monitoring with a configurable recording safety stop.
- Audio Vision — microphone threshold detection with automatic recording start.
- Scheduled Recording — one-time start/stop scheduling using exact Android alarms and the required Alarms & reminders access.
- Profiles — typed local recording-preference snapshots with save/load support.
- Sound Meter — live relative microphone-level meter.
- Sensor Dashboard — live accelerometer, gyroscope and magnetometer data.
- Speedometer — GPS speed in km/h with accuracy feedback.
- Clinometer — accelerometer pitch/roll measurement.
- Compass — accelerometer + magnetometer heading.
- Pedometer — hardware step counter with Android activity-recognition permission handling.
- Metal Detector — magnetic-field strength in µT.
- Parking Marker — GPS parking save/recovery with map-app navigation.
- QR Generator — offline QR generation.

The Settings UI no longer presents these tools as Coming Soon/Soon. The runtime unlocker removes those badges and makes the corresponding feature cards open the functional hub.

Verification is performed by the release pipeline before the feature branch is considered mergeable: unit tests, release compilation, lint, APK integrity, package/signature/alignment checks and release-manifest hardening all must pass.
