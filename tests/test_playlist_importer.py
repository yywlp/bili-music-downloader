import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from playlist_importer import extract_netease_playlist_id


class TestPlaylistImporter(unittest.TestCase):
    def test_extract_from_share_text(self):
        text = "分享歌单: Healing Flow Music 吉喆吉0209 https://music.163.com/m/playlist?id=17757421514&creatorId=616298502"
        self.assertEqual(extract_netease_playlist_id(text), "17757421514")

    def test_extract_from_web_url_and_raw_id(self):
        self.assertEqual(
            extract_netease_playlist_id("https://music.163.com/#/playlist?id=987654321"),
            "987654321",
        )
        self.assertEqual(extract_netease_playlist_id("17757421514"), "17757421514")
        self.assertIsNone(extract_netease_playlist_id("not a playlist"))


if __name__ == "__main__":
    unittest.main()
