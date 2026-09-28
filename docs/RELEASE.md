# Production release procedure

## Required GitHub Actions secrets

Configure these repository secrets before creating the release tag:

- `SCAN_RELEASE_KEYSTORE_BASE64` — base64 of the production JKS/keystore
- `SCAN_RELEASE_STORE_PASSWORD`
- `SCAN_RELEASE_KEY_ALIAS`
- `SCAN_RELEASE_KEY_PASSWORD`

The keystore itself must never be committed.

### One-time Windows signing bootstrap

On the trusted Windows machine that will own the permanent Scan signing identity, clone/update the repository, authenticate GitHub CLI, then run:

```powershell
gh auth login
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-production-signing.ps1
```

The script:

- refuses to overwrite an existing production identity,
- can reuse an existing keystore via `-ExistingKeystorePath`,
- otherwise requires an explicit `CREATE` confirmation before generating the permanent key,
- prompts for passwords without echoing them,
- stores the keystore outside the repository by default at `%USERPROFILE%\ScanSigningBackup\scan-production.jks`,
- records the public certificate/fingerprint information beside that backup,
- configures all four encrypted GitHub Actions signing secrets,
- starts the production-acceptance workflow automatically.

Back up the keystore in at least two secure offline locations and store both passwords in a password manager. Losing this identity can prevent compatible direct-APK updates. Never commit the keystore or passwords.

After completing the physical-device acceptance matrix for the exact release SHA, set the non-secret repository Actions variable:

- `SCAN_V1_MANUAL_ACCEPTANCE_SHA` — exact `main` SHA whose manual gates in `docs/V1_DEVICE_QA.md` have all passed

Do not set this variable early. It is the explicit human release attestation checked by the publication workflow.

## Production-signed acceptance candidate

Before tagging, the exact final `main` SHA must have a successful **v1 Production Device Acceptance Candidate** workflow run.

That workflow:

1. requires the real production signing secrets,
2. builds the exact `main` candidate as a production-signed APK/AAB,
3. builds the frozen Phase 20 pre-v1 baseline with the same production key,
4. verifies package/version/privacy/signatures and matching signing certificates,
5. performs an API 35 production-key update + fresh-install smoke,
6. uploads a 14-day acceptance kit for the required physical-device tests.

Use that acceptance kit for every manual release gate. See `docs/V1_DEVICE_QA.md`.

## Pre-tag checklist

1. Confirm all release-candidate work is merged to `main`.
2. Confirm the exact final `main` HEAD passes Android CI and the push-triggered v1 Production Certification workflow.
3. Confirm the exact final `main` HEAD passes **v1 Production Device Acceptance Candidate** and download its production-signed acceptance kit.
4. Complete and record every manual gate in `docs/V1_DEVICE_QA.md`.
5. Record the production signing certificate fingerprint outside the repository.
6. Set `SCAN_V1_MANUAL_ACCEPTANCE_SHA` to the exact accepted `main` SHA only after all manual gates pass.
7. Confirm `app/build.gradle.kts` still reports `versionCode = 1` and `versionName = "1.0.0"`.
8. Confirm Room schema v22 is committed and CI reports no schema drift.
9. Confirm there are zero open P0/P1 release defects.

## Publish

Create an annotated or lightweight tag named exactly:

```text
v1.0.0
```

The tag-driven `Publish v1 Release` workflow then:

1. rejects the tag unless `v1.0.0` resolves to the exact current `main` HEAD and that SHA already has a successful push-triggered v1 Production Certification run,
2. requires a successful production-signed acceptance-candidate run for that exact SHA,
3. requires `SCAN_V1_MANUAL_ACCEPTANCE_SHA` to equal that exact SHA,
4. requires the production signing secrets,
5. reconstructs the keystore only inside the runner,
6. runs unit tests and release lint,
7. builds the minified production APK and Play AAB,
8. renames the artifacts to their public release names before verification,
9. verifies APK/AAB signatures, package ID, version, and privacy permissions,
10. computes SHA-256 checksums whose filenames match the downloadable release assets,
11. publishes the APK, AAB, and checksum file in the GitHub Release.

The workflow intentionally rejects any tag other than `v1.0.0` for this frozen v1 release.

### Guarded final release command

After **every** manual gate in `docs/V1_DEVICE_QA.md` is complete for the exact current `main` SHA, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\finalize-v1-release.ps1
```

The finalizer refuses to continue unless the exact current `main` SHA already has successful normal certification and production-key acceptance runs. It then requires you to type that exact SHA as the manual acceptance attestation, sets `SCAN_V1_MANUAL_ACCEPTANCE_SHA`, creates `v1.0.0`, watches the publication workflow to completion, resolves the GitHub Release, and closes release-blocker issue #20 only after publication succeeds.

It never substitutes for the physical-device evidence; it only makes the irreversible release step hard to perform incorrectly.

## Google Play

Use `Scan-v1.0.0.aab` from the verified GitHub Release for Play Console upload. Confirm Play Console shows package `com.thiepn.scan`, version code `1`, target API 36, and the same signing lineage expected by the configured production keystore / Play App Signing setup.
