"""EricPDF Windows — private local PDF reader and timed lecture recorder."""
from __future__ import annotations

import json
import queue
import sys
import threading
import time
import wave
from datetime import datetime
from pathlib import Path

import fitz
import sounddevice as sd
from PySide6.QtCore import Qt, QDateTime, QObject, QSettings, QTimer, Signal
from PySide6.QtGui import QAction, QImage, QPixmap
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QDateTimeEdit, QFileDialog, QFrame,
    QHBoxLayout, QLabel, QListWidget, QMainWindow, QMessageBox, QPushButton,
    QScrollArea, QSlider, QSplitter, QSystemTrayIcon, QTextEdit, QVBoxLayout,
    QWidget,
)

APP_NAME = "EricPDF"
SAMPLE_RATE = 16000
DEFAULT_DURATION_MIN = 180
DATA_DIR = Path.home() / "Documents" / "EricPDF"
DATA_DIR.mkdir(parents=True, exist_ok=True)


def display_time(seconds: float) -> str:
    n = max(0, int(seconds))
    return f"{n // 3600:02d}:{(n // 60) % 60:02d}:{n % 60:02d}"


class Recorder(QObject):
    """One 16-kHz microphone stream feeds both WAV saving and optional offline ASR."""
    caption = Signal(str)
    status = Signal(str)
    finished = Signal(str)

    def __init__(self) -> None:
        super().__init__()
        self.active = False
        self.started_at = 0.0
        self.thread: threading.Thread | None = None
        self.chunks: queue.Queue[bytes] = queue.Queue(maxsize=500)

    def start(self, path: Path, model_folder: str) -> bool:
        if self.active:
            return False
        self.active = True
        self.started_at = time.monotonic()
        self.chunks = queue.Queue(maxsize=500)
        self.thread = threading.Thread(
            target=self._capture, args=(path, model_folder), daemon=True,
            name="EricPDF-lecture-capture",
        )
        self.thread.start()
        return True

    def stop(self) -> None:
        self.active = False

    def _capture(self, path: Path, model_folder: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        transcript = []
        recognizer = None
        last_partial = ""
        try:
            if model_folder:
                try:
                    from vosk import KaldiRecognizer, Model, SetLogLevel
                    SetLogLevel(-1)
                    model_path = Path(model_folder)
                    if not (model_path / "am").is_dir():
                        self.status.emit("Invalid Vosk model folder; recording WAV without captions.")
                    else:
                        self.status.emit("Loading offline speech recognition model...")
                        recognizer = KaldiRecognizer(Model(str(model_path)), SAMPLE_RATE)
                        recognizer.SetWords(True)
                except Exception as exc:
                    self.status.emit(f"Captions unavailable: {exc}")

            def callback(indata, frames, time_info, status) -> None:
                if status:
                    self.status.emit(f"Microphone: {status}")
                try:
                    self.chunks.put_nowait(bytes(indata))
                except queue.Full:
                    self.status.emit("Audio processing backlog — check your PC performance.")

            with wave.open(str(path), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(SAMPLE_RATE)
                with sd.RawInputStream(
                    samplerate=SAMPLE_RATE, channels=1, dtype="int16",
                    blocksize=4096, callback=callback,
                ):
                    self.status.emit("Recording • WAV saved locally")
                    while self.active or not self.chunks.empty():
                        try:
                            chunk = self.chunks.get(timeout=0.15)
                        except queue.Empty:
                            continue
                        output.writeframesraw(chunk)
                        if recognizer is not None:
                            if recognizer.AcceptWaveform(chunk):
                                result = json.loads(recognizer.Result())
                                phrase = result.get("text", "").strip()
                                if phrase:
                                    ts = display_time(time.monotonic() - self.started_at)
                                    transcript.append(f"[{ts}] {phrase}")
                                    self.caption.emit(phrase)
                            else:
                                partial = json.loads(recognizer.PartialResult()).get("partial", "").strip()
                                if partial != last_partial:
                                    last_partial = partial
                                    self.caption.emit(partial or "Listening...")
                    if recognizer is not None:
                        phrase = json.loads(recognizer.FinalResult()).get("text", "").strip()
                        if phrase:
                            transcript.append(f"[{display_time(time.monotonic() - self.started_at)}] {phrase}")
                            self.caption.emit(phrase)
            if transcript:
                path.with_suffix(".txt").write_text(
                    "\n".join(transcript) + "\n", encoding="utf-8"
                )
            self.finished.emit(f"Saved {path.name}" + (" + transcript" if transcript else ""))
        except Exception as exc:
            self.finished.emit(f"Recording error: {exc}")
        finally:
            self.active = False


class EricPDFWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.settings = QSettings("innovateX", APP_NAME)
        self.document = None
        self.pdf_path: Path | None = None
        self.page = 0
        self.zoom = 130
        self.recorder = Recorder()
        self.recorder.caption.connect(self.update_caption)
        self.recorder.status.connect(self.set_status)
        self.recorder.finished.connect(self.recording_finished)
        self.recording_path: Path | None = None
        self.is_recording = False
        self.target_duration = DEFAULT_DURATION_MIN * 60
        self.scheduled_at: datetime | None = None
        self.armed = False
        self._quitting = False

        self.setWindowTitle("EricPDF • Windows | innovateX")
        self.resize(1380, 860)
        self.setMinimumSize(890, 540)
        self._create_ui()
        self._create_tray()

        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(500)
        self.restore_schedule()
        recent = self.settings.value("last_pdf", "", type=str)
        if recent and Path(recent).is_file():
            self.open_pdf(Path(recent), silent=True)

    def _create_ui(self) -> None:
        root = QWidget()
        root.setObjectName("root")
        outer = QVBoxLayout(root)
        outer.setContentsMargins(16, 12, 16, 12)
        outer.setSpacing(10)

        head = QHBoxLayout()
        brand = QLabel("EricPDF")
        brand.setObjectName("brand")
        head.addWidget(brand)
        head.addWidget(QLabel("Windows Edition  •  Developed by innovateX"))
        head.addStretch()
        open_button = QPushButton("Open PDF")
        open_button.clicked.connect(self.pick_pdf)
        head.addWidget(open_button)
        outer.addLayout(head)

        split = QSplitter(Qt.Horizontal)
        left = QFrame()
        left.setObjectName("panel")
        sidebar = QVBoxLayout(left)
        sidebar.setSpacing(12)
        sidebar.addWidget(QLabel("LECTURE LIBRARY"))
        self.recent = QListWidget()
        self.recent.itemDoubleClicked.connect(
            lambda item: self.open_pdf(Path(item.text()))
        )
        sidebar.addWidget(self.recent)
        saved = self.settings.value("recent", [], type=list)
        self.recent.addItems([str(p) for p in saved if Path(str(p)).exists()])
        import_button = QPushButton("Add PDF to library")
        import_button.clicked.connect(self.pick_pdf)
        sidebar.addWidget(import_button)

        sidebar.addWidget(QLabel("AUDIO AUTO-STOP"))
        self.duration = QComboBox()
        for mins, label in [
            (30, "30 minutes"), (60, "1 hour"), (120, "2 hours"),
            (180, "3 hours"), (240, "4 hours"), (360, "6 hours"),
        ]:
            self.duration.addItem(label, mins)
        self.duration.setCurrentIndex(3)
        sidebar.addWidget(self.duration)

        self.record_button = QPushButton("●  Start recording")
        self.record_button.setObjectName("record")
        self.record_button.clicked.connect(self.toggle_record)
        sidebar.addWidget(self.record_button)
        self.elapsed = QLabel("00:00:00 / 03:00:00")
        sidebar.addWidget(self.elapsed)

        sidebar.addWidget(QLabel("SCHEDULED START  •  LOCAL TIME"))
        self.when = QDateTimeEdit()
        self.when.setCalendarPopup(True)
        self.when.setDisplayFormat("ddd, dd MMM yyyy  HH:mm")
        self.when.setDateTime(QDateTime.currentDateTime().addSecs(3600))
        sidebar.addWidget(self.when)
        self.arm_button = QPushButton("Schedule session")
        self.arm_button.clicked.connect(self.toggle_schedule)
        sidebar.addWidget(self.arm_button)
        self.schedule_status = QLabel("No scheduled recording")
        self.schedule_status.setWordWrap(True)
        sidebar.addWidget(self.schedule_status)

        sidebar.addWidget(QLabel("OFFLINE SPEECH CAPTIONS"))
        self.model_path = str(self.settings.value("vosk_model", "", type=str))
        model_button = QPushButton("Choose Vosk model folder")
        model_button.clicked.connect(self.pick_model)
        sidebar.addWidget(model_button)
        self.model_label = QLabel(
            self.model_path if self.model_path else "Optional: add EN or DE Vosk model"
        )
        self.model_label.setWordWrap(True)
        sidebar.addWidget(self.model_label)
        sidebar.addStretch()
        split.addWidget(left)

        center = QFrame()
        center.setObjectName("pdfPanel")
        reader = QVBoxLayout(center)
        page_nav = QHBoxLayout()
        prev_btn = QPushButton("◀ Previous")
        prev_btn.clicked.connect(lambda: self.go_page(-1))
        next_btn = QPushButton("Next ▶")
        next_btn.clicked.connect(lambda: self.go_page(1))
        self.page_label = QLabel("Open a PDF to begin")
        self.zoom_slider = QSlider(Qt.Horizontal)
        self.zoom_slider.setRange(75, 250)
        self.zoom_slider.setValue(130)
        self.zoom_slider.valueChanged.connect(self.zoom_changed)
        page_nav.addWidget(prev_btn)
        page_nav.addWidget(self.page_label, 1, Qt.AlignCenter)
        page_nav.addWidget(next_btn)
        page_nav.addWidget(QLabel("Zoom"))
        page_nav.addWidget(self.zoom_slider)
        reader.addLayout(page_nav)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setAlignment(Qt.AlignCenter)
        self.page_image = QLabel("Your PDF pages will appear here")
        self.page_image.setAlignment(Qt.AlignCenter)
        self.page_image.setObjectName("pdfCanvas")
        self.scroll.setWidget(self.page_image)
        reader.addWidget(self.scroll)
        split.addWidget(center)
        split.setStretchFactor(0, 0)
        split.setStretchFactor(1, 1)
        split.setSizes([320, 1020])
        outer.addWidget(split)

        self.caption_box = QTextEdit()
        self.caption_box.setReadOnly(True)
        self.caption_box.setFixedHeight(100)
        self.caption_box.setPlaceholderText(
            "Live transcript appears here after selecting an offline Vosk speech model."
        )
        outer.addWidget(QLabel("LIVE CAPTIONS"))
        outer.addWidget(self.caption_box)
        self.status_bar = QLabel(
            "Local first · PDF and audio stay on your computer. Obtain permission before recording a lecture."
        )
        outer.addWidget(self.status_bar)
        self.setCentralWidget(root)
        self.setStyleSheet("""
            QMainWindow, QWidget#root { background:#f3f6fb; color:#1b2b43; }
            QLabel#brand { font-size:25px; font-weight:800; color:#1b4a98; }
            QFrame#panel, QFrame#pdfPanel { background:white; border:1px solid #dce4f0; border-radius:13px; }
            QLabel#pdfCanvas { background:#e8edf5; padding:15px; color:#6c7890; }
            QPushButton { border:1px solid #cbd7e9; border-radius:8px;
                padding:9px 12px; background:#eef4ff; font-weight:600; }
            QPushButton:hover { background:#dceafe; }
            QPushButton#record { color:white; background:#205ad1; border:0; }
            QPushButton#record:hover { background:#1746a6; }
            QComboBox, QDateTimeEdit, QListWidget, QTextEdit {
                background:white; border:1px solid #cfd9e8;
                border-radius:7px; padding:7px; }
            QSplitter::handle { background:#f3f6fb; width:10px; }
        """)

    def _create_tray(self) -> None:
        self.tray = QSystemTrayIcon(self)
        self.tray.setIcon(self.style().standardIcon(
            self.style().StandardPixmap.SP_MediaVolume
        ))
        from PySide6.QtWidgets import QMenu
        menu = QMenu()
        show_action = QAction("Show EricPDF", self)
        show_action.triggered.connect(self.showNormal)
        menu.addAction(show_action)
        quit_action = QAction("Quit EricPDF", self)
        quit_action.triggered.connect(self.quit_app)
        menu.addAction(quit_action)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(
            lambda reason: self.showNormal()
            if reason == QSystemTrayIcon.ActivationReason.DoubleClick else None
        )
        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray.show()

    def set_status(self, message: str) -> None:
        self.status_bar.setText(message)

    def update_caption(self, line: str) -> None:
        if line.strip():
            self.caption_box.setPlainText(line)

    def pick_pdf(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Open lecture PDF", str(Path.home()), "PDF files (*.pdf)"
        )
        if path:
            self.open_pdf(Path(path))

    def open_pdf(self, path: Path, silent: bool = False) -> None:
        try:
            doc = fitz.open(str(path))
            if len(doc) < 1:
                raise ValueError("PDF has no pages")
            if self.document:
                self.document.close()
            self.document = doc
            self.pdf_path = path
            self.page = 0
            self.settings.setValue("last_pdf", str(path))
            recent = [str(path)] + [
                str(p) for p in self.settings.value("recent", [], type=list)
                if str(p) != str(path) and Path(str(p)).is_file()
            ]
            self.settings.setValue("recent", recent[:20])
            self.recent.clear()
            self.recent.addItems(recent[:20])
            self.render_page()
            self.set_status(f"Opened {path.name} • {len(doc)} pages")
        except Exception as exc:
            if not silent:
                QMessageBox.warning(self, "PDF error", str(exc))

    def render_page(self) -> None:
        if self.document is None:
            return
        try:
            page = self.document[self.page]
            scale = self.zoom / 100.0
            image = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            qimage = QImage(
                image.samples, image.width, image.height, image.stride,
                QImage.Format.Format_RGB888,
            ).copy()
            self.page_image.setPixmap(QPixmap.fromImage(qimage))
            self.page_label.setText(
                f"Page {self.page + 1} / {len(self.document)}"
            )
        except Exception as exc:
            self.set_status(f"Unable to render PDF page: {exc}")

    def go_page(self, direction: int) -> None:
        if self.document is None:
            return
        self.page = min(max(self.page + direction, 0), len(self.document) - 1)
        self.render_page()

    def zoom_changed(self, value: int) -> None:
        self.zoom = value
        self.render_page()

    def pick_model(self) -> None:
        folder = QFileDialog.getExistingDirectory(
            self, "Select an extracted Vosk model folder", str(Path.home())
        )
        if folder:
            if not (Path(folder) / "am").is_dir():
                QMessageBox.warning(
                    self, "Invalid model",
                    "Select the extracted Vosk folder containing an 'am' directory."
                )
                return
            self.model_path = folder
            self.model_label.setText(folder)
            self.settings.setValue("vosk_model", folder)

    def chosen_duration(self) -> int:
        return int(self.duration.currentData() or DEFAULT_DURATION_MIN) * 60

    def has_consent(self) -> bool:
        return QMessageBox.question(
            self, "Lecture recording permission",
            "Do you have permission to record this lecture?\n"
            "The microphone will be visible in EricPDF, and WAV recordings remain local.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        ) == QMessageBox.StandardButton.Yes

    def toggle_record(self) -> None:
        if self.is_recording:
            self.stop_record()
        else:
            if self.has_consent():
                self.start_record()

    def start_record(self) -> None:
        if self.is_recording:
            return
        stem = self.pdf_path.stem if self.pdf_path else "Lecture"
        safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in stem)[:60]
        filename = f"{safe}-{datetime.now():%Y%m%d-%H%M%S}.wav"
        self.recording_path = DATA_DIR / filename
        self.target_duration = self.chosen_duration()
        self.caption_box.setPlainText("Listening…")
        if self.recorder.start(self.recording_path, self.model_path):
            self.is_recording = True
            self.record_button.setText("■  Stop recording")
            self.record_button.setStyleSheet("background:#d83c4a; color:white")
            self.set_status("Recording started; auto-stop is enabled.")
        else:
            self.set_status("Recorder is already busy.")

    def stop_record(self) -> None:
        if self.is_recording:
            self.recorder.stop()
            self.is_recording = False
            self.record_button.setText("●  Start recording")
            self.record_button.setStyleSheet("")
            self.set_status("Finalizing recording and transcript…")

    def recording_finished(self, result: str) -> None:
        self.is_recording = False
        self.record_button.setText("●  Start recording")
        self.record_button.setStyleSheet("")
        self.set_status(result)

    def toggle_schedule(self) -> None:
        if self.armed:
            self.armed = False
            self.scheduled_at = None
            self.arm_button.setText("Schedule session")
            self.schedule_status.setText("Scheduled recording canceled")
            self.settings.remove("scheduled_at")
            return
        chosen = self.when.dateTime().toPython()
        if chosen <= datetime.now():
            QMessageBox.warning(self, "Invalid time", "Choose a future date and time.")
            return
        if not self.has_consent():
            return
        self.scheduled_at = chosen
        self.armed = True
        self.settings.setValue("scheduled_at", chosen.isoformat())
        self.settings.setValue("scheduled_duration", self.duration.currentIndex())
        self.arm_button.setText("Cancel scheduled session")
        self.schedule_status.setText(
            f"Armed for {chosen:%d %b %Y, %H:%M}. Keep EricPDF running."
        )
        self.set_status("Scheduled recording armed • app must remain running.")

    def restore_schedule(self) -> None:
        raw = self.settings.value("scheduled_at", "", type=str)
        if not raw:
            return
        try:
            date = datetime.fromisoformat(raw)
            if date <= datetime.now():
                self.settings.remove("scheduled_at")
                return
            self.scheduled_at = date
            self.armed = True
            self.when.setDateTime(QDateTime.fromString(
                date.isoformat(), Qt.DateFormat.ISODate
            ))
            self.duration.setCurrentIndex(
                int(self.settings.value("scheduled_duration", 3))
            )
            self.arm_button.setText("Cancel scheduled session")
            self.schedule_status.setText(
                f"Armed for {date:%d %b %Y, %H:%M}. Keep EricPDF running."
            )
        except (ValueError, TypeError):
            self.settings.remove("scheduled_at")

    def tick(self) -> None:
        if self.armed and self.scheduled_at and datetime.now() >= self.scheduled_at:
            self.armed = False
            self.scheduled_at = None
            self.settings.remove("scheduled_at")
            self.arm_button.setText("Schedule session")
            self.schedule_status.setText("Scheduled session started")
            if not self.is_recording:
                self.start_record()
        if self.is_recording:
            elapsed = time.monotonic() - self.recorder.started_at
            remaining = max(0, self.target_duration - elapsed)
            self.elapsed.setText(
                f"{display_time(elapsed)} / {display_time(self.target_duration)}  "
                f"• remaining {display_time(remaining)}"
            )
            if remaining <= 0:
                self.stop_record()
        else:
            self.elapsed.setText(
                f"00:00:00 / {display_time(self.chosen_duration())}"
            )

    def quit_app(self) -> None:
        self._quitting = True
        self.stop_record()
        self.tray.hide()
        QTimer.singleShot(400, QApplication.instance().quit)

    def closeEvent(self, event) -> None:
        if not self._quitting and (self.is_recording or self.armed):
            if QSystemTrayIcon.isSystemTrayAvailable():
                self.hide()
                self.tray.showMessage(
                    "EricPDF is running", "Your scheduled or active recording continues.",
                    QSystemTrayIcon.MessageIcon.Information, 3500
                )
                event.ignore()
                return
            reply = QMessageBox.question(
                self, "Close EricPDF?",
                "Closing will cancel the scheduled or active recording. Close anyway?",
            )
            if reply != QMessageBox.StandardButton.Yes:
                event.ignore()
                return
        self.stop_record()
        event.accept()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setOrganizationName("innovateX")
    app.setQuitOnLastWindowClosed(False)
    window = EricPDFWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
