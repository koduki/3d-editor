#!/usr/bin/env python3
"""Create/consume a pinned KuCode runtime asset lock using only Python stdlib."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "libproot.so": "android/app/src/main/jniLibs/arm64-v8a/libproot.so",
    "libproot-loader.so": "android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so",
    "debian-arm64-rootfs.tgz": "android/app/src/main/assets/runtime/debian-arm64-rootfs.tgz",
    "openvscode-server-linux-arm64.tgz": "android/app/src/main/assets/runtime/openvscode-server-linux-arm64.tgz",
    "debian-manifest.txt": "android/app/src/main/assets/runtime/debian-manifest.txt",
}
TAG = re.compile(r"v[0-9][A-Za-z0-9._-]*\Z")
SHA = re.compile(r"[0-9a-f]{64}\Z")
MAX_BYTES = 1024 * 1024 * 1024


def digest(p):
    h = hashlib.sha256()
    with p.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def validate_elf(p):
    size = p.stat().st_size
    with p.open("rb") as f:
        hdr = f.read(64)
        if len(hdr) != 64 or hdr[:7] != b"\x7fELF\x02\x01\x01":
            raise ValueError(f"{p.name}: expected ELF64 little endian")
        fields = struct.unpack("<16sHHIQQQIHHHHHH", hdr)
        if fields[2] != 183 or fields[1] not in (2, 3):
            raise ValueError(f"{p.name}: expected AArch64 executable/shared object")
        phoff, phsize, phnum = fields[5], fields[9], fields[10]
        if phsize != 56 or not phnum or phoff + phsize * phnum > size:
            raise ValueError(f"{p.name}: invalid program header table")
        f.seek(phoff)
        loads = 0
        for _ in range(phnum):
            typ, flags, off, addr, _, filesz, memsz, align = struct.unpack("<IIQQQQQQ", f.read(56))
            if typ == 1:
                loads += 1
                if align < 16384 or align & (align - 1) or (off - addr) % 16384 or off + filesz > size or filesz > memsz:
                    raise ValueError(f"{p.name}: invalid or non-16KB LOAD segment")
        if not loads:
            raise ValueError(f"{p.name}: no LOAD segment")


def validate_lock(lock):
    if lock.get("schema") != 1 or lock.get("abi") != "arm64-v8a" or not isinstance(lock.get("release"), str) or not TAG.fullmatch(lock["release"]):
        raise ValueError("invalid schema, ABI or release tag")
    assets = lock.get("assets")
    if not isinstance(assets, dict) or set(assets) != set(FILES):
        raise ValueError("lock must contain exactly the required runtime assets")
    for name, item in assets.items():
        if not isinstance(item, dict) or not isinstance(item.get("sha256"), str) or not SHA.fullmatch(item["sha256"]):
            raise ValueError(f"{name}: invalid hash")
        if type(item.get("size")) is not int or not 0 < item["size"] <= MAX_BYTES:
            raise ValueError(f"{name}: invalid size")


def matches(p, item):
    return p.is_file() and not p.is_symlink() and p.stat().st_size == item["size"] and digest(p) == item["sha256"]


def verify_set(paths, lock):
    validate_lock(lock)
    for name, p in paths.items():
        if not matches(p, lock["assets"][name]):
            raise ValueError(f"{name}: size or SHA-256 mismatch")
        if name.endswith(".so"):
            validate_elf(p)
    manifest = paths["debian-manifest.txt"].read_text(encoding="utf-8")
    if f'# Rootfs Archive SHA-256: {lock["assets"]["debian-arm64-rootfs.tgz"]["sha256"]}' not in manifest.splitlines():
        raise ValueError("Debian manifest does not describe the locked rootfs")


def create_lock(root, release):
    paths = {n: root / p for n, p in FILES.items()}
    lock = {"schema": 1, "abi": "arm64-v8a", "release": release,
            "assets": {n: {"sha256": digest(p), "size": p.stat().st_size} for n, p in paths.items()}}
    verify_set(paths, lock)
    return lock


def materialize(root, lock, cache, source=None):
    validate_lock(lock)
    cache.mkdir(parents=True, exist_ok=True)
    # Verify all inputs before publishing any destination. Cache keyed by content.
    paths = {}
    for name, rel in FILES.items():
        item = lock["assets"][name]
        dest = root / rel
        cached = cache / item["sha256"]
        if matches(dest, item):
            paths[name] = dest
            continue
        if not matches(cached, item):
            fd, tmpname = tempfile.mkstemp(dir=cache)
            tmp = Path(tmpname)
            try:
                with os.fdopen(fd, "wb") as out:
                    if source is not None:
                        inp = (source / name).open("rb")
                    else:
                        url = f'https://github.com/koduki/kucode/releases/download/{lock["release"]}/{name}'
                        inp = urllib.request.urlopen(url, timeout=60)
                    with inp:
                        remaining = item["size"]
                        while remaining:
                            block = inp.read(min(remaining, 1024 * 1024))
                            if not block:
                                break
                            out.write(block)
                            remaining -= len(block)
                        if remaining or inp.read(1):
                            raise ValueError(f"{name}: download size mismatch")
                if not matches(tmp, item):
                    raise ValueError(f"{name}: download SHA-256 mismatch")
                os.replace(tmp, cached)
            finally:
                tmp.unlink(missing_ok=True)
        paths[name] = cached
    verify_set(paths, lock)
    for name, p in paths.items():
        dest = root / FILES[name]
        if p == dest:
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        fd, tmpname = tempfile.mkstemp(dir=dest.parent)
        tmp = Path(tmpname)
        os.close(fd)
        try:
            shutil.copyfile(p, tmp)
            if not matches(tmp, lock["assets"][name]):
                raise ValueError(f"{name}: staged copy mismatch")
            tmp.chmod(0o755 if name.endswith(".so") else 0o644)
            os.replace(tmp, dest)
        finally:
            tmp.unlink(missing_ok=True)
    verify_set({n: root / p for n, p in FILES.items()}, lock)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", type=Path, default=ROOT)
    sub = ap.add_subparsers(dest="cmd", required=True)
    gen = sub.add_parser("lock")
    gen.add_argument("--release", required=True)
    gen.add_argument("--output", type=Path, required=True)
    for cmd in ("fetch", "verify"):
        p = sub.add_parser(cmd)
        p.add_argument("--lock", type=Path, required=True)
        if cmd == "fetch":
            p.add_argument("--cache", type=Path, default=Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "kucode" / "runtime")
            p.add_argument("--source-dir", type=Path, help="Local producer output; same integrity checks as remote")
    a = ap.parse_args()
    try:
        if a.cmd == "lock":
            lock = create_lock(a.root, a.release)
            # Source provenance is additional review evidence, not a replacement for
            # the independently committed content hashes.
            sources = {}
            for name in ("scripts/build-proot-android.sh", "scripts/fetch-runtime-assets.sh"):
                source = a.root / name
                if source.is_file():
                    sources[name] = digest(source)
            lock["producer"] = {
                "commit": os.environ.get("GITHUB_SHA", "unknown"),
                "sourceScripts": sources,
                "ndk": Path(os.environ.get("ANDROID_NDK_HOME", "unknown")).name,
            }
            a.output.parent.mkdir(parents=True, exist_ok=True)
            a.output.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        else:
            lock = json.loads(a.lock.read_text(encoding="utf-8"))
            if a.cmd == "fetch":
                materialize(a.root, lock, a.cache, a.source_dir)
            else:
                verify_set({n: a.root / p for n, p in FILES.items()}, lock)
        print(f"Runtime assets {a.cmd}: OK ({lock['release']})")
    except (OSError, ValueError, TypeError, AttributeError) as e:
        ap.exit(1, f"Runtime assets {a.cmd}: {e}\n")


if __name__ == "__main__":
    main()
