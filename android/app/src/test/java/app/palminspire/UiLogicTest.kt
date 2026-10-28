package app.palminspire

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 界面逻辑（工具折叠、活动文字、历史分组等）的纯函数测试。 */
class UiLogicTest {
    private fun tool(id: String, name: String) = Row(Ev(id, "", "tool", name = name))
    private fun ev(id: String, k: String) = Row(Ev(id, "", k, text = id))

    @Test fun consecutiveToolsFoldIntoGroup() {
        val rows = listOf(ev("u", "user"), tool("a", "Read"), ev("th", "thinking"), tool("b", "Edit"), tool("c", "Bash"),
            ev("t", "text"), tool("d", "Read"), tool("e", "Read"))
        val g = Timeline.group(rows)
        assertEquals(listOf("user", "group", "text", "tool", "tool"), g.map { it.ev.k })
        assertEquals(listOf("a", "b", "c"), g[1].group.map { it.ev.id })                  // 思考收进组、不算数
        assertEquals("g-a", g[1].ev.id)
        assertEquals("读 1 · 改 1 · 命令 1".split(" · ").toSet(), Timeline.groupSummary(g[1].group).split(" · ").toSet())
    }

    @Test fun questionsPlansAndAgentsStayVisible() {
        val rows = listOf(tool("a", "Read"), tool("b", "Read"), tool("q", "AskUserQuestion"), tool("c", "Read"))
        assertEquals(listOf("tool", "tool", "tool", "tool"), Timeline.group(rows).map { it.ev.k })
        assertEquals("读 3", Timeline.groupSummary(listOf(tool("a", "Read"), tool("b", "Read"), tool("c", "NotebookRead"))))
    }

    @Test fun usageStateWords() {
        val hot = Usage("B", Window(99, null), Window(81, null), 0)
        val ok = Usage("主", Window(8, null), Window(1, null), 0)
        assertEquals("预警", usageState(hot, busy = 0))
        assertEquals("活跃", usageState(ok, busy = 1))
        assertEquals("空闲", usageState(ok, busy = 0))
        assertEquals("预警", usageState(Usage("C", Window(0, null), Window(92, null), 0), busy = 2))
    }

    @Test fun historyBuckets() {
        val noon = 12 * 3600
        assertEquals("今天", historyBucket(3600, noon))
        assertEquals("昨天", historyBucket(noon + 3600, noon))
        assertEquals("更早", historyBucket(noon + 86400 + 1, noon))
        assertEquals("更早", historyBucket(null, noon))
    }

    @Test fun sessionActivityAndDirTagsParse() {
        val r = SessionRow.parse(JSONObject("""{"sid":"s","activity":{"tool":"Read","summary":{"path":"/w/HomeScreen.kt"},"ts":"2026-10-26T13:00:00Z"}}"""))
        assertEquals("Read", r.actTool)
        assertEquals("/w/HomeScreen.kt", r.actSummary.optString("path"))
        assertEquals("", SessionRow.parse(JSONObject("""{"sid":"s","activity":null}""")).actTool)
        val o = NewOptions.parse(JSONObject("""{"dirs":["/a"],"dir_tags":{"/a":"工作树"}}"""))
        assertEquals("工作树", o.dirTags["/a"])
    }
}
