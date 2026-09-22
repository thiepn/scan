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
