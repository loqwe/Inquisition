import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.cloud_lua_reverse.artifact import _validate_member, build_manifest, extract_member


class ArtifactTest(unittest.TestCase):
    def test_manifest_contains_hashes_and_sorted_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("脚本/cloud.lua", b"\x86LUA\x03\x00payload")
                package.writestr("entry.json", json.dumps({"enc": "1"}))
            manifest = build_manifest(archive)
            self.assertEqual(32, len(manifest["artifact"]["md5"]))
            self.assertEqual(64, len(manifest["artifact"]["sha256"]))
            self.assertEqual(["entry.json", "脚本/cloud.lua"], [item["name"] for item in manifest["entries"]])

    def test_unflagged_utf8_member_name_is_decoded_and_extracted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            payload = b"payload"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("脚本/cloud.lua", payload)

            data = bytearray(archive.read_bytes())
            for signature, flags_offset in ((b"PK\x03\x04", 6), (b"PK\x01\x02", 8)):
                header_offset = data.index(signature)
                flags = struct.unpack_from("<H", data, header_offset + flags_offset)[0]
                struct.pack_into("<H", data, header_offset + flags_offset, flags & ~0x800)
            archive.write_bytes(data)

            manifest = build_manifest(archive)
            self.assertEqual(["脚本/cloud.lua"], [item["name"] for item in manifest["entries"]])
            extracted = extract_member(archive, "脚本/cloud.lua", root / "out")
            self.assertEqual(payload, extracted.read_bytes())

    def test_extract_member_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("../escape.lua", b"bad")
            with self.assertRaises(ValueError):
                extract_member(archive, "../escape.lua", root / "out")

    def test_validate_member_rejects_windows_path_traversal(self):
        with self.assertRaises(ValueError):
            _validate_member(r"..\escape.lua")

    def test_validate_member_rejects_windows_drive_path(self):
        with self.assertRaises(ValueError):
            _validate_member(r"C:\escape.lua")


if __name__ == "__main__":
    unittest.main()
