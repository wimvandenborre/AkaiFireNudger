#!/usr/bin/env python3
"""Validate and atomically replace the extension without truncating Bitwig's open JAR."""
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile


def install(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Stage alongside the destination so os.replace stays on the same filesystem.
    staged = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".FireNudger-", suffix=".tmp",
                                         dir=destination.parent, delete=False) as output:
            staged = Path(output.name)
            with source.open("rb") as incoming:
                shutil.copyfileobj(incoming, output)
            output.flush()
            os.fsync(output.fileno())
        with zipfile.ZipFile(staged) as archive:
            for required in ("com/akai/fire/sequence/DrumSequenceMode.class",
                             "com/akai/fire/sequence/GrooveSession.class"):
                archive.getinfo(required)
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise ValueError("Corrupt extension entry: " + bad_entry)
        staged.chmod(0o644)
        os.replace(staged, destination)
        print("Installed atomically:", destination)
        print("Save your project and fully restart Bitwig to load this build; controller Restart may reuse cached code.")
    finally:
        if staged is not None and staged.exists():
            staged.unlink()


if __name__ == "__main__":
    if len(sys.argv) > 2:
        raise SystemExit("Usage: python3 scripts/install-extension.py [destination]")
    source = Path(__file__).resolve().parent.parent / "target/FireNudger.bwextension"
    destination = Path(sys.argv[1]).expanduser() if len(sys.argv) == 2 else (
        Path.home() / "Documents/Bitwig Studio/Extensions/FireNudger.bwextension")
    install(source, destination)
