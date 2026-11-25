# Wombat Liberates — Project Architecture

Last updated: 24 November 2026

How the app works, end to end. `PROJECT-ROADMAP.md` covers what's next; `PROJECT-DECISIONS.md`
covers why things are the way they are.

---

## Stack

| Layer | Technology | Notes |
|---|---|---|
| Platform | Android, Kotlin, View system with `viewBinding` | `minSdk 30` (**Android 11+**, the first version with accessibility screenshots, which scanning needs; Android won't install it on older phones), `compileSdk`/`targetSdk 35`, Java 17 |
| Kindle control | `AccessibilityService` | `canTakeScreenshot`, `canPerformGestures`, `canRetrieveWindowContent`; config in `res/xml/accessibility_probe_config.xml` |
| OCR | Google ML Kit text recognition `16.0.1` (bundled model) | Fully offline; model ships inside the APK |
| Optional AI cleanup | Wombat Cleanup server (separate container beside Ollama, `gemma3:12b`, shared key); ML Kit GenAI Prompt (Gemini Nano) `1.0.0-beta2` | Never on the main path; run from Review only |
| EPUB | Hand-written EPUB 3 writer (`EpubWriter.kt`) | Uncompressed `mimetype` first, nav + title page + chapters, images, cover |
| Storage | App-private files + JSON; `SharedPreferences`; Android Keystore for the Ollama password; MediaStore for Documents | No database |
| Tests | JUnit 4 on the JVM (`org.json` added for tests) | `./gradlew :app:testDebugUnitTest` — 58 tests |

## Source Layout (`app/src/main/java/com/techwombat/liberates/`)

| File | Role |
|---|---|
| `KindleAccessibilityProbeService.kt` | The scanner: batch loop, screenshots, OCR, page turns, stuck/link recovery, tables, pictures, cover question, prompt panel over Kindle |
| `PageLayout.kt` | Turns OCR lines into page text: drops Kindle chrome, detects tables (row-by-row reading), inserts picture placeholders |
| `KindleChrome.kt` | Recognises Kindle's footer/header text, reading progress, and the end-of-book screen; `strip()` used at capture and in cleanup |
| `Illustrations.kt` | Picture markers (`[[image:img-0001.jpg]]`) and `IllustrationDetector` |
| `ScanWorkspace.kt` | One scan (book or test): raw text, versions, pictures, cover, EPUB status, metadata. Also `Library` and `Workspaces` |
| `OcrTextFiles.kt` | Raw OCR file per scan (append-only page joining, last-page memory) |
| `OcrCleanup.kt`, `OcrRulesParser.kt`, `OcrRulesStore.kt` | Device cleanup: chrome strip → contextual `|` fixes → ordered literal rules (`assets/ocr-rules.txt` defaults + user rules) |
| `OcrQuality.kt` | Offline quality score: share of words that look like OCR errors, with examples |
| `OcrSpelling.kt` | On-device spelling correction (no AI): SymSpell word/word-pair frequencies (`assets/spelling/`, MIT) plus the book's own vocabulary; joins broken words; `needsReview()` flags doubtful words for AI |
| `ChapterDetector.kt` | Chapters from the printed contents page (matched in order to body headings; contents page dropped), else heading patterns; split "CHAPTER" / "TWELVE" headings joined |
| `AiReviewSelection.kt` | Picks the sentences still containing doubtful words (typically ~5% of the text) for server AI, and merges corrected sentences back |
| `CleanupServer.kt`, `KeystoreText.kt` | Wombat Cleanup client, settings (key Keystore-encrypted), background WorkManager check and notifications |
| `TextCorrection.kt` | `TextChunker` (2,500-char paragraph chunks) and the on-device (Gemini Nano) prompt |
| `WordDiff.kt` | Word-level diff for comparing versions |
| `EpubWriter.kt`, `BookExport.kt` | EPUB building; saving to Documents; opening in a reader app via `FileProvider` |
| `MainActivity.kt` | Main screen: scan/import, **Your books** library, Advanced settings (scan limit, tables, pictures, test scan, OCR corrections, Ollama, diagnostics) |
| `ReviewActivity.kt` | Review screen for a book or the test scan |
| `ScanSettings.kt` | Scan preferences |
| `ScanDoNotDisturb.kt` | Optional Do Not Disturb (priority-only) for the length of a scan, restored afterwards and after a crash |
| `ProbeLog.kt`, `PersistentProbeLog.kt` | Diagnostics shown in the app and written to `files/kindle-accessibility-probe.log` |
| `HelpActivity.kt` | Help screen |

## Storage Layout

```text
files/
  books/<book-id>/                 one folder per book (id = "book-<millis>")
    kindle-ocr.txt                 raw OCR, append-only, never edited by cleanup
    kindle-ocr-last-page.txt       last saved page (de-duplicates a continued scan)
    images/img-0001.jpg …          saved pictures; ids never reused
    versions/
      server-pieces-<job>.json     sentences sent to the cleanup server (until merged)
      workspace.json               title, author, scan summary, preferred version, cover, saved EPUB, quality cache, picture counter
      device.meta.json / .chunks.jsonl        device cleanup (regenerated automatically)
      ollama-<millis>.meta.json / .chunks.jsonl   one per AI run (also gemini-…)
  test-scan/                       same layout; the separate test scan
  ocr-rules.txt                    editable cleanup rules (seeded from assets)
  kindle-accessibility-probe.log   diagnostics (contains book text — never commit)
cache/shared-epubs/                temporary EPUBs handed to reader apps
SharedPreferences: scan_settings (table_mode, save_illustrations, dnd_while_scanning, dnd_set_by_scan), cleanup_server (url, Keystore-encrypted key)
Documents/Wombat Liberates/<Title>.epub   saved EPUBs (MediaStore; survive app deletion)
```

Older app versions kept one book directly in `files/`; `Library` moves it into `books/` on first
launch. Text cleaned by much older versions (`kindle-ocr-corrected.txt`) is imported once as an
"Earlier cleanup" version.

**Versions** are stored as JSON-lines, one chunk per line, aligned with the raw text's chunks, so
any two versions made from the same raw text can be compared chunk by chunk. AI runs append each
chunk as it finishes, so a stopped run can be continued.

## The Scan, Step by Step

A scan always targets one workspace (a library book or the test scan). Per screen:

0. **Wait out interruptions first.** If the phone is ringing or in a call (`AudioManager` mode;
   no permission), pause — no swipes, no screenshots, never Back — and resume 2 s after the call
   ends once Kindle is back (a "Call finished" panel asks the user to return if needed). If a
   notification was posted in the last 6 s (`TYPE_NOTIFICATION_STATE_CHANGED`), or the top system
   window has grown past its size at scan start (banner or pulled-down shade), wait; give up
   waiting after 30 s so a chatty notifier can't stall the scan. A capture during which a call or
   notification started is discarded and retaken.
1. **Wait for Kindle** to be the active app. If Kindle disappeared *right after our own page
   turn* (a link opened another app), press Back up to 3 times, then pause and ask.
2. **Screenshot**, crop 6% off the top and 3% off the bottom, make a 32×32 greyscale
   fingerprint for "did the page change?".
3. **OCR** with ML Kit; each line keeps its position.
4. **Find pictures** (if enabled): on a 72-pixel-wide copy, rows with "ink" (colour far from the
   page background) but no OCR text, forming areas ≥12% of the page tall and ≥25% wide. Crops
   are saved as JPEG (quality 85).
5. **Assemble the page** (`PageLayout.analyze`): drop chrome lines (pattern match, or short lines
   in the top 2% / bottom 5% bands); if ≥3 rows have separate lines side by side (≥25% of rows)
   it's a **table**, read row by row ("Item Quality: Epic"); otherwise join lines and blocks into
   paragraphs. Picture placeholders go between the text above and below each picture. Reading
   progress is taken from the footer before it's removed.
6. **Decide**:
   - Kindle's **end-of-book screen** (two of its phrases, e.g. "Review this book on Amazon",
     "On my profile as") → finish, without saving it or tapping it.
   - First screen of a **continued** scan equal to the last saved page → skip it.
   - **Same screen as before** → stuck: swipe again → tap the right margin (97% across, 50% down)
     → tap lower in the margin (70% down) → if Kindle last showed ≥99%, finish as end of book,
     otherwise pause with "Turn the page yourself, then tap Continue / This is the end / Stop".
   - **No text and no picture** → skip as blank (stop after 10 in a row).
   - **Table** → Keep / Skip per the setting; *Ask* pauses with an over-Kindle panel (Keep, Skip,
     Keep all tables, Skip all tables, Stop).
   - Otherwise **save**: pictures go to `images/`, placeholders become markers, the page is
     appended to the raw text (joined to the previous page as a continued sentence, a de-hyphenated
     word, or a new paragraph; picture markers are always their own paragraph).
   - In the **first 10 screens of a new scan**, each picture is offered as the **cover** (Use as
     cover / Not the cover) until one is chosen.
7. **Turn the page**: a swipe from 85% to 15% across at mid-height (250 ms), then wait 1.2 s.
   Interrupted gestures are retried 3 times.
8. **On finish**: Do Not Disturb is restored if the scan turned it on; the scan summary (screens, blanks, tables skipped, stop reason) is saved to
   the workspace, and the Review screen opens on top of Kindle.

Over-Kindle panels are accessibility overlays (no extra permission), hidden before every
screenshot, with a short buzz to get attention.

## After the Scan

- **Device cleanup** (automatic, regenerated whenever raw text, rules or cleanup code change):
  Kindle chrome strip → contextual `|` fixes (`|` → I/l by context) → ordered literal rules →
  **spelling correction** (`OcrSpelling`): only OCR-style edits (missing/extra/swapped or look-alike
  letters), stray capitals, missing apostrophes, broken words joined, real-word slips like "er"→"her"
  only when both neighbouring words strongly agree; names and words the book uses regularly are kept.
  ~2 s for 660k words; ~90 changes per 41k words on a real scan, nearly all correct.
  Rules: defaults in `assets/ocr-rules.txt`, user additions in Advanced settings.
- **Quality check** on the device version: share of suspicious words (odd symbols, letter/digit
  mixes, "yOur"-style capitals, stray single letters; names like "WillCo" and picture markers are
  not counted). < 0.5% Looks good, < 2% A few problems, else Poor → Ollama recommended. Most
  common suspect words are listed — good candidates for user rules.
- **Review screen**: summary, quality, versions (view in parts, save as text/EPUB, open in reader,
  choose the EPUB version, delete AI versions), saved-EPUB status and button, **Illustrations**
  thumbnails (view larger, use as cover, delete), compare any two versions (red removed / green
  added, warning if a chunk changed > 20%), and **Improve with AI** (Ollama or Gemini Nano;
  resumable; markers restored if the model drops them).
- **Server AI cleanup** (Wombat Cleanup, `~/repos/wombat-cleanup`): only sentences with words
  `OcrSpelling.needsReview()` still doubts are uploaded (about 5% of a book); the server proofreads
  them with `gemma3:12b` and the phone merges plausible corrections back as a "Server AI" version.
  A WorkManager check every 15 min downloads results and notifies; the job is deleted from the server.
- **EPUB**: chapters from `ChapterDetector` (printed contents page, else headings), paragraphs, single newlines as line
  breaks (table rows), pictures as figures, the chosen cover as the EPUB cover (not repeated
  inside), deleted pictures dropped. Saved to `Documents/Wombat Liberates/<Title>.epub`,
  overwriting the book's previous file; flagged out of date when text, version choice, pictures
  or cover change.
- **Open in EPUB reader**: builds a temporary EPUB and sends a plain "view" request so Android
  offers "Always" for the user's chosen reader.

## Screens

- **Main**: 1. Scan or import (Start Kindle scan asks for title/author and creates a book;
  Import text file creates a book) · 2. Your books (tap for Review, Continue scanning,
  Save/Update EPUB, Open in reader, Edit details, Delete) · Advanced settings and diagnostics.
- **Review** (book or test scan) — see above.
- **Help**.

## Testing and Verification

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` on the server. Unit tests cover text
  joining, workspaces/library/migration, quality scoring, diffing, Kindle chrome patterns
  (including OCR-garbled footers from real logs), page layout and tables, illustration detection,
  markers, and EPUB output.
- On-device behaviour is confirmed by Rocky. Diagnostics: `adb exec-out run-as
  com.techwombat.liberates cat files/kindle-accessibility-probe.log > scan-log.txt`; crashes:
  `adb logcat -b crash -d`.

## Known Gaps

- AI cleanup runs only while the Review screen is open (resumable, but not in the background).
- Pictures are captured at screen resolution; tiny text in maps or diagrams may be unreadable.
- Detection thresholds (pictures, tables, quality levels, chrome bands) are first guesses tuned
  on a few books; dark/sepia Kindle themes are untested.
- Kindle's continuous-scroll mode is not supported (needs page-turn mode).
- No in-app EPUB reading (deliberate: Wombat Reader does that).
