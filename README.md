# EricPDF — Android offline lecture notebook

A native Android PDF annotation and lecture recording app, designed for local-first study. Features include PDF library, handwritten ink, bookmarks, notes, WAV lecture recordings, optional offline German/English transcription using Vosk, and PDF/audio/transcript exports.

The initial Android source project was prepared as `EricPDF-Android-source.zip`. This repository is being populated with the source code and GitHub Actions debug-APK workflow. Recording must follow university rules and lecturer consent.

## Build

The Android project uses Kotlin, Jetpack Compose, JDK 17, Gradle 8.13, and Android SDK 36. On successful CI build, download the artifact `EricPDF-debug-apk` from **Actions**.

Status: pre-release; APK compilation and physical-device testing pending.
