# .agents/skills/ship/workflows/build_and_verify.py
# Antigravity Dynamic Workflow invoked by /ship during S3_IMPL_WORKFLOW.
# Implements:
# - Disjoint file ownership lock (1 file = 1 worker)
# - Bounded parallel Wave 1 (concurrency 3 -> 5) + sequential Wave 2
# - Serial critical-section verification (max 2 retries back to responsible worker)
# - Fresh-context Independent QA verifying Default-FAIL acceptance criteria
# - Clean main context: returns ONLY map, changed files, and structured results

import asyncio

MAX_CONCURRENCY = 3
MAX_RETRIES = 2

spec_path = args.get("spec_path", "spec.md")
acceptance_path = args.get("acceptance_path", "")

phase("1. Plan KuCode Layers & File Ownership")
plan = await agent(
    f"Read ONLY `{spec_path}`, `AGENTS.md`, and relevant paths (do not dump full files). "
    "Decompose the approved contract into KuCode layers:\n"
    "- Wave 1 (parallel, strictly disjoint owned_files): Pure-JVM Kotlin core (`android/app/src/main/java/dev/koduki/kucode/`), "
    "VS Code extensions (`kucode-tools/` or `kucode-android/`), CLI (`kucode-cli.cjs`), or scripts (`scripts/`).\n"
    "- Wave 2 (sequential after Wave 1): Android Host wiring (`MainActivity.kt`, `BootHostBridge.kt`, `boot.html`) or cross-layer glue.\n"
    "Every task MUST specify exact `owned_files` with zero overlap in Wave 1 and never include locked harness/CI/contract files.",
    schema={
        "type": "object",
        "properties": {
            "wave1": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "layer": {"type": "string"},
                        "owned_files": {"type": "array", "items": {"type": "string"}},
                        "instructions": {"type": "string"},
                    },
                    "required": ["id", "layer", "owned_files", "instructions"],
                },
            },
            "wave2": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "layer": {"type": "string"},
                        "owned_files": {"type": "array", "items": {"type": "string"}},
                        "instructions": {"type": "string"},
                    },
                    "required": ["id", "layer", "owned_files", "instructions"],
                },
            },
        },
        "required": ["wave1", "wave2"],
    },
    role="KuCode Layer Planner",
)

# Enforce distributed-system exclusive lock: 1 file -> at most 1 Wave 1 worker
ownership_map = {}
for task in plan["wave1"]:
    for f in task["owned_files"]:
        if f in ownership_map:
            raise RuntimeError(f"File ownership conflict in Wave 1: {f} assigned to both {ownership_map[f]} and {task['id']}")
        ownership_map[f] = task["id"]

phase("2. Wave 1 Parallel Implementation (Bounded Concurrency)")
sem = asyncio.Semaphore(MAX_CONCURRENCY)
workers = {}

async def run_wave1_worker(task):
    async with sem:
        worker = agent(
            f"Implement task `{task['id']}` ({task['layer']}) strictly following `{spec_path}` and `.agents/rules/`.\n"
            f"EXCLUSIVE OWNERSHIP LOCK: Edit ONLY these files: {task['owned_files']}.\n"
            "Do NOT edit locked contracts, CI workflows, guards, or unrelated tests.\n"
            f"Instructions:\n{task['instructions']}",
            schema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "changed_files": {"type": "array", "items": {"type": "string"}},
                    "summary": {"type": "string"},
                },
                "required": ["task_id", "changed_files", "summary"],
            },
            role=f"Wave1: {task['id']}",
        )
        workers[task["id"]] = worker
        return await worker

wave1_results = await parallel(plan["wave1"], run_wave1_worker) if plan["wave1"] else []

phase("3. Wave 2 Sequential Wiring")
async def run_wave2_worker(task):
    worker = agent(
        f"Implement Wave 2 task `{task['id']}` ({task['layer']}) integrating Wave 1 outputs per `{spec_path}`.\n"
        f"EXCLUSIVE OWNERSHIP LOCK: Edit ONLY these files: {task['owned_files']}.\n"
        f"Wave 1 summaries: {[{'id': r['task_id'], 'files': r['changed_files'], 'summary': r['summary']} for r in wave1_results]}\n"
        f"Instructions:\n{task['instructions']}",
        schema={
            "type": "object",
            "properties": {
                "task_id": {"type": "string"},
                "changed_files": {"type": "array", "items": {"type": "string"}},
                "summary": {"type": "string"},
            },
            "required": ["task_id", "changed_files", "summary"],
        },
        role=f"Wave2: {task['id']}",
    )
    workers[task["id"]] = worker
    for f in task["owned_files"]:
        ownership_map[f] = task["id"]
    return await worker

wave2_results = await pipeline(plan["wave2"], run_wave2_worker) if plan["wave2"] else []

all_changed_files = sorted({
    f
    for r in (wave1_results + wave2_results)
    for f in r["changed_files"]
})

phase("4. Serial Critical-Section Verification (Max 2 Retries)")
verification_verdict = None
for attempt in range(MAX_RETRIES + 1):
    verification_verdict = await agent(
        "Run KuCode's full local verification suite ONCE in serial (do NOT edit any code):\n"
        "1. `node scripts/guards/run-all-guards.mjs`\n"
        "2. `bash -n scripts/*.sh`\n"
        "3. `node --check android/app/src/main/assets/kucode-cli.cjs android/app/src/main/assets/runtime-probes.cjs android/app/src/main/assets/kucode-android/extension.js android/app/src/main/assets/kucode-tools/extension.js android/app/src/main/assets/kucode-tools/acp-client.js android/app/src/main/assets/kucode-tools/acp-webview.js`\n"
        "4. `python3 -m unittest discover -s tests -p test_runtime_assets.py -v`\n"
        "5. `node --test tests/*.test.cjs`\n"
        "6. If Android SDK is available locally and Kotlin/Gradle files changed, run `cd android && ./gradlew :app:testDebugUnitTest :app:testDebugMediumUnitTest :app:testReleaseUnitTest` (otherwise record gradle_skipped_reason).\n"
        f"Known file ownership map: {ownership_map}\n"
        "Return strict JSON with pass/fail, concise failed_items, and the single responsible_task_id if failed.",
        schema={
            "type": "object",
            "properties": {
                "passed": {"type": "boolean"},
                "failed_items": {"type": "array", "items": {"type": "string"}},
                "responsible_task_id": {"type": "string"},
                "gradle_status": {"type": "string"},
            },
            "required": ["passed", "failed_items", "gradle_status"],
        },
        role=f"Serial Verifier #{attempt + 1}",
        model="flash",
    )
    if verification_verdict["passed"]:
        log(f"Serial verification PASSED on attempt {attempt + 1}.")
        break
    if attempt == MAX_RETRIES:
        raise RuntimeError(f"ESCALATE: Verification failed after {MAX_RETRIES} retries: {verification_verdict['failed_items']}")

    target_id = verification_verdict.get("responsible_task_id", "")
    target_worker = workers.get(target_id)
    if target_worker is None:
        raise RuntimeError(f"ESCALATE: Could not route failure to a single worker ({target_id}): {verification_verdict['failed_items']}")

    log(f"Routing verification failure back to {target_id} (retry {attempt + 1}/{MAX_RETRIES}).")
    await target_worker(
        f"Serial verification failed with items:\n{verification_verdict['failed_items']}\n"
        "Fix ONLY within your owned files. Never skip tests or add suppression comments.",
        schema={"type": "string"},
    )

phase("5. Layer Review & Fresh-Context Independent QA")
layer_review = await agent(
    f"Audit changed files {all_changed_files} against `{spec_path}` and `AGENTS.md` (read-only). "
    "Check module boundaries (`kucode-android` vs `kucode-tools`), Workspace Trust scopes, `NOFOLLOW_LINKS`, and `LogRedactor`. "
    "Return concise contract/boundary issues only.",
    schema={
        "type": "object",
        "properties": {
            "blocking_issues": {"type": "array", "items": {"type": "string"}},
            "notes": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["blocking_issues", "notes"],
    },
    role="Layer Reviewer (Read-Only)",
    type_name="research",
    model="flash",
)

qa_report = await agent(
    f"You are an independent Fresh-Context QA engineer who did NOT write the implementation.\n"
    f"Inspect `{spec_path}`, `{acceptance_path}`, and changed files {all_changed_files}.\n"
    "Add missing edge-case unit tests if needed (never weaken existing tests), re-run tests, and verify each acceptance criterion "
    "with real command output evidence before marking it passed. Keep AURA physical-device gates as NOT RUN.",
    schema={
        "type": "object",
        "properties": {
            "all_automated_criteria_passed": {"type": "boolean"},
            "added_test_files": {"type": "array", "items": {"type": "string"}},
            "unmet_criteria": {"type": "array", "items": {"type": "string"}},
            "physical_device_status": {"type": "string"},
        },
        "required": ["all_automated_criteria_passed", "added_test_files", "unmet_criteria", "physical_device_status"],
    },
    role="Independent QA",
)

# Return ONLY structured map/results to the main /ship orchestrator
log("BUILD_AND_VERIFY_RESULT", {
    "spec_path": spec_path,
    "changed_files": sorted(set(all_changed_files + qa_report["added_test_files"])),
    "verification": verification_verdict,
    "layer_review": layer_review,
    "qa": qa_report,
})
