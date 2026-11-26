# FamilyTrack BD

Consent-based Android family app with offline-first location sync, connectivity history, WhatsApp-style family messaging, voice notes, calls, and multiplayer Tic-Tac-Toe.

## Upload this project to GitHub

For phone-based upload instructions, see [`MOBILE_BUILD_GUIDE_BN.md`](UPLOAD_TO_GITHUB_BN.md). The intended repository is `quixoratech/Family-trac`. Upload the extracted project files to the repository root, then create `.github/workflows/android-apk.yml` if the mobile file picker hides dot-folders. Do not upload the ZIP as a single repository file.

## Build the Android app

**Requirements**
- Android Studio compatible with the Android Gradle Plugin and Gradle versions declared in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`.
- JDK 21 (or the JDK version required by the installed Android Gradle Plugin).
- Android SDK Platform 36.1 and the matching build tools.

**Steps**
1. Open this project folder in Android Studio.
2. Allow Android Studio to sync and download the declared Gradle and Maven dependencies.
3. Set up Firebase for the package ID `com.aistudio.familytrackbd.mrvkqz`; ensure the configured Firestore named database exists and that `firestore.rules` is deployed to that database.
4. Keep `app/google-services.json` matched to the same Firebase project and Android package ID.
5. For a debug build, use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The Android Gradle Plugin's default debug signing is used; no project-local `debug.keystore` is required.
6. For a release build, provide a valid upload keystore and the environment variables `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD`. Do not commit the keystore or passwords.

## Role-specific interface

- The authorized Admin account is `quixoratech@gmail.com` (case-insensitive comparison). Admin authorization is checked in the app and enforced again by Firestore security rules.
- Family Mode navigation is intentionally limited to Chats and Calls. Family Hub, Nearby, device tracking, location history, and network-activity screens are not exposed in Family Mode navigation.
- Voice notes stay in the chat composer. The Tic-Tac-Toe control is beside the voice-message control; from a direct chat it automatically sends an online game invitation to that chat member. From a group chat it opens the game screen without choosing an opponent.
- Admin Mode retains the administration and device-management screens.

## Firebase / secrets

- `.env.example` documents the optional Gemini key placeholder. Create a local `.env` only if a feature actually needs it; never commit real keys.
- Firebase client configuration is not a substitute for Firestore security rules. Deploy and test `firestore.rules` before using real family data.
- Firestore rules tests are in `firestore.test.js`. They require the Firebase Local Emulator Suite and the Node packages `firebase` and `@firebase/rules-unit-testing`.

## Consent and platform limits

Location and network-history collection must remain opt-in on each participating device. Android may delay or restrict background work, location updates, and foreground-service starts; therefore exact continuous availability or exact event timing cannot be guaranteed. The app must show its persistent location-sharing notification while location foreground tracking is active, and users can pause sharing from the app or notification.

## Project contents

- `app/src/main/java`: Android application source.
- `app/src/test` and `app/src/androidTest`: Android/unit tests.
- `firestore.rules` and `firestore.test.js`: database authorization rules and emulator tests.
- `firebase.json`: Firebase emulator/deployment configuration.

This source archive does not include a built APK or Gradle Wrapper executable/JAR. Open it in Android Studio to resolve dependencies and build locally.
