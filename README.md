# EricPDF · Offline Android lecture notebook

Native Android PDF study notebook, inspired by the **workflow** of PDF note-taking apps such as Flexcil (not affiliated with Flexcil). Designed for lecture slides, handwritten notes, and professor recordings with a **private, local-first** architecture.

## Implemented in this source project (v0.7.0)

| Feature | Implementation |
|---|---|
| PDF import | Android file picker (`ACTION_OPEN_DOCUMENT`); original copied to app-private storage |
| PDF reading | Full-height Android `PdfRenderer` reader, floating controls, pinch zoom, and two-finger horizontal swipe to change pages (only at fit zoom) |
| Folder library | Sidebar-free nested folders with PDF thumbnails, breadcrumb paths, global title/course/folder search, sorting, rename, move, and safe empty-folder deletion |
| PDF text search | PDFBox-Android extracts native text per page, returns clickable match pages; OCR NOT included |
| Ink | Pen (blue/red), yellow highlighter, stroke eraser, undo-last-stroke; normalized vector points persist in JSON |
| Bookmarks | Local persistent per-PDF page bookmarks |
| Notes | Autosaved typed note for every PDF page |
| Lecture audio | Android foreground microphone service, 16kHz 16-bit mono WAV; recording persists across activity backgrounding while OS permits |
| Automatic recording stop | Select 30 min, 1, 2, 3 or 4 hours in the Lecture Desk; the foreground service stops and finalizes the WAV at the selected audio duration |
| Lecture start reminder | Tap Schedule beside Record, choose a future local date/time and duration; Android notifies you to open the selected PDF and tap Record (background microphone auto-start is intentionally prohibited) |
| Windows desktop edition | PDF reader, scheduled starts, duration-based WAV auto-stop and optional Vosk offline captions; Windows EXE is built by a separate GitHub Actions workflow |
| Live transcript | Interim offline Vosk text updates during speech; completed phrases are saved with timestamps |
| Automatically written live notes | New **Live notes** tab shows provisional speech and permanently stores confirmed phrases with page and timestamp; export the notes as TXT |
| Background lecture mode | User starts foreground microphone service while app is visible; recording, transcription and note-writing can continue after switching apps, subject to Android system limits |
| After-lecture transcript | Re-transcribe a saved WAV with the matching Vosk model |
| Playback | Per-session WAV playback and seek to timestamp using transcript segments |
| Annotation timeline | Audio timestamp and current PDF page cues persisted during page changes/ink events |
| Transcript | Search, correct recognized text, jump to transcript's page/timestamp |
| Exports | Save annotated PDF (rasterized pages), export transcripts as `.txt`, save original lecture WAV files |
| Data locality | Original PDFs, audio recordings and notes remain local. Optional online mode sends short mic audio chunks via an authenticated HTTPS proxy to OpenRouter; Offline mode does not transmit audio. |
| Backup / restore | Folder tree metadata is included in ZIP archives; manual ZIP export/restore and optional automatic local backups to user-selected Android Documents folder (Storage Access Framework). Worker schedules after edits, after completed recording and every 6 hours; last 3 automatic snapshots kept. |

> **Status:** Pre-release developer build, not production-certified. GitHub Actions compiles a debug APK and runs JVM tests. Real phone / microphone / screen-lock tests are still required before relying on it for critical lectures.

## Timed recordings and scheduled start (v0.7.0)

**Android:** Open any PDF → pull out the floating **Lecture Desk** → choose **Auto-stop: 30m / 1h / 2h / 3h / 4h** → tap **Record** and confirm microphone permission. The recorder stops itself after the selected duration, finalizes the WAV and persists captions when an appropriate speech model is available. You can stop earlier from the recording control or Android notification.

For future lecture starts, tap the **schedule clock icon** in Lecture Desk. Choose the reminder date and time and planned duration. Grant notification permission when Android requests it. The reminder is scheduled with WorkManager, so Android battery settings and sleep may delay delivery; it is **not an exact alarm**. Tapping the notification opens the PDF; you must tap **Record** to activate the microphone. No silent recording takes place.

**Windows:** [EricPDF Windows installation and limitations](windows/README.md). The Windows EXE builds through [EricPDF Windows EXE Actions](https://github.com/Eric9435/Ericpdf/actions/workflows/windows.yml). Windows can start a previously armed scheduled recording while the app remains running (including in the system tray). The PC must be awake and the app must still be running.

## Navigation and ownership

- **Two-finger swipe left:** next PDF page; **two-finger swipe right:** previous page. At high zoom the swipe does not flip pages, so you can navigate the zoomed document. Pinch zoom and one-finger inking remain available.
- **No left sidebar:** the main Documents screen opens at the root. Create nested folders (e.g. TUHH > Microsystem), use breadcrumbs, search across all folders, and move PDFs using the item action menu.
- **Settings:** separate page for speech model, optional online proxy, manual/automatic backup.
- **About:** Developed by **innovateX** · Developer **Eric Scott @ Aung Phone Myat** · Contact **ericscott.de@gmail.com**. Current version is displayed from Android's installed package metadata.

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

## Optional Online transcription (v0.4.0)

**OpenRouter replaces the OpenAI API completely.** The audio is sent in ~6-second
batches to the authenticated Node proxy in `cloud-session-server/`, which forwards
it to `https://openrouter.ai/api/v1/audio/transcriptions`. This is near-live, not
continuous word-by-word streaming; the delay includes chunk length and networking.

OpenRouter's `openai/whisper-large-v3-turbo` model is an open-source Whisper
speech-to-text model offered through providers such as Groq/DeepInfra. The model
slug identifies its creator; you **do not need an OpenAI API account**.

Deploy the proxy with private environment variables `OPENROUTER_API_KEY` and
`ERICPDF_ACCESS_TOKEN` (24+ characters). Use
`https://YOUR-SERVICE.onrender.com/transcribe` in the app. OpenRouter usage
and Render hosting may cost money. The original PDF, WAV and notes remain local,
and Offline mode with Vosk still requires no network access.

**Limitation:** Cut words around the 6-second boundary can be inaccurate or
missing. If transcription fails, the WAV continues recording, but those
failed cloud segments will not automatically transcribe. Real-device tests
with long German/English lectures are required.

## Automatic local backups — no Firebase (v0.5.0)

The original working library remains private inside EricPDF, for fast annotation.
To protect it from **uninstall**, choose a **shared Documents folder** in the
Android file picker, such as `Documents/EricPDF Backups` (create one from the
picker). Do **not** choose Android/data or an app-private folder.

From the EricPDF home screen:

1. Tap **Choose backup folder** and choose/create a folder under **Documents**.
2. Tap **Use this folder** (Android's system folder-access approval).
3. EricPDF schedules a ZIP snapshot after data changes (coalesced to reduce
   battery/storage usage), at the end of a recording, and periodically every
   6 hours while Android permits background work. **Android may delay a
   scheduled snapshot** when the device is idle or conserving battery.
4. **Backup now** immediately makes a new ZIP snapshot, after recordings finish.
   You can confirm the **Last successful backup** date and look for files named
   `EricPDF-auto-YYYYMMDD-HHMMSS-SSS.zip` in your chosen folder.
5. Only after a **successful backup**, you can safely uninstall and reinstall
   EricPDF **without losing the backed-up state**. Never assume unbacked-up
   edits will survive uninstall!
6. After reinstall, pick the **same shared Documents folder** again. EricPDF
   will offer **Restore latest** when its working library is empty. You can
   also tap **Restore latest** manually or choose an existing ZIP through
   the original Restore picker. Existing document IDs are skipped, not overwritten.
7. ZIP files survive normal uninstall, provided the chosen storage provider
   keeps them. Android **revokes the old folder permission when the app is
   uninstalled**, so a new installation must select the folder again.
8. EricPDF retains at most **3** automatically created snapshot ZIPs (the
   manual exports are never deleted). This requires enough free device storage
   for the new ZIP in addition to existing backups. Vosk language models are
   intentionally excluded and may need reimport/download.

**No online sync:** Local ZIP backups are **not** cross-device replication,
and cannot survive phone loss, storage damage, factory reset, or deletion of
the Documents folder unless you separately copy them to another device.
**Not encrypted:** ZIP files include PDF documents, private notes and WAV
recordings. Store the chosen folder securely and do not share these files
unintentionally. Backups are best-effort, NOT synchronous per-keystroke
transactions; test restoring a ZIP before uninstalling.
