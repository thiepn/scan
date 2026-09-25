# Scan v1.0.0 Production Certification

Phase 20 is the final release phase. No new product features are accepted here.

## Release identity

- Package: `com.thiepn.scan`
- Version name: `1.0.0`
- Version code: `1`
- Minimum Android: API 26
- Target Android: API 36
- Database: Room schema v22
- Data model: local-first; app-private files plus Room metadata
- Account requirement: none
- Mandatory cloud: none

## Automated release gates

The `v1 Production Certification` workflow must pass on the final release commit.

| Gate | Automated proof |
| --- | --- |
| Regression | Full debug unit-test suite |
| Migration registration | Every adjacent Room migration from v1 through v22 is registered in order |
| Schema freeze | Room exports schema v22 and CI rejects any uncommitted schema drift |
| Static quality | `lintRelease` |
| Minified packaging | `assembleRelease` with R8/resource shrinking |
| Play artifact | `bundleRelease` |
| Release signing mechanics | Ephemeral certification key signs the release candidate and `apksigner` / `jarsigner` verify it |
| Release identity | APK package is `com.thiepn.scan` and versionName is exactly `1.0.0` |
| Privacy permissions | APK must not request INTERNET, broad external storage, MANAGE_EXTERNAL_STORAGE, or REQUEST_INSTALL_PACKAGES |
| Fresh install | Release APK installs and launches on API 26 and API 35 emulators |
| Update path | Baseline debug build is installed, launched to create its local database, then replaced in-place by the candidate and relaunched |
| Large-document contracts | 1,000-page preview-budget and saturating-storage tests run in the unit suite |
| Large text | Emulator smoke relaunches the release candidate at 200% font scale and verifies the primary Scan surface remains present |
| Checksums | SHA-256 generated for APK and AAB |

## Manual release gates

These are evidence gates and must not be marked complete from CI alone.

- [ ] Samsung physical-device pass
- [ ] Pixel physical-device pass
- [ ] Mid-range / constrained-memory Android pass
- [ ] TalkBack pass for library, scan entry, document view, editor actions, export, security, and destructive confirmations
- [ ] Manual 200% font-scale spot check with no unreachable critical action
- [ ] Real 1,000-page import/open/search/export stress case
- [ ] Low-storage failure/recovery on a real device
- [ ] Process-death recovery during capture and queued processing
- [ ] Encrypted portable backup restore after app-data wipe; verify document/pages/OCR/assets, wrong-password rejection, and tamper rejection
- [ ] Fresh install from the production-signed APK
- [ ] Upgrade from the last pre-v1 candidate while retaining documents, OCR, folders/tags, security state, workflows, and settings
- [ ] Production keystore fingerprint recorded outside the repository
- [ ] Signed production APK and AAB independently verified
- [ ] Zero open P0/P1 release defects

## Release decision

`v1.0.0` is publishable only when:

1. the final commit passes all automated gates,
2. every manual gate above has recorded evidence,
3. the production signing secrets are configured,
4. the `v1.0.0` tag points to that exact certified commit.

Do not weaken or bypass a failed gate to publish.
