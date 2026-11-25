# AURA Android ARM64 probe (SH-01)

Status: NOT RUN on AURA. This is a minimal SDK compatibility experiment, not a KuCode production toolchain upgrade.

Candidate matrix (2026-11-25):
- AGP 9.5.1, Gradle 9.6.0, compile SDK 37, Build Tools 36.0.0 (AGP 9.5 compatibility table: https://developer.android.com/build/releases/agp-9-5-0-release-notes).
- JDK 25 LTS candidate; record actual distribution and version (AGP minimum 17).
- Google Android CLI Linux ARM64 1.1.16919734 (https://developer.android.com/tools/agents/android-cli/release-notes, https://developer.android.com/tools/agents/android-cli/download).
- Native ARM64 aapt2 and zipalign; Android CLI availability does not establish availability of its SDK payloads. Use independently verified upstream artifacts, and document source/version/hash for any fallback. Do not use x86 emulation.

Install SDK/JDK/Gradle using reviewed official distributions (or a reviewed ARM64 fallback), set ANDROID_HOME and optionally KUCODE_AAPT2 and KUCODE_GRADLE, then run inside AURA's Linux environment:

```bash
bash scripts/probe-android-arm64.sh
```

The probe checks host ABI and actual ELF headers, compiles a resource-bearing Java app with AGP 9.5.1, runs a JVM test, produces a signed debug APK, verifies signature/alignment and records checksums and logs. The fixture is separate from production KuCode; successful results do not prove Kotlin/PRoot/native runtime packaging compatibility. Record AURA model, Android build, runtime version, SDK download provenance and exact tool hashes with the report. Redact credentials before sharing logs.

Next gates: Kotlin compilation, Gradle daemon/child processes/file watching, actual KuCode build using #20 assets, then #27 E2E. Do not mark #18 done before real AURA evidence. No ADB required.
