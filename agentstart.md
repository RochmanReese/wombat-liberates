# Wombat Liberates — Agent Starter Prompt

Use this when starting a new AI agent session on this project.

---

## Short prompt (Claude Code)

```
You are working on Wombat Liberates. Read /home/wombat/repos/wombat-liberates/docs/MASTER.md, then
PROJECT-ARCHITECTURE.md, PROJECT-ROADMAP.md and PROJECT-DECISIONS.md, and follow the instructions there.
```

---

## Full prompt (any AI with file access)

```
You are working on Wombat Liberates, an Android app that turns Kindle books the user owns into
EPUBs: an accessibility service turns Kindle's pages, screenshots and OCRs each page on the phone,
cleans the text locally (Ollama/Gemini Nano AI cleanup is optional), saves illustrations, and
builds EPUBs for Wombat Reader or any other reader. Built by TechWombat · Rochman Reese.

Start by reading /home/wombat/repos/wombat-liberates/docs/MASTER.md, then PROJECT-ARCHITECTURE.md,
PROJECT-ROADMAP.md and PROJECT-DECISIONS.md. These are the active sources of truth. Older notes in
the project root (HANDOVER.md, handover.md, build.md, briefs/, OCR-*-brief.md) are historical or
feature-specific; consult them only when their topic is being worked on.

Rules:
- Note the current time — this is your session start time; record it in the session log at exit
- Run `git fetch` and compare with origin/master before reading code or building. This server's
  clone can be behind GitHub (Wombat Reader once was, and a stale build crashed the user's app).
- Read PROJECT-SESSIONLOG.md in the project root — first (topmost) entry only — to see where the
  last session left off
- Before anything else, check whether the previous session's end-of-session checklist
  (agentexit.md) was completed — if not, do that first
- Never spawn an Agent-tool subagent (Explore, Plan, general-purpose, or any other type) without
  asking Rocky first and getting a yes — standing rule across all projects
- If anything is unclear about the project, a task, or an instruction — ask before proceeding,
  never assume
- After reading, summarise your understanding of the current state and what needs doing next
- Wait for explicit instruction before starting any new work
- Once given a task, work through it fully without stopping for permission at each step
- If mid-task you are unsure about approach or requirements, stop and ask rather than guessing
- This agent runs on wombat-server. The test phone is connected to Rocky's own PC, not this
  server. `./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` run here; anything that
  touches the phone (installing, logcat, app files) must be given to Rocky as copy-paste commands
  for his PC, using the `wombat-server:` scp alias, e.g.
    scp wombat-server:~/repos/wombat-liberates/app/build/outputs/apk/debug/app-debug.apk wombat-liberates.apk
    adb install -r wombat-liberates.apk
  Always install over the top (`adb install -r`); never suggest uninstalling — it deletes the
  user's books.
- Any claim about how the app looks or behaves on the phone must be confirmed by Rocky, never
  assumed from a successful build.
- Keep book text out of version control: scan logs, crash logs, raw OCR samples (rawtext1.txt),
  screenshots and EPUBs are never committed.
```
