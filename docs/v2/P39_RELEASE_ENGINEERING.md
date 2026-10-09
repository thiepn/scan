# P39 — V2 release engineering and distribution

Status: **implementation in `v2/development`; certification and physical-device qualification are not complete.**
Source of truth: `docs/v2/MASTER_PLAN.md` (the official P39 entry was read on 2026-10-09).

## Release identity

- Application ID remains `com.thiepn.scan`.
- The v2 development branch now uses **`versionName = "2.0.0"`** and **`versionCode = 2`**. The previously committed v1 baseline had `1.0.0` / code `1`.
- The GitHub release list was empty at the time of the P39 audit, but **this does not establish Play Console versionCode history**. P40 must confirm code 2 exceeds *every* versionCode already installed/distributed, including private/internal tests, before final signing.
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`; `targetSdk 36` matches Google's 31 August 2026 requirement for new non-exempt Android apps **as checked on 2026-10-09**. Recheck at P40.
- Room database **v22** and all pre-v22 migrations remain unchanged. No destructive migration is allowed.

## New v2-specific release workflows

| Path | Purpose | Allowed to publish? |
| --- | --- | --- |
| `.github/workflows/v2-development.yml` | Version/privacy preflight, fixture tests, JVM tests, debug APK/test APK and *merged* permission audit | No |
| `.github/workflows/v2-certification.yml` | v2-branch release-contract change or manual build of 2.0.0 APK + AAB, lint/tests, byte hashes and signing report with a **disposable test key** | No |
| `.github/workflows/v2-device-acceptance.yml` | v2-branch release-contract change or manual API 26 and API 35 emulator UI/storage/instrumentation suite with exact-SHA evidence | No |
| `.github/workflows/v2-publication-rehearsal.yml` | Read-only exact-commit verification of a successful certification run and downloaded checksums | No |

These workflows use new names and artifact names (`scan-v2-*`, `Scan-v2.0.0-CERTIFICATION-ONLY.*`). They do **not** invoke the hardcoded v1 promotion, v1 acceptance, or publishing scripts. The frozen `main` branch remains unchanged. On `v2/development`, the legacy `.github/workflows/release.yml` v1 tag trigger is narrowed from `v*` to `v1.*` so a future `v2.0.0` tag cannot accidentally start the hardcoded v1 release workflow. No v1 publication logic or signed artifact was executed. The publication rehearsal has `contents: read`, not `contents: write`, and therefore cannot create a GitHub Release.

The **certification key is ephemeral and knowingly unsuitable for update-compatible production distribution**. Never publish the dry-run APK or upload its AAB to Play.

## Dependency and privacy inventory (declarations, not audit sign-off)

Versions are explicitly specified in `app/build.gradle.kts` at the direct dependency level, including:
- Android Gradle Plugin 9.4.0, Kotlin Compose plugin 2.4.10, KSP 2.3.12, Room 2.8.5.
- Compose BOM 2026.09.00, AndroidX core 1.17.0, Activity Compose 1.13.0, Lifecycle 2.11.0, Biometric 1.1.0.
- Kotlin coroutines 1.10.2, bundled SQLite 2.7.1.
- Google Play services ML Kit document scanner 16.0.0; text-recognition Latin/CJK/Devanagari packages 16.0.1.
- PdfBox Android 2.0.27.0, JUnit 4.13.2.

**Not yet signed off:** transitive dependency locks/reproducibility, current library licenses and notices, supply-chain advisories, merged release SBOM, SDK runtime data behavior, and Play SDK Index warnings. Capture a full release dependency tree and perform legal/license review on the **exact P40 candidate**; do not infer the transitive graph from these direct declarations.

The source manifest explicitly removes transitive `INTERNET` and disables Android backup. This is checked again on the **merged APK** in v2 CI/certification; an XML source check alone is insufficient. Google ML Kit's own documented diagnostic data collection/model deliveries still require a current Google Play Data Safety review, even if Scan itself has no active `INTERNET` permission.

## Store/distribution readiness

- Keep `play/` v1 listing copy and assets as historical material; **do not relabel v1 screenshots as v2**. V2-specific asset requirements are recorded in `play/v2/README.md`.
- Produce authentic, correctly sized v2 screen captures from a **feature-complete exact candidate** after P25–P38 integration; store hashes, device identifiers and captured source SHA.
- Re-review `play/DATA_SAFETY_DRAFT.md`, `play/SIGNING_STRATEGY.md`, privacy URL `https://thiepn.dev/scan/privacy/`, support email, SDK disclosures, content rating, and Play app-access declarations.
- Do not show a live Google Play badge until the actual public Play listing is independently verified.
- Current official target-API policy: https://support.google.com/googleplay/android-developer/answer/11926878
- ML Kit disclosure: https://developers.google.com/ml-kit/android-data-disclosure

## Outstanding release gates — BLOCKING

- [ ] P25–P38 genuinely integrated and verified in the real app. The prior standalone ZIPs are not integrated features merely because Kotlin smoke tests passed.
- [ ] P38 accessibility, real device and localization acceptance (official P38).
- [ ] Exact-head v2-development CI succeeds.
- [ ] v2 disposable-key certification finishes successfully and artifact checksum/provenance are inspected.
- [ ] API 26 / 35 emulator evidence reviewed; **physical** Samsung, Pixel, constrained-memory device tests are still required.
- [ ] Rehearse `v2-publication-rehearsal.yml` with a successful exact-SHA certification run.
- [ ] Confirm highest distributed versionCode with Play Console.
- [ ] Full transitive dependency, security and license review on the frozen final candidate.
- [ ] Accurate v2 screenshots, release notes, policies and Data Safety declaration.
- [ ] Production signing identity selection, same-key update, exact SHA freeze and final immutable publication: **P40 only**.

## Operator procedure

1. Finish P25–P38 integration and keep `main` frozen.
2. Check development gate for exact v2 head and exported logs/merged permissions.
3. Use the v2-branch `P39_RELEASE_ENGINEERING.md` push trigger to run **Scan v2 Certification Dry Run**. GitHub `workflow_dispatch` usually requires workflow registration on the default branch; it must not be assumed functional while `main` stays frozen.
4. Inspect the `scan-v2-certification-<SHA>` artifact's APK, AAB, signing report, provenance and checksum list. Note test signing only.
5. Use the same v2 branch push to execute **Scan v2 Device Acceptance (Emulators)** and review API-specific evidence. Manual dispatch after default-branch registration is optional.
6. **After the workflow exists on the default branch**, run **Scan v2 Publication Rehearsal (Read Only)** with the exact SHA and certification run ID. Before that, inspect local certification checksums and reports; the independent GitHub re-download rehearsal remains outstanding.
7. P40: production key, physical update compatibility, approval and release; do **not** reuse the disposable certification artifacts.

No P39 script, job or document is permission to publish to GitHub or Google Play.
