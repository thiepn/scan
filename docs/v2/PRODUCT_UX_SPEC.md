# Scan 2.0 — Product and interaction specification

Status: direction for P22; **visual references and real-device screenshots still required**.

## Experience principles

1. **Not a dashboard.** No KPI tiles, giant marketing headline, SaaS side rail or generic purple gradient hero. The application starts at the document library (or capture handoff, if deliberately invoked).
2. **Camera-first without empty ceremony.** A persistent primary Scan action on the library surface; the last used scan mode is remembered and changeable before capture.
3. **A small number of genuine routes.** Major editing tasks are full-screen pages, not stacked floating dialogs. Confirmation, a small filter and quick one-off settings may use sheets/dialogs.
4. **One safe default.** Default scan -> review -> save -> share works without organizing, naming, account setup or manually tuning restoration controls.
5. **State is visible.** The user can tell captured vs saving vs OCR queued vs searchable vs export ready. No spinner that hides unsaved changes.
6. **Advanced features at the point of need.** A user looking at an ID sees ID actions; book spreads expose book controls. General library is not littered with PDF standard and security controls.
7. **Every source remains recoverable.** Original/edited preview toggle, Undo, Restore source when applicable, and confirmation for irreversible operations.
8. **Android-native interaction.** System share sheet, document picker, back gesture, large-font reflow, haptics only when meaningful and accessible semantics.

## Information architecture

| Route | Main job | Visible primary actions | Secondary/advanced entry |
| --- | --- | --- | --- |
| Library | find and reopen scans | Scan, search, recent documents | folders/tags, sort, batch action |
| Capture | aim and capture | shutter/auto capture, mode, flash | import, settings if supported by provider |
| Review | inspect recent pages safely | Save, Add page, reorder, retake | enhance/crop, scan mode hints |
| Document | read and share | Share PDF, Edit, Add page | More: organize, export variants, security |
| Page Editor | fix the current page | Crop, Rotate, Enhance, Cleanup, Undo, Done | text replacement, markup when applicable |
| Search | find page/text | query, result highlights, jump to page | filters for type/tag/folder |
| Collections | manage folders/tags | create, move, bulk actions | smart collections and automation |
| Settings | app behavior/privacy | default scan mode, storage/backup | accessibility, advanced workflows |

The Capture route may temporarily hand off to ML Kit's external Google Play services scanner UI. **Do not design or promise control over that external camera interface.** A dedicated in-app CameraX route is conditional on the P26 benchmark and compatibility gate.

### Library surface

- Compact title and immediate search affordance; list/grid mode and sort; recent/smart collections as low-noise compact chips or subheadings, not cards occupying the entire screen.
- Thumbnails use a consistent page aspect ratio and a meaningful title/page count/date; OCR processing or Needs Review shows a quiet, actionable state.
- Large libraries are lazy/paged and maintain search/selection stability during navigation and background work.
- The Scan action remains clearly visible. Multi-selection converts the toolbar to bulk actions without permanent visual clutter.

### Capture and review

- Scan starts with the previous or sensible default mode (Document); Book/Receipt/ID/Photo are accessible in a small mode selector.
- Never claim pages are saved until the provider results are durably copied and transactionally registered.
- Once capture returns, present saved page thumbnails immediately and queue OCR separately. A failure offers Retry/Keep pages, not total loss.
- Review prioritizes reorder, crop, retake, rotation and add page. Batch adjustments are explicit and reversible.
- No requirement to finish OCR before basic PDF share unless user explicitly requests searchable output.

### Document reader/editor

- Page display occupies most of the view; a compact page strip and page counter make multi-page navigation obvious.
- The most common actions (Share, Edit, Add page) remain reachable. Use context-specific tool groups; expert PDF controls live behind a clearly named Tools route.
- Display original/edited/export preview distinctions honestly. Native imported PDF is preserved until a transformation requires rebuilding.
- Text results indicate whether derived OCR is pending/stale/available and whether user edits are active.

### Appearance and motion

- Utility-oriented, neutral near-white / graphite surfaces, dark mode parity; one restrained ink-blue or sea-green action accent with meaning.
- Strong information hierarchy through spacing, typography and image area, not repeated decorative containers.
- Do not imitate unrelated THIEPN website/dashboard designs; this is an Android scanner.
- Animations must communicate capture/progress/navigation; support reduced-motion settings and avoid blocking interactions.
- Avoid permanent bottom navigation with arbitrary features. Prefer contextual destinations, a visible Scan action and proper back-stack routes.

### Interaction/a11y contract

- TalkBack labels identify action, document/page and state.
- 200% font scale reflows and exposes scrollable controls rather than clipping text.
- Back gesture preserves pending edits/captured pages. Destructive delete/redaction has a distinct irreversible confirmation.
- Loading, export and OCR states have cancellation/retry rules; notify completion without hiding errors.
- Landscape/tablets adapt through spatial layout (e.g. preview + page rail), not enlarged phone cards.

## Measurable research/QA tasks

- Ask testers to scan and share one receipt, import one PDF, find a phrase, retake a page, scan an ID, undo an edit, and restore a backup.
- Record success rate, taps, time, mistakes, where the user looked for actions and screen-reader blockers.
- Collect screenshot/video evidence for small phone, large phone, 200% font, dark/light and tablet before declaring P22 complete.
- Performance goals must be calibrated using P21 baseline: proposed benchmark *targets* (not observed claims) are <2 s interactive warm library on reference mid-range device, median <20 s one-page capture-to-share under consistent lighting excluding first scanner-module download, no dropped/missing pages under interrupted sessions, and zero known P0/P1 defects.
