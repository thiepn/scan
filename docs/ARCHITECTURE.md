# Scan architecture

## Product invariants

1. A document is usable without an account or network connection.
2. Captured/imported source files are copied into app-private storage before becoming library items.
3. The database stores metadata and references, not large image blobs.
4. OCR is derived data; OCR failure never destroys a scan.
5. Imported PDFs remain preserved as PDFs. Page JPEGs produced by `PdfRenderer` are local derivatives for preview/OCR.
6. Sharing uses `content://` URIs from a narrowly scoped `FileProvider`.
7. Search is local.
8. No broad filesystem permission is requested.
9. Advanced engines must sit behind interfaces or repository seams rather than couple directly to Compose UI.

## Current layers

### Capture

`MainActivity` launches `GmsDocumentScanner` with JPEG/PDF output and gallery import through a mode-aware adapter. General Document mode remains resource-bound with no explicit page limit; specialized modes may set single-page or bounded multipage limits and can choose a lighter scanner mode where appropriate.

### Persistence

`ScanRepository` coordinates all writes. `FileStore` owns the app-private hierarchy. Room owns document/page metadata. Page identity is UUID based and independent of visible order.

### Book processing

`BookSpreadProcessor` analyzes bounded derivatives for landscape spread geometry, center-gutter contrast/seam strength, and vertical consistency. `BookSpreadScoring` converts those signals into conservative spread confidence; only high-confidence spreads are auto-split.

Book splitting is non-destructive. The original `PageEntity` is marked as a hidden `preservedBookSource`, while two new active pages reference it through `sourceSpreadPageId` and store side, split confidence, and dewarp strength. Left/right insertion and source hiding occur in one Room transaction. Restoration deletes the derived pages and reactivates the source in one transaction.

Dewarping uses a bounded cylindrical bitmap mesh concentrated near the gutter. It is intentionally conservative and optional during manual review. Derived pages then pass through normal crop, enhancement, OCR, search, ordering, and PDF pipelines. Preserved source spreads never participate in active-page OCR/search/export and are excluded from normal Deleted pages.

### High-speed capture queue

Rapid capture separates durability from expensive processing. A scanner batch is first copied into app-private page files, inserted into Room, and paired with durable `page_processing` jobs under a `capture_sessions` record. No OCR, Book dewarping, or image enhancement rendering is required before the next scanner session can start.

While a capture session is `CAPTURING`, heavy queue work is intentionally deferred. Ending the loop changes the session to `PROCESSING`. The queue then fingerprints one page at a time, suppresses only conservative perceptual duplicates, and processes accepted pages in bounded chunks. Bitmap lifetime is page-scoped; queue size therefore does not imply proportional bitmap memory.

Processing state is restart-safe. On app startup, in-flight page jobs return to `QUEUED`, an unfinished `CAPTURING` session becomes `INTERRUPTED`, and preserved jobs are drained again. Failed jobs remain durable and can be retried.

`HighSpeedProcessingPolicy` constrains chunk size using Android memory class, battery state, thermal status, and scan mode. Severe thermal pressure or critically low unplugged battery moves the session to `PAUSED` and schedules a later retry.

ML Kit's scanner remains responsible for its own live document detection and automatic capture behavior. The repository queue only works with completed page images returned by that capture UI.

### Smart cleanup

Cleanup is a reversible semantic operation layer over immutable page sources. `PageEntity.cleanupRecipe` stores a compact vector recipe made of normalized strokes and accepted automatic suggestions; no cleaned source JPEG replaces the canonical page image.

Cleanup coordinates live in the perspective-corrected but unrotated page coordinate system. Rotation therefore preserves cleanup automatically, while a crop/perspective change clears cleanup because the coordinate basis has changed. Duplicate pages may copy cleanup recipes because their source geometry is identical; replacement pages start clean.

The rendering order is:

`source → crop/perspective → cleanup/inpainting → enhancement → display/PDF rotation`

`PageCleanupRenderer` rasterizes each bounded vector mask, samples unmasked texture around the affected region, reconstructs a local horizontal/vertical color field, and feathers the mask boundary. This is deterministic offline document reconstruction rather than generative image synthesis.

`CleanupSuggestionDetector` analyzes bounded derivatives and produces conservative suggestions for punch holes, border shadows, finger/hand-colored border components, and small stains/spots. Suggestions are metadata only until accepted. Batch cleanup intentionally uses a higher confidence threshold than the interactive editor.

Cleanup changes OCR truth. The cleanup recipe participates in `OcrFingerprint`; repository mutations clear stale OCR/FTS and recognize the cleaned semantic bitmap. PDF generation applies cleanup before enhancement and builds its invisible text layer from the same cleaned geometry. Native imported-PDF passthrough is prohibited whenever cleanup exists.

### OCR-driven text editing

Text replacement is a second reversible semantic operation layer. The canonical page image is never overwritten. `PageEntity.textEditRecipe` stores normalized word/line/block replacement operations, while `ocrBaseLayout` preserves the untouched spatial OCR result that those operations reference.

The editor always selects regions from the preserved base OCR layout. Saved replacements patch blocks, lines, words, and full text into `ocrLayout`/`ocrText`, so document search, text export, highlights, classification, and specialized-field extraction observe the edited text rather than stale recognition. Clearing the recipe restores the base OCR layout exactly without another recognition pass.

Replacement rendering uses normalized OCR boxes in the final semantic page orientation. The renderer samples local ink color/density, reconstructs only the selected background rectangle from surrounding page texture, fits replacement text to the original region, preserves OCR angle, supports multiline line/block edits, and offers automatic or explicit left/center/right alignment plus bounded size adjustment.

For pages with text replacement the visual/export pipeline is:

`source → crop/perspective → cleanup/inpainting → display rotation → text replacement → enhancement → preview/PDF raster`

Crop, rotation, cleanup, OCR-language changes, and OCR-regenerating scan-mode changes are rejected while saved text replacements exist, because those operations would invalidate the coordinate basis. The user can revert text replacements first; page-reset restores the original OCR truth and clears the text recipe.

PDF generation never uses native imported-PDF passthrough when text replacements exist. It rasterizes the edited page and writes the stored edited OCR layout as the invisible searchable text layer, keeping visual output and search/copy truth synchronized.

### OCR

`OcrEngine` wraps ML Kit Text Recognition. The bundled Latin model makes recognition available without a first-use model download. Recognition runs after capture/import on an application coroutine scope.

### PDF import

The original input PDF is copied unchanged into the document directory. `PdfPageRasterizer` renders bounded-resolution JPEG derivatives page-by-page, so search can work without replacing the source PDF.

### UI

Compose surfaces are intentionally small: Library and Document. Search, favorites, archive, scan/import, PDF share, text share, rename, and delete are all local operations.

## Extension seams

The A0-A20 design remains the target architecture. The next production expansions should preserve these boundaries:

- custom CameraX capture/vision engine can replace the ML Kit capture adapter;
- richer OCR artifacts can add block/word geometry without changing canonical sources;
- custom searchable-PDF writer can consume page images + OCR geometry;
- qpdf native worker can add merge/split/encryption while imported originals remain immutable;
- cleanup/text-edit/redaction operations should be non-destructive operation layers over immutable sources;
- optional E2EE sync must replicate local state rather than become the source of truth.
