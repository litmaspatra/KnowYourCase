# KnowYourCase

KnowYourCase is an Android app for quickly looking up Indian court case details from a CNR number. It can scan a QR code or printed CNR, read a code from an existing photo, or accept manual entry.

## Download

Download the latest signed Android APK from the [GitHub Releases page](https://github.com/litmaspatra/KnowYourCase/releases/latest).

The app supports Android 8.0 and newer. After downloading the APK, open it and allow installation from your browser or file manager if Android asks.

## Features

- Scan a QR code or printed CNR using the camera
- Select a QR-code or CNR image from the phone gallery
- Enter a 16-character CNR manually
- Fetch case details from the official eCourts website in the background
- Show parties, advocates, case stage, next hearing, court, registration details, acts and sections
- Translate case details into Hindi with court-specific wording and phonetic Indian names
- Keep the 50 most recent complete results on the device for instant repeat searches
- Refresh a result manually, or automatically when its next-hearing date has passed
- Check and wake the backend with the red/green Wi-Fi status button
- Use consistent Pixelarticons artwork throughout the app and launcher
- Share case details from the result screen

## How to use

1. Open KnowYourCase.
2. Choose **Scan Document** or **Enter CNR Manually**.
3. Scan/select the document, or enter its 16-character CNR.
4. Wait while the app retrieves the case details. A recently saved result opens immediately.
5. Use **Show all details** for the complete record, **हिंदी** for Hindi, or **Refresh Case Details** to fetch the latest court record.

An internet connection is required for a case that has not already been cached. The first Hindi translation may download an approximately 30 MB language model.

## Privacy

Recent case results are cached only on the Android device. The app connects to the official eCourts website and uses the configured KnowYourCase backend for CAPTCHA solving and, when required, result parsing. Do not use the app on a device or network you do not trust.

## Important notice

KnowYourCase is an independent convenience tool. It is not affiliated with, endorsed by, or an official service of eCourts, the e-Committee of the Supreme Court of India, or any court. Information can be delayed, incomplete, or unavailable. Always verify important case information using the official court record. This app does not provide legal advice.

## Building from source

Requirements:

- Android Studio with JDK 17
- Android SDK 34
- An Android device or emulator running Android 8.0 or newer

Clone the repository, create `local.properties` with your Android SDK path, and build the debug APK:

```text
./gradlew assembleDebug
```

The backend URL is configured through `BuildConfig.BASE_URL` in `app/build.gradle`.

Release builds must be signed with your own Android signing key. Signing keys and passwords are intentionally excluded from this repository.
