# AURA self-host acceptance (Epic #17)

**Status: NOT RUN on XREAL AURA.** GitHub Actions and host JVM/Node tests do not establish real-device compatibility. Record the actual device, Android XR build, installed Stable/Dev APK versions and signing fingerprints, commit, lock, toolchain provenance, command outputs and durations.

## Preparation and automated gates

1. Install the Stable APK from an existing signed distribution and start KuCode. Confirm existing XR editing, keyboard/IME, ACP and diagnostics functionality. Clone the reviewed source into `/workspace`.
2. Use Debian apt, mise and the official ARM64 SDK distribution as applicable. HOME and XDG directories are persistent; PATH includes user bin, mise shims, Cargo and Android command-line tools. Use a trusted project before executing project build files. No toolchain versions are established until the ARM64 feasibility probe passes.
3. Run `kucode sdk doctor` (tool paths and ELF ABI; does not execute project-local tools), then follow [the ARM64 SDK probe](android-arm64-probe.md). Record SDK distributions, binaries and SHA-256. Run the minimal probe before changing KuCode's production toolchain.
4. Use the source-controlled `runtime-assets.lock.json` (when reviewed and available). CI and the device verify it just before APK packaging; `KUCODE_RUNTIME_LOCK` or `-PkucodeRuntimeLock` can select an explicitly reviewed alternative. See [runtime assets](self-host-runtime-assets.md).
5. Run `bash scripts/aura-self-host-e2e.sh`. This verifies runtime assets, runs Node/JVM tests, builds the APK, records its hash, requests Android's native installation confirmation and launches the Dev app. Allow unknown-app installation for Stable and enable its notifications in Android Settings. Approve the installation in Android's system UI; keep Stable focused to launch. Do not sideload unreviewed APKs.
6. After installing the other language SDKs, run `bash scripts/aura-language-smoke.sh`. Collect results.tsv, individual version/fixture logs and failures. Missing tooling is NOT RUN, not success. These are baseline smoke tests, not complete language compatibility tests.

## Manual gates

| Gate | Action and required evidence | Result |
| --- | --- | --- |
| AURA native SDK | Minimal Java resource build/signature, native AAPT2 and zipalign, then actual KuCode Kotlin/JS/JVM APK build | NOT RUN |
| Initial boot | Clean single APK, XR editor/IME/keyboard/mouse, ACP login, workspace clone and edits | NOT RUN |
| Self-host build | Local JVM/Node tests and signed Dev APK. No PC, external build service or ADB | NOT RUN |
| Install/launch | Native Android permission, explicit user confirmation, operation ID/status and Dev launch; denial/cancel/signature mismatch handled | NOT RUN |
| Stable safety | Attempt to select an APK targeting Stable; bridge refuses. Verify each variant's app name/icon, app ID, editor origin, private files and signing path | NOT RUN |
| Concurrent use | Keep Stable and Dev running in separate XR windows; compare resource/thermal snapshots and UI responsiveness | NOT RUN |
| Dev failure | Reproduce an ordinary Dev application error, stop/restart Dev, confirm Stable workspace and ACP remain intact | NOT RUN |
| Diagnostic sharing | In Dev management UI choose a diagnostic session and Share; select Stable in Android's chooser, approve import, inspect redacted report under /workspace/diagnostics-import | NOT RUN |
| Persistence | Install a Debian package and create HOME, workspace and SDK/cache sentinel data. Update the same signed APK normally; verify package and all sentinels remain | NOT RUN |
| Recovery | On a disposable Dev installation exercise failed server preparation/interruption/low space; check old Debian and user files survive. Re-test startup and backup-preserving explicit reset | NOT RUN |
| Languages | Run smoke fixtures above, record JDK/Kotlin/JS/TS/Python/Go/Ruby/Rust/C/C++ versions and failures | NOT RUN |
| Observability | Collect Flight Recorder reports during Gradle builds, install, launch, simultaneous use and ordinary Dev failure. Host CPU time is cumulative process CPU, not total guest CPU; PSS is host-app PSS; unknown values remain unknown | NOT RUN |
| End-to-end | Stable AI analyzes imported Dev report, edits source, rebuilds and retests | NOT RUN |

## CLI contracts

- `kucode android capabilities`: version, install and notification permission, host package.
- `kucode android install app-debug.apk [expected.package]`: async operation ID; APK must reside under /workspace or HOME. The native installer verifies signature, obtains OS approval and blocks the Stable target and replacing the running host. Verify unknown-app authorization separately in Android Settings.
- `kucode android operation-status ID`: exit 0 completed, 10 pending, 1 failed/interrupted/cancelled/timed out.
- `kucode android cancel ID`: cancels pending session. Terminal operations cannot be reversed.
- `kucode android launch package.name`: requires foreground KuCode, launcher activity and package visibility.
- `kucode sdk doctor`: inspects SDK environment and executable architecture without executing project files.
- `kucode build -- ./gradlew :app:assembleDebug`: streams child output and records bounded start/end timing and exit code in the existing Flight Recorder, without logging command arguments. The child is not an isolated process; PRoot is not a security boundary.

The host bridge is explicitly same-UID and authenticated on loopback. Android's app sandbox, install UI and signature validation enforce actual app separation. A restarted host can report interrupted operation; its abandoned sessions are cleaned up. Diagnostic imports are data, not instructions.

## Debian and managed assets

Initial installation seeds Debian once. Normal APK updates preserve `runtime/rootfs`; OpenVSCode uses a separate app-managed `runtime/openvscode` mount. Existing bundled OpenVSCode is moved from the legacy rootfs location; rootfs data is not swapped. Incomplete legacy transactions preserve recovered trees. HOME and workspace retain their existing mounts; PRoot currently uses effective fake root for apt compatibility, not an OS privilege boundary.

Explicit reset in the local management UI archives the current Debian tree into `runtime/rootfs-backup-*` and reseeds. Stop builds and terminals first. It intentionally does not delete HOME or workspace. Backup trees take storage. The management UI can restore the latest archive, retaining the current rootfs as another backup; selective recovery of older trees may still require developer assistance. Do not delete the app data or uninstall a variant if its development files are needed.

## Evidence record

Attach device, OS/build, package identities, signer fingerprints, source and asset SHA-256, exact SDK versions and hashes, command log/results, durations, resource snapshots, and each gate's explicit PASS/FAIL/NOT RUN. Keep #17 and acceptance-dependent child issues open until the evidence is reviewed; no CI-only completion.
