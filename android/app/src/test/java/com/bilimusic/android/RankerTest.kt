package com.bilimusic.android

import com.bilimusic.android.core.BiliClient
import com.bilimusic.android.core.RankMode
import com.bilimusic.android.core.Ranker
import com.bilimusic.android.core.VideoCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {

    @Test
    fun filtersNoiseAndPrefersLosslessPureSong() {
        val candidates = listOf(
            VideoCandidate("BV1", "青花瓷 1小时循环 睡眠纯享", "UP1", 5_000_000, 3600, "60:00"),
            VideoCandidate("BV2", "青花瓷 DJ慢摇电音版", "UP2", 2_000_000, 220, "03:40"),
            VideoCandidate("BV3", "【Hi-Res无损】青花瓷 - 周杰伦 原唱纯享", "UP3", 800_000, 239, "03:59"),
            VideoCandidate("BV4", "青花瓷 周杰伦 高清版", "UP4", 900_000, 238, "03:58"),
            VideoCandidate("BV5", "AI翻唱 青花瓷", "UP5", 1_500_000, 230, "03:50"),
        )

        val ranked = Ranker.rankCandidates(candidates, "青花瓷", "周杰伦", RankMode.LOSSLESS_FIRST)
        assertEquals(2, ranked.size)
        assertEquals("BV3", ranked.first().bvid)
        assertTrue(ranked.first().badges.contains("无损/Hi-Res"))
    }

    @Test
    fun parsesBatchLinesCorrectly() {
        assertNull(Ranker.parseBatchLine("# 注释行"))
        assertNull(Ranker.parseBatchLine("   "))
        assertEquals("青花瓷" to "周杰伦", Ranker.parseBatchLine("青花瓷 - 周杰伦"))
        assertEquals("兰亭序" to "", Ranker.parseBatchLine("兰亭序"))
    }

    @Test
    fun formatsPlayCountAndSanitizesFilename() {
        assertEquals("482.0万", Ranker.formatPlayCount(4_820_000))
        assertEquals("1.25亿", Ranker.formatPlayCount(125_000_000))
        assertEquals("青花瓷_周杰伦", Ranker.sanitizeFilename("青花瓷/周杰伦"))
    }

    @Test
    fun encodesWbiQueryWithSignature() {
        val q = BiliClient.encodeWbiQuery(
            params = mapOf("keyword" to "青花瓷", "page" to "1"),
            imgKey = "7cd084941338484aae1ad9425b84077c",
            subKey = "4932caff0ff746eab6f01bf08b70ac45",
        )
        assertTrue(q.contains("wts="))
        assertTrue(q.contains("w_rid="))
    }
}
