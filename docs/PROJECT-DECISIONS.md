# Wombat Liberates — Decision Log

One file for significant decisions. Max 5 lines per entry. Append only — never edit past entries.
Format: `## {Date} — {Topic}` then bullet points.

---

## 26 July 2026 — On-device OCR, screenshots never kept
- ML Kit's bundled text recognition runs fully offline; each screenshot is OCR'd and discarded.
- Book text stays on the phone unless the user chooses an AI cleanup that sends it elsewhere.

## 26 July 2026 — App-controlled batch instead of manual collection
- Kindle gives no reliable signal when a user turns a page, so manual collection produced
  half-turned pages and duplicates. The app turns pages itself and always knows when a new page
  is on screen. Manual "reading mode" is parked for v2 (see Roadmap).

## 26 July 2026 — Raw OCR is immutable
- Raw OCR is source data; every cleanup is stored separately and can be redone or compared.

## 24 November 2026 — No screen cap; stop on evidence of the end
- The 1,000-screen safety cap was why long books stopped early. Scans now end on Kindle's
  end-of-book screen, at ≥99% footer progress, or by asking the user — never on a count.

## 24 November 2026 — Device cleanup is the default; Ollama is optional
- Local cleanup runs automatically and is usually enough. Ollama/Gemini run only when chosen in
  Review, ideally when the quality check rates the OCR poor, and are compared before use.

## 24 November 2026 — Swipe to turn pages
- Taps followed links (opening Facebook etc.) and footnotes. Swipes never activate links; margin
  taps are fallbacks only. If Kindle still loses the screen, press Back, then ask.

## 24 November 2026 — Ask rather than give up
- When unsure (stuck page, table, link left Kindle, possible cover) the scan pauses with an
  over-Kindle panel and a buzz, instead of stopping or guessing. Settings let unattended scans
  avoid questions (Tables: Keep/Skip).

## 24 November 2026 — Library of books; EPUBs to Documents; reading stays in Wombat Reader
- Every scan is a book kept until deleted; test scans stay separate. EPUBs auto-save to
  `Documents/Wombat Liberates`. Liberates makes books; Wombat Reader reads them — no in-app reader.

## 24 November 2026 — Interruptions pause the scan; never act on another app's screen
- A phone call pauses the scan with no gestures and no Back presses; notification banners are waited
  out and any spoiled capture is retaken, so message text can't enter a book. Do Not Disturb is an
  opt-in convenience that uses the user's own rules and is always restored.

## 24 November 2026 — AI cleanup moves to a server job (planned)
- Whole-book AI cleanup ties the phone up for hours. It will run as a separate container beside
  Ollama on wombat-server, uploaded once and collected later; shared key first, Zitadel only if it
  proves worthwhile. Direct phone→Ollama will be removed.

## 24 November 2026 — No DRM removal
- Reading KFX/AZW files directly would mean decrypting Kindle DRM, which is unlawful even for owned
  books. Liberates only reads what Kindle shows on screen.

## 24 November 2026 — Illustrations saved by default; cover chosen by the user
- Pictures are cropped and kept at screen resolution. Only pictures in the first 10 screens of a
  new scan are offered as the cover, and only the user decides; the cover is not repeated inside.

## 24 November 2026 — Fix OCR slips on the phone; AI only for what's left
- Proofreading a whole book with a 12B model took ~2 min per chunk (~9–10 h a book) — far too much
  compute for a few dozen slips. Dictionary + book-vocabulary spelling correction runs on the phone
  in seconds; only sentences still containing doubtful words (~5%) go to the server.

## 24 November 2026 — Chapters from the book's own contents page
- The printed contents page is the most reliable chapter list: match its entries, in order, to
  headings in the body, then drop the printed page (the EPUB has its own). Heading patterns are
  the fallback; garbled "CHAPTER" / "TWELVE" splits are joined.

