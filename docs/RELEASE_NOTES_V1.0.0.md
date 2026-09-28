# Scan v1.0.0

Scan v1.0.0 is the first production release of the local-first Android document scanner.

## Core release

- capture, import, OCR, search, organization, editing, restoration, redaction, forms, structured extraction, assembly, standards/compliance, security, backup, and workflow automation
- local-first document storage and processing with no account or app-managed cloud document service
- no app analytics/advertising SDK, no `INTERNET` permission, and no broad storage permission
- scanner capture is supplied by Google Play services; its ML Kit document-scanner module may be downloaded or updated before first use
- secure-vault protection and encrypted portable backup
- large-document, low-storage, process-recovery, and constrained-device hardening
- Room schema v22 with explicit migration chain
- minified production APK plus Android App Bundle
- release-polished library and document hierarchy with compact page actions, progressive disclosure for advanced tools, search-aware OCR text visibility, and a cohesive light/dark color system

## Certification

The release is gated by unit regression, release lint, schema drift checks, signed APK/AAB verification, privacy-permission checks, install/update smoke tests across old and modern Android emulators, large-document contract tests, accessibility smoke checks, a production-key acceptance candidate with same-key upgrade verification, and the manual physical-device/TalkBack acceptance matrix in `docs/V1_DEVICE_QA.md`.
