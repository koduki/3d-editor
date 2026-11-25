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

Historical app releases do not expose the five standalone assets. The initial independently produced runtime-only release is referenced by the reviewed source-controlled lock; see the bootstrap section below. Actual AURA build is NOT RUN. No new SDK version is claimed compatible.

## Bootstrap and committed lock

The main CI can bootstrap an independent runtime-only prerelease when the reviewed
lock does not yet exist. It publishes a version tied to the producer commit without
overwriting any existing release assets (not a new application release). The small
`kucode-runtime-lock` workflow artifact contains the candidate lock. Review the
producer commit, pinned script hashes, NDK selection, Debian manifest and OpenVSCode
hash before committing the lock to source. On later builds with a committed lock,
CI uses the same remote fetch path as AURA and skips Docker/NDK generation.
Gradle performs an additional mandatory verification before APK assets/native libs
are merged. A generated but unreviewed lock is not itself a trust anchor.

When using an explicitly reviewed alternate lock, point Gradle to the same lock with
`KUCODE_RUNTIME_LOCK=/absolute/path/to/runtime-assets.lock.json`. Do not mix lock sets.
