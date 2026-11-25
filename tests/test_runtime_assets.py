import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("runtime_assets", Path(__file__).resolve().parents[1] / "scripts/runtime-assets.py")
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


def elf(machine=183, alignment=16384):
    return (struct.pack("<16sHHIQQQIHHHHHH", b"\x7fELF\x02\x01\x01".ljust(16,b"\0"),
                        3,machine,1,0,64,0,0,64,56,1,0,0,0)
            + struct.pack("<IIQQQQQQ",1,5,0,0,0,120,120,alignment))


class RuntimeAssetsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.producer = self.root / "producer"
        self.source = self.root / "source"
        self.source.mkdir()
        self.target = self.root / "target"
        self.cache = self.root / "cache"
        for name, rel in assets.FILES.items():
            p = self.producer / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(elf() if name.endswith(".so") else name.encode())
        sha = assets.digest(self.producer / assets.FILES["debian-arm64-rootfs.tgz"])
        (self.producer / assets.FILES["debian-manifest.txt"]).write_text(f"# Rootfs Archive SHA-256: {sha}\n")
        self.lock = assets.create_lock(self.producer, "v1.0.0")
        for name, rel in assets.FILES.items():
            (self.source / name).write_bytes((self.producer / rel).read_bytes())

    def test_fetch_and_offline_reuse(self):
        assets.materialize(self.target, self.lock, self.cache, self.source)
        for rel in assets.FILES.values():
            (self.target / rel).unlink()
        assets.materialize(self.target, self.lock, self.cache, self.root / "missing")
        assets.verify_set({n:self.target/p for n,p in assets.FILES.items()}, self.lock)

    def test_bad_hash_no_publish(self):
        p = self.source / "debian-manifest.txt"
        p.write_bytes(b"x" * p.stat().st_size)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            assets.materialize(self.target, self.lock, self.cache, self.source)
        self.assertFalse(self.target.exists())

    def test_size_mismatch(self):
        (self.source / "libproot.so").write_bytes(b"bad")
        with self.assertRaisesRegex(ValueError, "size"):
            assets.materialize(self.target, self.lock, self.cache, self.source)
        self.assertFalse(self.target.exists())

    def test_invalid_abi_and_alignment(self):
        p = self.root / "bad.so"
        for content in (elf(machine=62), elf(alignment=4096)):
            p.write_bytes(content)
            with self.assertRaises(ValueError):
                assets.validate_elf(p)

    def test_wrong_manifest(self):
        p = self.producer / assets.FILES["debian-manifest.txt"]
        p.write_text("# Rootfs Archive SHA-256: wrong\n")
        with self.assertRaisesRegex(ValueError, "does not describe"):
            assets.create_lock(self.producer, "v1.0.0")

    def test_lock_rejects_unknown_entry(self):
        lock = json.loads(json.dumps(self.lock))
        lock["assets"]["../bad"] = lock["assets"]["libproot.so"]
        with self.assertRaises(ValueError):
            assets.validate_lock(lock)


if __name__ == "__main__":
    unittest.main()
