# Scan v2.0.0 — Distribution staging (NOT a store listing)

This folder documents v2-specific metadata only. Do not copy `play/store-assets` or the v1 listing verbatim into a v2 submission. No screenshots, signing keys, false Play URLs or staged publication artifacts are committed here.

## Draft identity

- Product: Scan
- Android application ID: `com.thiepn.scan`
- Development version: `2.0.0`, `versionCode 2` (confirm higher than the maximum Play/internal-test versionCode in P40)
- Category: Productivity (review actual store taxonomies at submission)
- Primary language: English; German translation must receive native-speaker review
- Website: https://thiepn.dev/scan/
- Privacy: https://thiepn.dev/scan/privacy/ (verify reachable and accurate before listing)
- Support: **user-supplied verified public support email required**

## No claims without app evidence

Only list functions demonstrably available in the final P40 signed release build. Do not advertise P25–P38 standalone ZIP code as shipped features. No invented OCR accuracy, automatic AI processing, or cloud capabilities. Do not reuse v1 screenshots as 2.0 images.

## Assets required after integration

- App store icon: actual v2 icon, correct Google Play dimensions and format
- Feature graphic: real v2 design, reviewed for legibility and branding
- Phone screenshots from final v2 SHA (light/dark, library, scan, editor, OCR, save/share)
- Record screenshot capture device, API, original PNG checksum, source commit SHA and build ID
- Tablet screenshots only if tablet design is truly qualified
- English/German title, short/full descriptions in current Play limits and fact-checked against app
- Version-specific release notes

## Privacy, SDK and policy check

1. Audit **merged AAB/APK permissions**, not just `AndroidManifest.xml`. Scan must not silently gain `INTERNET` or broad-storage permission.
2. Review third-party ML Kit/Play services diagnostic collection even with app-level `INTERNET` removed. https://developers.google.com/ml-kit/android-data-disclosure
3. Recheck Play target API policy (new apps and app updates: API 36+ from August 31, 2026 as assessed on October 9): https://support.google.com/googleplay/android-developer/answer/11926878
4. Fill Play Console Data Safety, content rating, ads, app access, target audience, privacy policy and SDK forms based on the exact signed v2 candidate.
5. Confirm actual public Play page before adding a live Play download badge.
6. Verify Play app-signing lineage versus direct APK signature: cross-channel mismatch could make an app-private library inaccessible after a forced uninstall.

## Publication lock

`v2-publication-rehearsal.yml` verifies only disposable-key certification artifacts and **cannot publish**. P40 supplies permanent signing, physical-device evidence, independent proof of same-key updates, SHA freeze and approved publication.
