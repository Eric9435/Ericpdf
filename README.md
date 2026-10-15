# EricPDF · Offline Android lecture notebook

Native Android PDF study notebook, inspired by the **workflow** of PDF note-taking apps such as Flexcil (not affiliated with Flexcil). Designed for lecture slides, handwritten notes, and professor recordings with a **private, local-first** architecture.

## Implemented in this source project (v0.3.0)

| Feature | Implementation |
|---|---|
| PDF import | Android file picker (`ACTION_OPEN_DOCUMENT`); original copied to app-private storage |
| PDF reading | Android `PdfRenderer`, one-page viewer, page navigation, zoom, jump-to-page |
| PDF text search | PDFBox-Android extracts native text per page, returns clickable match pages; OCR NOT included |
| Ink | Pen (blue/red), yellow highlighter, stroke eraser, undo-last-stroke; normalized vector points persist in JSON |
| Bookmarks | Local persistent per-PDF page bookmarks |
| Notes | Autosaved typed note for every PDF page |
| Lecture audio | Android foreground microphone service, 16kHz 16-bit mono WAV; recording persists across activity backgrounding while OS permits |
| Live transcript | Interim offline Vosk text updates during speech; completed phrases are saved with timestamps |
| Automatically written live notes | New **Live notes** tab shows provisional speech and permanently stores confirmed phrases with page and timestamp; export the notes as TXT |
| Background lecture mode | User starts foreground microphone service while app is visible; recording, transcription and note-writing can continue after switching apps, subject to Android system limits |
| After-lecture transcript | Re-transcribe a saved WAV with the matching Vosk model |
| Playback | Per-session WAV playback and seek to timestamp using transcript segments |
| Annotation timeline | Audio timestamp and current PDF page cues persisted during page changes/ink events |
| Transcript | Search, correct recognized text, jump to transcript's page/timestamp |
| Exports | Save annotated PDF (rasterized pages), export transcripts as `.txt`, save original lecture WAV files |
| Data locality | No account, no analytics, no backend, no `INTERNET` permission, no cloud upload |
| Backup / restore | Manual local ZIP export and import of PDFs, annotations, typed notes and WAV/transcript sessions; Vosk language models excluded |

> **Status:** Pre-release developer build, not production-certified. GitHub Actions compiles a debug APK and runs JVM tests. Real phone / microphone / screen-lock tests are still required before relying on it for critical lectures.

## Requirements

- Android 8.0 (API 26) or higher; target API 35.
- Android Studio (any release supporting AGP 8.13.x, Kotlin 2.2.20), JDK 17 and Android SDK 36.
- Gradle 8.13. The ZIP does not contain a Gradle wrapper JAR; use a local Gradle 8.13 installation or execute `gradle wrapper --gradle-version 8.13` once, then build using `./gradlew`.

```bash
# In the source root, with Android SDK and Gradle installed:
gradle :app:assembleDebug :app:testDebugUnitTest
# Result:
# app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, push the project to GitHub and run the included **Android debug APK** workflow under Actions. On successful compilation, retrieve the APK from the `EricPDF-debug-apk` workflow artifact.

## First-run tutorial

1. Launch EricPDF and tap **Import PDF**. Pick a lecture PDF from Android's Files app; optionally set the course label first.
2. Open the document. Select **Pen**, **Highlight** or **Erase**. Your strokes are saved on the selected page; **Read** mode allows pinch zoom.
3. To transcribe professor speech **entirely offline**, download a supported small Vosk model ZIP with your browser once:
   - English: [vosk-model-small-en-us-0.15.zip](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip)
   - German: [vosk-model-small-de-0.15.zip](https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip)
   Model listings and licenses: https://alphacephei.com/vosk/models
4. In EricPDF's home screen, select **English ASR** or **Deutsch ASR**, tap **Speech model**, and select the downloaded ZIP (do **not** manually unzip it). The import expands the model under the app's private files.
5. Return to the PDF and tap **Record**. **Obtain your lecturer's permission before recording.** The app asks for microphone permission and places a persistent recording notification. Annotate the PDF as the professor speaks.
6. Tap **Stop**. Open **Transcript**. You can play the lecture, select recognized phrases to jump to the recording/time/page, fix transcript errors, and save as TXT or WAV. If no model was available during recording, install it then select **Transcribe offline**.
7. Tap the share/export icon in the reader toolbar to export a visually annotated PDF.

## Local backup and restore

Use **Back up** on the library screen to create an `EricPDF-backup-*.zip` archive using Android's file picker. This includes original PDFs, handwritten ink, page notes, bookmarks, recorded WAV audio, transcripts, and page timeline cues. Use **Restore** to import that ZIP later; documents whose IDs already exist are skipped, not overwritten. Imported speech models are excluded. Test restoration before removing the app, and keep a backup outside of app-private storage. **The ZIP is not encrypted** and may contain sensitive lecture recordings; store it securely.

## Storage details

All app records are under `Context.getFilesDir()/ericpdf/` (not visible to other ordinary apps; removing this app normally deletes them):

```
ericpdf/
  library/{document-uuid}/
    document.pdf                 original, unmodified
    document.json                title / course / last page
    notes.json                   page notes
    bookmarks.json               marked pages
    ink/{page}.json              vector ink points for each page
    audio/{session-uuid}.wav     PCM microphone recording
    audio/{session-uuid}.json    transcript and timestamps
    audio/{session-uuid}-cues.json page/annotation timing markers
    audio/{session-uuid}-live-notes.txt automatic lecture notes
  models/en/                    installed offline English model (optional)
  models/de/                    installed offline German model (optional)
```

**Important:** Source PDFs are never modified. Exported PDFs are **rasterized**, which usually removes selectable/searchable text and can produce a larger file. Typed page notes are stored in JSON (they are not embedded in the exported PDF). Back up original PDFs, recordings, and exports before uninstalling or clearing application data. Auto backups/cloud syncing are intentionally disabled.

## Privacy, recording law and transcription limits

- Audio capture only starts through a visible Record action and uses Android's foreground microphone notification.
- **Germany:** Under §201 StGB, unauthorized recording of non-publicly spoken words can be unlawful. Ask the professor and comply with TUHH lecture policy before recording. https://www.gesetze-im-internet.de/stgb/__201.html
- The recognition engine does **not** perform speaker diarization. Text shown is recognized speech, not verified to be spoken by a specific person.
- English and German use separate models; selecting one does **not** recognize both languages simultaneously. Technical words, accents, equation dictation and noisy lecture halls can lower accuracy. Correct transcripts manually.
- Vosk small models typically use around 300 MB of runtime RAM. They are not bundled with the APK; import only trusted model ZIP files.
- No microphone capture occurs while a saved WAV is being re-transcribed.
- This is not an exact copy of Flexcil. Their proprietary design and gestures are not reused.

## Known limitations / next development priorities

- Android device build and microphone/long-lecture QA have **not** been run.
- No lasso selection, shapes, text boxes on PDFs, pressure sensitivity, smooth freehand ink prediction, infinite notebook, PDF merge/split/reorder, multi-document split view, outline tree, PDF text selection/copy, PDF OCR, or cloud sync.
- Handwritten stroke timing cues exist but are not currently animated during audio playback.
- Notes are per-PDF page, not independent notebooks. Exported PDF flattens annotations into page images.
- Transcript timestamps are phrase-level; postprocessed page links use recorded navigation cues (may be approximate).
- Models are installed per language; model switch while a recording is active only applies to the next session.
- The WAV format is limited to 4 GiB per file; very long recordings should be split (not yet implemented).
- No migrations, encrypted-at-rest storage, automated restore, or full-device acceptance tests in this source version.

## Repository

Official source: [Eric9435/Ericpdf](https://github.com/Eric9435/Ericpdf). Under **Actions → EricPDF Android APK**, download the artifact from a successful workflow run; unzip it to install the debug APK. No cloud login is needed inside the Android app.

## Licensing and dependencies

This original project source is provided for customization. Third-party dependencies retain their own licenses: AndroidX/Jetpack, Vosk Android 0.3.47 (Apache 2.0), PDFBox-Android 2.0.27.0 (Apache 2.0). The speech models are user-supplied; check each selected model's license independently. No Flexcil code or assets are included.

## Live notes transparency

**Live notes** are timestamped recognized phrases associated with the current PDF page, not an AI-generated conceptual summary. Provisional recognition may change; only finalized phrases are saved. The offline model is needed for both transcription and automatic notes. Recording may continue with the screen locked after the user starts it while the app is visible, but system power restrictions or microphone contention may interrupt it. Android displays a persistent recording notification with a Stop control. Recording without a speech model still saves WAV audio only.

## Optional Online transcription (v0.3.0)

Select **Online** from Library settings. This streams microphone audio over encrypted WSS to a speech provider via an ephemeral credential; PDFs, WAV recordings and transcript files remain in app-private storage. Requires a separately hosted authenticated token issuer in `cloud-session-server/`, an OpenAI API key stored only in the server's environment, and API billing. Enter its HTTPS `/session` URL and access code in the app. The access code is kept in runtime memory, not saved. Offline Vosk remains selectable. If the cloud connection fails, WAV recording continues locally without live cloud captions. Obtain lecturer permission before recording. Realtime transcripts may be inaccurate. See the separate server setup instructions and account usage limits.
