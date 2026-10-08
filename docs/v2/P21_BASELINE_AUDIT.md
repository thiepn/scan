# P21 — Initial baseline audit (2026-10-08)

**Status: PARTIAL / open.** This is a source-and-CI inspection, **not** a physical-device quality certification. Do not mark P21 complete until the measured tasks and image corpus have been executed.

## Verified repository baseline

| Item | Evidence | Current conclusion |
| --- | --- | --- |
| Base SHA | `4fed03797cda8009a1df7d9fef2cd8cc03de6739` | Frozen unreleased v1 candidate |
| Android CI | Actions run `36615499210` | Passed at base SHA |
| v1 production certification | Actions run `36615499422` | Passed; API 26 and API 35 emulator jobs also passed |
| Play listing validation | Actions run `36615499225` | Passed |
| Production signing acceptance | Actions run `36615499458` | Failed solely at required signing secrets; no production-signed artifact was generated |
| Public release | GitHub Releases collection empty at audit time | Unreleased |
| Development | `v2/development` | Separated from main; Android CI push filter updated for this branch |
| Native package | `com.thiepn.scan` | Retain |
| Room schema | `ScanDatabase` v22; committed v22 JSON | Migration path must be extended, not reset |
| Minimum SDK | 26 | Continue until justified |
| Internet permission | Manifest explicitly removes transitive `INTERNET`; certification checks merged APK | Do not reintroduce without separate product/privacy approval |

### Source hotspots requiring controlled refactor

| Source | Approximate size on frozen base | Concern to validate |
| --- | ---: | --- |
| `data/ScanRepository.kt` | 5,600 lines, ~217 KB | Broad business logic and queue coordination; difficult isolated tests and module ownership |
| `ui/DocumentScreen.kt` | 3,528 lines, ~150 KB | Dozens of screen-local flags and dialogs; navigation, recomposition and state-restoration risk |
| `ui/LibraryScreen.kt` | 1,261 lines, ~55 KB | Library, search, selection, organization, automation and backup UI combined |
| `MainActivity.kt` | 1,022 lines, ~40 KB | Scanner result handling, navigation and protected-document state attached to the Activity |
| `data/ScanDatabase.kt` | v22 with many historic migrations | Migration regressions could destroy existing user data |
| `AppGraph.kt` | One app-scoped graph building DAO, repository, OCR, vault, PDF services | Useful seam for gradual injection; not a reason to rewrite app startup all at once |

**Note:** large files and many state flags are maintainability *risk indicators*, not proven runtime defects. Changes require tests proving behavior parity.

### User journey map as currently exposed

- Library + scan-mode selection -> ML Kit document scanner -> process results via `MainActivity` -> `ScanRepository` -> Room/files/OCR.
- Library -> DocumentScreen, with extensive per-page and per-document editing/actions.
- Document -> share/export, native-PDF passthrough when available, or rendered/searchable outputs.
- Organization/automation controls, vault protection and backup exist in the library/document workflows.
- Navigation is primarily one nullable selected document ID plus local Compose UI state. P23 should replace it with explicit route/back-stack and scoped state, without breaking scanner result restoration.

### Competitive reference facts (not feature completion claims)

- Adobe Scan exposes polished fast capture and integrated PDF workflow; recent Android releases add curvature correction, enhanced OCR language coverage and cleaning.
- vFlat emphasizes book curve flattening and two-page capture; benchmarking is required rather than claiming equivalent performance from current cylindrical dewarp.
- Google ML Kit Document Scanner provides on-device detection, crop, filters and cleaning through an external Play services UI. It reduces camera implementation burden but limits Scan-specific camera UX and may need module provisioning.
- Competitors are references for *outcomes*, not instructions to copy visual identity or pricing models.
- Official sources: https://www.adobe.com/devnet-docs/adobescan/android/en/releasenotes.html ; https://vflat.com/en/ ; https://developers.google.com/ml-kit/vision/doc-scanner .

## Early engineering risks (to reproduce; not yet confirmed defects)

1. **Search query load** — `LibraryScreen` starts `repository.searchDocuments` inside `LaunchedEffect(query, filter, liveDocuments.map { it.updatedAt })`; verify debounce/cancellation behavior under rapid typing and large library changes.
2. **UI state complexity** — `DocumentScreen` and `LibraryScreen` hold many independent mutable modal/editor flags. Exercise back stack, save-state, rotation, app switch, process death and multiple actions in rapid succession.
3. **Capture edge cases** — ML Kit result handling in MainActivity needs real tests for empty/null results, external-UI cancellation, gallery documents, app/background recreation and module absence.
4. **Data size and retention** — 1,000-page tests, low-disk interruption, backup restore and native-PDF imports were specified in v1, but do not count as real physical-device passes without evidence.
5. **Book quality uncertainty** — existing seam detection and dewarp are described and tested at logic level; a real spread corpus is required before calling quality competitive.
6. **Release coupling** — current v1 certification and finalizers assume v1.0.0, versionCode 1, SHA-bound artifacts and v1 filenames. V2 must create its own path.

## Required baseline test dataset (not yet collected)

Use synthetic/publicly shareable paper samples and keep private documents out of the public repository.

- 12 clean A4 typed pages with known source text, including small type, grayscale and columns.
- 8 receipts: thermal paper, small text, folds, long and short receipts.
- 8 book spreads across gutter curvature, uneven lighting and wide/narrow binding.
- 8 mixed-difficulty sheets: skew, rotated, glare, shadow, coffee mark, hands in frame and textured backgrounds.
- 8 OCR samples: EN, DE, FR, Korean, Japanese, Chinese, Devanagari, handwriting; group quantitative accuracy by script/print legibility.
- 6 PDF inputs: native searchable, image-only, forms, mixed page sizes, large page count and malformed/unsupported input.
- At least one reference 1,000-page PDF generated from non-sensitive repeatable material for stress, with hashes.
- Reference originals, manually checked crops, target OCR text, visual outputs and device metadata; do not alter golden outputs merely to silence regressions.

Dataset counts are initial *targets*, not files already present.

## Proposed first measurement suite

| Metric | How to measure | Baseline status |
| --- | --- | --- |
| One-page scan -> locally saved | Stopwatch + log trace on Samsung/Pixel; distinguish first-time model download | Not measured |
| Saved -> shareable PDF | Stopwatch; direct/native and OCR-PDF paths | Not measured |
| Library cold/warm time to interaction | Android Macrobenchmark/startup trace, documented hardware | Not measured |
| Search latency 100/1,000 docs | Seed deterministic Room database, trace p50/p95, verify hits | Not measured |
| Memory and jank | Profile capture, 1,000-page view/search/export and document scrolling | Not measured |
| OCR accuracy | Ground-truth CER/WER per script and image set | Not measured |
| Auto-crop and book dewarp | Pixel/reference crop plus human legibility review; false split rate | Not measured |
| Restore | Wrong password, tampered backup, successful restore + checksum/data counts | Not measured |
| Accessibility | Physical TalkBack, 200% font, gestures and content actions | Not measured |

## Phase exit criteria (P21)

- [x] Verify source baseline SHA, repo structure, CI status and release blocker
- [x] Split v2 development from frozen `main`
- [x] Specify v2 UX, architecture sequence, release safety contract and full phase list
- [x] Enable Android CI on v2 push (configuration committed; run result pending at audit time)
- [ ] Build shareable fixture corpus with expected outputs and hashes
- [ ] Run initial Android UI/flows through real devices and save screenshots
- [ ] Capture actual performance/scan-quality numbers and hardware specs
- [ ] File P0/P1 repros and prioritize P22/P23 acceptance changes
- [ ] Verify latest v2 branch CI passes after this audit commit

## Next work immediately after baseline measurements

P22: implement full-screen Library / Capture return / Review / Document / Page Editor / Search prototypes and visual acceptance, not a fake static SaaS home. P23: split the DocumentScreen and ScanRepository logic in small verified commits while keeping all current operations available.
