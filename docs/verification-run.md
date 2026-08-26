# Verification run

The production feature branch is verified through the same release pipeline used for delivery.

The verification sequence is:

1. Compile the exact `DefaultRelease` variant and run its unit tests.
2. Run the Studio release lint audit.
3. Select and checksum the universal release APK.
4. Verify the APK container and required files.
5. Verify package ID, release signature, non-debug certificate and ZIP alignment.
6. Verify the release manifest hardening rules.
7. Publish the APK only from a verified `master` build.

The former Coming Soon production tools are implemented in `ProductionFeatureHubDialog`; no manifest registration for that dialog is required because it is opened directly from the host activity. The default manifest retains only the permissions/components actually required by the production build, including exact-alarm and activity-recognition permissions.
