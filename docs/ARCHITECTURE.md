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

`MainActivity` launches `GmsDocumentScanner` with `SCANNER_MODE_FULL`, JPEG and PDF output, and gallery import. Page limit is deliberately not set, leaving scan completion to the user.

### Persistence

`ScanRepository` coordinates all writes. `FileStore` owns the app-private hierarchy. Room owns document/page metadata. Page identity is UUID based and independent of visible order.

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
