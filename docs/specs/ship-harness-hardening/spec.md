# Feature Contract: `/ship` Harness Hardening (Issue #36/#37 Retrospective)

- **Branch:** `feat/ship-harness-hardening`
- **Delivery Target:** `merge-only`
- **Requested Protected Path Unlocks (`allowedProtectedPaths`):**
  1. `.agents/agents/ship-reviewer.md` *(new 6th permanent read-only subagent)*
  2. `.agents/agents/ship-architect.md`
  3. `.agents/agents/ship-implementer.md`
  4. `.agents/agents/oracle-test-author.md`
  5. `.agents/ship.config.json`
  6. `scripts/guards/run-all-guards.mjs`
  7. `scripts/guards/ci-log-compressor.mjs`
- **Explicitly Untouched Protected Files:** `.github/workflows/**`, `.agents/hooks.json`, `runtime-assets.lock.json`, `LICENSES/**`, `android/**`, and all never-unlockable secret files (`.env*`, `*.keystore`, `*.jks`, `*.p12`).

---

## 1. Goals & Non-Goals

### Goals
1. **Fix `wait-pr-gates.mjs` Codex Gate Bugs & Cloud Resilience**:
   - Parse real GitHub Codex Connector `<!-- codex-review-state:v1 {...} -->` metadata (supporting both nested `code_review.{status,commit_sha,has_any_findings,has_unresolved_findings}` and flat `{status,headRefOid|commit_sha}` forms).
   - Check `updatedAt`/`updated_at` in addition to `createdAt`/`submittedAt`, match `headRefOid` against `commit_sha` when available, and **NEVER** classify a `running` placeholder or active `eyes` reaction without a completed verdict as `"clean"` or `reviewPosted: true`.
   - Grant up to `+300s` (`runningGraceSeconds: 300`, capped at `maxTotalTimeoutSeconds: 900`) while Codex is actively `running`.
   - Retry transient `gh` HTTP 5xx / network failures with exponential backoff (`runGhWithRetry`, max 4 retries), while failing fast on deterministic 4xx client errors.
   - Deduplicate `@codex review` across invocations via fresh PR comment inspection (`hasFreshCodexMention`), and emit periodic heartbeat progress lines to `stderr` (keeping `stdout` strict JSON).
2. **Fix `ci-log-compressor.mjs` Category Misclassification**:
   - Prevent benign `libproot.so` log lines during Android builds from misclassifying `:app:testDebugUnitTest` / `AssertionError` failures as `apk_elf_alignment`.
   - Classify primarily over focused error lines (`keyLines` + `tailLines`), falling back to full `cleaned` lines only if `unknown`, and tighten `apk_elf_alignment` regex to real alignment failure signatures.
3. **Add `state.mjs resume` Command**:
   - Support cleanly resuming from `ESCALATED` / `WAITING_HUMAN` to a target state, clearing `escalationReason = null`, setting `gate = "AUTO_PROCEED"` (or `WAITING_HUMAN` for S1/S2), and selectively resetting retry counters (`--reset-retries impl,codex,ci|all`).
4. **Add `.githooks/pre-commit` Secondary Boundary Guard (`check-staged-boundaries.mjs`)**:
   - Provide secondary enforcement for surfaces where `.agents/hooks.json` PreToolUse hooks do not fire: always block `neverUnlockablePatterns`; during an active `/ship` run, block staged edits to unapproved `protectedPatterns`, block staged `M`/`D`/`R` of oracle files when `oracleLocked === true` (allowing initial `A` addition on a feature branch), and block staged `M`/`D`/`R` of locked contracts when `contractLocked === true` (unless in `allowedProtectedPaths` or `KUCODE_ALLOW_BOUNDARY_EDIT=1`).
5. **Pre-PR Adversarial Review & Performance × Cost Optimization**:
   - Add 6th permanent read-only subagent `.agents/agents/ship-reviewer.md` (`model: pro`, read-only tools), register in `.agents/ship.config.json` and Guard 11 `READ_ONLY_SUBAGENTS`, and update `tests/agentic-harness.test.cjs` Tests 1 & 4.
   - Configure 2-tier implementer routing (`execution.implementerTiers`: `standard: "flash"`, `complex: "inherit"`) and micro-fix policy (`ciSelfHeal.microFixMaxLines: 50`) in `.agents/ship.config.json` and `SKILL.md`.
   - Encode Issue #36/#37 lessons into `.agents/agents/oracle-test-author.md`, `ship-architect.md`, `ship-implementer.md`, `.agents/rules/kotlin-android-host.md`, `SKILL.md`, and `AGENTS.md` (keeping `AGENTS.md <= 90` lines).

### Non-Goals
- Modifying any Android app production code (`android/**`), CI workflow YAML (`.github/workflows/**`), or `.agents/hooks.json`.
- Adding external npm dependencies (all scripts remain zero-dependency Node.js 22 built-ins).
- Introducing a full `/teamwork` daemon for single-PR `/ship` tasks.

---

## 2. Public Contracts & Normative Interfaces

### 2.1 `.agents/skills/ship/scripts/wait-pr-gates.mjs`
- **Exported `parseCodexReviewState(body)`**:
  - Input: `body: string`.
  - Extracts JSON from `<!-- codex-review-state:v1 {...} -->`.
  - Supports BOTH:
    - Real GitHub Codex Connector nested schema: `{ code_review: { commit_sha, status, has_any_findings, has_unresolved_findings }, security_review?: { ... } }`
    - Flat schema: `{ status, headRefOid?, commit_sha?, has_any_findings?, has_unresolved_findings? }`
  - Returns `null` if marker absent or JSON invalid; otherwise:
    ```js
    {
      version: 1,
      status,                   // lowercased trimmed string (e.g. "running", "completed", "error") or ""
      headRefOid,               // string | null (normalized from code_review.commit_sha || headRefOid || commit_sha)
      hasAnyFindings,           // boolean | null
      hasUnresolvedFindings,    // boolean | null
      raw,                      // parsed JSON object
    }
    ```
- **Exported `extractCodexFindings(reviews = [], lineComments = [], issueComments = [], options = {})`**:
  - Backward-compatible with 3-argument callers (`options = {}`).
  - `options`: `{ sinceIso?: string, headRefOid?: string, reactions?: Array<object> }`.
  - Freshness (`isFresh`): considers the **maximum valid timestamp** among `updatedAt`, `updated_at`, `submittedAt`, `submitted_at`, `createdAt`, `created_at` so in-place edited Codex summary comments are treated as fresh when updated after `sinceIso - 5000`. Items with no timestamp remain fresh (backward-compatible).
  - Head SHA matching: if both `options.headRefOid` and `parsedState.headRefOid` are non-empty and do NOT match (comparing full SHA or prefix match `>= 7` chars case-insensitively), that `codex-review-state:v1` marker is stale for the current HEAD and MUST NOT mark the current commit as `clean`/`completed` or `running`.
  - Running & Eyes semantics:
    - If a fresh matching `codex-review-state:v1` marker has `status === "running"`, set `isRunning = true`, do **NOT** set `reviewPosted = true` from that running placeholder alone, and **NEVER** return `status === "clean"`.
    - If `options.reactions` contains a Codex `eyes` reaction (`CODEX_LOGIN_RE` + `content === "eyes"`) and no fresh completed review/verdict for current HEAD exists, set `hasEyesReaction = true`, `isRunning = true`, `reviewPosted = false`, `status = "running"`.
    - If a fresh matching `codex-review-state:v1` marker has `status === "completed"` and `hasUnresolvedFindings === false` (and no fresh P0/P1 blocking findings), set `reviewPosted = true` and `status = "clean"` (unless `quotaLimited`).
    - Priority of `status`: `"quota_limited"` > `"blocking_findings"` (when `blocking.length > 0`) > `"running"` (when `isRunning && !reviewPosted`) > `"pending"` (when `!reviewPosted`) > `"clean"`.
  - Return object (additive superset of existing shape):
    `{ status, reviewPosted, isRunning, hasEyesReaction, codexState, quotaLimited, totalCodexItems, blockingCount, blocking, all }`.
- **Exported `evaluateGateVerdict({ ciPassed, codex, timedOut })`**:
  - `!ciPassed` -> `{ readyToAutoMerge: false, exitCode: 2, nextAction: "heal_ci", escalateReason: null }`
  - `codex.quotaLimited` -> `{ readyToAutoMerge: false, exitCode: 3, nextAction: "escalate_human", escalateReason: ... }`
  - `timedOut || !codex.reviewPosted || codex.status === "running" || codex.isRunning` -> `{ readyToAutoMerge: false, exitCode: 3, nextAction: "escalate_human", escalateReason: ... }` (NEVER auto-merge while running!)
  - `codex.blockingCount > 0` -> `{ readyToAutoMerge: false, exitCode: 2, nextAction: "fix_codex_findings", escalateReason: null }`
  - Otherwise -> `{ readyToAutoMerge: true, exitCode: 0, nextAction: "auto_merge", escalateReason: null }`.
- **Exported `computeCodexDeadline({ nowMs, baseDeadlineMs, startMs, codex, runningGraceSeconds = 300, maxTotalTimeoutSeconds = 900, graceGranted = false })`**:
  - Computes `maxAbsoluteDeadlineMs = startMs + maxTotalTimeoutSeconds * 1000`.
  - When `(codex?.isRunning || codex?.status === "running" || codex?.hasEyesReaction) && !graceGranted`:
    - `deadlineMs = Math.min(maxAbsoluteDeadlineMs, Math.max(baseDeadlineMs, nowMs + runningGraceSeconds * 1000))`
    - Returns `{ deadlineMs, graceGranted: true, extended: deadlineMs > baseDeadlineMs }`.
  - Otherwise returns `{ deadlineMs: Math.min(maxAbsoluteDeadlineMs, baseDeadlineMs), graceGranted, extended: false }`.
- **Exported `hasFreshCodexMention(issueComments = [], { sinceIso, autoMentionComment = "@codex review" } = {})`**:
  - Returns `true` if any comment body contains `autoMentionComment` (case-insensitive) or `/@codex\s+review\b/i` AND its latest timestamp (`updatedAt`/`updated_at`/`createdAt`/`created_at`) is fresh (`>= Date.parse(sinceIso) - 5000` when `sinceIso` provided).
- **Exported async `runGhWithRetry(args, options = {})`**:
  - `options`: `{ maxRetries = 4, baseDelayMs = 500, runner, sleepFn, onRetry }`.
  - Retries transient errors: HTTP `500`/`502`/`503`/`504`, `Bad Gateway`, `Service Unavailable`, `Gateway Timeout`, `ETIMEDOUT`, `ECONNRESET`, `ECONNREFUSED`, `ENOTFOUND`, `EAI_AGAIN`, `socket hang up`, `TLS`, HTTP `429` / secondary rate limit.
  - Fails fast (zero retries) on deterministic 4xx client errors (`HTTP 400`, `401`, `403` non-rate-limit, `404`, `422`, unknown flag).
  - Uses exponential backoff `baseDelayMs * (2 ** attempt)` and rethrows the last error after exhausting `maxRetries`.
- **Exported async `pollPr(prNumber, options = {})`**:
  - Supports injectable `{ ghRunner, sleepFn, logFn, nowFn, pollSeconds, heartbeatSeconds, codexTimeoutSeconds, runningGraceSeconds, maxTotalTimeoutSeconds, ciTimeoutSeconds, noAutoMention, repo, sinceIso }`.
  - Reads `headRefOid` from initial `gh pr view`, passes `headRefOid` + fetched `reactions` into `extractCodexFindings`, deduplicates `@codex review` via `hasFreshCodexMention`, applies `computeCodexDeadline`, writes periodic single-line heartbeat to `logFn` (default `process.stderr.write`, NEVER `stdout`), and includes `codex.runningGraceGranted` and `codex.effectiveTimeoutSeconds` in the returned report.

### 2.2 `scripts/guards/ci-log-compressor.mjs`
- Keeps `compressCiLog(rawText, options = {})` and `formatMarkdown(report)` (`schema: 1`) backward-compatible.
- Tightens `apk_elf_alignment` regex so benign `libproot.so` filenames never trigger it:
  `/16KB LOAD alignment|LOAD offset=|not 16KB[- ]aligned|ELF alignment (?:check )?failed|has no LOAD segments|libproot(?:-loader)?\.so[^\n]*(?:not 16KB|align|FAILED|error|missing|invalid)/i`.
- Two-phase category classification:
  1. First match `CATEGORIES` against focused error lines (`[...deduped, ...tailLines]`).
  2. Only if still `"unknown"`, fall back to matching against all `cleaned` lines.
- Ensures a Gradle unit test failure log containing earlier benign `libproot.so` lines classifies as `"gradle_test"`, while real 16KB ELF alignment failures classify as `"apk_elf_alignment"`.

### 2.3 `.agents/skills/ship/scripts/state.mjs`
- **Exported `resumeRun(statePath, options = {})`**:
  - `options`: `{ targetState?: string, resetRetries?: string[] | "all" }`.
  - Rejects if no active state exists or if `state.currentState === "DONE"`.
  - Rejects invalid `targetState` (must be in `VALID_STATES` and NOT `"ESCALATED"` or `"DONE"`), and enforces lock preconditions (`S3a`/`S3b`/`S4`/`S5` require `contractLocked`; `S3b`/`S4`/`S5` require `oracleLocked`; `S6`/`S7`/`S8` require `ciStatus === "passed"` and `codexStatus === "passed"`).
  - Defaults `targetState` when omitted to `state.oracleLocked ? "S3b_IMPL_WORKFLOW" : (state.contractLocked ? "S3a_ORACLE_TEST_GEN" : "S0_RECON")`.
  - Clears `state.escalationReason = null`, sets `state.stallsInState = 0`, sets `state.gate = (targetState === "S1_AUTO_GRILL" || targetState === "S2_CONTRACT_LOCK") ? "WAITING_HUMAN" : "AUTO_PROCEED"`.
  - Resets retry counters specified in `resetRetries` (`"all"` resets `impl`, `codex`, `ci`; array validates each bucket is in `["impl", "codex", "ci"]` and throws on unknown bucket).
- **CLI**: `node .agents/skills/ship/scripts/state.mjs resume [targetState] [--reset-retries impl,codex,ci|all]` -> prints updated JSON state and exits `0` (exits `1` on invalid input/precondition).

### 2.4 `.agents/skills/ship/scripts/check-staged-boundaries.mjs` & `.githooks/pre-commit`
- **Exported `parseNameStatusOutput(raw, nulDelimited = false)`**: parses `git diff --cached --name-status` (or `-z`) into `Array<{ status, path, oldPath? }>`.
- **Exported `evaluateStagedEntries(entries, options = {})`**:
  - `options`: `{ hasActiveShipRun = false, currentState = "", contractLocked = false, oracleLocked = false, oracleTestFiles = [], allowedProtectedPaths = [], config = {}, overrideEnv = process.env.KUCODE_ALLOW_BOUNDARY_EDIT === "1" }`.
  - Rules:
    1. `neverUnlockablePatterns` (`.env*`, `*.keystore`, `*.jks`, `*.p12`): **ALWAYS `deny`** regardless of status, `hasActiveShipRun`, `allowedProtectedPaths`, or `overrideEnv`.
    2. If `overrideEnv === true` or `!hasActiveShipRun`: allow remaining non-secret entries.
    3. `protectedPatterns`: **`deny`** for any status (`A`/`M`/`D`/`R`) unless matched in `allowedProtectedPaths`.
    4. `oraclePatterns` / `oracleTestFiles`: when `oracleLocked === true`, **allow `A`** (initial addition of new oracle test on feature branch) and **`deny` `M`, `D`, `T`, `R`** (modifying, deleting, or renaming an already-tracked oracle file, including rename `oldPath`).
    5. `contractPatterns`: when `contractLocked === true`, **allow `A`** (initial addition of new feature spec/ADR) and **`deny` `M`, `D`, `T`, `R`** unless matched in `allowedProtectedPaths`.
  - Returns `{ decision: "allow" | "deny", violations: Array<{ status, path, rule, reason }>, reason: string | null }`.
- **CLI**: `node .agents/skills/ship/scripts/check-staged-boundaries.mjs [--cwd <dir>]` inspects staged git entries in `cwd`, reads `.agents/ship.config.json` & branch state (or `KUCODE_SHIP_STATE_FILE`), prints clear error to `stderr` and exits `1` on `deny`, exits `0` on `allow`.
- **`.githooks/pre-commit`**: invokes `node .agents/skills/ship/scripts/check-staged-boundaries.mjs` before running `run-all-guards.mjs --changed-only`.

### 2.5 Agent Roster, Config, Guards & Guidance
- **`.agents/agents/ship-reviewer.md`**: valid YAML frontmatter (`name: ship-reviewer`, `subagent: true`, `model: pro`, `commandExecutionPolicy: sandbox`, read-only tools `view_file`, `run_command`, `send_message`), pre-PR adversarial review prompt focusing on concurrency/lock DAG/CAS/consent/crash-recovery/security P0/P1 defects.
- **`.agents/ship.config.json`**: adds `execution.agents.reviewer = "ship-reviewer"`, `execution.models.reviewer = "pro"`, `execution.implementerTiers = { standard: "flash", complex: "inherit" }`, `codexReview.runningGraceSeconds = 300`, `codexReview.maxTotalTimeoutSeconds = 900`, `ciSelfHeal.microFixMaxLines = 50`.
- **`scripts/guards/run-all-guards.mjs`**: adds `"ship-reviewer"` to `READ_ONLY_SUBAGENTS` in Guard 11.
- **Prompt & Rule Contracts**:
  - `.agents/agents/oracle-test-author.md`: forbids `execFileSync` against in-process loopback HTTP servers (use async `execFile`/`spawn`) and forbids Java `HttpURLConnection` for restricted headers like `Origin`/`Sec-Fetch-Site` (use raw `Socket`).
  - `.agents/agents/ship-architect.md` & `.agents/agents/ship-implementer.md`: include the 4-point Concurrency & Lifecycle Boundary Checklist (lock DAG separating sync UI/JS bridge from I/O, CAS/monotonic snapshot ordering, per-request consent recheck, crash-finalized recovery); `ship-architect.md` also assigns `standard (flash)` vs `complex (inherit)` Wave tiers.
  - `.agents/rules/kotlin-android-host.md`: adds `@JavascriptInterface` sync-read vs I/O-lock separation invariant.
  - `.agents/skills/ship/SKILL.md`: documents `ship-reviewer` pre-PR adversarial review, tiered implementer routing, micro-fix (<50 lines) policy, `state.mjs resume`, and `.githooks/pre-commit` secondary boundary lock.
  - `AGENTS.md`: adds `ship-reviewer` (`pro`) and stays concise (`<= 90` lines).

---

## 3. Disjoint Implementation Ownership (Wave 1 Parallel -> Wave 2 Sequential)

- **Wave 1-A (`complex`, `Model: "inherit"`)**: `.agents/skills/ship/scripts/wait-pr-gates.mjs`
- **Wave 1-B (`standard`, `Model: "flash"`)**: `.agents/skills/ship/scripts/state.mjs`, `.agents/skills/ship/scripts/check-staged-boundaries.mjs`, `scripts/guards/ci-log-compressor.mjs`
- **Wave 1-C (`standard`, `Model: "flash"`)**: `.agents/agents/ship-reviewer.md`, `.agents/agents/ship-architect.md`, `.agents/agents/ship-implementer.md`, `.agents/agents/oracle-test-author.md`, `.agents/rules/kotlin-android-host.md`
- **Wave 2-A (`standard`, `Model: "flash"`)**: `.agents/ship.config.json`, `scripts/guards/run-all-guards.mjs`, `.githooks/pre-commit`, `.agents/skills/ship/SKILL.md`, `AGENTS.md`, `tests/agentic-harness.test.cjs`

---

## 4. Clean-Room Oracle Test Plan (`S3a_ORACLE_TEST_GEN`)
- `tests/oracle/ship-harness-hardening.oracle.test.cjs` covering `AC-1`..`AC-9` (pure Node.js black-box E2E/contract suite).
- No Android app code changed (`AC-10` physical XREAL AURA device gate: N/A / `NOT RUN` recorded truthfully).

---

## 5. Rollback Plan
- Single git revert of the squash commit on `main` restores the previous 5-agent harness cleanly with zero data migration or runtime asset impact.
