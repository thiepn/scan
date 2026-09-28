#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
PLAY = ROOT / "play"
LISTING = PLAY / "listing" / "en-US"
ASSETS = PLAY / "store-assets"

LIMITS = {
    "title.txt": 30,
    "short-description.txt": 80,
    "full-description.txt": 4000,
}

EXPECTED_PNGS = {
    ASSETS / "icon-512.png": (512, 512),
    ASSETS / "feature-graphic.png": (1024, 500),
    **{
        ASSETS / "phone" / f"{name}.png": (1080, 2146)
        for name in (
            "01-library",
            "02-scan-modes",
            "03-sort-filter",
            "04-automation-center",
            "05-document-view",
            "06-export-pdf",
        )
    },
}


def png_info(path: Path) -> tuple[int, int, int]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    if data[12:16] != b"IHDR":
        raise ValueError("missing IHDR")
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    if bit_depth != 8:
        raise ValueError(f"expected 8-bit PNG, got {bit_depth}-bit")
    return width, height, color_type


errors: list[str] = []

for filename, limit in LIMITS.items():
    path = LISTING / filename
    if not path.is_file():
        errors.append(f"missing listing file: {path.relative_to(ROOT)}")
        continue
    value = path.read_text(encoding="utf-8").strip()
    if not value:
        errors.append(f"{filename}: must not be empty")
    if len(value) > limit:
        errors.append(f"{filename}: {len(value)} characters exceeds {limit}")
    else:
        print(f"{filename}: {len(value)}/{limit}")

for path, expected_size in EXPECTED_PNGS.items():
    if not path.is_file():
        errors.append(f"missing store asset: {path.relative_to(ROOT)}")
        continue
    try:
        width, height, color_type = png_info(path)
    except Exception as exc:
        errors.append(f"{path.relative_to(ROOT)}: {exc}")
        continue
    if (width, height) != expected_size:
        errors.append(
            f"{path.relative_to(ROOT)}: expected {expected_size[0]}x{expected_size[1]}, "
            f"got {width}x{height}"
        )
    if path.name == "feature-graphic.png" or path.parent.name == "phone":
        if color_type != 2:
            errors.append(
                f"{path.relative_to(ROOT)}: Play preview graphics must be 24-bit RGB PNG without alpha "
                f"(PNG color type 2), got color type {color_type}"
            )
    minimum_bytes = 1_000 if path.name == "icon-512.png" else 10_000
    if path.stat().st_size < minimum_bytes:
        errors.append(
            f"{path.relative_to(ROOT)}: unexpectedly small/corrupt-looking file "
            f"(< {minimum_bytes} bytes)"
        )
    print(
        f"{path.relative_to(ROOT)}: {width}x{height}, "
        f"{path.stat().st_size} bytes, color type {color_type}"
    )

if errors:
    print("\nGoogle Play listing validation failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print("\nGoogle Play listing validation passed.")
