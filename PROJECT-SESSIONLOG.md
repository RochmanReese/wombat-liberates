# Wombat Liberates — Session Log

_Newest entries go at the top of this file, not the bottom. Format: see `agentexit.md`._

## Session 3: 24 November 2026 — Whole-book scanning, library, illustrations, project docs

### What we did
- **Whole books**: removed the 1,000-screen cap (scan logs showed the "table book" simply hit it at
  87%); blank pages skipped; clean Continue; raw OCR appended instead of rewritten each page.
- **Kindle chrome removed** at capture and in device cleanup ("mins left in book", "Page X of Y",
  "Location…", percentages, toolbar leftovers) — ~3,800 fragments down to 8 on a 5,233-page log.
- **Ending and recovery**: Kindle's end-of-book screen detected; stuck pages get swipe → margin
  taps → finish at ≥99% or an over-Kindle question. **Swipe page turns** after Rocky found taps
  opening links (Facebook); Back recovery if another app still opens.
- **Tables** read row by row with Ask / Keep / Skip; **illustrations** cropped and saved, cover
  question in the first 10 screens, thumbnails with delete/cover in Review, pictures + cover in EPUBs.
- **Versions + Review screen** (quality rating, viewer, red/green comparison, save/open);
  **Ollama made optional**, run only from Review. **Library of books** with EPUBs auto-saved to
  `Documents/Wombat Liberates`; test scans separate; old single book migrates automatically.
- **Calls and notifications**: a call pauses the scan (no swipes, no Back presses) and resumes when
  it ends; notification banners are waited out and captures they spoiled are retaken; optional
  Do Not Disturb while scanning (permission flow, restored after the scan or a crash).
- **AI cleanup moved to a server**: new `wombat-cleanup` service (`~/repos/wombat-cleanup`, live at
  cleanup.wombatex.com, gemma3:12b, shared key, resumable jobs). Liberates' Ollama settings replaced
  by Cleanup server settings; background WorkManager check + notification.
- Whole-book AI proved far too slow (~2 min/chunk, ~9–10 h/book), so: **on-device spelling
  correction** (SymSpell dictionaries in assets + the book's own vocabulary; tuned on real scans to
  ~90 changes per 41k words, nearly all correct) and **AI only for doubtful sentences** (~5%).
- **Chapter detection** from the printed contents page (or headings); 24/24 sections found on the
  real sample, contents page dropped. Review shows the chapter list.
- Created the project scaffolding: `agentstart.md`, `agentexit.md`, this log (renamed from
  `SESSION-LOG.md`), `docs/MASTER.md`, `docs/PROJECT-ARCHITECTURE.md`, `docs/PROJECT-ROADMAP.md`
  (includes the parked v2 Reading mode write-up), `docs/PROJECT-DECISIONS.md`.
- **EPUB compatibility**: Liberates EPUBs now also carry an EPUB 2 `toc.ncx` (Wombat Converts
  rejected nav.xhtml-only books; Converts was fixed too). Same session also fixed Converts/Narrates
  sign-in ("signed in but nothing works") — see those projects' logs.
- Commits on master: `6468d01`, `03df1bb`, `1cad3f6`, plus the toc.ncx commit (all pushed).

### Where we left off
- Rocky confirmed on the phone: 20-screen test scan works well; swipe page turns; and a full-book
  scan that finished correctly with the cover used and maps in place, readable with occasional
  OCR slips ("er"/"her", missing "l").
- Agreed next: server AI cleanup as a **separate container** beside Ollama (shared key, no Zitadel
  yet), replacing phone→Ollama. Discussed and declined: reading KFX directly (would mean DRM
  removal). v2 Reading mode parked in the Roadmap.
- Rocky tested spelling fixes, chapters and Clean on server on the phone; committed and pushed.
- The benchmark whole-book gemma job was cancelled (too slow: about 2 min per chunk).
- Phone testing, later the same day:
  - **Hang fixed:** Library, Review and chapters hung on "Loading / Checking text". Cause: Kotlin
    `Regex.findAll` on Android makes a new ICU matcher per match, and each copies the whole text,
    so a whole book was quadratic. Server JVM timings hid it. Fix: `Regex.matchesIn`
    (TextMatches.kt, one matcher) everywhere. The library list now shows books before their
    quality line.
  - **OCR rules now match whole words only:** "mne => me" had been turning damned → "damed"
    (51×), painfully → "painfuly" and I'll → "I'lll".
  - **Spelling:** when the book clearly uses one candidate, it wins ("stallion" ×16 over
    "station" ×1).
  - **Chapters on The Last Ranger: 3 → 57.**
    - Contents entries lose their numbers.
    - Titles printed in capitals and glued onto the first sentence ("TREACHERY he heard…") are
      split off, but only if the next word isn't capitals too.
    - The lost decorative first letter is restored from the book's names, word pairs, pronoun
      agreement and common openers ("She heard", "As the", "Leiyn rode").
    - Titles use the contents page's spelling.
  - **Server cleanup of doubtful sentences:** 447 sentences.
    - Ollama 0.12.9 misreads this AMD card's free memory (3 GB of about 18 GB free; only Ollama
      and Kokoro use the card), so it put just 8 of 49 layers on the card.
    - wombat-cleanup now sends `num_gpu: 999`: 49/49 layers, about 0.6 s per sentence (was
      3.6–5.6 s), about 5 min per book.
    - Resizable BAR appears to be off (`vis_vram_total` is 0.25 GB); worth checking in the BIOS,
      or upgrade Ollama.
  - A debug-only freeze watchdog was used to find the hang and has been removed.
  - Device cleanup version is now 5.
  - Chapter numbers printed above titles ("4" / "HOME") were left at the end of the previous
    chapter: number-only lines (incl. OCR "I7", "2.6"), lone letters and numbers glued onto a
    short last line are now removed at every section end. Titles stay plain (Rocky's choice: no
    "Chapter 4 –" prefixes).
  - EPUBs now have a visible Contents page (nav.xhtml in the spine) after the title page.

### First thing next session
- Remind Rocky to check **Resizable BAR / Above 4G decoding** in the BIOS at the next server reboot
  (`vis_vram_total` is 0.25 GB; Ollama sees 3 GB of about 18 GB free VRAM). After the reboot, confirm
  gemma loads 49/49 layers without `num_gpu`. If not, consider upgrading Ollama (0.12.9).

### Watch out for
- **Wombat Reader incident**: this server's wombat-reader clone was 5 commits behind GitHub
  (DB schema 6 vs 2). A build from the stale clone crashed the installed app on launch ("A
  migration from 6 to 2 was required"); fixed by installing GitHub's latest. The server clone now
  matches GitHub. Always `git fetch` before building either app; never uninstall to "fix" a crash.
- The phone is on Rocky's PC, not this server: give adb/scp commands (`scp wombat-server:…`).
- Untracked, never commit: `scan-log.txt`, `crash.txt`, `rawtext1.txt`, `books.tar`, `liberates-stack.txt` (book text and phone dumps). Older loose
  notes (`HANDOVER.md`, `handover.md`, `build.md`, `briefs/`) are historical; consider moving them
  under `docs/legacy/` with Rocky's OK.
- Thresholds (pictures, tables, quality, chrome bands) are first guesses; tune from real logs.

---

## Earlier entries (original log format, preserved as written)

- Prepared `handover.md` for the next agent: integrate the standalone `OCR-rules` template into an editable, app-private OCR rules system; preserve raw OCR, keep contextual pipe handling built in, add user add/delete/reset controls, and validate against synthetic cases plus the untracked `rawtext1.txt` sample.
- Working rule: do not use complex shell-inline edits for Kotlin or XML. Make one validated file change at a time, inspect its diff, build immediately, and restore promptly on any failure.

## Undated entries (July 2026)

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
