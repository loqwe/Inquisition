import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from tools.cloud_lua_reverse.artifact import build_manifest, extract_member


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

    def test_extract_member_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("../escape.lua", b"bad")
            with self.assertRaises(ValueError):
                extract_member(archive, "../escape.lua", root / "out")

    def test_extract_member_rejects_windows_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "script.lr"
            with mock.patch.object(zipfile, "_sanitize_filename", side_effect=lambda name: name):
                with zipfile.ZipFile(archive, "w") as package:
                    package.writestr("..\\escape.lua", b"bad")
                with self.assertRaises(ValueError):
                    extract_member(archive, "..\\escape.lua", root / "out")


if __name__ == "__main__":
    unittest.main()
