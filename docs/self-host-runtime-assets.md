# Self-host build: runtime assets (SH-03)

The runtime producer uses the existing NDK/Docker scripts. The APK consumer does not need either. On Linux ARM64, an independently verified Android SDK/JDK/Gradle combination is still required (#18).

## Producer (CI or controlled build machine)

Run the existing asset scripts, then create a lock for the exact release tag:

```sh
./scripts/build-proot-android.sh
./scripts/fetch-runtime-assets.sh
python3 scripts/runtime-assets.py lock --release vX.Y.Z --output runtime-assets.lock.json
```

Publish the five files named in the lock as individual GitHub Release assets along with the lock. Do not overwrite a published asset; use a new release tag if contents change. Review the producer provenance (source pins, NDK, Debian digest and OpenVSCode version) and commit the reviewed lock to the consuming source revision before using it. A lock obtained from the same mutable download location is not independently trusted.

## Consumer (AURA or CI)

```sh
python3 scripts/runtime-assets.py fetch --lock runtime-assets.lock.json
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

All five files are verified by size and SHA-256 before staging; ELF AArch64 and 16KB LOAD alignment and the Debian manifest/rootfs association are checked. Content-addressed cache is under XDG_CACHE_HOME or ~/.cache/kucode/runtime. Valid cached assets allow repeat builds without network access for the runtime assets only. Gradle/SDK dependencies have their own caches. No x86 emulation.

`--source-dir` can use a trusted local producer output with the exact same checks. `verify` checks an existing checkout. Never treat a lock generated from untrusted binaries as an independent integrity check.

Current status: release asset publication and a reviewed concrete lock still need a successful producer release; current historical APK releases do not expose the five standalone assets. Actual AURA build is NOT RUN. No new SDK version is claimed compatible.
