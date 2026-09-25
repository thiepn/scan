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
2. Confirm the final `main` commit passes Android CI and v1 Production Certification.
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

1. requires the production signing secrets,
2. reconstructs the keystore only inside the runner,
3. runs unit tests and release lint,
4. builds the minified production APK and Play AAB,
5. verifies APK/AAB signatures, package ID, version, and privacy permissions,
6. computes SHA-256 checksums,
7. publishes the APK, AAB, and checksum file in the GitHub Release.

The workflow intentionally rejects any tag other than `v1.0.0` for this frozen v1 release.

## Google Play

Use `Scan-v1.0.0.aab` from the verified GitHub Release for Play Console upload. Confirm Play Console shows package `com.thiepn.scan`, version code `1`, target API 36, and the same signing lineage expected by the configured production keystore / Play App Signing setup.
