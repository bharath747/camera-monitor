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
8. On each phone, open the app once and copy the Firebase UID shown in the status. The camera phone and viewer phone will have different UIDs.
9. Merge rules like the following into your existing Firestore rules (do not replace the existing rules or open the whole database). Replace both UID placeholders with the two UIDs from your phones:

```javascript
function isCameraMonitorUser() {
  return request.auth != null
    && request.auth.uid in ['CAMERA_PHONE_UID', 'VIEWER_PHONE_UID'];
}

match /devices/camera-monitor-device {
  allow get, create, update: if isCameraMonitorUser();
  match /commands/{commandId} {
    allow create, get: if isCameraMonitorUser();
  }
}

match /cameraMonitor/live/sessions/{sessionId} {
  allow get, create, update: if isCameraMonitorUser();
  match /viewerCandidates/{candidateId} {
    allow get, list, create: if isCameraMonitorUser();
  }
  match /cameraCandidates/{candidateId} {
    allow get, list, create: if isCameraMonitorUser();
  }
}
```

**Security note:** Anonymous Authentication is convenient for a temporary personal app, but do not use a blanket `request.auth != null` rule because anyone can sign in anonymously. Restrict these paths to your two device UIDs as above. If you uninstall/reinstall or clear app data, an anonymous UID can change and the allowlist must be updated.

## Build APK

On each push, GitHub Actions runs a compile-only check if the Firebase secret is missing; it does not publish that placeholder-config APK. After adding `GOOGLE_SERVICES_JSON`, open **Actions → Build Camera Monitor APK → Run workflow**. The usable APK is uploaded as the artifact `camera-monitor-debug-apk`.

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
