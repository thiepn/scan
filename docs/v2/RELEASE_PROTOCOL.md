# Scan 2.0 — Development, versioning and release protocol

**The old v1 production signing and release flow is intentionally paused.** No v1 public tag, v1 acceptance attestation or artificial signing bypass. This document is a requirement for P39/P40, not evidence of passing them.

## Branch and baseline policy

- Stable baseline: `main@4fed03797cda8009a1df7d9fef2cd8cc03de6739` pending the owner's decision about archival retention.
- Development: `v2/development`, created directly from that SHA. Integrate reviewed incremental work there, with CI on every push.
- Keep the existing package identity `com.thiepn.scan`. Do not create a second Android package just to call it v2.
- Record code, data and UI compatibility at each phase. Carry forward all existing document formats and the Room v22 migration chain.
- A completely unshipped v1 means the app may first appear publicly as 2.0.0; still use a monotonically increasing `versionCode` (planned 2, subject to existing distributions and Play history) and retest all upgrade scenarios.
- Do not modify/tag the frozen v1 branch to unblock v2 work. No production signing required for ordinary v2 feature development.

## CI and acceptance design (build during P39)

1. Development gate: compile, lint, unit tests, Compose instrumentation, schema migration tests, permission/privacy contract, data preservation fixtures and reproducible scan/visual/PDF test corpus.
2. Quality gate: reference devices + API 26/current stable API emulator install/upgrade/launch; benchmark results with hardware details and sample fixtures.
3. Candidate build gate: fresh `2.0.0` APK/AAB using intended permanent identity, verify package/version, signing and native/OCR/export paths; preserve exact accepted bytes.
4. Human device gate: independent Samsung, Pixel and constrained-memory device runs; TalkBack, 200% font, 1,000-page, low storage, process death, capture cancel/retake, backup wrong password/tamper, fresh install and same-key upgrade.
5. Provenance gate: acceptance artifact digest, device-evidence timestamps/source SHA, exact code/artifact/signer match, independent download and hash verification.
6. Publication gate: dedicated `v2.0.0` release workflow, verified draft, immutable public GitHub release and attestations; Google Play separately after store declarations and signing-identity fingerprint confirmation.

The current scripts `scripts/verify-release.sh`, `scripts/finalize-v1-release.ps1`, `scripts/run-v1-device-qa.ps1`, `.github/workflows/release.yml`, and `.github/workflows/production-acceptance.yml` are **hardcoded for v1**. They may serve as audited patterns but must never be simply rerun against v2 artifacts or a v2 tag. Tests must prove v2-specific source, version, filenames, artifacts and signing semantics.

## Required release acceptance evidence

- [ ] Feature freeze and P21–P39 phase evidence checked in
- [ ] Zero open P0/P1; P2 residuals disclosed and approved
- [ ] Committed forward Room migration schema and migration test from v22/legacy
- [ ] Native imported PDF retention and existing backup restoration pass
- [ ] Crash/process death and low-storage scan recovery pass
- [ ] Capture module availability, graceful provider failure, first-use UX tested
- [ ] Real sample-image crop/restoration/OCR golden set, with no critical regressions
- [ ] 1,000-page search/edit/export real stress test
- [ ] Physical Samsung, Pixel and constrained-device smoke pass
- [ ] TalkBack and large-text physical-device pass
- [ ] Data safety, merged permissions, network/privacy disclosures independently audited
- [ ] Support/privacy pages reachable; Play requirements revalidated against current policy
- [ ] Permanent key safely backed up and version-compatible signer verified
- [ ] Production candidate APK/AAB produced and independent hashes recorded
- [ ] Immutable release property enabled and verified; original accepted bytes and provenance published
- [ ] Play App Signing fingerprint matches intended direct APK signing lineage **before** promising cross-channel updates
- [ ] The Play listing is public and verified **before** activating any "Get it on Google Play" distribution badge

## Fail-closed policies

- CI certification, emulator launch and a green README checklist never count as actual physical-device sign-off.
- Do not use the old v1 dev-signed APK as if it were a public production-signed distributable.
- Do not promise image quality or OCR accuracy that has not been measured on representative fixtures.
- Do not add `INTERNET` for optional AI/cloud features without an explicitly approved privacy/product revision.
- Do not discard an existing signing identity once real installations depend upon it.
- Never commit, paste into chat or print a private keystore/password. Use GitHub encrypted secrets and offline backups.
- Freeze SHA and release candidate only **after** feature development; any source change afterward requires re-certification and fresh physical acceptance evidence.
