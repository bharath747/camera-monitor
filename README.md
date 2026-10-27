# Camera Monitor

Personal Android app for on-demand live camera viewing with no recording. The same APK is installed on both phones: one is the camera phone and one is the viewer phone.

## Architecture

- Android/Kotlin app with a foreground camera service.
- Firebase project: `family-location-tracker-8e2cd` (the same project used by `location-tracker`).
- Firestore stores WebRTC signaling only.
- Firebase Cloud Messaging delivers `START_LIVE` / `STOP_LIVE` commands to the camera phone.
- WebRTC sends video peer-to-peer; Firebase does not store video.
- GitHub Actions builds a debug APK artifact.

## Required Firebase setup

1. In Firebase Console, open the existing project `family-location-tracker-8e2cd`.
2. Add a **new Android app** with package name `com.bharath.cameramonitor`. Do not reuse the location-tracker Android app registration because the package names differ.
3. Download that new app's `google-services.json`.
4. In GitHub, open this repository's **Settings → Secrets and variables → Actions → New repository secret**.
   - Name: `GOOGLE_SERVICES_JSON`
   - Value: paste the complete contents of the downloaded JSON file.
   - Do not commit `google-services.json` to the repository.
5. In Firebase Authentication, enable the **Anonymous** sign-in provider.
6. Ensure Cloud Firestore is enabled.
7. The existing `location-tracker` Cloud Function `dispatchDeviceCommand` must already be deployed. It listens to `devices/{deviceId}/commands/{commandId}` and sends FCM for commands with status `PENDING`. This app registers its FCM token under `devices/camera-monitor-device`.
8. Add compatible Firestore rules for the new paths without replacing the existing rules. At minimum, authenticated app instances need to read/write:
   - `devices/camera-monitor-device` and its `commands` subcollection
   - `cameraMonitor/live/sessions` and the `viewerCandidates` / `cameraCandidates` subcollections within each session

**Security note:** Anonymous Authentication is convenient for a temporary personal app, but `request.auth != null` alone allows any anonymous user to sign in. Before using this outside a short personal test, restrict these Firestore paths more tightly and consider App Check. Do not open the entire database to public read/write access.

## Build APK

After adding `GOOGLE_SERVICES_JSON`, open **Actions → Build Camera Monitor APK → Run workflow**. The APK is uploaded as the artifact `camera-monitor-debug-apk`.

## First-time use

1. Install the APK on both Android phones.
2. On the camera phone, open the app, grant camera/notification permissions, and tap **Enable Remote Camera** while the app is visible. Leave the persistent notification running.
3. Disable aggressive battery optimization for Camera Monitor and allow background activity. Keep the phone charged and ventilated.
4. On the viewer phone, open the app and tap **Watch Live**.
5. Tap **Stop** in the viewer or **Stop Live** on the home screen to end the stream.

## Limitations to know

- Android may restrict camera foreground services if the process is killed and a remote command tries to restart it. For best results, start the foreground service manually on the camera phone before leaving it unattended.
- Power saving can delay or suppress background work on some phone brands.
- This first version uses public STUN servers only. Some mobile networks require a TURN relay, so peer-to-peer connection may fail on certain networks.
- This app does not record video.
- The first successful build and a multi-hour test are required before relying on it for an unattended 3–4 day session.
