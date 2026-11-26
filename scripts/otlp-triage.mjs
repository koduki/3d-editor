#!/usr/bin/env node
/**
 * Generic Read-Only OTLP Flight Recorder triage CLI.
 *
 * Delegates local file/directory and HTTP(S) OTLP JSON analysis to
 * `.agents/skills/ship/scripts/analyze-flight-recorder.mjs`.
 *
 * Usage:
 *   scripts/otlp-triage.mjs <file|dir> [--session-id <id>] [--build-sha <sha>] [--abnormal-only]
 *   scripts/otlp-triage.mjs --url <http(s)://...> [--header-env <ENV_NAME>] [--session-id <id>] [--build-sha <sha>] [--abnormal-only]
 */
import { runCli } from "../.agents/skills/ship/scripts/analyze-flight-recorder.mjs";

runCli(process.argv.slice(2)).catch((err) => {
  console.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
