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

### Smart intake and workflow automation

Phase 17 adds a separate durable automation layer instead of embedding rules in UI preferences. Room stores reusable `processing_presets`, ordered `workflow_rules`, persisted-SAF `workflow_destinations`, and append-only operational `workflow_runs`. These records intentionally avoid foreign keys so workflow history survives later document/rule/preset cleanup.

Intake evaluation occurs only after OCR, classification suggestions, specialized field extraction, and capture validation have settled. Rules can match scan mode, document type, title/OCR content, extracted field key/value, page bounds, review state, and unfiled state. A document/rule/trigger tuple is queued at most once automatically, preventing repository refreshes from replaying the same intake automation.

A processing preset can combine extraction-schema execution, explicit document classification, folder/tag filing, review/favorite/archive lifecycle state, deterministic naming templates, PDF compliance/accessibility settings, vault policy, and optional destination delivery. Naming tokens include date/time, mode/type, original title, and extracted fields. Automatic delivery goes through a persisted Android Storage Access Framework tree URI and therefore does not require broad filesystem access.

Workflow execution is serialized behind a repository mutex. Every run transitions through durable pending/running/succeeded/failed state. A process death converts stranded running work back into retryable failed work at startup. Failures use bounded exponential retry with a per-rule attempt limit; future retry timestamps survive restarts, while in-process timers are only an optimization. Manual retry and selected-document batch execution use the same run engine and history as automatic intake.

Security remains last in the preset action order so local vault protection can be applied after optional destination output has been generated. Destination exports still pass through the existing PDF/text/structured export paths, preserving their existing vault, compliance, OCR, and source-integrity checks rather than introducing a second renderer.

### Full restoration engine V2

Phase 18 upgrades the visual enhancement seam into a deterministic custom-CV restoration pipeline without adding a mandatory native computer-vision runtime. `PageVisualRecipe` v2 retains all previous tonal controls and adds illumination correction, local contrast, white balance, adaptive B&W, and an explicit restoration profile. The codec continues to read v1 recipes; legacy pages do not receive new restoration strengths unless the user reapplies a preset or edits them.

`DocumentRestorationEngine` analyzes only a tiny aspect-preserving derivative. A repeatedly smoothed luminance grid estimates low-frequency page illumination while bright low-chroma samples estimate a bounded neutral balance. The full-resolution renderer then uses bilinear field sampling for multiplicative illumination normalization, bounded shadow flattening, local detail contrast, white balance, and local adaptive thresholding. This keeps analysis memory effectively constant with page resolution and avoids retaining a second full-resolution analysis image.

Profiles tune the same engine rather than branching into unrelated renderers. Receipt favors stronger paper normalization and adaptive text separation; Whiteboard raises the paper target while protecting chromatic marker strokes from whitening; Book deliberately preserves warmer paper and applies milder white balance; Notes and Form use intermediate settings. Photo mode remains restoration-off by default.

The visual pipeline remains non-destructive and coordinate-safe:

`source → perspective/crop → cleanup → restoration/enhancement → display rotation`

When saved OCR text replacements exist, their established orientation requirement remains authoritative:

`source → perspective/crop → cleanup → display rotation → text replacement → restoration/enhancement`

Form fill and markup continue after the visible page raster is prepared. Canonical source images are never replaced. Restoration is intentionally visual/output state: OCR/search continue to use the semantic geometry + cleanup pipeline, so changing brightness or restoration sliders cannot silently change searchable text or word coordinates.

### Accessibility, large-document, and device hardening

Phase 19 treats accessibility and scale as system invariants rather than per-screen patches. Selection mode exposes each page/document as one checkbox-like semantics node with explicit selected state and action labels, avoiding duplicate TalkBack targets from nested image/text/check controls. Page/document titles are headings, long-running document processing is announced through polite live regions, page action controls occupy a separate horizontally scrollable row, and library thumbnails shrink when system font scale is large so text retains usable width.

`DeviceCapabilityPolicy` derives a static render budget from Android memory class plus `isLowRamDevice`. The budget controls library thumbnails, normal and large-document page previews, enhancement/restoration previews, imported-PDF derivatives, OCR semantic rendering, Book-processing intermediates, and edited-page export rasters. Direct untouched JPEG/native-PDF passthrough remains original quality because it does not allocate large ARGB intermediates. This avoids using `largeHeap` as a substitute for bounded memory behavior.

PDF import is incremental. `PdfPageRasterizer.renderIncrementally` renders one page, writes its JPEG derivative, commits/updates that deterministic page record and document page count, then proceeds. A failure therefore leaves a usable partial document instead of discarding all completed work; the document is marked Needs Review. Bitmap lifetime remains page-scoped.

Full OCR truth remains in `pages.ocrText` plus FTS. `documents.ocrText` is only a bounded cross-page summary (128 KiB) used for lightweight classification/specialized summaries and library preview, eliminating multi-megabyte duplicate text payloads in normal library queries. Text/PDF exports continue reading page rows and are lossless.

Large page batches use set membership in UI/repository filtering and conservative 900-ID SQL chunks for `IN` mutations. The same chunking applies to bulk document filing/classification/review/favorite/archive operations and tag replacement. Page-wide Compose metrics (Book review, edit presence, form counts, structured-data counts/staleness) are computed in one memoized pass keyed by document revision rather than decoded on every transient UI recomposition.


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
