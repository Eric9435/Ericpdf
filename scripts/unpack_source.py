#!/usr/bin/env python3
"""Restore EricPDF source files from the GitHub transfer parts.

Usage:
  python -m pip install brotli
  python scripts/unpack_source.py

Run from any folder within the cloned Ericpdf repository.
"""
import base64
import hashlib
import io
from pathlib import Path
import shutil
import tarfile
import tempfile

try:
    import brotli
except ImportError as exc:
    raise SystemExit("Install Brotli first: python -m pip install brotli") from exc

ROOT = Path(__file__).resolve().parents[1]
parts = sorted((ROOT / "transfer").glob("part*.b64"))
if len(parts) != 8:
    raise SystemExit(f"Expected 8 source parts, found {len(parts)}.")

encoded = "".join(p.read_text(encoding="ascii").strip() for p in parts)
if len(encoded) != 31152:
    raise SystemExit("Source archive has the wrong length.")
compressed = base64.b64decode(encoded, validate=True)
expected = "c4be760db35ec135f1a9aa1af61b5346a5074f539d9e4f92632beff425db3731"
if hashlib.sha256(compressed).hexdigest() != expected:
    raise SystemExit("Archive verification failed. Files were not modified.")

with tempfile.TemporaryDirectory() as directory:
    with tarfile.open(fileobj=io.BytesIO(brotli.decompress(compressed)), mode="r:") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            if not path.parts or path.parts[0] != "EricPDF" or ".." in path.parts:
                raise SystemExit("Unsafe archive entry detected.")
            if member.issym() or member.islnk():
                raise SystemExit("Symbolic links are not allowed.")
        archive.extractall(directory, filter="data")
    src = Path(directory) / "EricPDF"
    count = 0
    for file in src.rglob("*"):
        if file.is_file():
            relative = file.relative_to(src)
            target = ROOT / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, target)
            count += 1
print(f"Restored {count} original EricPDF project files to {ROOT}")
print("For Android builds use the README instructions, Android Studio, or GitHub Actions.")
