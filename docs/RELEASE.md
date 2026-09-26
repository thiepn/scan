# Production release procedure

## Required GitHub Actions secrets

Configure these repository secrets before creating the release tag:

- `SCAN_RELEASE_KEYSTORE_BASE64` — base64 of the production JKS/keystore
- `SCAN_RELEASE_STORE_PASSWORD`
- `SCAN_RELEASE_KEY_ALIAS`
- `SCAN_RELEASE_KEY_PASSWORD`

The keystore itself must never be committed.

## Pre-tag checklist

1. Merge the Phase 20 certification PR.
2. Confirm the exact final `main` HEAD passes Android CI and the push-triggered v1 Production Certification workflow.
3. Complete and record every manual gate in `docs/V1_CERTIFICATION.md`.
4. Confirm `app/build.gradle.kts` still reports `versionCode = 1` and `versionName = "1.0.0"`.
5. Confirm Room schema v22 is committed and CI reports no schema drift.
6. Confirm there are zero open P0/P1 release defects.

## Publish

Create an annotated or lightweight tag named exactly:

```text
v1.0.0
```

The tag-driven `Publish v1 Release` workflow then:

1. rejects the tag unless `v1.0.0` resolves to the exact current `main` HEAD and that SHA already has a successful push-triggered v1 Production Certification run,
2. requires the production signing secrets,
3. reconstructs the keystore only inside the runner,
4. runs unit tests and release lint,
5. builds the minified production APK and Play AAB,
6. renames the artifacts to their public release names before verification,
7. verifies APK/AAB signatures, package ID, version, and privacy permissions,
8. computes SHA-256 checksums whose filenames match the downloadable release assets,
9. publishes the APK, AAB, and checksum file in the GitHub Release.

The workflow intentionally rejects any tag other than `v1.0.0` for this frozen v1 release.

## Google Play

Use `Scan-v1.0.0.aab` from the verified GitHub Release for Play Console upload. Confirm Play Console shows package `com.thiepn.scan`, version code `1`, target API 36, and the same signing lineage expected by the configured production keystore / Play App Signing setup.
