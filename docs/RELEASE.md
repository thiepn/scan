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
- exports `scan-production-certificate.pem` for Play App Signing identity/fingerprint checks,
- configures all four encrypted GitHub Actions signing secrets,
- starts the production-acceptance workflow automatically.

Back up the keystore in at least two secure offline locations and store both passwords in a password manager. Losing this identity can prevent compatible direct-APK updates. Never commit the keystore or passwords.

After completing the physical-device acceptance matrix, use `scripts/finalize-v1-release.ps1`. The finalizer sets two non-secret repository variables only after strict evidence verification and exact-SHA human attestation:

- `SCAN_V1_MANUAL_ACCEPTANCE_SHA` — exact accepted `main` SHA
- `SCAN_V1_ACCEPTANCE_RUN_ID` — exact successful production-acceptance run whose artifact was physically tested

Do not set either variable manually. The publication workflow requires both and validates the recorded run independently.

## Production-signed acceptance candidate

Before tagging, the exact final `main` SHA must have a successful **v1 Production Device Acceptance Candidate** workflow run.

That workflow:

1. requires the real production signing secrets,
2. builds the exact `main` candidate as a production-signed APK/AAB,
3. builds the frozen Phase 20 pre-v1 baseline with the same production key,
4. verifies package/version/privacy/signatures and matching signing certificates,
5. performs an API 35 production-key update + fresh-install smoke,
6. uploads a 90-day acceptance kit for the required physical-device tests and exact-release publication.

Use that acceptance kit for every manual release gate. See `docs/V1_DEVICE_QA.md`.

## Pre-tag checklist

1. Enable GitHub release immutability at **Repository Settings → General → Releases → Enable release immutability**. This must be enabled before `v1.0.0` is published.
2. Confirm all release-candidate work is merged to `main`.
3. Confirm the exact final `main` HEAD passes Android CI and the push-triggered v1 Production Certification workflow.
4. Confirm the exact final `main` HEAD passes **v1 Production Device Acceptance Candidate** and download its production-signed acceptance kit.
5. Complete and record every manual gate in `docs/V1_DEVICE_QA.md`.
6. Record the production signing certificate fingerprint outside the repository.
7. Package and strictly verify the completed device evidence; do not set release-attestation variables manually.
8. Confirm `app/build.gradle.kts` still reports `versionCode = 1` and `versionName = "1.0.0"`.
9. Confirm Room schema v22 is committed and CI reports no schema drift.
10. Confirm there are zero open P0/P1 release defects.

## Publish

Create an annotated or lightweight tag named exactly:

```text
v1.0.0
```

The tag-driven `Publish v1 Release` workflow then:

1. rejects the tag unless `v1.0.0` resolves to the exact current `main` HEAD and that SHA already has a successful push-triggered v1 Production Certification run,
2. requires `SCAN_V1_MANUAL_ACCEPTANCE_SHA` to equal that exact SHA,
3. requires `SCAN_V1_ACCEPTANCE_RUN_ID` to identify a successful completed `.github/workflows/production-acceptance.yml` run for exact `main`,
4. requires exactly one non-expired acceptance artifact named `scan-v1-production-acceptance-<sha>` from that run,
5. downloads that original GitHub Actions artifact instead of rebuilding the app,
6. verifies its exact six-file acceptance-kit shape, internal SHA-256 manifest, release SHA, workflow run ID, baseline SHA, and signing-certificate digest agreement,
7. copies the **exact accepted APK/AAB bytes** to `Scan-v1.0.0.apk` and `Scan-v1.0.0.aab`,
8. computes public SHA-256 checksums for those exact bytes,
9. writes `release-provenance.txt` with the accepted source SHA, acceptance run ID, artifact name, APK/AAB/baseline hashes, and signer SHA-256,
10. publishes the exact accepted APK, exact accepted AAB, checksum file, and provenance record in the GitHub Release.

The tag-time publication job no longer rebuilds Scan and no longer needs the private signing key. The binary that was physically tested is the binary that is published.

The workflow intentionally rejects any tag other than `v1.0.0` for this frozen v1 release.

### Guarded final release command

After **every** manual gate in `docs/V1_DEVICE_QA.md` is complete for the exact current `main` SHA, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\finalize-v1-release.ps1
```

The finalizer refuses to continue unless the exact current `main` SHA already has successful normal certification and production-key acceptance runs. It strictly verifies the complete evidence package, re-downloads and byte-matches the original acceptance artifact, requires you to type the exact SHA as the manual acceptance attestation, rechecks that `main` has not moved, records both `SCAN_V1_MANUAL_ACCEPTANCE_SHA` and the evidence-bound `SCAN_V1_ACCEPTANCE_RUN_ID`, creates `v1.0.0`, watches publication to completion, resolves the GitHub Release, and closes release-blocker issue #20 only after publication succeeds.

It never substitutes for the physical-device evidence; it only makes the irreversible release step hard to perform incorrectly.

## Google Play

The reproducible Play Console listing package lives under `play/`.

Before opening the production track:

Read `play/SIGNING_STRATEGY.md` first. The permanent Scan production key is the intended app-signing identity for both direct APK distribution and Google Play; do not accept an unrelated Play app-signing identity and assume cross-channel updates remain compatible.


1. Run `powershell -ExecutionPolicy Bypass -File .\scripts\check-play-readiness.ps1` (which includes the listing validator).
2. Confirm the public privacy policy is available at `https://thiepn.dev/scan/privacy/`.
3. Confirm the app's Library overflow still exposes the Privacy policy link.
4. Supply the public support email in Play Console; it is intentionally not invented or stored in this repository.
5. Review `play/DATA_SAFETY_DRAFT.md` against Google's current Data Safety form and current ML Kit disclosure documentation.
6. Upload the title, descriptions, icon, feature graphic and six authentic phone screenshots from `play/`.
7. Complete App access, Ads, Content rating, Target audience, Data Safety and other required Play Console declarations.
8. Use `Scan-v1.0.0.aab` from the verified GitHub Release for Play Console upload.
9. Confirm Play Console shows package `com.thiepn.scan`, version code `1`, target API 36, and the expected Play App Signing / production-key lineage.
10. Keep `thiepn.dev/scan` in the `planned` or `testing` distribution state until the public Play listing is actually available.
11. Only after public verification, change the website distribution source of truth to `published`; the official Google Play badge then becomes the primary install CTA while the signed GitHub APK stays available as an alternative.

Do not use the AAB as an end-user download. It is a publishing artifact for Google Play.

## Draft-first GitHub publication

The `v1.0.0` tag workflow does **not** make the GitHub Release public immediately.

After the finalizer creates the accepted tag, `.github/workflows/release.yml`:

1. resolves the evidence-bound successful production-acceptance run;
2. downloads the original acceptance artifact from that exact run;
3. promotes the exact accepted APK/AAB bytes into an isolated release payload;
4. creates a **draft** GitHub Release containing exactly:
   - `Scan-v1.0.0.apk`
   - `Scan-v1.0.0.aab`
   - `release-checksums.sha256`
   - `release-provenance.txt`
5. re-downloads GitHub's stored draft assets into a clean directory;
6. requires the remote asset set to be exact;
7. compares every downloaded byte with the trusted local promoted payload;
8. validates the downloaded checksum manifest and evidence-bound provenance;
9. only then changes the release from draft to public.

If draft verification fails, the release remains non-public and the workflow fails. A rerun may replace a stale **draft** from the same tag, but it refuses to overwrite an already-public release.

The publication verifier is `scripts/verify-release-publication.sh` and its adversarial CI self-test is `scripts/test-verify-release-publication.sh`.

## Immutable publication lock

Scan v1.0.0 requires GitHub's **immutable releases** setting.

The local finalizer queries GitHub's repository immutability setting and refuses to create the tag unless immutability is enabled. The tag workflow still performs draft-first byte verification. After changing the verified draft to public, it then:

1. requires the release API to report `immutable: true`;
2. verifies GitHub's automatically generated immutable-release attestation;
3. re-downloads the now-public assets;
4. byte-compares them again with the trusted promoted payload;
5. re-validates checksums and acceptance provenance;
6. verifies each of the four public release assets against GitHub's release attestation.

Once publication succeeds, GitHub locks both the release assets and the associated tag against modification. GitHub also creates a cryptographically verifiable release attestation for the tag, commit SHA, and assets.

Anyone can verify the finished release with a current GitHub CLI:

```bash
gh release verify v1.0.0
gh release verify-asset v1.0.0 Scan-v1.0.0.apk
gh release verify-asset v1.0.0 Scan-v1.0.0.aab
```

If the workflow ever publishes a release that GitHub does not report as immutable, it deletes that mutable release and fails rather than accepting it as v1.0.0.

The finalizer requires strict physical-device evidence and byte-binds the local acceptance kit to the original production acceptance artifact from the recorded successful GitHub Actions run before tagging v1.0.0.
