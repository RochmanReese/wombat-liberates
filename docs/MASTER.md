# Wombat Liberates — Master Document

**Start here. This is the hub for everything.**

Last updated: 24 November 2026

---

## What This Is

Wombat Liberates is an Android app that turns Kindle books you own into ordinary EPUB files you
can read anywhere — in [Wombat Reader](https://github.com/RochmanReese/wombat-reader) or any
other EPUB reader. Built by TechWombat · Rochman Reese, as part of the WombatEx family.

It works the way a person would: with Kindle open on the phone, an Android accessibility service
turns each page, takes a screenshot, reads the text with Google ML Kit's **on-device** OCR, and
throws the screenshot away. The text is cleaned on the phone (Kindle's own footer removed, common
OCR errors fixed with editable rules), pictures are cropped and kept, and the result is built into
an EPUB saved to `Documents/Wombat Liberates`.

Nothing leaves the phone unless the user chooses the optional **Ollama** AI cleanup, which sends
text only to the user's own Ollama server. On-device Gemini Nano cleanup is also optional.

→ How it works (technical): [PROJECT-ARCHITECTURE.md](PROJECT-ARCHITECTURE.md)
→ What's next, including v2 ideas: [PROJECT-ROADMAP.md](PROJECT-ROADMAP.md)
→ Settled choices and why: [PROJECT-DECISIONS.md](PROJECT-DECISIONS.md)
→ Session-by-session history: [../PROJECT-SESSIONLOG.md](../PROJECT-SESSIONLOG.md)
→ Agent start / end checklists: [../agentstart.md](../agentstart.md), [../agentexit.md](../agentexit.md)

---

## Origin

Started 26 July 2026 as a feasibility probe: can an accessibility service read a Kindle page at
all? (`build.md` and `briefs/KINDLE-ACCESSIBILITY-BRIEF.md` record that first one-page test.) A
manual "collect pages as I read" mode was tried the same day and abandoned — Kindle gives no
reliable page-change signal (see Roadmap → v2 for how it could now be revisited). The working
design became an app-controlled batch: the app turns the pages itself, so it always knows when a
new page is on screen.

---

## Current Status

**24 November 2026 — whole-book scanning, a book library, illustrations and call/notification
handling are built and pushed to master, and partly confirmed on Rocky's phone.**

Confirmed on the phone: a 20-screen test scan "working pretty good"; swipe page turns; and a first
**full-book scan** (default Kindle text size, black on white) that finished correctly — the cover
picture used as the EPUB cover, maps included in place, text "perfectly readable" with occasional
OCR slips such as "er" for "her" or a missing "l" in "ll" (the job AI cleanup is for).

What the app now does (details in `PROJECT-ARCHITECTURE.md`):

- **Scans whole books** with no screen cap, stopping at Kindle's end-of-book screen, at 99–100%
  progress, or by asking the user. (The earlier 1,000-screen cap was the real reason long books
  "stopped scanning".)
- **Turns pages by swiping**, so links and footnotes are never followed; recovers with Back if
  another app opens; tries margin taps if Kindle ignores a swipe; pauses with an on-screen
  question rather than giving up silently.
- **Removes Kindle's footer/header** ("mins left in book", "Page X of Y", percentages) — ~3,800
  fragments down to 8 on a 5,233-page real scan log.
- **Handles tables** (read row by row; Ask / Keep / Skip setting with an over-Kindle prompt) and
  skips blank pages.
- **Saves illustrations** and asks whether an early picture is the cover.
- **Library of books**: every scan is a book with versions (Raw, Device cleanup, AI runs), a
  Review screen (quality rating, viewer, red/green comparison, thumbnails), and EPUBs auto-saved
  to `Documents/Wombat Liberates`. Test scans stay separate.
- **Ollama is optional**, run only from Review when the quality check suggests it.
- **Calls and notifications**: a call pauses the scan (never presses Back); notification banners
  are waited out and spoiled captures retaken; optional Do Not Disturb while scanning.
- **Spelling fixes on the phone** (no AI) during device cleanup; **Clean on server** sends only the
  sentences still in doubt to the Wombat Cleanup service (`cleanup.wombatex.com`, gemma3:12b) and
  notifies when done.
- **Chapters** from the book's printed contents page (dropped from the text) or chapter headings.

58 unit tests pass. Not yet confirmed on the phone: migration of the old single book into the
library, saving to Documents, the full Review/thumbnail flow, picture-detection accuracy, the
table prompt, and link recovery via Back.

## Next Up

1. Test on the phone: spelling fixes and chapters on a real book, Clean on server (doubtful
   sentences only), calls/notifications and Do Not Disturb.
2. Decide whether `wombat-cleanup` gets a GitHub repository (local git only so far).
3. Short test scan with Kindle's **dark or sepia** page colour; work through the Roadmap's
   "confirm on the phone" list.

Parked for v2: **Reading mode** (collect pages in the background while the user reads Kindle
normally) — fully written up in `PROJECT-ROADMAP.md`.

## Related Projects

- **Wombat Reader** (`~/repos/wombat-reader`) — the companion EPUB reader. Separate app and repo.
  It registers for EPUBs, so Liberates' "Open in EPUB reader" lands books straight in its library.
  Its database is at schema version 6 (August 2026); never build it from a stale clone.
