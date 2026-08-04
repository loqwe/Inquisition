import unittest

from tools.cloud_lua_reverse.protocol_reference import presence_report


class ProtocolReferenceTest(unittest.TestCase):
    def test_presence_report_marks_visible_and_hidden_tokens(self):
        report = presence_report(b"POST /heartBeat assignmentId")
        self.assertTrue(report["/heartBeat"])
        self.assertTrue(report["assignmentId"])
        self.assertFalse(report["/completeTask"])


if __name__ == "__main__":
    unittest.main()
