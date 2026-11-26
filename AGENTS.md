# AGENTS.md — KuCode Repository Map & Invariants

> Single entry map shared by **Google Antigravity (`agy`)** and **OpenAI Codex**. Keep this file concise (~90 lines); detailed domain guides live under `docs/` and `.agents/rules/`.

## 1. Repository Map (Progressive Disclosure)
- Product overview & architecture: `README.md`
- Normative spec (Section 5 supersedes v0.9.1 baseline): `spec.md`
- Architecture Decision Records (ADR-0001..0012): `docs/adr/README.md`
- AURA real-device acceptance & CLI contracts: `docs/aura-self-host-acceptance.md`
- Runtime asset supply-chain lock (pinned SHA-256): `runtime-assets.lock.json`
- End-to-end autonomous workflow (`/ship`): `.agents/skills/ship/SKILL.md`
- Non-ignorable deterministic guards: `scripts/guards/run-all-guards.mjs`

## 2. Hard Boundaries (AI Must Never Modify Without Explicit Human Override)
- CI/CD & harness controls: `.github/workflows/**`, `scripts/guards/**`, `.agents/hooks.json`
- Supply-chain & legal compliance: `runtime-assets.lock.json`, `scripts/build-proot-android.sh`, `scripts/fetch-runtime-assets.sh`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `LICENSES/**`, `android/gradle/wrapper/gradle-wrapper.properties`
- Security & build contracts: `android/app/proguard-rules.pro`, `android/app/src/main/res/xml/network_security_config.xml`
- Approved feature contracts (locked automatically after `/ship` S2 approval): `spec.md`, `docs/adr/**`, `docs/specs/**/spec.md`
- Never delete, `.skip`, `@Ignore`, or weaken existing tests, and never add suppression comments (`eslint-disable`, `@ts-ignore`, `# noqa`, new `@Suppress`) to pass checks.

## 3. Fast Local Verification Commands
1. Non-ignorable guards: `node scripts/guards/run-all-guards.mjs`
2. Syntax checks: `bash -n scripts/*.sh && node --check android/app/src/main/assets/kucode-cli.cjs android/app/src/main/assets/runtime-probes.cjs android/app/src/main/assets/kucode-android/extension.js android/app/src/main/assets/kucode-tools/extension.js android/app/src/main/assets/kucode-tools/acp-client.js android/app/src/main/assets/kucode-tools/acp-webview.js`
3. Python & Node tests: `python3 -m unittest discover -s tests -p test_runtime_assets.py -v && node --test tests/*.test.cjs`
4. Android JVM tests (when Android SDK is present): `cd android && ./gradlew :app:testDebugUnitTest :app:testDebugMediumUnitTest :app:testReleaseUnitTest`

## 4. Truthfulness Invariant
- CI and host JVM/Node tests do **NOT** prove XREAL AURA real-device compatibility (`spec.md` §5.5). Always record unexecuted physical-device checks as `NOT RUN`.

## Code Review Rules

### Security, Trust & Boundaries
- Flag any workspace-scoped override of `kucode.acp.{defaultAgent,permissionMode,agents}`; these must remain `scope: "application"` and `restricted: true` and be read from application/global inspection in `acp-webview.js`.
- Flag any reintroduction of `--disable-workspace-trust`, weakening of `NOFOLLOW_LINKS` path confinement, or bypass of `LogRedactor` before writing diagnostics or AI bundles.
- Flag any change allowing `AndroidHostBridge` to install over Stable (`dev.koduki.kucode`) or replace the running host package.
- Flag direct cross-module imports between `android/app/src/main/assets/kucode-android/` and `android/app/src/main/assets/kucode-tools/`; they must interact only via versioned Host Bridge/CLI contracts.

### Android XR, Runtime & Persistence
- Flag changes that break `targetSdk = 35`, `arm64-v8a` 16KB ELF alignment, `useLegacyPackaging = true`, or uncompressed `.tgz` packaging (`noCompress`).
- Flag changes that wipe or overwrite persistent user data (`runtime/rootfs`, `/root`, `/workspace`, SDK caches) during normal APK updates.
- Flag PATH ordering bugs where `/usr/local/bin` or `/usr/bin` precedes user/SDK toolchains (`mise/shims`, `.cargo/bin`, `$ANDROID_HOME/...`) across terminals, Tasks, and ACP.
- Flag non-deterministic or unredacted logging of secrets/command arguments in `kucode-cli.cjs` or `SessionLogStore`.

### Scope & Signal Hygiene
- Safe path: do not report stylistic nits or checks already enforced deterministically by `node scripts/guards/run-all-guards.mjs`.
- Focus strictly on P0/P1 defects: contract violations, security/privacy regressions, crash/deadlock/race bugs, and data-loss risks.
