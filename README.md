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
- Specialized Scan Modes: Document, Receipt, ID Card, Business Card, Book, Whiteboard, Form, Photo, Notes, and Certificate, each with mode-specific capture guidance, page limits, enhancement defaults, document type, OCR policy, PDF quality, and capture validation.
- ID Card mode uses a dedicated two-step front/back capture flow; the front is staged durably before launching the back scan, and cancelling the second side still preserves a one-page ID that can later use the explicit Scan back action.
- Receipt, Business Card, ID Card, Form, and Certificate modes perform deterministic on-device field extraction; extracted details are persisted locally, shown in the document editor, and individually copyable.
- Mode validation flags unusual aspect ratios, missing ID sides, unexpected page counts, and conservatively low capture resolution through the existing Needs Review workflow.
- Photo mode is visual-first: Original enhancement defaults, high-quality export, no OCR/search/text export, and no hidden OCR text layer when PDFs are rebuilt or merged.
- Book Scanner detects likely open-book spreads from landscape geometry plus center-gutter evidence, auto-splits only high-confidence spreads, and keeps lower-confidence pages intact for manual review.
- Book spread splitting preserves the untouched original source, creates ordered left/right derived pages, applies bounded cylindrical mesh dewarping near the gutter, and auto-detects an independent crop for each resulting page.
- Book review includes a live gutter preview with manual gutter adjustment, optional dewarping, sequential Review next spread workflow, and one-tap Restore original spread that removes the derived pair and reactivates the source.
- OCR, FTS search, page editing, text/PDF export, merge, and page ordering operate only on active logical book pages; preserved source spreads stay hidden from normal Deleted pages and remain available solely for reversible restoration.
- High-Speed Continuous Scanning adds an opt-in Rapid capture loop for Document, Book, Form, and Notes. Each returned scanner batch is copied into app-private storage and committed to Room before the scanner is reopened; cancelling the next scanner ends capture and starts deferred processing.
- Rapid capture persists capture sessions and per-page processing jobs, so interrupted or force-closed sessions recover on the next app launch instead of losing queued pages.
- A lightweight perceptual fingerprint stage conservatively suppresses near-identical duplicate frames before OCR while retaining low-quality but non-duplicate captures for user review.
- Heavy processing is strictly sequential/chunked and adapts to memory class, battery state, and Android thermal status. Severe heat or critically low unplugged battery pauses the queue and resumes it later.
- Rapid-session progress, duplicate counts, low-quality flags, failures, and retry actions are visible in the document editor. The processing model is designed to avoid retaining page bitmaps between jobs, allowing very large multi-batch sessions without scaling memory use with page count.
- Live page detection and automatic shutter timing remain provided by the production ML Kit Document Scanner capture UI; Scan's Phase 8 pipeline begins once each captured page image is returned.
- Smart Cleanup / Magic Eraser stores reversible normalized vector masks rather than modifying source JPEGs. Manual brush cleanup supports fingers, objects, handwriting/marks, stains, holes, and other obstructions with before/after preview, undo, clear, and adjustable brush size.
- Cleanup rendering uses bounded local document-aware reconstruction: nearby unmasked page texture is sampled, a horizontal/vertical local background field is reconstructed across the mask, and mask edges are feathered. Source images remain immutable.
- Conservative automatic cleanup suggestions detect likely punch holes, border shadows, finger/hand regions, and stains/spots. Per-page suggestions show confidence and require acceptance; batch Auto clean applies only high-confidence detections.
- Cleanup is semantic rather than cosmetic: OCR fingerprints include the cleanup recipe, OCR/FTS are refreshed after cleanup changes, searchable PDF text is regenerated from the cleaned geometry, and native-PDF passthrough is disabled when a page has cleanup masks.
- Cleanup coordinates are stored in crop-corrected, unrotated page space so 90° rotation remains aligned. Changing crop/perspective clears cleanup masks explicitly because their coordinate system is no longer compatible; Reset page edits also removes cleanup masks.
- Annotation & form editing adds pen/highlighter, text boxes, rectangles/arrows, stamps, reusable signatures/initials, text/date/checkbox/radio fields, and undoable page-space operations.
- Secure redaction removes intersecting OCR/search text, flattens black redaction regions into generated PDF pixels, strips PDF metadata fields, avoids native-PDF passthrough, and includes an explicit verification check.
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
