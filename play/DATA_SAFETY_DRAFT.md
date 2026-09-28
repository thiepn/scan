# Google Play Data Safety draft

This is an engineering handoff for completing Play Console's Data Safety form. It is **not** a legal determination and must be checked against the exact production dependency graph and Google's current disclosure instructions at submission time.

## First-party Scan behavior

Based on the frozen v1 architecture:

- no account system
- no advertising SDK
- no app analytics SDK
- no app-managed cloud document service
- no app `INTERNET` permission in the merged release artifact
- document files stored in app-private storage
- metadata/OCR stored in local Room/SQLite
- exports occur only when the user explicitly saves or shares them
- encrypted portable backup is user-created and user-controlled
- Android system document providers may receive exported files when the user chooses a destination
- Scan itself does not upload scanned document images, OCR text, PDFs, folders, tags, or form values to THIEPN servers

## Google Play services / ML Kit

Scan uses Google Play services' ML Kit document-scanner flow and ML Kit OCR libraries.

Google's ML Kit documentation states:

- scanned image/text inputs and ML outputs are processed on-device and are not sent to Google servers as feature input/output content;
- ML Kit SDKs may collect device information, app information, identifiers, performance metrics, API configuration, feature input/output size, feature versions, event types and error codes for diagnostics and usage analytics;
- collected SDK metrics are encrypted in transit and Google states the listed data is not shared with third parties;
- Play-services-delivered models and scanner components may contact Google to download or update models/resources.

These SDK disclosures must be evaluated against Play Console's current definitions of "collected", "shared", purposes, optionality and ephemeral processing.

## Likely Play Console topics to review

Do not mechanically answer the form from this list. Review the exact current form.

- Device or other identifiers
- App information
- App performance / diagnostics
- App interactions / SDK usage events
- Whether Google Play services / ML Kit collection qualifies as collection by the app under the current Play definition
- Encryption in transit for SDK diagnostics
- No sale of user data
- No first-party advertising use
- No first-party account data
- No location, contacts, SMS, call log, health, financial/payment, browsing-history or microphone collection by Scan
- Document/camera content remains on-device in the Scan architecture

## Privacy policy consistency

The final Data Safety answers must stay consistent with:

- `https://thiepn.dev/scan/privacy/`
- the exact production manifest
- the exact production dependency graph
- Google's current ML Kit data-disclosure documentation

If any dependency, analytics integration, crash reporter, account system, cloud backup or remote processing is added later, review both the privacy policy and Data Safety form before shipping that build.
