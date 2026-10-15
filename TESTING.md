# Device acceptance checklist (not yet performed)

Run on an Android tablet and phone (Android 8, 12, 14/15 and 16 if possible).

## Build gates

- [ ] GitHub Actions `gradle :app:assembleDebug :app:testDebugUnitTest` passes
- [ ] Install debug APK and open library without runtime crashes
- [ ] Android accessibility checks: touch targets, screen reader, rotations and tablets
- [ ] Performance profile with 200-page lecture slides and large PDFs

## Import and PDF

- [ ] Import a PDF, verify page count, last page and app restarts
- [ ] Reject non-PDF files and corrupt PDFs without orphaning files
- [ ] Test scanned/image-only PDFs: text search must not fabricate results
- [ ] Search native-text PDFs with German/English text
- [ ] Verify PDF page navigation, bookmarks, magnification and portrait/tablet layout
- [ ] Test ink strokes/highlighter/eraser/undo after app process kill
- [ ] Verify original PDF is unmodified; annotated export accurately matches colors and strokes
- [ ] Verify flattened PDF export size, lower memory footprint and page limits

## Recording and transcription

- [ ] Deny RECORD_AUDIO; recording must not start and app must not crash
- [ ] Grant permission, start/stop recording, check mic foreground notification
- [ ] Test during screen lock, task switching, call interruption, phone app mic use, and low storage
- [ ] Record 90+ minutes and verify WAV header, duration and complete playback
- [ ] With no model, record WAV and transcribe later after EN model import
- [ ] With EN model, recognize live English and verify timestamps near speech boundaries
- [ ] With DE model, verify German accents and punctuation limitations
- [ ] Test model ZIP extraction safety (zip-slip, zip bomb, invalid model)
- [ ] Test seeking to transcript timestamps and synchronized PDF page cues
- [ ] Edit recognized words and verify text persistence after app restart
- [ ] Export WAV and TXT and read them in external apps
- [ ] Verify delete removes a session's audio, metadata and cues without touching other sessions

## Data and privacy

- [ ] No app network requests or INTERNET permission
- [ ] Files remain accessible within application sandbox with storage permissions denied
- [ ] App uninstall/clear-data behavior communicated clearly
- [ ] No audio begins before permission and visible record action
- [ ] Verify sensitive lectures are not automatically cloud-backed up
