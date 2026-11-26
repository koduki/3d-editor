---
trigger: glob
globs: "android/app/src/main/assets/**/*.js, android/app/src/main/assets/**/*.cjs, tests/*.test.cjs"
description: "Conventions, Workspace Trust, module boundary, and CLI contract rules for KuCode VS Code extensions and Node scripts."
---

# KuCode VS Code Extensions & CLI Rules

1. **Module Separation (ADR-0012 §5)**:
   - `kucode-tools/` (generic XR UX, ACP chat, clipboard, diagnostics) and `kucode-android/` (Android-only project integration) are strictly separate extensions.
   - Never import files across `kucode-tools/` and `kucode-android/`. Communicate only through versioned Host Bridge HTTP API v1 or `kucode-cli.cjs`.
2. **Workspace Trust & Config Scope**:
   - `kucode-tools/package.json` must keep `capabilities.untrustedWorkspaces.supported = "limited"`.
   - `kucode-android/package.json` must keep `capabilities.untrustedWorkspaces.supported = false`.
   - `kucode.acp.{defaultAgent,permissionMode,agents}` must keep `"scope": "application"` and `"restricted": true`, and runtime code must ignore workspace-level overrides via `cfg.inspect()`.
3. **CLI Contract (`kucode-cli.cjs`)**:
   - Output machine-readable JSON on stdout with `version: 1`.
   - Exit codes: `0` success/completed/cancelled, `10` pending (`operation-status`), `1` failed/interrupted/timed_out.
   - `kucode build -- <cmd>` must strip `KUCODE_ADMIN_TOKEN` and `KUCODE_ADMIN_PORT` from child env and never log command arguments or secrets to Flight Recorder events.
4. **PATH & Runtime Parity**:
   - Keep user/SDK toolchains (`mise/shims`, `.cargo/bin`, `$ANDROID_HOME/...`, `~/.local/bin`) ahead of `/usr/local/bin` and `/usr/bin` in `acp-client.js` so ACP matches terminals and VS Code Tasks.
5. **Zero External Dependencies**:
   - Keep assets and Node tests runnable on OpenVSCode's bundled Node (`node --test`) with zero npm install step.
