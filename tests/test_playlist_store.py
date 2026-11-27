import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from playlist_store import PlaylistStore, list_track_folders, scan_local_tracks


class TestPlaylistStore(unittest.TestCase):
    def test_scan_local_tracks_and_folders(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "青花瓷.m4a").write_bytes(b"abc")
            sub = root / "老歌精选"
            sub.mkdir()
            (sub / "用心良苦.mp3").write_bytes(b"def")
            (sub / "readme.txt").write_text("ignore", encoding="utf-8")

            tracks = scan_local_tracks(root)
            self.assertEqual(len(tracks), 2)
            self.assertEqual(set(t.ext for t in tracks), {"M4A", "MP3"})
            self.assertEqual(list_track_folders(tracks), ["默认目录", "老歌精选"])

    def test_custom_playlist_crud_and_persistence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            f1 = root / "半壶纱.m4a"
            f2 = root / "旧梦一场.mp3"
            f1.write_bytes(b"111")
            f2.write_bytes(b"222")

            store_file = root / "playlists.json"
            store = PlaylistStore(store_file)
            self.assertTrue(store.create_playlist("开车听"))
            self.assertFalse(store.create_playlist("开车听"))

            added = store.add_tracks("开车听", [str(f1), str(f2), str(f1)])
            self.assertEqual(added, 2)

            # Reload from disk
            reloaded = PlaylistStore(store_file)
            self.assertEqual(reloaded.list_playlists(), ["开车听"])
            self.assertEqual(len(reloaded.get_tracks("开车听")), 2)

            self.assertTrue(reloaded.rename_playlist("开车听", "通勤精选"))
            self.assertEqual(reloaded.list_playlists(), ["通勤精选"])

            removed = reloaded.remove_tracks("通勤精选", [str(f1)])
            self.assertEqual(removed, 1)
            self.assertEqual(len(reloaded.get_tracks("通勤精选")), 1)

            self.assertTrue(reloaded.delete_playlist("通勤精选"))
            self.assertEqual(reloaded.list_playlists(), [])


if __name__ == "__main__":
    unittest.main()
