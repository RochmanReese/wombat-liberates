# OCR rule sharing and curated updates — coding brief

## Purpose

Add an opt-in path for users to share selected local OCR corrections as candidates for a centrally curated default rule pack. Approved rules can then be shipped to all users safely in a later app release or signed rules-pack update.

This is a separate feature from local OCR corrections. Local cleanup must remain fully usable offline.

## Non-negotiable decisions

- Never upload raw OCR, cleaned OCR, screenshots, book titles, or surrounding book text by default.
- Never automatically upload a user-created rule. Sharing requires an explicit action and confirmation for each rule.
- Never automatically promote an AI suggestion or user rule into the global default set.
- Only reviewed rules are published to users.
- Preserve local user rules during every default-pack update.
- Default packs must be versioned, parser-validated, integrity-checked, and recoverable after a failed update.
- Existing users must be migrated safely from the current combined `ocr-rules.txt` model.

## Target rule model

```text
Effective cleanup rules
= curated default pack
+ local custom rules
```

Suggested local storage:

```text
assets/ocr-default-rules.txt             # bundled fallback default pack
filesDir/ocr-default-rules.txt           # newest verified downloaded default pack, if any
filesDir/ocr-custom-rules.txt            # user-created rules only
SharedPreferences/default_rules_version  # active curated pack version
```

The existing `filesDir/ocr-rules.txt` contains seeded defaults plus custom additions. It is a migration source, not the long-term format.

## Implementation and verification checklist

### [ ] 1. Separate defaults from custom rules and migrate existing users

**Work**

- Replace the combined effective-file model with separate default and custom rule stores.
- On upgrade, detect the current `ocr-rules.txt` format.
- If it begins with the known old default pack, move only its suffix into `ocr-custom-rules.txt`.
- If it cannot be recognized safely, preserve its contents as legacy custom rules and show a clear migration notice rather than discarding anything.
- Make cleanup combine defaults first and custom rules second.

**Success conditions**

- A new install has active defaults and no custom rules.
- An existing user retains every custom rule after migration.
- A default update cannot remove or reorder custom rules.
- Raw OCR, cleaned OCR, diagnostics, and Ollama settings are not changed by migration.

**Tests**

- JVM tests: new-install store initialization; recognized legacy default-plus-custom migration; defaults-only migration; unrecognized legacy file preservation.
- Instrumented test: upgrade fixture with raw/cleaned OCR and Ollama preferences, then assert only rules storage changes.
- Regression test: effective rules apply defaults before custom rules.

### [ ] 2. Define a privacy-minimized candidate-rule protocol

**Work**

- Define a versioned API schema for a submitted candidate.
- Include only the literal Find/Replace pair and operational metadata: app version, rules-pack version, locale/language selection, submission timestamp, and a client-generated anonymous installation identifier if needed for abuse prevention.
- Exclude OCR text, cleaned text, filenames, titles, author names, screenshots, and explanatory context by default.
- Add a consent record/version so users can see what they agreed to share.

**Suggested candidate shape**

```json
{
  "schemaVersion": 1,
  "find": "zOomed",
  "replace": "zoomed",
  "language": "en",
  "appVersion": "0.1.0",
  "defaultRulesVersion": "2026-07-01",
  "submittedAt": "2026-07-27T00:00:00Z"
}
```

**Success conditions**

- The request cannot contain captured book text through the normal client path.
- Invalid, empty, duplicate, overlong, or malformed rules are rejected before sending.
- The schema can evolve without breaking older apps or services.

**Tests**

- Serialization/deserialization tests for schema versions.
- Validation tests for empty Find, oversized fields, line breaks, malformed rules, and duplicate canonical forms.
- Request-inspection test proving no raw OCR or cleaned OCR field is present.

### [ ] 3. Build the candidate-rules service

**Work**

- Implement an HTTPS API, for example `POST /v1/rule-candidates`.
- Store candidates in a moderation queue with statuses such as `pending`, `rejected`, `approved`, and `published`.
- Canonicalize and hash Find/Replace pairs for deduplication while retaining the original submitted form for review.
- Add rate limiting, request-size limits, audit timestamps, and server-side validation.
- Provide authenticated reviewer/admin access; do not expose the moderation queue publicly.

**Success conditions**

- A valid opt-in candidate is accepted once and deduplicated thereafter.
- Invalid requests receive actionable 4xx responses.
- Unauthenticated users cannot approve or publish rules.
- The service stores no OCR body text because the API does not accept it.

**Tests**

- API integration tests for create, duplicate, validation, rate-limit, and authorization paths.
- Database migration tests.
- Security tests for unauthenticated review/publish attempts and oversized request rejection.

### [ ] 4. Add an opt-in “Share correction” user flow

**Work**

- Add a per-rule **Share correction** action in the OCR corrections list.
- Before first share, show plain-language consent explaining that the literal Find/Replace pair will be sent for review and will not be applied globally automatically.
- Show submission progress, success, already-submitted, and failure states.
- Keep a local submission history so accidental repeated taps do not resubmit unnecessarily.
- Provide a setting to revoke future sharing permission; it does not erase previously submitted candidates from the service unless a separate deletion process is implemented.

**Success conditions**

- Users can use local rules without ever seeing or using sharing.
- Sharing requires an explicit action and consent.
- A failed request never deletes the local correction.
- A submission does not make the rule global or change any other local rule.

**Tests**

- UI/instrumented tests: consent required on first share; successful submission state; network failure preserves the rule; duplicate submission is handled.
- Mock-server test verifying the request body contains only approved fields.
- Manual device test with offline and slow-network conditions.

### [ ] 5. Build reviewer tooling and a curated-rule workflow

**Work**

- Provide a small authenticated web interface or internal CLI to list, search, inspect, approve, reject, and annotate candidates.
- Show submission count, candidate conflicts, language, and publication history.
- Require a reviewer to add or link a synthetic regression test before approval.
- Treat broad/ambiguous replacements as high-risk and reject them by default.

**Success conditions**

- A reviewer can trace every published rule to its candidate and review decision.
- Candidates such as `zOomed => zoomed` can be approved safely.
- Candidates that infer missing words or rewrite long phrases cannot be published accidentally.

**Tests**

- Reviewer authorization tests.
- Workflow test: pending → approved → published, and pending → rejected.
- Test that publication is blocked without an associated regression test or approval record.

### [ ] 6. Publish signed, versioned default rule packs

**Work**

- Generate a curated plain-text rules pack plus a manifest containing version, SHA-256 digest, creation time, minimum app version, and signature.
- Store the public verification key in the app.
- Publish packs through HTTPS/CDN or bundle them in ordinary app releases for the initial MVP.
- Keep prior pack metadata available for rollback.

**Success conditions**

- A published pack has a stable version and deterministic contents.
- Clients reject a pack with an invalid signature, hash, schema, or parser errors.
- Publishing a new pack does not expose unreviewed candidates.

**Tests**

- Deterministic pack-generation test.
- Signature verification tests for valid, altered, expired, and wrong-key manifests.
- Parser test for every published pack before release.
- Rollback test to a previous verified pack.

### [ ] 7. Add safe default-pack updates to the app

**Work**

- Offer a user-controlled **Check for OCR rule updates** action initially; do not silently download rules in the first release.
- Download the manifest and pack over HTTPS, verify before use, then atomically replace only `filesDir/ocr-default-rules.txt`.
- Retain the prior verified pack until the new one is accepted.
- Re-render the corrections UI while preserving custom rules.
- Record the active pack version and update result locally.

**Success conditions**

- A valid newer pack updates defaults while custom rules remain intact and ordered afterward.
- A failed, tampered, incompatible, or offline update leaves the active rules unchanged.
- Users can see the active default-pack version and last update result.

**Tests**

- Instrumented/mock-web-server test: valid update; no update available; offline; malformed file; invalid hash/signature; interrupted download.
- Regression test that a custom rule still overrides or follows a newly updated default exactly as specified.
- Manual device test across app restart and reinstall.

### [ ] 8. Add AI suggestions as a separate, local review queue

**Work**

- Compare raw OCR and AI-cleaned text locally to derive short literal candidate edits.
- Show before/after snippets with **Add locally**, **Share for review**, and **Ignore** actions.
- Do not ask the AI to modify shared rules directly.
- Exclude large rewrites, missing-word guesses, reordering, and multi-paragraph edits from rule-candidate generation.

**Success conditions**

- The audit log reflects actual raw-to-cleaned differences, not an AI explanation of what it thinks it changed.
- Candidate generation never silently changes local or global rules.
- Ambiguous edits are review-only and cannot become a one-tap global publication.

**Tests**

- Diff tests for simple substitutions, insertions, deletions, line-order changes, and large rewrites.
- UI tests for add, share, and ignore actions.
- Privacy test confirming the local diff queue is not uploaded without an explicit share action.

### [ ] 9. End-to-end release and operations verification

**Work**

- Run the entire lifecycle against staging: local rule → user share → moderation → signed pack → client update.
- Document privacy policy, consent wording, moderation responsibilities, abuse handling, backups, retention/deletion policy, and incident rollback.
- Review operational cost, server ownership, and authentication before public launch.

**Success conditions**

- An approved candidate reaches a test client only through a signed curated pack.
- A rejected candidate never reaches clients.
- A user can opt out and still use every local correction feature offline.
- Production monitoring reports failures without collecting book text.

**Tests**

- Staging end-to-end test with two isolated test accounts/devices.
- Load and rate-limit test for candidate submission.
- Disaster-recovery test for pack rollback and service outage.
- Final privacy/security review before enabling public submissions.

## Recommended delivery order

1. Complete current local-rule UI testing and commit it.
2. Implement step 1 first; separating default/custom files is required for safe future updates.
3. Ship a rules-pack update MVP using only bundled app-release updates.
4. Add candidate sharing and moderation behind an opt-in beta flag.
5. Add signed over-the-air rule-pack updates only after the service and reviewer workflow are proven.
6. Add local AI suggestion review last; do not make it a prerequisite for safe shared defaults.

## Explicit non-goals for the first release

- Automatic submission of all user rules.
- Automatic publication of any candidate rule.
- Uploading books, OCR output, screenshots, or context snippets.
- Automatically applying community rules without review and signature validation.
- Asking an AI model to make global rule changes without human approval.
