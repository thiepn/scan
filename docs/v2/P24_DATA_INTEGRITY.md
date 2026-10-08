# P24 — Data integrity, staged writes and recovery

Status: **implemented in `v2/development`; final emulator/device certification pending.**
Database remains v22. Do **not** infer support for a v23 migration from this phase; no schema change was made.

## Integrity fixes implemented

### Staged asset commits

- `FileStore` now writes imported/page-copy bytes to **unique sibling staging files** in the same filesystem, flushes the writer's file descriptor before publication, and performs an atomic `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
- The previous implementation deleted the existing published destination **before** attempting `File.renameTo`. A rename failure could permanently lose a previously valid original/export. The new path never explicitly removes the old destination before publication, and fails closed when an atomic move is unsupported.
- Independently generated exports no longer share one `destination.tmp` path, which could allow overlapping export processes to overwrite each other's staged bytes.
- A bounded cold-start cleanup reclaims only private files with the new `.stage` suffix older than 24 hours. It runs on `Dispatchers.IO`, does not touch recent in-flight stages, only traverses app-owned documents/exports and has a maximum of 128 deletions per invocation.
- Existing published `document.pdf`, original JPGs and normal export paths remain unchanged. No general-purpose trash cleaner or external-file deletion was introduced.

### Encrypted backup restore

- Restoration copies into a fresh document directory, validates the restored paths, then commits documents/pages/fields via the existing `@Transaction` DAO.
- If decrypt, copy, asset/path validation or the transaction fails, only newly copied, **uncommitted** restore assets are discarded. This prevents permanent orphan directories after a partial restore.
- After the database transaction succeeds, the restored files are owned by that document. A failure to rebuild derived OCR search rows does **not** delete the restored content or report the committed restore as undone; OCR text remains in the database and search is rebuildable on startup.
- Wrong-password and tampering rejection are covered by existing encrypted backup tests. Plaintext extraction and data exposure should still be reviewed on actual devices.

### Persisted recovery tests

`FileStoreDurabilityInstrumentedTest`
- Atomic replacement publishes new bytes while old bytes remain until commit.
- Missing staged file cannot remove the previous published file.
- Simultaneous staging has distinct paths; writes do not collide.
- Cold-start pruning deletes old private stages but preserves recent stages and finished document bytes.

`V22DurabilityInstrumentedTest`
- Reopens a **real on-disk v22 database** with the original PDF path, archive/trash/favorite state and preserved book source/left-right descendants, and verifies that content and metadata survive reopening.

`InterruptedCaptureRecoveryInstrumentedTest`
- Simulates a crash during a `CAPTURING` session, reruns existing DAO startup recovery transitions, verifies capture becomes `INTERRUPTED`, a running job requeues, completed work remains completed, and both stored pages survive.

Existing `SecureBackupInstrumentedTest` verifies encrypted round-trip and password/tamper rejection. Existing `LegacyV1MigrationInstrumentedTest` verifies v1→v22 migration.

### Qualification workflow

`scripts/run_v2_ui_qualification.sh` now executes all named P22/P24 instrumentation suites in one emulator run. After the test task it explicitly reinstalls the debug APK before resetting application state and taking standard-light, dark and 200%-font screenshots. This addresses the previous workflow's post-test `pm clear` failure after Android Gradle uninstalled the app.

## Important limits / next gates

- [ ] Exact-head CI green for debug/release and JVM + Android instrumentation APK compilation
- [ ] API 35 emulator runs all listed instrumentation suites successfully and screenshots are reviewed
- [ ] Restore/capture tests with process kill, low-storage, disk-full and I/O fault injection on actual Android hardware
- [ ] Crash-safe retention of canonical assets and directory synchronization evaluated on ext4/f2fs devices; `ATOMIC_MOVE` support must be observed rather than assumed
- [ ] Migration of real pre-v22 user databases of each representative legacy version, not merely v1 and current v22 fixtures
- [ ] Performance qualification of large file copies and cold-start cleanup on a populated library
- [ ] Extend explicit staging protocol to non-`FileStore` render/cache paths after their durability audits

No Room schema version increment, destructive migration, broad storage permission, internet permission, production signing, main merge or Play Store release was performed.

Further work: P25 Library/Search UX, P26 capture-provider qualification, P27 native page editor, P28 export/share.
