# P22 — Scan-native interaction and visual system (implementation report)

**Status: implemented for key routes; final emulator screenshots and physical-device sign-off remain gates.**  
Development branch: `v2/development`  
Do not release, sign, merge to main, or declare full P22 visual qualification before evidence review.

## Interaction decisions (shipped in code)

- A **single tap** of the main `Scan` button now launches the stored previous scan mode. The default is Document. The selection persists in app-private preferences; a corrupt/unknown mode falls back to Document. No account or network dependency was added.
- An adjacent, separately labeled **Choose scan mode** action opens the new full-width Material bottom sheet; all ten modes remain accessible, with frequent modes prioritized and optional continuous capture clearly separated.
- PDF import is a direct, top-bar action rather than an overflow-menu-only affordance.
- The empty document library offers Scan and Import PDF immediately instead of instruction-only copy.
- Document rows are unboxed transparent surfaces with separators, preserving thumbnail, selection, status, folder and tag semantics.
- The active document now exposes `Share PDF`, `Add pages`, and `Find text` as first-order actions in a horizontally scrollable toolbar; the PDF export settings and advanced tools remain available. General Document mode no longer spends a card on redundant mode explanation.
- The app theme uses a specific paper/ink neutral palette with a restrained teal primary action, compact corners, and coordinated dark mode. No giant header, home dashboard or decorative KPI layout.

## Preserved behavior / boundaries

- Still uses Google ML Kit's external scanner capture UI (a native CameraX camera replacement is a later feasibility gate, P26).
- OCR, PDF engines, native imported-PDF pass-through, file storage, Room schema, secure-vault semantics, and existing advanced editors are unchanged by P22.
- No new permissions, remote processing, accounts, advertisements or subscriptions.
- The primary Scan tag `primary-scan-action` remains for previous large-font accessibility smoke checks.

## Test contract

Android CI now compiles `assembleDebugAndroidTest` in addition to release/debug builds and JVM tests.

`ScanV2LibraryInstrumentedTest` checks:
1. Library heading and main Scan action are accessible.
2. PDF import is available directly.
3. Mode chooser opens without launching ML Kit.
4. Document, Receipt, Book and ID-mode rows exist.

`.github/workflows/v2-ui-qualification.yml` adds an API 35 emulator run with instrumented Compose tests and **three direct emulator screenshots** of an empty app library at standard light, standard dark and 200% system-font scaling. Artifacts are SHA-256 indexed. This is a preliminary visual evidence package, not a substitute for physical-device sign-off.

## Remaining P22 qualification gates

- [ ] Exact development SHA passes Android CI including unit and Android instrumentation APK compilation
- [ ] API 35 UI qualification job executes Compose instrumentation successfully
- [ ] Review actual light, dark and 200%-font screenshots for truncation, touch targets and visual hierarchy
- [ ] Review physical Samsung at common font scale and 200% font scale; compare screenshots to reference flows
- [ ] Validate small/large phone, landscape, tablet, soft keyboard, TalkBack and switch navigation
- [ ] Task-based scan/receipt/ID/PDF-import/search flow timing on hardware and log friction
- [ ] Dedicated full-screen page editor, document reader and persistent route/back-stack improvements remain for P23–P27; do not claim they are implemented now

## Next development

- If CI fails: fix that same SHA's failure first, rerun and record evidence.
- If CI passes: inspect screenshots and run supported real-device flows before marking P22 qualified.
- Proceed with P23 modularization only with focused parity tests and without destructive persistence changes.

References: `docs/v2/MASTER_PLAN.md`, `docs/v2/PRODUCT_UX_SPEC.md`, `docs/v2/P21_BASELINE_AUDIT.md`.
