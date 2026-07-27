# OCR coding brief — editable local cleanup rules

## Purpose

Implement editable local OCR cleanup rules for Quick local cleanup. This guide is the shared implementation and verification checklist for this work. Mark a stage complete only after its success conditions and listed tests have passed.

## Non-negotiable safeguards

- Raw OCR is immutable. Cleanup writes only the separate cleaned-text output.
- `rawtext1.txt` is user-supplied book text for local regression testing. Do not add or commit it.
- Context-sensitive pipe correction remains code, not a literal rule.
- Rules are literal and case-sensitive, not regular expressions.
- Inspect each diff and build after each meaningful change.

## Implementation and verification checklist

### [x] 1. Baseline inspection

**Work**

- Inspect current cleanup code, settings UI, storage helpers, Gradle setup, and existing test structure.

**Success conditions**

- The ownership and flow of cleanup input, cleaned output, raw storage, settings UI, and test infrastructure are known.
- The existing app builds before changes.

**Evidence and tests**

- Run `./gradlew :app:assembleDebug` successfully.
- Record the relevant files and existing cleanup input/output path in the implementation notes or commit description.

### [x] 2. Default rules packaging

**Work**

- Add a bundled default-rules resource derived from the repository `OCR-rules` template.
- On first use, initialize `filesDir/ocr-rules.txt` from that bundled resource.

**Success conditions**

- A fresh app installation can obtain the complete default rules without accessing repository files.
- Default rule ordering is preserved.

**Evidence and tests**

- Unit test that bundled defaults load and contain representative rules.
- Unit test that an absent app-private rules file is seeded with those defaults in order.

### [x] 3. App-private rules file

**Work**

- Create and maintain the UTF-8 app-private file `filesDir/ocr-rules.txt`.
- Preserve user edits across application restarts.
- Provide reset behavior that restores default rules only.

**Success conditions**

- The rules file is created only when absent.
- Existing edited rules are not overwritten at a subsequent load.
- Reset restores defaults without clearing raw OCR, cleaned OCR, diagnostics, or Ollama settings.

**Evidence and tests**

- Instrumented/storage test: first load creates the file.
- Instrumented/storage test: later loads preserve an edited file.
- Instrumented/storage test: reset restores defaults and leaves unrelated stored state intact.

### [x] 4. Rules parser

**Work**

- Parse UTF-8 rules in `find => replace` form.
- Ignore blank lines and comment lines beginning with `#`.
- Split only on the first `=>`.
- Treat Find and Replace as literal text and preserve their order.
- Report malformed or empty-Find entries clearly when saving.

**Success conditions**

- Valid rules parse predictably.
- Invalid rules are not silently saved as usable rules.
- Literal special characters have no regular-expression meaning.

**Evidence and tests**

- Unit tests for comments, blank lines, missing delimiters, empty Find, first-delimiter splitting, whitespace handling, literal special characters, and order preservation.
- Unit tests for clear validation results from malformed input.

### [x] 5. Cleanup pipeline

**Work**

- Keep contextual pipe cleanup in code and run it before literal rules.
- Apply default and user-saved literal rules in saved order.
- Continue to write only cleaned text, never raw OCR.

**Success conditions**

- Built-in contextual correction precedes literal replacement.
- Cleanup results are deterministic for the same rules and input.
- Raw OCR storage remains unchanged after cleanup.

**Evidence and tests**

- Unit test proving a literal rule can rely on the preceding contextual correction.
- Storage/integration test that raw text is unchanged while cleaned text is updated.
- Unit tests for the required acceptance examples:

  ```text
  As | told Councilman -> As I told Councilman
  "| believe           -> "I believe
  |'m                  -> I'm
  fel|                 -> fell
  I'|                  -> I'll
  4| understand        -> “I understand
  |I nodded            -> “I nodded
  yOur chance          -> your chance
  ```

### [ ] 6. Advanced settings UI

**Work**

- Add an **OCR corrections** section under Advanced settings.
- Show saved custom rules with Find → Replace and a delete action.
- Provide Find and Replace fields plus an **Add correction** action.
- Validate empty and duplicate Find values.
- Add **Reset custom corrections** and explain that rules are literal and case-sensitive.

**Success conditions**

- Users can view, add, delete, and reset corrections.
- Invalid or duplicate rules are not saved.
- Reset affects only rules.

**Evidence and tests**

- UI/instrumented tests where practical: add appears and persists; delete removes only that rule; reset restores defaults; blank and duplicate Find values display validation.
- Manual emulator/device pass for scrolling, layout, and understandable messages.

### [x] 7. End-to-end local cleanup

**Work**

- Connect saved rules to Quick local cleanup.

**Success conditions**

- A saved custom rule affects the next cleanup run.
- Cleanup never mutates prior raw capture.
- Saved rules remain effective after application recreation.

**Evidence and tests**

- Instrumented integration test: save a rule, run cleanup, assert cleaned output, then assert raw output is unchanged.
- Repeat after application recreation to prove persistence.

### [x] 8. Real-sample regression

**Work**

- Run the same cleanup logic locally against untracked `rawtext1.txt` if it remains available.

**Success conditions**

- Pipe errors reduce from 59 to 0 without obvious corruption in inspected lines.
- The supplied book text remains untracked and uncommitted.

**Evidence and tests**

- Record pipe count before and after cleanup: `59 -> 0`.
- Inspect representative corrected output lines manually.
- Confirm `rawtext1.txt` is absent from the staged diff.

### [ ] 9. Release readiness

**Work**

- Review the final changes and prepare them for user review before any commit.

**Success conditions**

- The app builds, relevant tests pass, and the diff contains only intended app, rules, and test changes.
- No book text or unrelated user files are staged.

**Evidence and tests**

- Run `./gradlew :app:assembleDebug`.
- Run relevant unit and instrumented test tasks.
- Run `git diff --check`.
- Inspect `git status` and the final diff; specifically verify `rawtext1.txt` remains untracked.

## Final acceptance criteria

- All nine stages are marked complete with their evidence recorded.
- The eight required cleanup examples pass, including order-sensitive dialogue conversions (`4|` and `|I`).
- A direct test proves local cleanup never changes raw OCR.
- The user reviews the final diff before any commit.
