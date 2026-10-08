# Scan 2.0 — Master development plan

Status: **approved development direction / not released**  
Development branch: `v2/development`  
Source baseline: `main@4fed03797cda8009a1df7d9fef2cd8cc03de6739` (unreleased, automated v1 certification passed).  
Intended first public product release: **2.0.0**, subject to all acceptance gates below.

## Release decision

The owner has intentionally **deferred v1 signing and publication** in favor of another full development cycle. Do not create the `v1.0.0` tag, invoke `scripts/finalize-v1-release.ps1`, set v1 acceptance attestation variables, or claim the old certified artifact is ready for public release. The v1 scripts and frozen SHA are archival reference material, not a shortcut to v2 publication. Keep `main` untouched during exploratory development; merge a fully qualified v2 candidate only after the new release pipeline exists.

## Product definition

**Scan 2.0 is a scan-first, local-first Android document workspace**: immediate capture, excellent page quality, reliable editable documents, fast search and transparent exports. No account, subscription, ads, watermark, forced cloud processing, broad storage permissions, or network access in the main Android app. The Google Play services document scanner may download/update its external module; make this distinction explicit in user-facing privacy and availability language.

### Product priorities, in order

1. Never lose an original page, fail silently, or make a document unopenable through editing.
2. Scanning a single receipt or multi-page document must be simple; essential actions are visible without searching advanced menus.
3. Image quality, crop accuracy, readability and OCR need measured real-document improvement, not just knobs.
4. Every advanced feature must integrate into a coherent workflow, with reversibility and clear export semantics.
5. Search, library, viewer and capture remain responsive on moderate-memory Android devices.
6. In-app privacy and offline behavior remain truthful and auditable.

### Preserve from the baseline

- Stable package ID `com.thiepn.scan`, Android min SDK 26 unless a documented compatibility decision changes it.
- Room v22 and **all prior migration paths**; append schema migrations rather than resetting data.
- Durable, immutable source images and original native-PDF passthrough when untouched.
- Local FTS/OCR, extraction, folders/tags, non-destructive editing, secure backup, vault protection and standard export.
- Explicit failure recovery and bounded image/PDF working sets.
- Supported Google ML Kit scanner as the **known-good capture fallback** until a replacement is fully demonstrated.

### Source audit findings to address

- `ScanRepository.kt` (~5,600 lines) mixes many operations behind a single large facade. Extract narrow domain services with transaction boundaries and prove parity with integration tests; no wholesale rewrite.
- `DocumentScreen.kt` (~3,500 lines) and `LibraryScreen.kt` (~1,260 lines) concentrate UI states, actions and dialogs. Introduce route-aware state holders/view models and focused screens to reduce accidental coupling.
- `MainActivity.kt` (~1,020 lines) coordinates scanner intents and navigation state. Treat capture session persistence as a first-class workflow.
- Capture currently runs the Google Play services scanner UI: a new CameraX capture experience is a **conditional investment**, not a guaranteed replacement. Validate speed, detection, edge accuracy, lower-end hardware, offline availability and device compatibility before switching defaults.
- The old certification/release scripts require v1.0.0, version code 1 and exact SHA. V2 must have its own parametrized/version-correct gates and evidence provenance.
- CI/emulator success does not replace end-to-end paper scans, device testing, real 1,000-page stress or human TalkBack tests.

## Development stages and phases

Phases must produce working code, repeatable tests and a user-visible result where relevant. Do not count documentation alone as a finished implementation phase.

### Stage A — Foundation and product design

**P21 — Audit, instrumentation and baseline qualification**
- Record route map, feature inventory, all launch/capture/preview/save/export states, existing defects, dependencies and permission contract.
- Establish representative sample set: receipt, A4, notes, handwriting, ID, book, form, glare, shadows, skew, mixed scripts and multi-page PDFs.
- Create perf/quality baseline on emulator and real devices where available; label unmeasured targets explicitly.
- Exit: reproducible baseline report, prioritized bug register and sample fixtures with provenance.

**P22 — Scan-native design system and interaction specification**
- Build approved full-screen flows for Library, Capture, Review, Document, Page Editor, Search and Settings; light/dark, compact/large phones and accessibility.
- Remove dashboard/hero patterns, overloaded menus and pop-up-only navigation for major tasks; document component and gesture behavior.
- Exit: screenshot-based reference acceptance, measurable top-task usability test plan and UI semantics contract.

**P23 — Modular architecture and navigation**
- Introduce explicit domain boundaries for capture, documents, pages, OCR, image processing, export, search, backup and automation.
- Split oversized Compose routes into lifecycle-safe view models/state holders, use typed navigation, and isolate long-running jobs from UI lifetime.
- Move without changing stored semantics; keep compatibility adapter where needed.
- Exit: compile/test green, critical old/new workflow parity and no data loss on activity recreation.

**P24 — Data integrity, migrations and recovery**
- Test migration from the committed v22 database, imported native PDFs, soft deletion, book source provenance, vault state and encrypted backup restore.
- Define journal/staging protocol for scan capture, bulk editing and export, plus crash/failure injection.
- Exit: simulated process death/low-storage recovery, migration tests and verified backup round trip.

### Stage B — Everyday experience rebuild

**P25 — Library and search-entry redesign**
- Fast list/grid, recent and relevant collections, inline filtering, OCR snippets, reliable selection and contextual batch actions.
- Zero-document and very-large-library behavior; explicit empty states, useful sorting and accessible organization.
- Exit: top tasks have direct entry points; scroll/memory baselines pass.

**P26 — Capture workflow 2.0**
- Design mode selection (document, receipt, book, ID, photo) without slowing default one-tap scanning.
- Spike CameraX + on-device boundary/auto-shutter pipeline **behind a capture-provider interface**; compare it against existing ML Kit scanner using a fixed real-paper sample set.
- Retain Google provider if custom capture is worse, slower or materially less reliable; handle unsupported Play services and first-time module requirements transparently.
- Exit: capture survives cancellation/rotation/process recreation; original pages safely staged before processing; documented provider decision.

**P27 — Review and page editor 2.0**
- Immediately show the saved batch, with reorder, retake, crop, straighten, rotate, adjustment preview, add-page and undo/restore.
- Replace scattered modal flows with dedicated editor routes and clear non-destructive before/after.
- Exit: no accidental destructive operation; preview/export parity against golden images.

**P28 — Export, share and lifecycle polish**
- Make Share PDF, Save PDF, copy text and quick presets obvious; advanced standards, privacy, form and signing options remain discoverable but secondary.
- Validate SAF URI grants, filenames, content types, external PDF viewers and no double-rasterization of untouched PDFs.
- Exit: reliable phone-to-phone/file-manager/share-sheet smoke matrix and correct export dimensions/content.

### Stage C — Quality and document intelligence

**P29 — Image quality lab**
- Evaluate corner/perspective detection, lighting correction, shadows, color, background flattening, cleaning and text sharpness on fixed fixtures.
- Compare modes and devices with visual snapshots; protect handwriting/photos and original pixels.
- Exit: measurable improvement/no material regression in readability and image fidelity.

**P30 — OCR, language detection and usable text**
- Improve line/word reading order, OCR error feedback, search indexing, correction persistence and searchable PDF alignment.
- Test Latin/DE/FR and the bundled Chinese/Japanese/Korean/Devanagari models separately; do not claim universal language support.
- Exit: quantitative per-language OCR fixtures, zero stale-index false confidence after edits and navigable result highlights.

**P31 — Book capture and long-form reading**
- Benchmark spread detection, split confidence, gutter position, curved-page flattening, page order, RTL book order and restore-original behavior.
- Reduce false positive splitting; allow batch corrections and comfortable page review.
- Exit: paper-book corpus, no source loss and proven acceptable scans across gutter/shadow conditions.

**P32 — Import and document interchange**
- Improve image/PDF multi-import, duplicate detection, sorting/merge, metadata and path handling without requiring broad storage permissions.
- Preserve original PDFs when possible; define transparent converted-versus-original export indicators.
- Exit: mixed import/merge/export golden fixtures plus large and malformed input tests.

### Stage D — Professional features without complexity

**P33 — PDF workspace and assembly**
- Consolidate merge, split, extract, page insertion, compression, page numbering, bookmarks, signatures and PDF standard checks into task-oriented screens.
- Clearly distinguish editable scan markup, visible raster edits, text layer edits, metadata and irreversible redaction.
- Exit: interoperability validation with independent PDF readers and third-party validators.

**P34 — Forms and structured extraction**
- Streamline form filling, field extraction, receipt/business-card/ID flows, document templates and CSV/JSON/XLSX export.
- Review-first for low-confidence fields; clear privacy boundaries and no hallucinated/auto-confirmed values.
- Exit: schema/value correctness tests and accessible correction flows.

**P35 — Organization and automation 2.0**
- Make folders, tags, smart collections, presets and destination rules usable for normal people, not an automation dashboard.
- Show rule dry-runs, expected outcomes, conflicts, audit history and deterministic retry; prevent silent unintended exports.
- Exit: end-to-end intake-to-folder/export tests and safe workflow cancellation/recovery.

**P36 — Security, privacy and backup 2.0**
- Complete secure-vault UX, lock/unlock lifecycle, tamper evidence, secure portable backup, key management and permission disclosures.
- Threat-model document leakage through previews, background jobs, SAF and share intents; recheck imported-PDF security claims.
- Exit: independent negative-path security tests and user-tested backup recovery.

### Stage E — Production qualification and launch

**P37 — Performance, thermal and battery qualification**
- Test large libraries, 1,000-page PDFs, capture bursts, low storage, low RAM, high temperature, slow disks and background interruptions.
- Add device-lab benchmarks for time-to-interaction, capture→saved time, export, OCR throughput, memory peak and Compose jank.
- Exit: measured budgets, no P0/P1 failures, defined thresholds documented with hardware and methodology.

**P38 — Accessibility, adaptive layouts and localization**
- TalkBack, switch access, 200% font scale, contrast, keyboard navigation, landscape/tablet, edge-to-edge, dark/light.
- User-facing English/German first, with localization-safe strings for later supported languages.
- Exit: independent task-based accessibility acceptance on actual Android hardware and reviewed translations.

**P39 — V2 release engineering and distribution**
- Introduce fresh v2 CI/certification/acceptance/publication workflows and new artifact names; remove hardcoded v1 values from new release path.
- Version `2.0.0` with strictly increasing `versionCode`; confirm API level and current Play policies at release time.
- Freeze dependencies, audit permissions/license/SDK disclosures, generate accurate Play screenshots and privacy/Data Safety materials.
- Exit: rehearsed release dry-run with test identity, manifest/installer verification and branch/tag/evidence integrity checks.

**P40 — V2.0 release candidate, signing and physical acceptance**
- Freeze exact source SHA; create/reuse permanent signing identity **only when ready for production acceptance**.
- Build same-key update baseline and final signed candidate, complete device matrix, independent APK/AAB byte verification, provenance and evidence-bound publication.
- Do not publish until every gate in `RELEASE_PROTOCOL.md` passes.
- Exit: immutable verified public v2.0.0 release and, separately, verified Google Play availability.

## Advancement rules

- Complete phases sequentially by stage unless a dependency explicitly allows parallel work.
- No "done" status without code merged to `v2/development`, tests running green, reviewer-visible evidence and outstanding limitations recorded.
- Every feature change must keep a baseline/rollback path until migration/QA signs off.
- No feature may require an account, recurring subscription or remote AI to perform the essential scan-to-PDF/OCR journey.
- Scope control: simplify existing underused capabilities before adding new ones. Optional AI/cloud sync are **post-v2** explorations only with separately reviewed privacy/network architecture.
- When UI and architecture work conflict with release tooling, keep the v1 branch untouched and carry v2-specific tests on v2 development.
- On completion of P40, move to defect-only v2.0 stabilization; avoid endless feature addition.

## References

- `docs/ARCHITECTURE.md` — baseline invariants and current implementation
- `docs/V1_DEVICE_QA.md` — past device gate concepts, not valid evidence for v2
- `docs/v2/PRODUCT_UX_SPEC.md` — v2 navigation/visual contract
- `docs/v2/RELEASE_PROTOCOL.md` — v2 new signing, qualification and launch contract
