#!/usr/bin/env bash
set -euo pipefail

# Cross-compile Termux's PRoot + Samba talloc (static) for Android arm64-v8a
# using Android NDK, producing:
#   android/app/src/main/jniLibs/arm64-v8a/libproot.so
#   android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so
#
# Both binaries are loaded from Android's nativeLibraryDir so PRoot executes
# guest ELF binaries under targetSdk=35 (W^X) without execve() in app data.

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a}"

PROOT_VERSION="${PROOT_VERSION:-5.1.107.102}"
PROOT_SHA256="${PROOT_SHA256:-36fb68838d86e491bc577064e23d365ea936e03ded4e90f62dc540907c04f385}"
PROOT_URL="https://github.com/termux/proot/archive/refs/tags/v${PROOT_VERSION}.tar.gz"

TALLOC_VERSION="${TALLOC_VERSION:-2.5.0}"
TALLOC_SHA256="${TALLOC_SHA256:-912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007}"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"

ANDROID_API="${ANDROID_API:-34}"

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  else
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  fi
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: SHA-256 mismatch for $file" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $actual" >&2
    exit 1
  fi
  echo "[proot] Verified SHA-256 ($(basename "$file")): $actual"
}

if [[ -f "$OUT_DIR/libproot.so" && -f "$OUT_DIR/libproot-loader.so" && "${FORCE_REBUILD:-0}" != "1" ]]; then
  echo "[proot] Already built in $OUT_DIR (set FORCE_REBUILD=1 to rebuild)"
  ls -lh "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"
  exit 0
fi

# Locate Android NDK
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
  NDK_DIR="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
  echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME or install ndk under \$ANDROID_HOME/ndk." >&2
  exit 1
fi

HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_TAG="${HOST_OS}-x86_64"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "ERROR: NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
READELF="$TOOLCHAIN/bin/llvm-readelf"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kucode-proot-build-XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[proot] Using NDK: $NDK_DIR"
echo "[proot] Building in: $WORK_DIR"

# 1. Fetch, verify SHA-256, and cross-build Samba talloc as static libtalloc.a
echo "[proot] Downloading talloc ${TALLOC_VERSION}..."
curl -fSL "$TALLOC_URL" -o "$WORK_DIR/talloc.tar.gz"
verify_sha256 "$WORK_DIR/talloc.tar.gz" "$TALLOC_SHA256"
tar -xzf "$WORK_DIR/talloc.tar.gz" -C "$WORK_DIR"
TALLOC_SRC="$WORK_DIR/talloc-${TALLOC_VERSION}"
TALLOC_PREFIX="$WORK_DIR/talloc-install"

cat > "$TALLOC_SRC/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "aarch64"
Checking uname release type: "6.1.0"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF

echo "[proot] Configuring & building static libtalloc.a..."
(
  cd "$TALLOC_SRC"
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
  CFLAGS="-O2 -fPIC" \
  LDFLAGS="-Wl,-z,max-page-size=16384" \
  PYTHONHASHSEED=1 \
  ./configure \
    --prefix="$TALLOC_PREFIX" \
    --disable-rpath \
    --disable-python \
    --cross-compile \
    --cross-answers=cross-answers.txt
  make -j"$(nproc)"
  mkdir -p "$TALLOC_PREFIX/include" "$TALLOC_PREFIX/lib"
  cp talloc.h "$TALLOC_PREFIX/include/"
  "$AR" rcu "$TALLOC_PREFIX/lib/libtalloc.a" bin/default/talloc*.o
  "$RANLIB" "$TALLOC_PREFIX/lib/libtalloc.a"
)

# 2. Fetch, verify SHA-256, and cross-build Termux PRoot with unbundled loader
echo "[proot] Downloading termux/proot v${PROOT_VERSION}..."
curl -fSL "$PROOT_URL" -o "$WORK_DIR/proot.tar.gz"
verify_sha256 "$WORK_DIR/proot.tar.gz" "$PROOT_SHA256"
tar -xzf "$WORK_DIR/proot.tar.gz" -C "$WORK_DIR"
PROOT_SRC="$WORK_DIR/proot-${PROOT_VERSION}"

# NDK r27+ Clang treats implicit strcmp/memset declarations as errors
sed -i '1i #include <string.h>' "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c"
# Ensure unbundled loader ELF segments are 16KB page-aligned for Android 15+ devices
sed -i 's/-z,noexecstack/-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384/g' "$PROOT_SRC/src/GNUmakefile"

echo "[proot] Compiling proot + unbundled loader for Android arm64-v8a..."
(
  cd "$PROOT_SRC/src"
  # Ensure readelf is available on PATH (GNUmakefile uses `readelf` if loader-info is needed)
  export PATH="$TOOLCHAIN/bin:$PATH"
  make -j"$(nproc)" \
    V=1 \
    CC="$CC" \
    LD="$CC" \
    STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    PROOT_UNBUNDLE_LOADER="." \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"${PROOT_VERSION}\\\" -I. -I${TALLOC_PREFIX}/include" \
    CFLAGS="-Wall -Wextra -O2 -fPIE -DPROOT_UNBUNDLE_LOADER=\\\".\\\"" \
    LDFLAGS="-L${TALLOC_PREFIX}/lib -Wl,-Bstatic -ltalloc -Wl,-Bdynamic -Wl,-z,noexecstack -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    proot loader/loader
)

mkdir -p "$OUT_DIR"
cp "$PROOT_SRC/src/proot" "$OUT_DIR/libproot.so"
cp "$PROOT_SRC/src/loader/loader" "$OUT_DIR/libproot-loader.so"
"$STRIP" "$OUT_DIR/libproot.so"
"$STRIP" "$OUT_DIR/libproot-loader.so"
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"

echo "[proot] Verifying built binaries & 16KB ELF LOAD alignment..."
file "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"
"$READELF" -d "$OUT_DIR/libproot.so" | grep NEEDED || true
for bin in "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"; do
  "$READELF" -lW "$bin" | python3 -c '
import sys
bin_name = sys.argv[1]
loads = 0
for line in sys.stdin:
    parts = line.split()
    if parts and parts[0] == "LOAD":
        loads += 1
        offset = int(parts[1], 16)
        vaddr = int(parts[2], 16)
        align = int(parts[-1], 16)
        if align < 0x4000 or (offset - vaddr) % 0x4000 != 0:
            raise SystemExit(f"ERROR: {bin_name} LOAD segment not 16KB aligned: offset={hex(offset)} vaddr={hex(vaddr)} align={hex(align)}")
if loads == 0:
    raise SystemExit(f"ERROR: {bin_name} has no LOAD segments")
print(f"[proot] Verified 16KB LOAD alignment ({loads} segments): {bin_name}")
' "$bin"
done
ls -lh "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"
