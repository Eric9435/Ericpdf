# EricPDF — Android lecture notebook

**Source repository:** A native Android Kotlin/Jetpack Compose project for private, offline university study. The source package includes PDF import, annotation, search, page notes, lecture WAV recording, optional on-device Vosk speech transcription in English/German, and local exports. This is a **pre-release project**, not a verified production APK.

## Current GitHub status

The complete source archive was transferred in eight hash-verified files under the transfer/ directory, and an APK-building GitHub Actions workflow was installed. The workflow can restore readable project source into the repository, but **its first run failed before any steps ran**. No APK is available from that failed run. Inspect your GitHub Actions run for any account-level Actions or billing restrictions.

A fallback unpacking script is available at scripts/unpack_source.py.

## Restore readable source files if GitHub Actions cannot start

Clone or download the repository on a machine with Python 3.12+ and run:

    python -m pip install brotli
    python scripts/unpack_source.py

The script checks the compressed source archive SHA-256 before extracting original project files into the repo root. You can then commit those source files or open the project in Android Studio. The complete source ZIP was also provided in your ChatGPT conversation.

## Build APK

Prerequisites: Android SDK 36, JDK 17, Gradle 8.13.

    gradle :app:assembleDebug :app:testDebugUnitTest

If successful, the APK is at app/build/outputs/apk/debug/app-debug.apk. A Gradle wrapper JAR is not bundled; the workflow provisions Gradle.

## Privacy and limitations

- The app source uses app-private Android storage and does not request INTERNET permission.
- Speech transcription needs a separately imported local Vosk model and will not be 100% accurate.
- Annotated PDF exports flatten pages, so the output text may not remain selectable.
- Obtain lecturer permission before recording speech and follow university rules.
- Android compilation, practical microphone testing, and regression testing are not yet verified.
