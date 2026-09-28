# Google Play release package

This directory is the source-of-truth package for Scan's Google Play presence. It does **not** mean Scan is currently published on Google Play.

## App identity

- App name: `Scan`
- Package: `com.thiepn.scan`
- Version: `1.0.0`
- Version code: `1`
- Suggested category: **Productivity**
- Default listing language: **English (United States)**
- Website: `https://thiepn.dev/scan/`
- Privacy policy: `https://thiepn.dev/scan/privacy/`
- Source: `https://github.com/thiepn/scan`
- Public support email: **REQUIRED BEFORE PLAY SUBMISSION — not stored in this repository yet**

## Listing copy

The files under `listing/en-US/` are intentionally kept inside Google Play's current limits:

- title: 30 characters maximum
- short description: 80 characters maximum
- full description: 4,000 characters maximum

Validate them with `scripts/validate-play-listing.py`.

For a whole-repository preflight, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-play-readiness.ps1
```

The preflight verifies committed store assets, exact-`main` CI/certification, signing-secret presence, production acceptance, and the public privacy-policy endpoint. It intentionally reports Play Console declarations and physical-device acceptance as manual gates rather than pretending to automate them.

The cross-channel signing decision is documented in `SIGNING_STRATEGY.md`.

## Store assets

`store-assets/` contains:

- `icon-512.png` — 512 × 512 store icon
- `feature-graphic.png` — 1024 × 500 RGB PNG
- `phone/01-library.png` through `06-export-pdf.png` — authentic API 35 app screenshots

The phone screenshots are intentionally the six 1080 × 2146 captures from the certified visual branch. The three advanced 1080 × 2400 captures are not used for Google Play because their long edge exceeds the allowed 2:1 ratio relative to the short edge.

## Distribution hierarchy

Until the Google Play listing is actually published:

1. The product page may advertise Google Play as planned.
2. It must **not** render the official "Get it on Google Play" badge as a live install CTA.
3. The direct GitHub APK remains the only production download path once v1.0.0 itself is published.

After the Play listing is public and verified:

1. Google Play becomes the primary install/update channel.
2. The official Google Play badge links to `https://play.google.com/store/apps/details?id=com.thiepn.scan`.
3. Direct APK remains available as an advanced/alternative install path.
4. The AAB remains a release artifact for Play Console, not an end-user installation option.

## Submission checklist

- [ ] Permanent production signing configured
- [ ] Final physical-device acceptance complete
- [ ] GitHub `v1.0.0` release published
- [ ] Play App Signing configuration reviewed
- [ ] `Scan-v1.0.0.aab` uploaded
- [ ] Public support email supplied in Play Console
- [ ] App category and tags selected
- [ ] Store listing copy loaded from this directory
- [ ] Icon, feature graphic and phone screenshots uploaded
- [ ] Privacy policy URL set to `https://thiepn.dev/scan/privacy/`
- [ ] Data Safety form completed using `DATA_SAFETY_DRAFT.md` plus current SDK disclosure documentation
- [ ] Content rating questionnaire completed
- [ ] App access declaration completed (no account/login required)
- [ ] Ads declaration completed (no ads)
- [ ] Target audience / content declarations completed
- [ ] Store listing reviewed against the exact production build
- [ ] Public Play listing URL verified before enabling the website Play badge

## Important

Google Play policy and SDK disclosure requirements can change. Re-check current Play Console guidance immediately before submission rather than treating this repository as legal or policy advice.
