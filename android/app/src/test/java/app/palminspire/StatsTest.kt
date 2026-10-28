package app.palminspire

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    @Test fun parseHubReply() {
        val s = Stats.parse(JSONObject("""{"ok":true,"ready":true,
            "days":[{"date":"2026-10-27","tokens":1000,"acct":{"main":1000},"work_s":5400,"sessions":2,"tools":7}],
            "total":{"tokens":1000,"in":5,"out":50,"cw":0,"cr":945,"cache_hit":0.9947,"work_s":5400,"sessions":2,"tools":7,"msgs":3},
            "tools":[["Bash",5],["Read",2]],"models":[["claude-opus-5-5",3]],
            "sessions":[{"sid":"s1","account":"main","title":"demo","cwd":"/w","tokens":600,"active_s":3000}],
            "quota":{},"trend":[{"t":1,"main":[12,3]}]}"""))
        assertEquals(1000L, s.tokens); assertEquals(945L, s.cacheRead)
        assertEquals(mapOf("main" to 1000L), s.days[0].acct)
        assertEquals(listOf("Bash" to 5, "Read" to 2), s.toolTop)
        assertEquals("demo", s.top[0].title)
        assertEquals(12, s.trend[0].h5["main"]); assertNull(s.trend[0].h5["other"])
    }

    @Test fun warmingReply() {
        val s = Stats.parse(JSONObject("""{"ok":true,"ready":false,"progress":0.37}"""))
        assertFalse(s.ready); assertEquals(0.37f, s.progress, 1e-6f); assertEquals(0, s.days.size)
    }

    @Test fun numbers() {
        assertEquals("47.9" to "亿", StatFmt.tokens(4_786_450_295L))
        assertEquals("1.68" to "亿", StatFmt.tokens(168_400_000L))
        assertEquals("156" to "亿", StatFmt.tokens(15_600_000_000L))
        assertEquals("1.2" to "万", StatFmt.tokens(12_000L))
        assertEquals("9999" to "", StatFmt.tokens(9_999L))
        assertEquals("54.7" to "小时", StatFmt.hours(196_973))
        assertEquals("45" to "分钟", StatFmt.hours(2_700))
        assertEquals("1.28" to "万", StatFmt.count(12_847))
        assertEquals("Opus 5.5" to "Fable 5.1", StatFmt.model("claude-opus-5-5") to StatFmt.model("claude-fable-5-1"))
        assertEquals("Sonnet 5", StatFmt.model("claude-sonnet-5"))
        assertEquals("7.7" to "亿", StatFmt.tokens1(771_000_000L))                // 排行一位小数
        assertEquals("15.1" to "亿", StatFmt.tokens1(1_512_300_000L))
        assertEquals("1.2" to "万", StatFmt.tokens1(12_345L))
        assertEquals("Haiku 4.5", StatFmt.model("claude-haiku-4-5-20251001"))
    }
}
