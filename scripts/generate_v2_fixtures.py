#!/usr/bin/env python3
"""Generate safe, reproducible synthetic PDF fixtures for Scan 2.0 tests.

Uses only Python's standard library. This is NOT a real-camera/OCR-quality corpus.
No user documents or private data are involved.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def _pdf_text(value: str) -> bytes:
    escaped = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    return escaped.encode("ascii")


def build_pdf(page_sizes: list[tuple[int, int]], label: str) -> bytes:
    """Create a valid ASCII-text PDF with stable offsets and unique page labels."""
    if not page_sizes:
        raise ValueError("At least one page is required")
    if len(page_sizes) > 5000:
        raise ValueError("Fixture limited to 5000 pages")
    if any(width <= 0 or height <= 0 for width, height in page_sizes):
        raise ValueError("Page dimensions must be positive")
    if not label.isascii():
        raise ValueError("Fixture labels must be ASCII")

    kids = " ".join(f"{4 + i * 2} 0 R" for i in range(len(page_sizes)))
    objects: list[bytes] = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        f"<< /Type /Pages /Kids [{kids}] /Count {len(page_sizes)} >>".encode("ascii"),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    for i, (width, height) in enumerate(page_sizes, start=1):
        page_id = 4 + (i - 1) * 2
        stream_id = page_id + 1
        text = _pdf_text(f"{label} | page {i:04d} of {len(page_sizes):04d}")
        stream = (
            b"q 0.75 G 42 42 "
            + str(width - 84).encode("ascii")
            + b" "
            + str(height - 84).encode("ascii")
            + b" re S Q\nBT /F1 12 Tf 52 "
            + str(height - 78).encode("ascii")
            + b" Td ("
            + text
            + b") Tj ET\n"
        )
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {width} {height}] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {stream_id} 0 R >>"
            .encode("ascii")
        )
        objects.append(
            b"<< /Length " + str(len(stream)).encode("ascii")
            + b" >>\nstream\n" + stream + b"endstream"
        )

    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for n, obj in enumerate(objects, start=1):
        offsets.append(len(result))
        result.extend(f"{n} 0 obj\n".encode("ascii"))
        result.extend(obj)
        result.extend(b"\nendobj\n")

    start_xref = len(result)
    result.extend(f"xref\n0 {len(offsets)}\n".encode("ascii"))
    result.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    result.extend(
        f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\n"
        f"startxref\n{start_xref}\n%%EOF\n".encode("ascii")
    )
    return bytes(result)


def generate_fixtures(output_dir: Path, stress_pages: int = 1000) -> dict:
    if not 1 <= stress_pages <= 5000:
        raise ValueError("stress_pages must be between 1 and 5000")
    output_dir.mkdir(parents=True, exist_ok=True)
    cases = {
        "typed-3-pages.pdf": [(595, 842)] * 3,
        "mixed-page-sizes.pdf": [(595, 842), (612, 792), (250, 850), (842, 595)],
        f"stress-{stress_pages}-pages.pdf": [(595, 842)] * stress_pages,
    }
    manifest = {"generator": "Scan v2 deterministic PDF fixture generator", "files": []}
    for filename, pages in cases.items():
        data = build_pdf(pages, filename.removesuffix(".pdf"))
        (output_dir / filename).write_bytes(data)
        manifest["files"].append({
            "filename": filename,
            "pages": len(pages),
            "page_sizes_points": [list(size) for size in sorted(set(pages))],
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8"
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("build/v2-fixtures"))
    parser.add_argument("--stress-pages", type=int, default=1000)
    args = parser.parse_args()
    manifest = generate_fixtures(args.output_dir, args.stress_pages)
    for case in manifest["files"]:
        print(f"{case['filename']}: {case['pages']} pages; {case['sha256']}")


if __name__ == "__main__":
    main()
