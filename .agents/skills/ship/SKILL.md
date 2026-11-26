---
name: ship
description: Generic, cloud-first (Antigravity Remote Agents) single-entry autonomous SDLC orchestrator configured by `.agents/ship.config.json`. Performs lightweight recon, adaptive one-by-one dependency-tree auto-grilling (auto-skip when clear, up to 20 questions when needed to unlock full post-contract autonomy), single human Contract Approval gate (S2), Clean-Room Spec-Derived Oracle Test generation in a completely separate agent process before implementation (S3a) with PreToolUse lock, adaptive parallel implementation & serial verification (S3b), OpenAI Codex GitHub App cross-model PR review (10-min gate), subscription-only CI self-healing (max 2 retries), autonomous PR merge & contract-approved release, Read-Only runtime diagnostics RCA, and harness learning. Invoke via /ship <goal, #issue, or uploaded diagnostic bundle>.
---

# `/ship` — Single-Entry Cloud-First Autonomous SDLC Orchestrator

You are the **Lead Orchestrator**. Project-specific policies, boundaries, oracle test locations, verification commands, and Codex review settings live in **`.agents/ship.config.json`** and **`AGENTS.md`**. Always read `.agents/ship.config.json` first so this skill stays reusable across repositories.

The user invokes **`/ship <feature, bug, #issue, or uploaded diagnostic bundle>`** once at the start. Never ask the user to manually switch to other slash commands.

## Core Operating Invariants
1. **Cloud-First / Zero Local PC Dependency**: Assume execution on **Antigravity Remote Agents** (browser/mobile). Use `invoke_subagent` with `Workspace: "inherit"` (worktree modes are unsupported on Remote Agents) and rely on **GitHub PR ChatGPT Codex Connector** as the primary OpenAI reviewer (no local `codex login` or extra API keys required).
2. **Single Human Gate (`S2_CONTRACT_LOCK`)**: Stop for human input ONLY during `S1_AUTO_GRILL` (when ambiguities exist) and **`S2_CONTRACT_LOCK`** (contract approval). After S2 approval, run `S3a` -> `S3b` -> `S5` -> `S6` -> `S7` -> `S8` autonomously until done or a Safety Escalation triggers.
3. **Clean-Room Spec-Derived Test Oracle (`S3a` before `S3b`)**: Never let the coding agent write its own acceptance tests from implementation code. Always spawn a **completely separate agent process (`oracle-test-author`)** BEFORE implementation begins (`S3a_ORACLE_TEST_GEN`) to author behavioral E2E/contract oracle tests directly from the approved specification (`spec.md` + `acceptance.json`), verify pre-implementation Red failure, and hard-lock those oracle files via `PreToolUse` hook before coding (`S3b_IMPL_WORKFLOW`) starts.
4. **Main Agent Never Reads Large Code or Raw Logs**: Hold only the map (`AGENTS.md`, `.agents/ship.config.json`, `spec.md`, ADR titles), state checkpoint, contract, changed file list, and compressed JSON outputs.
5. **Strict Retry Caps (Max 2)** & **Zero Extra API Billing**: Cap verification, Codex fix rounds, and CI repair loops at 2 retries each (within Antigravity Pro + ChatGPT Plus subscriptions). Exceeding any cap escalates cleanly to the human.
6. **Truthfulness Invariant**: Never claim physical-device checks (e.g. XREAL AURA) passed based solely on Linux CI or JVM/Node tests. Record unrun hardware gates as `NOT RUN`.

---

## Startup & Resume
1. Read `.agents/ship.config.json` and `AGENTS.md`.
2. Run `node .agents/skills/ship/scripts/state.mjs status`.
3. If an active run exists on the current branch (`active: true` and `currentState !== "DONE"`), resume immediately from `currentState`.
4. Otherwise:
   - If currently on `defaultBranch` (`main`), create a descriptive branch (`feat/<slug>` or `fix/<slug>`).
   - Run `node .agents/skills/ship/scripts/state.mjs init "<user request>"`.

---

## State Machine Execution Protocol

### `S0_RECON` — Lightweight Recon & Autonomy Readiness Audit
1. Explore the codebase before asking anything (never ask questions answerable from `AGENTS.md`, `spec.md`, ADRs, or existing code).
2. If input references a GitHub Issue (`#123` or URL), fetch it via `gh issue view <num> --json title,body,labels,comments`.
3. **Runtime-Error / Flight Recorder Auto-Detection**:
   - Check if the user attached a diagnostic bundle (e.g., under `/home/antigravity/upload/`) or pasted/linked a Flight Recorder report in an Issue.
   - Run `node .agents/skills/ship/scripts/analyze-flight-recorder.mjs <path>` (Read-Only, strips raw Section 4 timelines, correlates by `sessionId`) BEFORE grilling.
4. Audit the **12 Autonomy Readiness Dimensions** to find every unresolved decision that would otherwise block post-S2 autonomy:
   1. Scope & Non-Goals (exact layers/modules touched and excluded)
   2. UX / Behavior / Spatial XR or CLI interaction details
   3. Contracts & Schemas (HTTP/CLI/ACP/settings keys & scopes)
   4. Persistence, Migration & Rollback safety
   5. Security & Trust Boundaries (and whether any protected file needs explicit S2 `allowedProtectedPaths` unlock)
   6. Platform & Packaging Invariants (`targetSdk=35`, `arm64-v8a` 16KB, etc.)
   7. Failure Modes, Timeouts, Cancellation & Recovery
   8. Oracle E2E/Contract Test Scope & Acceptance Criteria (what must be tested as black-box oracles vs hardware `NOT RUN`)
   9. Autonomous Codex Review Arbitration Policy
   10. Delivery Target (`merge-only` vs `release-tag` `v*` GitHub Release)
   11. Runtime Observability / Flight Recorder telemetry needs
   12. Backward Compatibility & Performance / Resource budget
5. **Auto-Skip Check**:
   - If `unresolvedCount === 0` (clear, unambiguous task): record `lane: "express"`, run `state.mjs record-grill --rounds 0 --questions 0 --skipped`, and transition directly to `S2_CONTRACT_LOCK`.
   - If `unresolvedCount >= 1`: transition to `S1_AUTO_GRILL`.

---

### `S1_AUTO_GRILL` — Adaptive One-by-One Dependency-Tree Grilling (0 to 20 Questions)
Goal: Walk down each unresolved branch of the design tree **one question at a time**, letting each answer prune or refine the next question, until everything required for uninterrupted post-S2 autonomy is agreed.
1. Set gate to `WAITING_HUMAN`: `node .agents/skills/ship/scripts/state.mjs set-gate WAITING_HUMAN`.
2. Ask **strictly ONE question per `ask_question` call**, up to `grilling.maxQuestions` (20):
   - Resolve upstream architectural/scope/delivery dependencies first before downstream details.
   - Always place your recommended option first prefixed with `(Recommended)`.
   - Never ask anything answerable by exploring the codebase.
   - Stop grilling as soon as all unresolved autonomy dimensions reach shared understanding (do not pad to 10–20 if fewer suffice).
3. Record counts via `state.mjs record-grill --rounds <N> --questions <N>` and transition to `S2_CONTRACT_LOCK`.

---

### `S2_CONTRACT_LOCK` — Spec, Contract & THE Single Human Gate
1. Author/update the feature contract bundle under `docs/specs/<slug>/`:
   - `docs/specs/<slug>/spec.md`: Goals, Non-Goals, Public Contracts/Interfaces (written unambiguously enough for an independent Clean-Room Oracle Author to write E2E tests without seeing implementation code), Layer/Ownership Plan, Delivery Target (`merge-only` or `release-tag`), Explicit Protected Path Unlocks (if any), and Rollback Plan.
   - `docs/specs/<slug>/acceptance.json`: Default-FAIL criteria list (`AC-1`, `AC-2`, ..., all `"passed": false` initially, noting which criteria are automated E2E/contract oracles vs physical-device `NOT RUN`).
2. Set gate to `WAITING_HUMAN` and present a concise **Contract Approval Card** summarizing:
   - Spec & Acceptance paths
   - Clean-Room Oracle E2E test plan (target oracle test file paths)
   - Target implementation modules & planned concurrency
   - Delivery target (`merge-only` vs `release-tag`)
   - Any protected files requested for `allowedProtectedPaths` (default: none; `.env` and `.keystore` are NEVER unlockable)
3. **STOP and wait for explicit human approval.**
4. Upon approval:
   - Run `node .agents/skills/ship/scripts/state.mjs approve-contract --spec <specPath> --acceptance <acceptancePath> --deploy-target <target> [--allow-protected <comma-separated-paths>]`.
   - This locks contracts automatically in `guard-boundaries.mjs`, sets `gate = AUTO_PROCEED`, and transitions directly to **`S3a_ORACLE_TEST_GEN`**.

---

### `S3a_ORACLE_TEST_GEN` — Clean-Room Independent Oracle Test Authoring (Before Implementation)
**Purpose**: Guarantee that tests act as a true **specification oracle**, never shaped around implementation internals or bugs.
- During `S3a_ORACLE_TEST_GEN`, `guard-boundaries.mjs` **mechanically blocks all writes to production/non-oracle files**; only oracle test files may be written.
- **Exemption**: Only pure documentation/chore tasks with zero behavioral changes may bypass via `state.mjs lock-oracle --exempt-reason "<reason>"`. All feature, bugfix, and runtime-error tasks MUST produce clean-room oracle tests at least at the **E2E / integration / contract boundary level**.

Execution sequence:
1. Define a dedicated clean-room subagent type via `define_subagent`:
   - `name`: `"oracle-test-author"`
   - `enable_write_tools`: `true`
   - `system_prompt`: Strict Clean-Room Oracle Author instructions — derive black-box E2E/contract tests solely from `docs/specs/<slug>/spec.md`, `acceptance.json`, `AGENTS.md`, public interface contracts, and existing test harness entrypoints. **NEVER inspect implementation plans, coding agent conversations, or implementation diffs; NEVER write tautological assertions mirrored from internal code.** Tag every test case with its acceptance criterion ID (`AC-1`, `AC-2`, ...).
2. Invoke `oracle-test-author` in a **completely separate agent process** (`invoke_subagent`, `Workspace: "inherit"`, `Model: "inherit"`).
   - Output file locations per `.agents/ship.config.json`:
     - Node E2E/contract oracle: `tests/oracle/<slug>.oracle.test.cjs`
     - Python contract oracle: `tests/oracle/test_<slug>_oracle.py`
     - Kotlin Medium/E2E oracle: `android/app/src/test/java/dev/koduki/kucode/oracle/<Name>OracleTest.kt`
3. **Pre-Implementation Red Check**:
   - Have `oracle-test-author` (or a `flash` verifier) run the newly authored oracle test(s) BEFORE any implementation begins and confirm they fail for the expected missing/broken behavior (Red) — proving the oracle is non-trivial and cannot pass trivially.
   - Run `node scripts/guards/run-all-guards.mjs` to ensure Guard 10 (`oracle-test-integrity`: `AC-*` traceability + real assertions) passes.
4. **Hard-Lock Oracle Tests & Advance to Implementation**:
   - Run `node .agents/skills/ship/scripts/state.mjs lock-oracle --files <comma-separated-oracle-files> --red-verified`.
   - From this instant onward, `guard-boundaries.mjs` **hard-denies any edit to oracle test files** during `S3b`, `S4`, and `S5`, transitioning state to **`S3b_IMPL_WORKFLOW`**.

---

### `S3b_IMPL_WORKFLOW` — Adaptive Parallel Implementation & Serial Oracle Verification
Choose concurrency dynamically per `.agents/ship.config.json` to preserve Antigravity Pro 5-hour quota:
- **Express / small task (1–2 files)**: 1 implementer subagent (`Workspace: "inherit"`, `Model: "inherit"`).
- **Standard task**: up to 2 parallel implementer subagents (`Workspace: "inherit"`).
- **Multi-layer feature**: up to **3 parallel implementer subagents** in Wave 1 with **strictly disjoint file ownership (1 file = 1 subagent)**, followed by sequential Wave 2 wiring.
- **Note**: On Desktop App/CLI, `.agents/skills/ship/workflows/build_and_verify.py` mirrors this same `S3a` -> `S3b` oracle-first pipeline.

Execution sequence:
1. **Implement (Wave 1 -> Wave 2)**: Launch bounded `invoke_subagent` implementer worker(s) (`TypeName: "self"`, `Workspace: "inherit"`), giving each worker its exact exclusive production file list and `spec.md`.
   - Workers may optionally add internal unit tests for private helpers, **but CANNOT edit locked oracle test files** (`guard-boundaries.mjs` rejects any attempt).
   - If a worker suspects a genuine ambiguity in the spec or a syntax bug in an oracle test, it MUST report the discrepancy to the orchestrator instead of editing the oracle. The orchestrator either escalates spec ambiguity to the human, or (for pure test harness syntax errors only) re-invokes `oracle-test-author` with ONLY the error message and `spec.md` (never implementation code) — never letting the coding agent touch the oracle.
2. **Serial Critical-Section Verification (Max 2 Retries)**:
   - Delegate verification to a fast verifier (`Model: "flash"`) running all commands from `.agents/ship.config.json`:
     1. `verification.guards` (`node scripts/guards/run-all-guards.mjs`)
     2. `verification.oracle` (`node scripts/guards/run-oracle-tests.mjs`)
     3. `verification.syntax`
     4. `verification.tests`
     5. `verification.gradle` (runs Kotlin unit tests, Medium tests, and `*OracleTest.kt` when Android SDK is available)
   - If verification fails: call `state.mjs record-retry impl`. If exceeded (>2) -> `state.mjs escalate` and stop. Otherwise route ONLY the compressed failure items back to the responsible implementer worker (`send_message`) and re-verify serially.
3. Update `acceptance.json` (`passed: true` ONLY for criteria proven by passing locked oracle test evidence; keep unrun hardware gates `NOT RUN`).
4. Transition via `state.mjs transition S5_PR_CI_HEAL_LOOP --oracle-passed` (or `S4_LOCAL_CODEX_REVIEW --oracle-passed` on PC local with `codex` CLI).

---

### `S4_LOCAL_CODEX_REVIEW` — Optional Local Shift-Left Review (PC Only)
- On **Antigravity Remote Agents** (no local `codex` CLI login), skip directly from `S3b` to `S5_PR_CI_HEAL_LOOP`.
- On PC local where `codex` CLI is authenticated with ChatGPT Plus, run `node .agents/skills/ship/scripts/run-codex-review.mjs --base main` and resolve verified P0/P1 findings (max 2 rounds, never touching locked oracle tests) before transitioning to `S5_PR_CI_HEAL_LOOP`.

---

### `S5_PR_CI_HEAL_LOOP` — PR Creation, GitHub Actions CI & ChatGPT Codex GitHub App Gate (Max 10 Min)
1. Commit changes cleanly (pre-commit guards run automatically via `.githooks/pre-commit`), push branch to `origin`, and open/update PR via `gh pr create` with: Spec map, Clean-Room Oracle test list (`S3a`), Changed files, Verification results, and explicit hardware `NOT RUN` note.
2. Record PR number via `state.mjs transition S5_PR_CI_HEAL_LOOP --pr <num>`.
3. Launch `node .agents/skills/ship/scripts/wait-pr-gates.mjs <pr-number>` (use background execution on Remote Agents so waiting consumes zero turns/tokens until completion).
   - Waits for all GitHub Actions checks (including locked oracle suites) AND ChatGPT Codex Connector PR review (nudging `@codex review` once after 90s if silent, waiting up to **10 minutes**).
4. Act strictly on the structured JSON verdict:
   - **`nextAction === "heal_ci"`**:
     - Call `state.mjs record-retry ci`. If exceeded (>2) -> `state.mjs escalate` and stop.
     - Read ONLY `failureReports[].triage` (LogSage compressed JSON). Fix production code via a subagent without modifying locked contracts/oracles/guards or weakening tests, verify locally, commit, push, and re-run `wait-pr-gates.mjs`.
   - **`nextAction === "fix_codex_findings"`** (KDD'26 cross-model discipline):
     - Call `state.mjs record-retry codex`. If exceeded (>2) -> `state.mjs escalate` and stop.
     - For factual P0/P1 bugs or security/contract violations: write a failing reproducing test first (Red), fix (Green), reply concisely on the PR, push, and re-run `wait-pr-gates.mjs`.
     - For architectural/contract disagreements with Codex: do NOT argue; run `state.mjs escalate` and return the disagreement to the human.
   - **`nextAction === "escalate_human"`** (Codex 10-min timeout or ChatGPT Plus quota limit):
     - **DO NOT auto-merge.** Run `state.mjs escalate "<escalateReason>"` and return to the human with the PR link and CI status.
   - **`nextAction === "auto_merge"`** (`readyToAutoMerge === true`):
     - Transition via `node .agents/skills/ship/scripts/state.mjs transition S6_AUTO_MERGE_AND_DEPLOY --ci-passed --codex-passed`.

---

### `S6_AUTO_MERGE_AND_DEPLOY` — Autonomous Merge & Contract-Approved Delivery
1. Merge the green, oracle-verified, Codex-verified PR: `gh pr merge <pr-number> --squash --delete-branch`.
2. Verify `main` branch `CI / CD` workflow run starts cleanly via `gh run list --branch main --limit 3`.
3. If and ONLY if the S2-approved contract specified `deployTarget === "release-tag"`:
   - Confirm version bump is merged on `main`, create and push annotated tag `v<version>`, and wait for the `Publish GitHub Release` job to publish APK/AAB/notices.
4. Transition to `S7_POST_DEPLOY_VERIFY`.

---

### `S7_POST_DEPLOY_VERIFY` — Post-Merge / Release Artifact Verification
1. Poll the `main` (or `v*` tag) `CI / CD` workflow completion via `gh run view` and confirm APK artifacts / GitHub Release assets published cleanly.
2. If the post-merge/release build fails, open a high-priority GitHub Issue with compressed LogSage triage and escalate to the human.
3. Transition to `S8_HARNESS_LEARN`.

---

### `S8_HARNESS_LEARN` — "2 Strikes -> Guard" & Final Report
1. Review `findingsHistory` via `state.mjs`. For any defect pattern occurring **2+ times**, propose adding a deterministic guard in `scripts/guards/run-all-guards.mjs` or a rule in `.agents/rules/` / `AGENTS.md`.
2. Transition to `DONE`: `node .agents/skills/ship/scripts/state.mjs transition DONE`.
3. Output a concise delivery report (PR URL, merge commit / Release URL, Clean-Room Oracle evidence, Acceptance checklist status, APK artifact link, and any 2-strikes guard proposals).
