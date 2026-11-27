import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ranker import VideoCandidate, format_play_count, parse_duration, rank_candidates


class TestRanker(unittest.TestCase):
    def test_parse_duration_and_play_count(self):
        self.assertEqual(parse_duration("3:42"), 222)
        self.assertEqual(parse_duration("01:02:03"), 3723)
        self.assertEqual(parse_duration(175), 175)
        self.assertEqual(format_play_count(4709304), "470.9万")
        self.assertEqual(format_play_count(9800), "9800")

    def test_banhusha_filters_noise_and_prefers_lossless(self):
        candidates = [
            VideoCandidate("BV16p411f7sq", "刘珂矣-《半壶纱》MV", "刘珂矣", 4709304, 222, "3:42"),
            VideoCandidate("BV1csZXBFETd", "“我要验牌”是啥？短短几天播放34亿", "某UP", 4423490, 190, "3:10"),
            VideoCandidate("BV1SS7Q6rEHf", "《盘点2026全网刷屏的神级emo歌单》半壶纱", "某UP", 4372003, 2072, "34:32"),
            VideoCandidate("BV1cw411c7z8", "【Hi-Res无损】《半壶纱》刘珂矣 - 倘若我心中的山水", "旧梦丶n", 3285119, 222, "3:42"),
            VideoCandidate("BV16hMuzaEN7", "刘珂矣《半壶纱》百万豪装录音棚大声听", "JLRS-LeoFM", 2985080, 222, "3:42"),
        ]

        lossless_ranked = rank_candidates(candidates, "半壶纱", mode="lossless_first")
        self.assertEqual([c.bvid for c in lossless_ranked], ["BV1cw411c7z8", "BV16p411f7sq"])
        self.assertIn("无损/Hi-Res", lossless_ranked[0].badges)

        views_ranked = rank_candidates(candidates, "半壶纱", mode="views_first")
        self.assertEqual([c.bvid for c in views_ranked], ["BV16p411f7sq", "BV1cw411c7z8"])

    def test_jiumengyichang_excludes_dj_and_cantonese_and_prefers_hires(self):
        candidates = [
            VideoCandidate("BV1cC4y1W72V", "阿悠悠 - 旧梦一场", "阿梦顺其自然", 636000, 174, "2:54"),
            VideoCandidate("BV1gB4y1P7UB", "阿悠悠 - 旧梦一场 (DJ版)", "副驾搭子", 599000, 159, "2:39"),
            VideoCandidate("BV19K4y1Z7kB", "《旧梦一场》粤语版，祭奠老港产武侠片", "不是词神", 542000, 177, "2:57"),
            VideoCandidate("BV1p3411Q7MY", "【hires】阿悠悠-《旧梦一场》｜细数过往", "熊猫先生-1979", 485000, 175, "2:55"),
            VideoCandidate("BV_LOW_HIRES", "【Hi-Res无损】旧梦一场", "小透明", 120, 175, "2:55"),
        ]
        ranked = rank_candidates(candidates, "旧梦一场", mode="lossless_first")
        self.assertEqual(ranked[0].bvid, "BV1p3411Q7MY")
        # Very low-view Hi-Res should not outrank 63.6万 normal pure upload
        self.assertEqual(ranked[1].bvid, "BV1cC4y1W72V")
        self.assertNotIn("BV1gB4y1P7UB", [c.bvid for c in ranked])
        self.assertNotIn("BV19K4y1Z7kB", [c.bvid for c in ranked])

    def test_dj_allowed_when_user_explicitly_requests_dj(self):
        candidates = [
            VideoCandidate("BV1gB4y1P7UB", "阿悠悠 - 旧梦一场 (DJ版)", "副驾搭子", 599000, 159, "2:39"),
        ]
        ranked = rank_candidates(candidates, "旧梦一场 DJ", mode="lossless_first")
        self.assertEqual(len(ranked), 1)
        self.assertFalse(ranked[0].excluded)


if __name__ == "__main__":
    unittest.main()
