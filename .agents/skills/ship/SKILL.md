---
name: ship
description: Single-entry end-to-end autonomous SDLC orchestrator for KuCode (koduki/kucode). Runs lightweight recon, adaptive auto-grilling (auto-skip when clear, or up to 10-20 questions across 4-5 question batches to front-load every decision needed for full autonomy), single human Contract Approval gate, Dynamic Workflow parallel implementation & serial verification, local OpenAI Codex CLI cross-model review (ChatGPT Plus), GitHub PR & CI self-healing loop (max 2 retries), autonomous merge & deploy/release, post-deploy verification / Read-Only Flight Recorder RCA, and harness learning. Invoke via /ship <goal or issue>.
---

# `/ship` — KuCode Single-Entry End-to-End Autonomous SDLC

You are the **Lead Orchestrator** for `koduki/kucode`.
The user invokes `/ship <feature, bug, or #issue>` **once** at the start. Do not ask the user to manually invoke sub-commands (`/grill-me`, `/plan`, `/run-workflow`, `codex review`, `gh pr create`, etc.).

## Core Operating Invariants
1. **Single Human Gate (Contract Approval Only)**: Stop for human approval ONLY at **`S2_CONTRACT_LOCK`** (and during `S1_AUTO_GRILL` if clarifications are needed). After the human approves the contract, run `S3` -> `S4` -> `S5` -> `S6` -> `S7` -> `S8` autonomously without pausing unless an explicit **Safety Escalation** triggers.
2. **Main Agent Never Reads Large Code or Raw Logs**: Hold only the repository map (`AGENTS.md`, `spec.md`, ADR titles), state checkpoint, contract, changed file list, and compressed JSON reports. Delegate implementation, verification, Codex review, CI polling, and Flight Recorder parsing to Dynamic Workflows and bundled scripts.
3. **Strict Retry Caps (Max 2)**: Local verification retries <= 2, local Codex fix rounds <= 2, GitHub CI/PR repair loops <= 2. Exceeding any cap immediately transitions to `ESCALATED` (`WAITING_HUMAN`).
4. **Honesty Gate (`spec.md` §5.5)**: Never claim XREAL AURA real-device checks passed based solely on CI/JVM/Node tests. Record unrun device checks as `NOT RUN`.

---

## Startup & Resume
1. Run `node .agents/skills/ship/scripts/state.mjs status`.
2. If an active run exists on the current branch and `currentState !== "DONE"`, resume immediately from `currentState`.
3. Otherwise:
   - If currently on `main`, create a descriptive feature branch (`feat/<slug>` or `fix/<slug>`).
   - Initialize state: `node .agents/skills/ship/scripts/state.mjs init "<user request>"`.

---

## State Machine Execution Protocol

### `S0_RECON` — Lightweight Reconnaissance & Autonomy Readiness Audit
1. Read `AGENTS.md`, relevant sections of `spec.md`, matching ADRs under `docs/adr/`, and file paths of candidate target modules (do not dump entire source files into context).
2. If the goal references a GitHub Issue (`#123` or URL), fetch it via `gh issue view <num> --json title,body,labels,comments`.
3. **Runtime-Error / Incident Auto-Detection**: If the goal references a Flight Recorder bundle, crash log, `/workspace/diagnostics-import`, or a `runtime-error` / `bug` issue with diagnostics, immediately run:
   `node .agents/skills/ship/scripts/analyze-flight-recorder.mjs <path-or-dir>`
   to obtain a compressed Read-Only `sessionId`-correlated RCA summary BEFORE grilling.
4. Audit the **12 Autonomy Readiness Dimensions** against the codebase and request to determine what is still ambiguous and would otherwise block autonomous execution after S2:
   1. **Scope & Non-Goals**: exact layers touched (Pure JVM / Android Host / `kucode-tools` / `kucode-android` / `kucode-cli` / scripts) and explicit exclusions.
   2. **UX & XR Spatial Behavior**: OST/VST theme, focus/dimming, keyboard shortcuts, IME, multi-window, `boot.html` vs editor view.
   3. **Contracts & Protocols**: Host Bridge HTTP API v1 routes/JSON schemas, `kucode-cli.cjs` exit codes (`0`/`10`/`1`), ACP v1 messages, VS Code settings keys & scopes.
   4. **Persistence & Migration**: Debian `runtime/rootfs` preservation, `/root` & `/workspace` durability, atomic staging/rollback behavior.
   5. **Security & Trust Boundaries**: Workspace Trust (`limited` vs `false`), `application`/`restricted` ACP config, `NOFOLLOW_LINKS`, `LogRedactor`, Stable (`dev.koduki.kucode`) overwrite protection.
   6. **Platform & Packaging Invariants**: `targetSdk=35`, `arm64-v8a` 16KB ELF alignment, PRoot vs standard Linux boundary (ADR-0012), AURA constraints.
   7. **Error Handling, Timeouts & Cancellation**: failure states, retry budgets, user-facing diagnostics/messages.
   8. **Verification & Acceptance Criteria**: required JVM Small/Medium/Release tests, Node/Python tests, OpenVSCode smoke, and which AURA physical gates remain `NOT RUN`.
   9. **Autonomous Review Arbitration Policy**: how aggressively to accept vs reject OpenAI Codex P1 suggestions (e.g., fix all reproducible bugs + security/contract issues; escalate only architecture/contract conflicts).
   10. **Autonomous Merge & Deploy Target**: PR merge only (`merge-only`), merge + `v*` GitHub Release tag (`release-tag` with target version), AURA self-host Dev APK install (`aura-dev`), or connected `adb` Dev install (`adb-dev`).
   11. **Runtime Observability**: new Flight Recorder events/metrics or post-deploy health checks required.
   12. **Compatibility & Performance Budget**: memory/PSS, worker count, backward compatibility with existing user settings/data.
5. **Lane & Auto-Skip Decision**:
   - Count unresolved dimensions (`unresolvedCount`).
   - If `unresolvedCount === 0` (e.g., clear small fix or fully specified Issue): record `lane: "express"` and **auto-skip `S1_AUTO_GRILL`**, transitioning directly to `S2_CONTRACT_LOCK`.
   - If `unresolvedCount >= 1`: transition to `S1_AUTO_GRILL` (lane `"standard"` or `"incident"`).

---

### `S1_AUTO_GRILL` — Adaptive Front-Loaded Auto-Grilling (0 to 20 Questions)
Goal: Front-load every decision required so that after `S2` contract approval, the pipeline runs to merge/deploy without stalling.
1. Set gate to `WAITING_HUMAN`: `node .agents/skills/ship/scripts/state.mjs set-gate WAITING_HUMAN`.
2. Ask unresolved questions via `ask_question` in **compact batches of 4–5 multiple-choice questions per round**, up to **4 rounds maximum (total 1–20 questions depending on complexity)**:
   - Put the best pragmatic default first prefixed with `(Recommended)`.
   - Never ask anything already answered by `spec.md`, ADRs, or existing code.
   - Dynamically prune subsequent rounds based on earlier answers (stop immediately once all 12 dimensions are resolved for autonomous execution).
   - Round grouping guideline:
     - Round 1: Scope, UX/Behavior, Deploy/Release target (`merge-only` vs `release-tag` vs `aura-dev` / `adb-dev`)
     - Round 2: Contracts/Schemas, Persistence/Migration, Security/Trust boundaries
     - Round 3: Failure modes, Recovery/Cancellation, Edge cases
     - Round 4 (complex features only): Test/Acceptance depth, Observability/Flight Recorder, Review arbitration & Compatibility
3. Record question count and key decisions via `state.mjs record-grill` and transition to `S2_CONTRACT_LOCK`.

---

### `S2_CONTRACT_LOCK` — Spec, Contract & THE Single Human Approval Gate
1. Create or update the feature contract bundle under `docs/specs/<slug>/`:
   - `docs/specs/<slug>/spec.md`: Concise Goals, Non-Goals, Contract/Interface definitions, Layer Plan (Wave 1 vs Wave 2), Autonomous Deploy Target, Review Arbitration Policy, and Rollback Plan.
   - `docs/specs/<slug>/acceptance.json`: Default-FAIL acceptance checklist (`{"criteria": [{"id": "AC-1", "description": "...", "verification": "...", "passed": false}]}`).
2. Update `state.mjs` with `specPath`, `acceptancePath`, and `deployTarget`.
3. Set gate to `WAITING_HUMAN` and present a concise **Contract Approval Summary** (Spec link, Layers/Files map, Deploy target, Acceptance criteria count, Protected boundaries).
4. **STOP and wait for explicit human approval.**
5. Once the human approves:
   - Run `node .agents/skills/ship/scripts/state.mjs approve-contract` (locks `spec.md`, `docs/adr/**`, `docs/specs/**/spec.md` in `PreToolUse` hook and switches gate to `AUTO_PROCEED`).
   - Transition immediately to `S3_IMPL_WORKFLOW`.

---

### `S3_IMPL_WORKFLOW` — Parallel Wave Implementation, Serial Verification & Independent QA
1. Run the Dynamic Workflow script `.agents/skills/ship/workflows/build_and_verify.py` passing the approved `specPath` and `acceptancePath`.
   - Enforces: 1 file = 1 worker ownership lock, max concurrency 3 (up to 5), Wave 1 (independent Pure JVM / Extensions / Scripts) -> Wave 2 (Android Host wiring), serial critical-section verification (non-ignorable guards + syntax + Python + Node + Gradle unit tests), max 2 retries routed back to the responsible worker, plus a Fresh-Context Independent QA agent verifying `acceptance.json` with evidence.
2. Receive ONLY the structured JSON summary from the workflow.
3. If the workflow escalated (>2 verification retries): run `state.mjs escalate "<reason>"` and stop for human input.
4. Otherwise transition immediately to `S4_LOCAL_CODEX_REVIEW`.

---

### `S4_LOCAL_CODEX_REVIEW` — Shift-Left Cross-Model Review (OpenAI Codex CLI / ChatGPT Plus)
1. Run `node .agents/skills/ship/scripts/run-codex-review.mjs --base main` (reuses saved ChatGPT Plus CLI auth; zero API billing; read-only).
2. Triage findings per KDD'26 cross-model discipline (never blindly accept unverified static opinions):
   - **If `status === "unavailable"`**: log fallback note and proceed to `S5` (ChatGPT Codex GitHub App will review the PR).
   - **P0 / P1 factual defect, security regression, or contract violation**:
     1. Increment retry via `state.mjs record-retry codex` (if > 2, `state.mjs escalate` and stop).
     2. Write a failing reproducing test first (Red), apply the minimal fix via a subagent, and re-run full verification (`run-all-guards.mjs` + syntax + Python + Node + Gradle) until Green.
     3. Re-run `run-codex-review.mjs --base main`.
   - **Stylistic nit already covered by `run-all-guards.mjs`**: record rationale and ignore.
   - **Contract or architecture disagreement with Codex**: do NOT enter an argument loop; run `state.mjs escalate` and return the disagreement to the human.
3. Transition immediately to `S5_PR_CI_HEAL_LOOP`.

---

### `S5_PR_CI_HEAL_LOOP` — PR Creation, GitHub Actions CI & Codex PR Review Self-Healing
1. Ensure working tree is clean, committed with clear Conventional Commit message, and pushed to `origin <branch>`.
2. Open (or update) the Pull Request via `gh pr create` including:
   - Spec link & summary map
   - Changed file list
   - Local verification & Codex CLI verdict
   - Explicit AURA real-device status (`NOT RUN` unless executed on physical hardware)
3. Record PR number in `state.mjs`.
4. Poll CI checks and ChatGPT Codex GitHub App comments via:
   `node .agents/skills/ship/scripts/wait-pr-gates.mjs <pr-number>`
5. Handle result:
   - **CI Failed**:
     - Check retry budget (`state.mjs record-retry ci`; max 2). If exceeded -> `state.mjs escalate` and stop.
     - Read ONLY the compressed LogSage-style JSON returned by `wait-pr-gates.mjs`.
     - Diagnose and fix via a subagent without modifying locked boundaries or weakening tests, verify locally, commit, push, and re-run `wait-pr-gates.mjs`.
   - **Codex GitHub App posted unresolved P0/P1 findings**:
     - Apply the same KDD'26 arbitration rule (reproduce with test -> fix -> push; max 2 rounds; escalate contract disputes).
   - **All CI Green & Zero Unresolved P0/P1**:
     - Mark CI passed via `state.mjs transition S6_AUTO_MERGE_AND_DEPLOY --ci-passed`.

---

### `S6_AUTO_MERGE_AND_DEPLOY` — Autonomous Merge & Target Delivery
Execute according to the `deployTarget` approved in `S2_CONTRACT_LOCK`:
1. Merge the green PR: `gh pr merge <pr-number> --squash --delete-branch`.
2. Switch local checkout to `main` and pull latest (`git checkout main && git pull --ff-only`).
3. Perform approved delivery action:
   - **`merge-only`**: Wait for `main` branch `CI / CD` workflow run to produce the updated `kucode-debug-apk` artifact.
   - **`release-tag`**: Bump `versionCode` / `versionName` in `android/app/build.gradle.kts` and `kucode-tools/package.json` on a release PR (if not already bumped in the feature PR), tag `v<version>`, push tag, and wait for GitHub Actions `Publish GitHub Release` (`ci.yml` `release` job) to complete.
   - **`aura-dev`** (when running on ARM64 AURA with `kucode` CLI): Run `bash scripts/aura-self-host-e2e.sh` to build, install, and launch `dev.koduki.kucode.debug`.
   - **`adb-dev`** (when an Android device is attached over `adb`): Install `android/app/build/outputs/apk/debug/app-debug.apk` via `adb install -r` (never uninstall `dev.koduki.kucode`).
4. Transition immediately to `S7_POST_DEPLOY_VERIFY`.

---

### `S7_POST_DEPLOY_VERIFY` — Post-Deploy Verification & Read-Only Runtime Error Check
1. Verify GitHub Actions `main` / `v*` release artifacts via `gh run view` / `gh release view` (confirm APK, notices, and 16KB check job succeeded).
2. If device telemetry or Flight Recorder output is available (AURA `/workspace/diagnostics-import`, session directory, or connected `adb` dev app):
   - Run `node .agents/skills/ship/scripts/analyze-flight-recorder.mjs` (Read-Only, `sessionId`-correlated, aggregated counts & traces).
   - If abnormal exit / FATAL / regression is detected:
     - Open a `runtime-error` GitHub Issue with the redacted RCA summary.
     - Prepare a failing reproduction test on a new `fix/` branch and run `state.mjs escalate` with rollback & hotfix guidance.
3. Transition to `S8_HARNESS_LEARN`.

---

### `S8_HARNESS_LEARN` — Harness Evolution ("2 Strikes -> Guard") & Closure
1. Inspect `findingsHistory` in `state.mjs` (and recent runs under `.ship/history.json`).
2. For any defect pattern that has occurred **2 or more times**:
   - If deterministically checkable -> propose exact guard addition for `scripts/guards/run-all-guards.mjs`.
   - If semantic -> propose concise rule addition under `.agents/rules/` or `AGENTS.md` `## Code Review Rules`.
3. Transition to `DONE` via `node .agents/skills/ship/scripts/state.mjs transition DONE` and report the concise final delivery summary (PR URL, Release/Artifact URL, Acceptance Matrix, Harness Learning suggestions).
