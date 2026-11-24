# Wombat Liberates — End of Session Checklist

Complete these steps before ending every session.

---

1. **Update docs/MASTER.md** — revise "Current Status" and "Next Up" if anything changed
2. **Update docs/PROJECT-ROADMAP.md** — tick off completed items, add anything new that came up
   (including ideas parked for later, with enough detail to pick them up cold)
3. **Update docs/PROJECT-ARCHITECTURE.md** — if the scan pipeline, storage layout, data formats,
   screens or dependencies changed
4. **Update docs/PROJECT-DECISIONS.md** — if significant decisions were made, add a brief dated
   entry (max 5 lines); append only
5. **Run the checks** — `./gradlew :app:testDebugUnitTest :app:assembleDebug` must pass; note
   anything that still needs confirming on Rocky's phone
6. **Update PROJECT-SESSIONLOG.md** — add a new entry at the TOP summarising what was done, where
   you left off, and what to watch out for
7. **Commit and push to Git** — `git fetch` first; commit all changed project files with a clear
   descriptive message and push to master. Never commit book text, scan/crash logs, screenshots,
   EPUBs or `rawtext1.txt`
8. **Update agent memory** — if anything non-obvious was learned about preferences, decisions, or
   conventions, save it to this project's memory directory

---

## Session log format

Match the format of the newest entries in `PROJECT-SESSIONLOG.md`
(`## Session N: {Date} — {short descriptive summary}`, then `### What we did` / `### Where we left
off` / `### Watch out for`). New entries go at the TOP. Never delete old entries.
