# Scan v2 — P23 Architecture and Navigation Implementation

Status: **implementation landed on `v2/development`; automated and device qualification gates still apply.**  
P21 baseline and P22 UI direction remain prerequisites; no release is authorized by this report.

## Changes delivered

### Route state and back stack
- `navigation/ScanNavigation.kt` defines typed routes: `ScanRoute.Library` and `ScanRoute.Document(id)`.
- `ScanNavigationState` is a Compose-observable state holder, with `openDocument`, `showLibrary`, `navigateBack`, and `documentId` selectors.
- A versioned route codec and `rememberSaveable` saver restore document navigation after configuration change or activity recreation. Invalid, unsupported, empty, and blank route IDs fail closed to the library.
- MainActivity no longer stores a free-floating `selectedDocumentId` string in its own saveable state. It uses the typed state holder for all capture/import callbacks, vault back navigation, document deletion, and library opening.
- Android Back is explicitly routed from document to library. Alert dialogs must still preserve their own native dismissal behavior in device qualification.

### Scanner and security boundaries
- `capture/PendingScanAction.kt`: moves the existing versioned scanner-action model/codec intact, preserving its on-disk saveable encoding and existing round-trip tests.
- `capture/MlKitScanLauncher.kt`: isolates Google Play Services/ML Kit mode setup and launcher calls; it does **not** replace the external camera interface.
- `capture/ScanCapturePayload.kt`: wraps Google's scanner result in a stable local page-PDF payload before the activity dispatches ingestion.
- `security/AndroidVaultAuthenticator.kt`: isolates Android biometric/device-credential prompt setup, with no change to vault encryption, integrity, or screenshot-blocking policy.

### Lifecycle-owned import
- `capture/ScanImportViewModel.kt` owns the potentially expensive `repository.importPdf` call in `viewModelScope`, outside a Compose screen's coroutine lifetime.
- It holds only a repository-backed suspend import function and a bounded event delivery channel. It does not retain Activity/Window/Snackbar/navigation references.
- The active Compose host collects import success/failure events, navigates to the imported document, or shows an error. Import busy state blocks duplicate imports and is combined with capture busy for library actions.
- No storage or Room migration was introduced. Import remains local and original PDF preservation remains in ScanRepository.

### CI and evidence
- `ScanNavigationStateTest`: route serialization, Unicode round-trip, malformed/unknown state, invalid IDs and Android-back-equivalent transitions.
- Existing `PendingScanActionCodecTest` now imports the extracted package; the encoding contract is unchanged.
- UI emulator workflow now uses `scripts/run_v2_ui_qualification.sh` as a **single bash process**, correcting the failure where the GitHub runner tried to execute a multiline `gradle ... \\` statement as separate commands.
- The UI workflow triggers for changes in `capture/**`, `navigation/**`, `security/**`, the activity, and the emulator script.

## Non-negotiable invariants

- No `INTERNET` permission, new cloud service, analytics, account dependency, or sign-in.
- Existing Room DB version, file ownership, OCR, vault document rules, PDF preservation, and durable rapid-capture session queue remain unchanged.
- Scanner-result idempotency continues to rely on clearing the saveable pending action **before** dispatching ingestion.
- No modification to frozen main, release tags, production signing, or Play Store deployment.

## Remaining architecture debt / qualification

- [ ] Android CI green at the exact implementation SHA
- [ ] API 35 Compose tests and light/dark/200%-font screenshot evidence from the fixed workflow
- [ ] Test Back in presence of all editor dialogs and nested sheets; ensure unsaved edits cannot be bypassed
- [ ] Extend lifecycle-owned job/result delivery beyond PDF import to document scan/rapid capture and OCR management, after interruption tests
- [ ] Split very large `DocumentScreen`, `LibraryScreen`, and `ScanRepository` by feature ownership (P24–P35 staged refactors), rather than attempting an unreviewed rewrite
- [ ] Replace remaining modal editor-only flows with typed full-screen destinations after P27 page-editor parity checks
- [ ] Physical device test: return from ML Kit, rotate during PDF import, process death during capture, vault unlock, interrupted rapid loop
- [ ] Measure UI state and lifecycle regressions on low-memory devices

**P23 counts as an architectural foundation, not a claim that all historical monolithic UI/repository files have already been modularized.** Next steps should be driven by CI and device evidence; P24 must guard all persistent data migration and recovery invariants.
