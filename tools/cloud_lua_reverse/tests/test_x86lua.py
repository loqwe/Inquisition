import unittest

from tools.cloud_lua_reverse.x86lua import printable_strings, probe_bytes


class X86LuaTest(unittest.TestCase):
    def test_probe_recognizes_header_and_version(self):
        report = probe_bytes(b"\x86LUA\x03\x00abc123\x00")
        self.assertTrue(report["isX86Lua"])
        self.assertEqual(3, report["version"])
        self.assertEqual("864c55410300", report["headerHex"])

    def test_printable_strings_applies_minimum_length(self):
        self.assertEqual(["hello", "world!"], printable_strings(b"\x00hello\x01abc\x00world!", 5))


if __name__ == "__main__":
    unittest.main()
