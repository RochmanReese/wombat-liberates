# Session log

Add each session summary immediately below this heading so the newest entry remains first.

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
