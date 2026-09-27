# Scan v1.0.0 — Final physical-device QA

This runbook completes the manual evidence gates that CI cannot honestly certify.

The release candidate under test must come from the **v1 Production Device Acceptance Candidate** workflow on the exact current `main` SHA. Do not use a locally rebuilt or differently signed APK as release evidence.

## 1. Freeze the candidate

1. Confirm the current `main` SHA.
2. Confirm `Android CI` and `v1 Production Certification` are green for that exact SHA.
3. Run **v1 Production Device Acceptance Candidate** for `main`.
4. Download `scan-v1-production-acceptance-<sha>`.
5. Record:
   - acceptance workflow run URL / run ID
   - exact release SHA
   - `Scan-v1.0.0-acceptance.apk` SHA-256
   - `Scan-v1.0.0-acceptance.aab` SHA-256
   - signing certificate SHA-256 digest from `acceptance-signing.txt`
6. Verify the workflow's production-key update smoke is green.

The acceptance kit also contains `Scan-v1.0.0-pre-v1-baseline.apk`, built from the frozen Phase 20 baseline using the same production signing key. Use it for the real-device upgrade test.

## 2. Device matrix

Complete the full critical-flow smoke on:

| Profile | Required evidence |
| --- | --- |
| Samsung physical device | model, Android/API level, pass/fail, evidence directory |
| Pixel physical device | model, Android/API level, pass/fail, evidence directory |
| Mid-range / constrained-memory Android device | model, Android/API level, RAM class/free memory, pass/fail |

A device may satisfy two labels only when it genuinely exercises both requirements; record the reason. Prefer distinct devices when available.

At each important checkpoint, with the tested screen visible:

```bash
bash scripts/collect-device-evidence.sh samsung-library
bash scripts/collect-device-evidence.sh pixel-document
bash scripts/collect-device-evidence.sh constrained-export
```

The script records non-secret device/build metadata, storage/memory state, current screenshot, UI hierarchy, and foreground activity state under `device-evidence/`. Keep user documents out of screenshots used as release evidence.

## 3. Critical-flow smoke

On every required physical-device profile:

- fresh-install the production acceptance APK
- launch the library
- scan a multipage document
- import a PDF
- verify page thumbnails and document reopen
- run OCR and search for recognized text
- jump from a search result to the matching page
- rotate, crop, enhance, and use the page **More** menu
- verify an advanced page action such as markup or recognized-text editing opens correctly
- add pages to an existing document
- enter and leave page-selection mode
- export a searchable PDF and open it in another PDF viewer
- Save As through Android's document provider
- archive and restore a document
- move a document to Trash and restore it
- confirm destructive actions require explicit confirmation
- open **Document tools** and confirm advanced sections remain reachable
- exercise security settings and lock/unlock where supported
- restart the app and verify the document remains intact

Do not use personal/private documents for release evidence.

## 4. TalkBack

Enable TalkBack and complete these paths without relying on sight:

- library title, search, filters, document cards, and Scan action
- scan entry and return to library/document
- document title, Search, Export, and overflow actions
- page headings, preview identification, Rotate/Crop/Enhance, and **More page actions**
- at least one full-screen editor and its Save/Cancel controls
- export quality selection and Save/Share controls
- security settings
- Trash/Delete forever confirmations
- page and document selection mode, including selected-state announcement

Pass criteria:

- controls have meaningful names
- selected/disabled state is announced where applicable
- focus does not become trapped
- critical actions can be reached in a logical order
- no critical action exists only as an unlabeled icon

Capture evidence at representative screens.

## 5. 200% font-scale acceptance

Record the current scale, set 200%, and restore the original value afterward.

```bash
adb shell settings get system font_scale
adb shell settings put system font_scale 2.0
```

Force-stop and relaunch Scan. Verify at minimum:

- library title and primary Scan action remain reachable
- search field is usable
- filter and overflow menus remain usable
- document title is readable without hiding Back/Search/Export/More
- **Document tools** disclosure is usable
- page Rotate/Crop/Enhance/More actions remain reachable
- dialogs allow reaching all confirm/dismiss actions
- Security & privacy controls do not become unreachable

Restore the previous scale:

```bash
adb shell settings put system font_scale <previous-value>
```

## 6. Real 1,000-page stress case

Use a non-sensitive 1,000-page PDF on a device with enough temporary free space.

Verify:

- import progresses without an application crash
- already committed pages remain recoverable if the import is interrupted
- reopening the document does not require loading all page bitmaps at once
- scrolling remains bounded and previews appear progressively
- OCR/search returns results without freezing the whole UI
- document search jump-to-page works late in the document
- text export completes
- searchable PDF export either completes or fails with an explicit storage/resource error
- after export, the app remains usable without restart

Record initial/final free storage and memory state with `collect-device-evidence.sh`.

## 7. Low-storage failure and recovery

Use a disposable/test device. Do not deliberately fill a personal daily-use phone to zero bytes.

Bring free internal storage low enough to exercise Scan's preflight protection while leaving Android operational. Attempt:

- large PDF import
- PDF export
- page copy/insert operation

Pass criteria:

- Scan refuses unsafe work before corrupting the library
- failure is visible and actionable
- no half-created document is presented as complete
- temporary/staged files are cleaned up
- after freeing storage, the same workflow succeeds
- previously existing documents remain readable

Record free-space state before failure and after recovery.

## 8. Process-death recovery

Exercise both capture state and queued processing:

1. Start a multipage/rapid capture flow.
2. Return at least one captured batch to Scan.
3. While processing is queued, terminate the app process from ADB or Android settings.
4. Relaunch Scan.
5. Verify already committed pages remain present and queued work resumes or exposes a recoverable state.
6. Repeat around append/insert/retake where practical.
7. For two-sided ID capture, verify a committed front side survives cancellation/process recreation before the back side is captured.

Do not count a normal Back navigation as process-death evidence.

## 9. Encrypted backup disaster-recovery test

Using a disposable release-test document set:

1. create documents with OCR, folders/tags, organization state, and page edits
2. create an encrypted portable backup
3. copy the backup outside app-private storage
4. clear Scan app data
5. relaunch/reinstall the production acceptance APK
6. restore the backup with the correct password
7. verify documents, page order, OCR/search, assets, folders/tags, security state, workflows, and settings represented by the backup
8. retry with an incorrect password and verify rejection
9. make a modified/tampered copy of the backup and verify rejection

Never alter the only copy of the valid backup for the tamper test.

## 10. Production-signed fresh install and upgrade

### Fresh install

```bash
adb uninstall com.thiepn.scan || true
adb install Scan-v1.0.0-acceptance.apk
```

Launch and complete the critical-flow smoke.

### Upgrade from the frozen pre-v1 baseline

```bash
adb uninstall com.thiepn.scan || true
adb install Scan-v1.0.0-pre-v1-baseline.apk
```

Create representative documents and state in the baseline build. Then:

```bash
adb install -r Scan-v1.0.0-acceptance.apk
```

Verify the upgrade retains:

- documents and page order
- OCR/search
- folders and tags
- favorites/archive state
- security state
- workflows
- settings

The acceptance workflow already checks that the baseline and final APK use the same production signing certificate and performs an emulator update smoke. The physical-device pass remains required.

## 11. Final evidence record

For the exact candidate SHA, record:

- [ ] Samsung physical-device pass
- [ ] Pixel physical-device pass
- [ ] Mid-range/constrained-memory pass
- [ ] TalkBack pass
- [ ] 200% font-scale pass
- [ ] real 1,000-page stress pass
- [ ] low-storage failure/recovery pass
- [ ] process-death recovery pass
- [ ] encrypted backup restore/wrong-password/tamper pass
- [ ] fresh production-signed install pass
- [ ] production-key upgrade pass
- [ ] production signing certificate fingerprint recorded outside the repository
- [ ] APK and AAB independently verified
- [ ] zero open P0/P1 release defects

If any source commit changes, this evidence is stale and the acceptance workflow plus affected manual checks must be repeated.

Only after every item above passes, set the repository Actions variable:

```text
SCAN_V1_MANUAL_ACCEPTANCE_SHA=<exact main SHA>
```

That variable is an explicit release attestation. The tag publication workflow requires it to match the exact `v1.0.0` release SHA; setting it does not replace the underlying evidence.
