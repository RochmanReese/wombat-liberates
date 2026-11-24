# Wombat Liberates — Roadmap

The single source of truth for what's done, what's next, and what's parked. Architecture lives in
`PROJECT-ARCHITECTURE.md`; decisions in `PROJECT-DECISIONS.md`.

---

## Done (v1 so far)

- [x] One-page OCR probe, then app-controlled batch scanning (July 2026)
- [x] OCR text joining across blocks and pages; editable local cleanup rules
- [x] Ollama and Gemini Nano correction; EPUB 3 export (Calibre/Kobo compatible)
- [x] Whole-book scanning with no screen cap; blank pages skipped; clean Continue (Nov 2026)
- [x] Kindle footer/header removal; end-of-book screen detection
- [x] Stuck-page recovery and on-screen questions; swipe page turns; Back recovery from links
- [x] Tables read row by row with Ask / Keep / Skip
- [x] Versions, Review screen, quality rating, comparison; Ollama optional
- [x] Library of books; EPUBs auto-saved to `Documents/Wombat Liberates`; test scans separate
- [x] Illustrations saved, cover question, thumbnails with delete/cover, pictures in EPUBs
- [x] Calls pause the scan (no Back presses); notification banners waited out and spoiled captures
      retaken; optional Do Not Disturb while scanning

## Now — confirm on the phone

- [ ] Full-book scan result reviewed (stop reason, screens, quality, pictures, EPUB in Wombat Reader)
- [ ] Old single book appears in Your books after updating
- [ ] Save/Update EPUB in Documents; file visible in a file manager and Wombat Reader's folder import
- [ ] Table prompt and Keep/Skip-all behaviour on a real table book
- [ ] Link page: swipe passes it; Back recovery if something still opens
- [ ] Picture detection: no missed illustrations, no false pictures from decorative text
- [ ] Dark / sepia Kindle theme test scan
- [ ] Text and call during a scan: no banner text in the book, pause/resume in the log, no Back presses
- [ ] Do Not Disturb setting: permission flow, on during scan, off afterwards

## Next — candidates (not yet scoped)

- **Server AI cleanup service** (agreed direction, not started): a separate container on
  wombat-server beside Ollama; the phone uploads device-cleaned chunks once, the server works
  through them and saves progress, the phone downloads the result later. Simple shared key for
  access to start (no Zitadel until it proves worthwhile). Replaces the phone→Ollama path so the
  phone is never tied up. Write a brief first.
- Send the device-cleaned text (not raw) to AI cleanup, so Compare shows only AI changes.

- Tune picture/table/quality thresholds from real scan logs.
- Editable AI cleanup prompt (so Ollama over-editing can be tightened from Review results).
- AI cleanup in the background (foreground service) for whole books.
- OCR rule sharing and curated default rules — brief exists: `OCR-rule-sharing-brief.md`.
- "Add as correction" straight from the quality check's suspect-word list.

---

## v2 — Reading mode (parked 24 November 2026)

**Idea:** Liberates runs in the background. Whenever the user reads a book in Kindle normally,
turning pages by hand, it recognises each new page and collects it — no automatic page turning.

**History:** tried on 26 July 2026 (commits `b9ed39b`, `be1cf8b`, `5462e24`) and abandoned.
Recorded problems: Kindle sends no reliable accessibility event when the user turns a page;
polling OCR'd half-turned pages mid-animation; exact OCR-text de-duplication failed because OCR
output varies for the same page; a capture could catch the Liberates screen; Kindle's continuous
scroll has no page boundaries.

**Why it could work now** (pieces built since):

1. **Settle detection** — a cheap downscaled screenshot about twice a second; OCR only when the
   screen has *changed and then stayed still* for ~1 s, so transition frames are never read.
2. **Picture fingerprints** for de-duplication instead of comparing OCR text.
3. **Kindle page numbers** — the footer ("Page 212 of 376", "Location 1234", %) is now parsed
   reliably, so each page has an identity: re-reads and jumps don't duplicate, pages are stored in
   **book order** regardless of reading order, and **gaps** are known ("pages 120–135 missing") —
   an automatic scan could later fill only the gaps.
4. **Only real reading screens** — capture only when Kindle's reader view is in front (not the
   library, menus, other apps or Liberates).

**Design questions to settle first:**

- Which book? Proposed: a small over-Kindle panel the first time a book is opened —
  "Collect this book while you read? Yes / Not this book" — remembered per book. Kindle may expose
  the title in its accessibility tree; check with the existing window-tree snapshot tool.
- No interruptions while reading: use defaults (keep tables, save pictures, no cover question)
  and sort things out in Review.
- Requirements: footer page/location visible; page-turn mode (not continuous scroll).
- Battery: ~2 cheap checks a second while Kindle is open; full OCR once per page turn.
- Transparency: a notification while collecting ("Collecting *Title* while you read") with Pause,
  plus an on/off setting.
- Storage change: pages keyed by page/location number rather than appended in order — the
  biggest structural change.

**Suggested first step:** a probe build — Rocky sends one window-tree snapshot from Kindle's
reader and a short log of manual page turns — to see what Kindle actually exposes (title, page
number, page-change events) before committing to the design.
