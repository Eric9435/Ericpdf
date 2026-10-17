# EricPDF Windows Edition (preview)

A native Windows PDF reading and lecture audio scheduler companion to the existing EricPDF Android app. No account, background server, AI key, or cloud connection is needed.

## Windows features

- Open and view multipage PDFs with previous/next page controls and adjustable zoom.
- Save recent PDFs locally (the original document is never uploaded).
- Choose a recording duration: **30 minutes, 1, 2, 3, 4 or 6 hours**.
- Start microphone capture manually and **auto-stop** when the duration expires.
- Arm a scheduled start at a chosen **local date and time** after confirming you have permission to record.
- Continue a scheduled or active session in the system tray while the window is minimized.
- Optionally select an **offline Vosk EN/DE model folder** for live captions and timestamped transcript TXT output.
- Lecture audio is saved as **16-kHz, mono WAV** in `%USERPROFILE%\Documents\EricPDF`. Text transcripts are saved beside the WAV file.
- No silent recording: the app shows recording status, a Stop control, and Windows microphone permission/usage indicators.

## Download and run

1. Go to [GitHub Actions](https://github.com/Eric9435/Ericpdf/actions/workflows/windows.yml).
2. Open the latest **successful** `EricPDF Windows EXE` run.
3. Download the `EricPDF-Windows-exe` artifact, unzip it and open `EricPDF-Windows.exe`.
4. Windows may warn about an **unsigned** development build. Verify that you downloaded it from the owner's GitHub run before allowing it.
5. For captions, obtain a Vosk small model for the lecture language, extract it, then click **Choose Vosk model folder** and select the directory containing `am`. Recording still works without a model, but captions do not.

## Source development

Requires Windows, Python 3.11, and a working microphone.

```powershell
py -3.11 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r windows\requirements.txt
python windows\ericpdf_windows.py
```

Package the single-file EXE:

```powershell
pip install pyinstaller
pyinstaller --noconfirm --clean --onefile --windowed --name EricPDF-Windows --collect-all vosk --collect-all sounddevice windows\ericpdf_windows.py
```

## Limitations

- This is a **Windows preview**, not complete annotation parity with the Android PDF notebook. Multi-page PDF viewing, audio, scheduling, and offline captions are implemented; drawing/inking and shared Android library syncing are not.
- Keep the Windows application running or minimized to the tray for future automatic start. A powered-off, suspended, or forcibly terminated computer cannot start a scheduled recording.
- Captions depend on the selected Vosk language model and microphone quality. Recognition is unverified until corrected.
- Long WAV recordings occupy substantial local space: roughly 115 MB/hour at 16 kHz 16-bit mono.
- Not code-signed or device-certified. Test a short recording before important lectures.
- Obtain consent before recording. In Germany, recording non-public speech without authorization can violate §201 StGB.
- Local WAV and TXT files are **not encrypted**. Secure your Windows user account and backups.

Developed by **innovateX** · Developer **Eric Scott @ Aung Phone Myat** · Contact **ericscott.de@gmail.com**
