# Session log
- Prepared `handover.md` for the next agent: integrate the standalone `OCR-rules` template into an editable, app-private OCR rules system; preserve raw OCR, keep contextual pipe handling built in, add user add/delete/reset controls, and validate against synthetic cases plus the untracked `rawtext1.txt` sample.
- Working rule: do not use complex shell-inline edits for Kotlin or XML. Make one validated file change at a time, inspect its diff, build immediately, and restore promptly on any failure.

Add each session summary immediately below this heading so the newest entry remains first.

## 2026-11-24 (later) — Library, link-safe page turns, illustrations

- Library: every scan is its own book under files/books (the old single book migrates in automatically); main screen lists Your books with author, screens, pictures, quality and EPUB status. Book actions: Review, Continue scanning, Save/Update EPUB in Documents, Open in EPUB reader, Edit title and author, Delete. Test scans stay separate. Import creates a new book.
- EPUBs auto-save to Documents/Wombat Liberates via MediaStore, replacing the previous file for that book; Review shows where/when and flags when the text, pictures or cover changed since.
- Page turns are swipes (never follow links or footnotes), with right-margin taps as fallbacks; if a link still takes the screen away from Kindle the scan presses Back up to 3 times, then pauses with a Continue prompt.
- Illustrations: large text-free areas with detail are cropped from each screen and saved as JPEGs with markers in the text; picture-only pages are kept. In the first 10 screens of a new scan the user is asked Use as cover / Not the cover. Review shows thumbnails (view larger, use as cover, delete). EPUBs include pictures in place and mark the cover (not repeated inside). AI cleanup preserves markers. Setting: Save illustrations into the book (default on).
- Wombat Reader note: the server clone was behind GitHub (DB v6); the stale build crashed on launch until GitHub's latest was installed. Always fetch before building.
- Unit tests: 58 passing. Not yet confirmed on device: library migration, Documents saving, cover prompt, picture detection thresholds.

## 2026-11-24 — Whole-book scanning, review screen, optional Ollama, tables

- Removed the 1,000-screen cap: scans run until Kindle reaches the end (an optional screen limit remains). Scan logs confirmed the table book had simply hit the old cap at 87%.
- Blank/picture pages are skipped instead of stopping the scan; Continue scan resumes without duplicating the last page; raw OCR is appended instead of rewriting the whole file.
- Kindle footer/header text ("mins left in book", "Page X of Y", "Location…", percentages, "Back to", "Aa") is removed by screen position and patterns; device cleanup also strips it from existing scans (about 3,800 fragments down to 8 on the 5,233-page log).
- Page-turn recovery: tap, swipe, lower tap, then finish if Kindle showed 99–100% or pause with an on-screen question. Kindle's end-of-book screen ends the scan without being saved. Interrupted gestures are retried.
- Tables are detected from side-by-side OCR lines and read row by row; new Pages with tables setting (Ask / Keep / Skip) with a buzz and Keep/Skip panel over Kindle.
- Every scan (book and separate test scan) now has versions: Raw, Device cleanup (automatic), and any Ollama or Gemini Nano runs, saved chunk by chunk so AI runs can be continued. New Review screen: OCR quality rating with most common suspect words, paged viewer, red/green comparison of any two versions with a rewrite warning, save as text/EPUB, Open in EPUB reader, choose the version used for Create EPUB.
- Ollama is optional and only runs when chosen on the Review screen; settings have their own Save button. Help updated to match.
- Unit tests: 49 passing (text store, workspace versions, quality scorer, diff, Kindle chrome, page layout).
- Finalised and user-tested the app: the streamlined Kindle scan → Ollama cleanup → EPUB workflow, Help guidance, whole-book safety limit, Calibre-compatible EPUB output with paragraph spacing, wombat banner/adaptive launcher icon, and clearer accessibility instructions are all working as intended.

- Expanded Help with clear scan-time requirements: Kindle must remain open in the foreground, the phone should not be used for anything else, and long books may take five to ten minutes; charging and scanning during idle time are recommended.

- Fixed the Android launcher icon fallback by separating the adaptive icon foreground from the launcher resource, so Android 8+ devices use the wombat face rather than recursively resolving the icon resource.

- Simplified the first-run flow: normal users now start a whole-book Kindle scan without choosing a count; the 1,000-screen safety limit moved to Advanced settings with an explanation. Added a Help page, moved technical status away from the header, and replaced user-facing “probe” wording with “scan,” including clear instructions to enable Wombat Liberates in Android Accessibility settings.

- Redesigned the main UI around three steps (Capture/import, Clean text, Create EPUB), with technical settings and diagnostics collapsed by default. Added the supplied wombat artwork as a header banner and a focused adaptive launcher icon; starting capture now starts the probe automatically when accessibility is enabled.

## 2026-07-26 — Consolidated session summary

- Analysed and evolved the Kindle OCR app from a diagnostic/manual collector into a bounded whole-book capture workflow, with user-selected screen limits, Stop controls, foreground checks, duplicate-final-screen/end-of-book detection, and discarded screenshots after OCR.
- Improved OCR text assembly: visual OCR blocks are joined, cross-page continuations are joined with a space or de-hyphenated as appropriate, while genuine paragraph boundaries are retained.
- Kept capture output safe and reversible: raw OCR, corrected OCR, diagnostics, and exports are separate; correction never overwrites the raw capture. Added raw text-file import for testing and removed obsolete tree-snapshot/one-page debug controls from the main UI.
- Added two correction paths: optional on-device Gemini Nano through ML Kit Prompt, and HTTPS Ollama correction using conservative 2,500-character paragraph chunks. Ollama settings are stored locally with Android Keystore encryption; no credentials are committed.
- Added clear correction feedback: connection, progress bar, completed/remaining chunk counter, completion, and failure messages.
- Added local EPUB 3 export using corrected text when available (otherwise raw text), editable title/author metadata, readable reflowable styling, chapter detection for headings such as CHAPTER SIX, a contents list, and Kobo-compatible packaging.
- Fixed EPUB compatibility and formatting based on testing: explicit title-page/manifest/spine entries resolved Calibre loading, and paragraph spacing is visibly retained. Calibre and phone EPUB readers now open the generated book successfully.
- Repeatedly built and verified the debug APK. This work was pushed in commits 80534dc, 91777d7, 1d1dbd9, 3b60643, ff47c53, 7e10d51, b237da9, 46c7be6, 63b9e24, 9d98565, 78eca05, and afd1dd0.

- Restored visible paragraph separation in generated EPUBs: paragraphs now have a readable bottom margin and no first-line indent, matching the captured text’s blank-line structure.

- Reworked EPUB packaging after Calibre reported an empty spine: added a title-page XHTML document and explicit manifest/spine entries for it and every chapter, preserving the required uncompressed mimetype-first EPUB layout.

- Added a local EPUB 3 exporter with title/author fields, Kobo-compatible ZIP structure, clean reflowable typography, escaped XHTML, and an automatic contents list from CHAPTER headings. It uses corrected OCR when available and never sends book text to a server.

- Added a persistent, determinate correction progress bar with a per-chunk completed/remaining counter and clear connection, sending, completion, or failure text.


## 2026-07-26 — Raw book capture and on-device correction

- Replaced manual polling collection with bounded automatic Kindle capture using accessibility screenshots, OCR, page-turn gestures, foreground checks, and Stop controls.
- Improved OCR formatting by joining visual-line/block splits and cross-page mid-sentence continuations; raw OCR still remains separate from diagnostics.
- Added user-selected automatic capture counts from 1 to 1,000 pages for whole-book raw collection.
- Added separate raw and corrected OCR files and exports. Raw text is never overwritten by correction.
- Added on-device Gemini Nano correction through ML Kit Prompt API. It checks model availability, downloads supported model assets when needed, processes saved OCR in 2,500-character paragraph chunks, and requires the app to remain foreground.
- Upgraded the Kotlin Android plugin to 2.2.20 because ML Kit GenAI requires Kotlin 2.2-compatible metadata.
- Added end-of-book detection: an unchanged cropped-screen fingerprint is retried once, then capture stops without appending repeated final-page OCR. The capture count is now labeled as screens, not Kindle page numbers.
- Added HTTPS Ollama server correction using chunked POST /api/generate calls, configurable model selection, HTTP Basic Auth, and Android Keystore-encrypted local credential storage. No endpoint or credentials are stored in source control.
- Added raw text-file import for testing existing captures; importing replaces raw text and clears only the derived corrected file. Removed the obsolete tree-snapshot and one-page OCR controls from the main UI.
- Improved import feedback with a visible confirmation toast showing the imported character count.
- Added visible Ollama connection, progress, completion, and failure notifications to make endpoint/auth/TLS/model issues diagnosable during testing.
- Verified with `./gradlew :app:assembleDebug`.
- Pushed commits: `80534dc`, `91777d7`, and `1d1dbd9`.
