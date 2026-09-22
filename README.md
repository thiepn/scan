# Scan

A local-first Android document scanner built to replace the everyday Adobe Scan workflow without requiring an account or subscription.

## v1.0 foundation

The current repository contains a native Android/Jetpack Compose application with:

- Google ML Kit's full document-scanner capture flow for reliable automatic document capture, crop, filters, multipage review, and gallery import.
- Durable app-private page/PDF storage; the scanner copies results into its own local document store instead of depending on transient provider URIs.
- Bundled on-device ML Kit OCR for Latin-script text.
- Background OCR after capture so scanning is not coupled to recognition latency.
- Local Room document library with live first-page thumbnails, titles, favorites, archive, page counts, and processing state.
- Search across document titles and recognized text.
- PDF import that preserves the original PDF while rendering local page derivatives for OCR/search.
- Multipage page viewer with selectable recognized text.
- Searchable PDF generation for scanned pages using invisible spatial OCR text.
- Native imported PDFs remain preserved and are shared without rasterizing them.
- AES-256 password-protected PDF copies.
- Merge multiple library documents into a single PDF while preserving native imported PDFs where possible.
- Extract page ranges such as `1-3,5,8` into a new PDF.
- Searchable PDF size presets: Original, High, Balanced, and Small, with bounded page-by-page recompression.
- Post-capture page reordering plus recoverable soft-deletion/restoration; current page order is reflected in search, text export, merged PDFs, and native-PDF exports.
- Document Trash with restore and separately confirmed Delete forever; normal search/merge/background processing excludes trashed items.
- Reopen any active document and scan additional pages into it later; appended pages join the same OCR/search/export model.
- Duplicate an existing page non-destructively and place the copy directly after its source page.
- Rotate pages clockwise in 90° steps without changing the immutable source; preview and every PDF export path honor the rotation.
- Full-screen post-capture crop/perspective editor with four draggable corners, live magnifier, conservative auto-detect, reset, non-destructive geometry metadata, geometry-aware OCR, thumbnails, and PDF export.
- Non-destructive scan enhancement editor with Original, Auto, Clean, Color, Grayscale, B&W, Notes, Receipt, and Whiteboard presets plus brightness, contrast, highlights, shadows, black/white point, warmth, saturation, sharpness, background whitening, and shadow normalization controls.
- Cosmetic enhancement is intentionally separate from OCR truth: OCR/search use the geometry-corrected source while previews and visible PDF output use the visual recipe.
- OCR V2 persists block/line/word reading order, bounding geometry, corner polygons, confidence, angle, language tags, and a SHA-256 source/geometry/model fingerprint for stale-result detection.
- Per-document offline OCR models are bundled for Latin, Chinese, Devanagari, Japanese, and Korean scripts; switching the model triggers a controlled re-recognition pass.
- Ranked local FTS5 search supports exact quoted phrases, prefix queries such as `acc*`, highlighted snippets, document-level page hits, jump-to-page navigation, exact recognized-word copying, and on-page word-box overlays.
- Room uses AndroidX Bundled SQLite for deterministic FTS5 availability, with migration-safe indexing and lazy spatial-OCR upgrades for documents created before OCR V2.
- Document Organization System with nested folders, reusable tags, document types, favorites/archive, bulk filing/tagging/classification, and direct organization from both the library and document editor.
- Smart collections provide Recent (7 days), Unfiled, and Needs Review views; organization filters can combine folder trees, tags, and document type with relevance/date/title/page-count sorting.
- Deterministic local classification suggests Receipt, Invoice, ID, Form, Notes, Letter, Business Card, Book, Whiteboard, or Certificate when OCR/title evidence is strong, but never overwrites a user-selected type.
- Folder deletion is non-destructive: documents and child folders are promoted to the deleted folder's parent; tag deletion only removes tag relationships.
- PDF sharing through a narrowly scoped `FileProvider`.
- Storage Access Framework Save As for searchable/protected/extracted/merged PDFs and OCR text, with no broad storage permission.
- Plain-text export of recognized pages.
- No account, no mandatory cloud, no analytics SDK, and no broad storage permission.

The capture implementation intentionally uses the production ML Kit Document Scanner in this first shippable baseline; scanner capture is isolated from the document repository so a custom CameraX/OpenCV engine can replace it without changing storage, OCR, library, or export layers.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37.0
- Android Build Tools 36.0.0
- Gradle 9.6.0

```bash
gradle :app:assembleDebug
```

Android Studio can import the repository directly. CI uses AGP 9.4, compile SDK 37, target SDK 36, and the current stable Compose BOM.

## Architecture

```text
ML Kit scanner / PDF import
            |
            v
       ScanRepository
       /     |      \
      v      v       v
 FileStore  Room   OCR engine
      |               |
      +-------- PDF engine
      |      |       |
      +------+-------+
             |
             v
       Compose library
             |
       share / export
```

The local database contains metadata only. Large page images and PDFs live in app-private files. Imported PDFs are preserved as native originals; rendered JPEG pages are derived OCR/search artifacts.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the production invariants and extension seams.
