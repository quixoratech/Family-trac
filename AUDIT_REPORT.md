# FamilyTrack BD — APK + source archive audit

Audit date: 2026-11-25

## Inputs inspected

- Source ZIP: `FamilyTrack-BD-Final.zip` (98 ZIP entries; 98 regular files after extraction).
- APK: `FamilyTrackBD.apk` (74,200,617 bytes).
- APK SHA-256: `d1015b1e1248c12ff744dd6785ff9a3f6660221202ac4892fe45a4256ab2f1d5`.
- APK archive integrity: passed (no corrupt ZIP entries).
- Source ZIP archive integrity: passed (no corrupt ZIP entries).

## APK verification

- APK contains 13 DEX files and native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- APK Signing Block v2 is present and the signer certificate was parsed.
- Signer certificate SHA-1: `5691e69c60e98f76581d02dc4a00dc54079ce94d`.
- The signer SHA-1 matches the Android OAuth certificate hash configured in `app/google-services.json`.
- The APK DEX contains the package ID `com.aistudio.familytrackbd.mrvkqz` and the configured Admin email.
- The source Gradle `applicationId` and Firebase JSON package name both match `com.aistudio.familytrackbd.mrvkqz`.

The APK is structurally valid and its signing certificate matches the Firebase Android OAuth configuration. This does not prove successful installation, launch, or every runtime feature.

## Admin authorization

- App-side authorization constant in `MainViewModel.kt` is `quixoratech@gmail.com`, normalized with trim + lowercase comparison.
- Firestore rules independently compare the authenticated email against `quixoratech@gmail.com` after lowercasing.
- Admin role creation and privileged family/device operations are guarded in the app and Firestore rules.

## Changes in this revision

1. Simplified Family Mode navigation to **Chats** and **Calls** only. Admin-only dashboard, devices, maps, history, network activity, Family Hub, Nearby, and settings are not shown in Family Mode navigation.
2. Added a Tic-Tac-Toe action beside the voice-message button in the chat composer.
3. When launched from a one-to-one chat, Tic-Tac-Toe automatically sends an invitation to that chat's other member. Group chats open the game screen without an automatic opponent.
4. Kept voice notes inside the chat composer rather than exposing a separate Family Mode voice-message tab.
5. Updated README with the role split and game behavior.

## Feature inventory found in source

- Admin dashboard, paired-device list, live map, seven-day location history, connectivity/network activity history, and activity center.
- Consent-based location sharing, foreground location service, pause/revoke handling, offline queue and background sync.
- Family messaging, typing/presence/last-seen display, voice notes, media/document attachments, message reactions/replies/edit/delete, voice calling, and multiplayer Tic-Tac-Toe.
- Existing shared-family tools and nearby communication code remain in the source project, but their routes are not exposed in Family Mode navigation.

## Validation limits

- XML/JSON parsing and ZIP CRC/integrity checks pass.
- APK signature block and configured certificate hash were checked.
- A full Android Gradle build, Kotlin compilation, Firebase Emulator Suite run, APK installation, and real-device runtime tests were not run in this environment. The source archive does not include `gradlew` or `gradle-wrapper.jar`, and no system Gradle/Android SDK/ADB is available here.
- The uploaded APK is the pre-edit binary. The chat-composer Tic-Tac-Toe integration and simplified Family Mode navigation are source changes in this ZIP and require a new Android build before those changes appear in an APK.

## Required final device validation

1. Open the project in Android Studio and sync Gradle dependencies.
2. Run unit tests and build a debug APK.
3. Install on an Android 14 device; test Google sign-in with the authorized Admin email and a different Family email.
4. Verify Family Mode exposes only Chats and Calls; open a direct chat and test voice notes, call flow, and the inline Tic-Tac-Toe invitation.
5. Test consent grant/revoke, location pause, offline queue/reconnect, and network activity history.
6. Run Firestore rules tests in the Firebase Local Emulator Suite before deploying rules.

## Platform limitation

Android background execution and network callbacks are subject to OS power management, permission state, and process lifecycle. Network activity history is best-effort and must not be represented as guaranteed exact online/offline detection when the OS has not delivered an event.
