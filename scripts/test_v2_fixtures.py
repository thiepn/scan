"""Standard-library tests for the deterministic v2 PDF fixture corpus."""
from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from generate_v2_fixtures import build_pdf, generate_fixtures


class V2FixtureTests(unittest.TestCase):
    def test_pdf_has_valid_xref_pointer_and_page_count(self):
        pdf = build_pdf([(595, 842), (612, 792), (842, 595)], "fixture")
        self.assertTrue(pdf.startswith(b"%PDF-1.4\n"))
        self.assertTrue(pdf.endswith(b"%%EOF\n"))
        xref_pointer = int(pdf.split(b"startxref\n")[-1].split(b"\n")[0])
        self.assertEqual(pdf[xref_pointer:xref_pointer + 5], b"xref\n")
        self.assertIn(b"/Count 3", pdf)
        self.assertEqual(pdf.count(b"/Type /Page /Parent"), 3)

    def test_repeated_builds_have_identical_hashes(self):
        pages = [(595, 842)] * 1000
        first = build_pdf(pages, "stress")
        second = build_pdf(pages, "stress")
        self.assertEqual(hashlib.sha256(first).digest(), hashlib.sha256(second).digest())
        self.assertEqual(first.count(b"/Type /Page /Parent"), 1000)

    def test_manifest_matches_actual_files(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            manifest = generate_fixtures(directory, stress_pages=7)
            self.assertEqual(len(manifest["files"]), 3)
            for item in manifest["files"]:
                data = (directory / item["filename"]).read_bytes()
                self.assertEqual(item["bytes"], len(data))
                self.assertEqual(item["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(
                sorted(path.name for path in directory.iterdir()),
                sorted([item["filename"] for item in manifest["files"]] + ["manifest.json"])
            )

    def test_rejects_invalid_parameters(self):
        with self.assertRaises(ValueError):
            build_pdf([], "empty")
        with self.assertRaises(ValueError):
            build_pdf([(0, 842)], "bad")
        with self.assertRaises(ValueError):
            build_pdf([(595, 842)], "non-ascii-한글")
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                generate_fixtures(Path(temp), stress_pages=5001)


if __name__ == "__main__":
    unittest.main()
