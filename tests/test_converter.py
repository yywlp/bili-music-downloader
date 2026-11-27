import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from converter import resolve_output_path, sanitize_filename, truncate_utf8_stem


class TestConverter(unittest.TestCase):
    def test_sanitize_filename(self):
        self.assertEqual(sanitize_filename('清明上河图: "原版"/测试*'), "清明上河图_ _原版__测试_")
        self.assertEqual(sanitize_filename("   "), "untitled")

    def test_utf8_byte_limit(self):
        very_long_cn = "【Hi-Res无损】《半壶纱》刘珂矣" * 15
        stem = truncate_utf8_stem(very_long_cn, ext=".mp3", max_bytes=200)
        self.assertLessEqual(len((stem + ".mp3").encode("utf-8")), 200)

    def test_conflict_policies(self):
        with tempfile.TemporaryDirectory() as tmp:
            p1, skipped1 = resolve_output_path(tmp, "半壶纱", conflict_policy="rename")
            self.assertEqual(p1.name, "半壶纱.mp3")
            self.assertFalse(skipped1)
            p1.write_bytes(b"fake")

            p2, skipped2 = resolve_output_path(tmp, "半壶纱", conflict_policy="rename")
            self.assertEqual(p2.name, "半壶纱 (2).mp3")
            self.assertFalse(skipped2)
            p2.write_bytes(b"fake")

            p3, skipped3 = resolve_output_path(tmp, "半壶纱", conflict_policy="rename")
            self.assertEqual(p3.name, "半壶纱 (3).mp3")
            self.assertFalse(skipped3)

            p_skip, skipped = resolve_output_path(tmp, "半壶纱", conflict_policy="skip")
            self.assertEqual(p_skip.name, "半壶纱.mp3")
            self.assertTrue(skipped)

            p_over, skipped_over = resolve_output_path(tmp, "半壶纱", conflict_policy="overwrite")
            self.assertEqual(p_over.name, "半壶纱.mp3")
            self.assertFalse(skipped_over)

            p_m4a, skipped_m4a = resolve_output_path(tmp, "半壶纱", conflict_policy="rename", ext=".m4a")
            self.assertEqual(p_m4a.name, "半壶纱.m4a")
            self.assertFalse(skipped_m4a)
            p_m4a.write_bytes(b"fake")
            p_m4a2, _ = resolve_output_path(tmp, "半壶纱", conflict_policy="rename", ext=".m4a")
            self.assertEqual(p_m4a2.name, "半壶纱 (2).m4a")


if __name__ == "__main__":
    unittest.main()
