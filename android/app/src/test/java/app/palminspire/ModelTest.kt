package app.palminspire

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun ev(json: String) = Ev.parse(JSONObject(json))!!

    @Test fun sessionRowFlags() {
        val r = SessionRow.parse(JSONObject("""{"sid":"host:abc","title":"","cwd":"/home/alice/wt/x","account":"main",
            "status":"starting","control":"host","host_id":"abc","host_status":"busy","context_pct":null}"""))
        assertTrue(r.hosted && r.starting && r.busy)
        assertEquals("wt/x", r.shortCwd)
        assertNull(r.contextPct)
        assertEquals("main", SessionRow.parse(JSONObject("""{"sid":"s"}""")).account)
    }

    @Test fun approvalParsesQuestionsAndCountsDown() {
        val a = Approval.parse(JSONObject("""{"id":"k2","kind":"ask","tool":"AskUserQuestion","risk":"normal","expires_in":100,"ttl":125,
            "questions":[{"question":"选哪个？","header":"方案","multiSelect":true,"options":[{"label":"A","description":"甲"}]}]}"""), nowMs = 0)
        assertEquals("ask", a.kind)
        assertTrue(a.questions[0].multi)
        assertEquals("甲", a.questions[0].options[0].description)
        assertEquals(70, a.remainingSeconds(30_000))
        assertEquals(0, a.remainingSeconds(500_000))
        assertTrue(Approval.parse(JSONObject("""{"id":"x","risk":"danger"}""")).danger)
    }

    @Test fun timelineMergesToolResultsAndHidesTitles() {
        val evs = listOf(
            ev("""{"id":"1","k":"user","text":"hi"}"""),
            ev("""{"id":"2","k":"tool","name":"Bash","tool_use_id":"t1","summary":{"command":"ls"}}"""),
            ev("""{"id":"3","k":"title","text":"标题"}"""),
            ev("""{"id":"4","k":"tool_result","tool_use_id":"t1","text":"a\nb","images":[{"img":"ff","mime":"image/png","bytes":3}]}"""),
            ev("""{"id":"5","k":"tool_result","tool_use_id":"zz","text":"孤儿结果"}"""),
        )
        val rows = Timeline.build(evs)
        assertEquals(listOf("user", "tool", "tool_result"), rows.map { it.ev.k })
        assertEquals("a\nb", rows[1].result!!.text)
        assertEquals("ff", rows[1].result!!.images[0].id)
        assertEquals("标题", Timeline.latestTitle(evs))
    }

    @Test fun mergeDedupesById() {
        val a = listOf(ev("""{"id":"1","k":"text","text":"x"}"""))
        val b = listOf(ev("""{"id":"1","k":"text","text":"x"}"""), ev("""{"id":"2","k":"text","text":"y"}"""))
        assertEquals(listOf("1", "2"), Timeline.merge(a, b).map { it.id })
    }

    @Test fun badEventsAreDropped() {
        assertNull(Ev.parse(JSONObject("""{"id":"1"}""")))
    }

    @Test fun usageAndScreen() {
        val u = Usage.parseAll(JSONObject("""{"main":{"at":5,"h5":{"pct":40,"resets_at":1},"d7":{"pct":73,"resets_at":null}}}"""))
        assertEquals(1, u.size)
        assertEquals(40, u[0].h5!!.pct)
        assertNull(u[0].d7!!.resetsAt)
        val s = Screen.parse(JSONObject("""{"rows":2,"cols":10,"cursor":{"x":3,"y":1},"alive":true,
            "lines":[[["红色标题","red","default",false,false]],[["❯ Opus","default","default",false,true]]]}"""))
        assertEquals("red", s.lines[0][0].fg)
        assertTrue(s.lines[1][0].reverse)
        assertEquals(3, s.cursorX)
    }

    @Test fun answersJsonJoinsMultiAndOther() {
        val qs = listOf(Question("Q1", "", true, emptyList()), Question("Q2", "", false, emptyList()), Question("Q3", "", false, emptyList()))
        val o = answersJson(qs, listOf(setOf("A", "B"), emptySet(), emptySet()), listOf("", " 自填 ", ""))
        assertEquals("A, B", o.getString("Q1"))
        assertEquals("自填", o.getString("Q2"))
        assertFalse(o.has("Q3"))
    }

    /** 首页不再按账号分组，按“最近活动”排——在跑 / 等你批的在前（相互间顺序固定，免得来回跳），其余按最后动静从新到旧。 */
    @Test fun sessionsSortByRecentActivity() {
        fun row(sid: String, status: String, ts: String = "", age: Int? = null) = SessionRow(sid, sid, "/w", "main", status, "host", "", null, age, "",
            actTool = if (ts.isEmpty()) "" else "Bash", actTs = ts)
        val now = java.time.Instant.parse("2026-10-28T05:00:00Z").toEpochMilli()
        val rows = listOf(
            row("idle-old", "idle", "2026-10-28T03:00:00Z"),
            row("busy-b", "busy"),
            row("idle-new", "idle", "2026-10-28T04:59:00Z"),
            row("idle-age", "idle", age = 600),                 // 没有活动时间：用 age_seconds 推（10 分钟前）
            row("busy-a", "thinking"),
            row("wait", "idle", "2026-10-28T01:00:00Z"),        // 有待批的算正在要你
        )
        assertEquals(listOf("busy-a", "busy-b", "wait", "idle-new", "idle-age", "idle-old"),
            sortByRecent(rows, setOf("wait"), now).map { it.sid })
    }

    @Test fun usageWindowResetFlag() {
        val u = Usage.parseAll(JSONObject("""{"main":{"at":1,"h5":{"pct":0,"resets_at":null,"reset":true},"d7":{"pct":81,"resets_at":9}}}"""))[0]
        assertTrue(u.h5!!.reset)
        assertFalse(u.d7!!.reset)
        assertTrue(SessionRow.parse(JSONObject("""{"sid":"s","yolo":true}""")).yolo)
    }

    /** 局域网信箱走 http（内容端到端加密）：配对地址 http / https 都收，别的协议拒绝。 */
    @Test fun pairAcceptsLanHttpAddress() {
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        fun cfg(relay: String) = JSONObject("""{"v":1,"relay":"$relay","token":"t","me":"phone","peer":"palm-pc","key":"$key","pc":"box"}""")
        assertEquals("http://100.80.1.2:5613", PairStore.parse(cfg("http://100.80.1.2:5613")).relay)
        assertEquals("https://x.example", PairStore.parse(cfg("https://x.example")).relay)
        assertTrue(runCatching { PairStore.parse(cfg("ftp://x")) }.isFailure)
    }
}
