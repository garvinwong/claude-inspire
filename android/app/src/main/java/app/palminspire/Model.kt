package app.palminspire

import org.json.JSONArray
import org.json.JSONObject

/** 中枢下发数据的解析（字段名与 hub/palm_hub.py、transcript.py 的输出一一对应）。逐条容错，坏数据只丢那一条。 */

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

data class SessionRow(
    val sid: String, val title: String, val cwd: String, val account: String, val status: String,
    val control: String, val hostId: String, val contextPct: Int?, val ageSeconds: Int?, val lastTool: String,
    val model: String = "", val effort: String = "",
    val actTool: String = "", val actSummary: JSONObject = JSONObject(), val actTs: String = "",
    val keepAlive: Boolean = false, val attached: Boolean = false, val yolo: Boolean = false,
) {
    val hosted get() = control == "host"
    val starting get() = sid.startsWith("host:")
    val busy get() = status in BUSY
    val shortCwd get() = shortPath(cwd)

    companion object {
        val BUSY = setOf("busy", "thinking", "executing_tool", "working", "running", "streaming")
        fun parse(o: JSONObject) = SessionRow(
            o.str("sid"), o.str("title"), o.str("cwd"), o.str("account").ifEmpty { "main" },
            o.str("host_status").ifEmpty { o.str("status") }, o.str("control"), o.str("host_id"),
            o.intOrNull("context_pct"), o.intOrNull("age_seconds"), o.str("last_tool"), o.str("model"), o.str("effort"),
            o.optJSONObject("activity")?.str("tool").orEmpty(), o.optJSONObject("activity")?.optJSONObject("summary") ?: JSONObject(),
            o.optJSONObject("activity")?.str("ts").orEmpty(),
            o.optBoolean("keep_alive"), o.optBoolean("attached"), o.optBoolean("yolo"),
        )
    }
}

/** 路径去掉家目录前缀（/home/<用户>/、/Users/<用户>/）和 WSL 盘符前缀（/mnt/<盘>/），空了就原样返回。 */
fun shortPath(p: String): String = p.replaceFirst(Regex("^/(home|Users)/[^/]+/"), "").replaceFirst(Regex("^/mnt/[a-z]/"), "").ifEmpty { p }

/**
 * 首页排序（按账号分组没必要，按最近活动排）：在跑或有待批的排最前（相互间按会话号固定，免得每次刷新来回跳），
 * 其余按最后一次动静从新到旧（活动时间缺就用 age_seconds 推）。
 */
fun sortByRecent(rows: List<SessionRow>, pendingSids: Set<String>, nowMs: Long): List<SessionRow> {
    fun lastMs(s: SessionRow): Long = runCatching { java.time.Instant.parse(s.actTs).toEpochMilli() }.getOrNull()
        ?: s.ageSeconds?.let { nowMs - it * 1000L } ?: 0L
    val (hot, rest) = rows.partition { it.busy || it.sid in pendingSids }
    return hot.sortedBy { it.sid } + rest.sortedByDescending { lastMs(it) }
}

data class Option(val label: String, val description: String)
data class Question(val question: String, val header: String, val multi: Boolean, val options: List<Option>)

data class Approval(
    val id: String, val kind: String, val tool: String, val sid: String, val title: String, val project: String,
    val human: String, val danger: Boolean, val expiresIn: Int?, val ttl: Int, val input: JSONObject,
    val questions: List<Question>, val plan: String, val receivedAtMs: Long,
) {
    fun remainingSeconds(nowMs: Long): Int? = expiresIn?.let { maxOf(0, it - ((nowMs - receivedAtMs) / 1000).toInt()) }

    companion object {
        fun parse(o: JSONObject, nowMs: Long = System.currentTimeMillis()) = Approval(
            o.str("id"), o.str("kind").ifEmpty { "perm" }, o.str("tool"), o.str("sid"), o.str("title"), o.str("project"),
            o.str("human").ifEmpty { o.str("tool") }, o.str("risk") == "danger", o.intOrNull("expires_in"),
            o.optInt("ttl", 115), o.optJSONObject("input") ?: JSONObject(),
            o.optJSONArray("questions").objects().map { q ->
                Question(q.str("question"), q.str("header"), q.optBoolean("multiSelect"),
                    q.optJSONArray("options").objects().map { Option(it.str("label"), it.str("description")) })
            },
            o.str("plan"), nowMs,
        )
    }
}

data class Img(val id: String, val mime: String, val bytes: Int)
data class Todo(val text: String, val status: String)

/** 会话事件：k 区分类型（user / command / command_output / queued / text / thinking / tool / tool_result /
 *  todo / helper / image / title / mode / system）。工具结果在列表组装时并进对应的工具卡（见 Timeline）。 */
data class Ev(
    val id: String, val ts: String, val k: String, val text: String = "", val name: String = "",
    val summary: JSONObject = JSONObject(), val toolUseId: String = "", val isError: Boolean = false,
    val images: List<Img> = emptyList(), val todos: List<Todo> = emptyList(), val agent: String = "",
    val command: String = "", val desc: String = "", val mode: String = "", val level: String = "",
) {
    companion object {
        private fun img(o: JSONObject) = Img(o.str("img"), o.str("mime"), o.optInt("bytes"))
        fun parse(o: JSONObject): Ev? {
            val k = o.str("k").ifEmpty { return null }
            val images = buildList {
                if (k == "image" && o.str("img").isNotEmpty()) add(img(o))
                o.optJSONArray("images").objects().forEach { add(img(it)) }
            }
            return Ev(
                o.str("id"), o.str("ts"), k, o.str("text"), o.str("name"), o.optJSONObject("summary") ?: JSONObject(),
                o.str("tool_use_id"), o.optBoolean("is_error"), images,
                o.optJSONArray("todos").objects().map { Todo(it.str("text"), it.str("status")) },
                o.str("agent"), o.str("command"), o.str("desc"), o.str("mode"), o.str("level"),
            )
        }
    }
}

/** 对话视图的一行：工具调用与它的结果合成一张卡；标题事件不进时间线；group 非空 = 折叠成组的连续工具调用。 */
data class Row(val ev: Ev, val result: Ev? = null, val group: List<Row> = emptyList())

object Timeline {
    private val HIDDEN = setOf("title")

    fun build(events: List<Ev>): List<Row> {
        val results = events.filter { it.k == "tool_result" && it.toolUseId.isNotEmpty() }.associateBy { it.toolUseId }
        val callIds = events.filter { it.k in setOf("tool", "helper", "todo") }.map { it.toolUseId }.toSet()
        return events.mapNotNull { e ->
            when {
                e.k in HIDDEN -> null
                e.k == "tool_result" && e.toolUseId in callIds -> null          // 已并进工具卡
                e.k in setOf("tool", "helper", "todo") -> Row(e, results[e.toolUseId])
                else -> Row(e)
            }
        }
    }

    /** 合并新事件：按 id 去重、保持顺序。 */
    fun merge(old: List<Ev>, incoming: List<Ev>): List<Ev> {
        if (incoming.isEmpty()) return old
        val seen = old.mapTo(HashSet()) { it.id }
        return old + incoming.filter { it.id.isEmpty() || seen.add(it.id) }
    }

    fun latestTitle(events: List<Ev>): String = events.lastOrNull { it.k == "title" }?.text ?: ""

    /** 不折叠的工具：要人看的（提问、计划）与子代理。 */
    private val KEEP = setOf("AskUserQuestion", "ExitPlanMode", "Task", "Agent")

    /**
     * 连续工具调用折叠成组：连着 ≥ min 个工具卡（中间夹的“思考”一并收进组）合成一行；
     * 文字、用户消息等会打断连续。组里有出错的，组头标出来。
     */
    fun group(rows: List<Row>, min: Int = 3): List<Row> {
        val out = mutableListOf<Row>()
        var run = mutableListOf<Row>()
        fun flush() {
            if (run.count { it.ev.k == "tool" } >= min) {
                val first = run.first { it.ev.k == "tool" }
                out += Row(first.ev.copy(id = "g-" + first.ev.id, k = "group"), group = run.filter { it.ev.k == "tool" })
            } else out += run
            run = mutableListOf()
        }
        for (r in rows) {
            val groupable = (r.ev.k == "tool" && r.ev.name !in KEEP) || (r.ev.k == "thinking" && run.isNotEmpty())
            if (groupable) run += r else { flush(); out += r }
        }
        flush()
        return out
    }

    /** 组头摘要：读 3 · 改 2 · 命令 1（按出现多少排序）。 */
    fun groupSummary(g: List<Row>): String = g.groupingBy {
        when (it.ev.name) {
            "Read", "NotebookRead" -> "读"; "Edit", "MultiEdit", "NotebookEdit", "Write" -> "改"; "Bash" -> "命令"
            "Grep", "Glob", "WebSearch" -> "搜"; "WebFetch" -> "取网页"; else -> "其他"
        }
    }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" }
}

/** 账号额度状态词：5h ≥ 85% 或 7d ≥ 90% 预警；有会话在跑 = 活跃；否则空闲。 */
fun usageState(u: Usage?, busy: Int): String = when {
    u != null && ((u.h5?.pct ?: 0) >= 85 || (u.d7?.pct ?: 0) >= 90) -> "预警"
    busy > 0 -> "活跃"
    else -> "空闲"
}

/** 历史页分组：按本地时间的今天 / 昨天 / 更早（ageSec 为距今秒数，secOfDay 为现在是当天第几秒）。 */
fun historyBucket(ageSec: Int?, secOfDay: Int): String = when {
    ageSec == null -> "更早"
    ageSec <= secOfDay -> "今天"
    ageSec <= secOfDay + 86400 -> "昨天"
    else -> "更早"
}

/** reset = 重置时间已过、缓存还是重置前的数（中枢已改报 0，10-28）。 */
data class Window(val pct: Int, val resetsAt: Long?, val reset: Boolean = false)
data class Usage(val account: String, val h5: Window?, val d7: Window?, val at: Long) {
    companion object {
        private fun w(o: JSONObject?) = o?.let { Window(it.optInt("pct"), if (it.isNull("resets_at")) null else it.optLong("resets_at"), it.optBoolean("reset")) }
        fun parseAll(o: JSONObject?): List<Usage> = if (o == null) emptyList() else
            listOf("main").mapNotNull { a ->
                o.optJSONObject(a)?.let { Usage(a, w(it.optJSONObject("h5")), w(it.optJSONObject("d7")), it.optLong("at")) }
            }
    }
}

/** 终端缺字替换：随包 DejaVu Sans Mono 与部分系统字体都没有这几个符号（显示成方框，真机实测），
 *  换成意思相近、同为单格宽的字，保持对齐。 */
object TermGlyphs {
    private val MAP = mapOf('⏵' to '▸', '⏴' to '◂', '⎿' to '└', '⏺' to '●', '⧉' to '❐', '⏸' to '‖', '⏹' to '■',
        '⎯' to '─', '⧖' to '▪')

    fun fix(s: String): String = if (s.none { it in MAP }) s else String(CharArray(s.length) { MAP[s[it]] ?: s[it] })
}

data class Seg(val text: String, val fg: String, val bg: String, val bold: Boolean, val reverse: Boolean)
data class Screen(val rows: Int, val cols: Int, val lines: List<List<Seg>>, val cursorX: Int, val cursorY: Int, val alive: Boolean) {
    companion object {
        fun parse(o: JSONObject): Screen {
            val lines = o.optJSONArray("lines")
            val parsed = (0 until (lines?.length() ?: 0)).map { y ->
                val arr = lines!!.optJSONArray(y) ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let { s -> Seg(s.optString(0), s.optString(1), s.optString(2), s.optBoolean(3), s.optBoolean(4)) }
                }
            }
            val cur = o.optJSONObject("cursor")
            return Screen(o.optInt("rows"), o.optInt("cols"), parsed, cur?.optInt("x") ?: 0, cur?.optInt("y") ?: 0, o.optBoolean("alive", true))
        }
    }
}

/** 新建页选项（中枢 new_options）：各账号缺省模型与思考程度（settings.json），能否选 Fable，缺省目录。 */
data class AcctOpt(val account: String, val model: String, val effort: String, val fable: Boolean)
data class NewOptions(val accounts: List<AcctOpt>, val efforts: List<String>, val defaultDir: String, val dirs: List<String>,
                      val dirTags: Map<String, String> = emptyMap()) {
    fun of(account: String) = accounts.firstOrNull { it.account == account }

    companion object {
        val EMPTY = NewOptions(emptyList(), listOf("low", "medium", "high", "xhigh", "max"), "", emptyList())
        fun parse(o: JSONObject) = NewOptions(
            o.optJSONArray("accounts").objects().map { AcctOpt(it.str("account"), it.str("model"), it.str("effort"), it.optBoolean("fable")) },
            o.optJSONArray("efforts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: EMPTY.efforts,
            o.str("default_dir"),
            o.optJSONArray("dirs")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            o.optJSONObject("dir_tags")?.let { t -> t.keys().asSequence().associateWith { t.optString(it) } } ?: emptyMap(),
        )

        /** opus[1m] → Opus 1M；claude-sonnet-5-5 → Sonnet 5.5；空 → 空（交给调用方写“默认”）。 */
        fun modelLabel(raw: String): String {
            if (raw.isBlank()) return ""
            val one = raw.contains("[1m]", ignoreCase = true)
            val base = raw.replace(Regex("\\[1m]", RegexOption.IGNORE_CASE), "").removePrefix("claude-")
            val m = Regex("([a-z]+)-?(\\d+)?-?(\\d+)?").matchEntire(base.lowercase())
            val name = m?.groupValues?.get(1) ?: base
            val ver = m?.let { listOf(it.groupValues[2], it.groupValues[3]).filter { v -> v.isNotEmpty() }.joinToString(".") }.orEmpty()
            return listOf(name.replaceFirstChar { it.uppercase() }, ver, if (one) "1M" else "").filter { it.isNotEmpty() }.joinToString(" ")
        }

        val EFFORT_LABEL = mapOf("low" to "低", "medium" to "中", "high" to "高", "xhigh" to "超高", "max" to "极限")
        fun effortLabel(raw: String) = EFFORT_LABEL[raw] ?: raw
    }
}

/** 斜杠命令（中枢 complete.py）：menu = 发出后在终端里弹菜单，手机要切到终端视图作答。 */
data class Cmd(val name: String, val desc: String, val src: String, val menu: Boolean) {
    companion object {
        fun parse(o: JSONObject) = Cmd(o.str("name"), o.str("desc"), o.str("src"), o.optBoolean("menu"))
    }
}

/** 输入框补全：整段开头是 “/命令名” → 命令面板；光标前最后一段以 @ 开头 → 文件补全（与 Claude Code 终端一致）。 */
object Suggest {
    sealed class Mode {
        data object None : Mode()
        data class Slash(val q: String) : Mode()
        data class File(val q: String) : Mode()
    }

    fun mode(text: String, cursor: Int): Mode {
        val before = text.substring(0, cursor.coerceIn(0, text.length))
        if (before.startsWith("/") && before.none { it.isWhitespace() }) return Mode.Slash(before.drop(1))
        val token = before.takeLastWhile { !it.isWhitespace() }
        if (token.startsWith("@")) return Mode.File(token.drop(1))
        return Mode.None
    }

    /** 命令名开头命中 > 命令名包含 > 说明包含；同档保持中枢给的顺序（内置在前）。 */
    fun filter(cmds: List<Cmd>, q: String): List<Cmd> {
        val k = q.lowercase()
        if (k.isEmpty()) return cmds
        return cmds.mapNotNull { c ->
            val n = c.name.removePrefix("/").lowercase()
            when {
                n.startsWith(k) -> 0 to c
                k in n -> 1 to c
                k in c.desc.lowercase() -> 2 to c
                else -> null
            }
        }.sortedBy { it.first }.map { it.second }
    }

    /** 选中命令：替换开头的命令段，补一个空格，光标落在空格后。 */
    fun applyCommand(text: String, cursor: Int, name: String): Pair<String, Int> {
        val after = text.substring(cursor.coerceIn(0, text.length)).trimStart()
        return "$name $after" to name.length + 1
    }

    /** 选中文件：把光标前的 @片段 换成 @路径 加空格。 */
    fun applyFile(text: String, cursor: Int, path: String): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val before = text.substring(0, c)
        val token = before.takeLastWhile { !it.isWhitespace() }
        val head = before.dropLast(token.length) + "@" + path + " "
        return head + text.substring(c) to head.length
    }

    /** 这段话是不是会在终端里弹菜单的命令（如 /model）。 */
    fun opensMenu(text: String, cmds: List<Cmd>): Boolean {
        val first = text.trim().takeWhile { !it.isWhitespace() }
        if (!first.startsWith("/")) return false
        return cmds.firstOrNull { it.name == first }?.menu ?: (first in FALLBACK_MENU)
    }

    private val FALLBACK_MENU = setOf("/model", "/effort", "/resume", "/rewind", "/status", "/config", "/memory",
        "/permissions", "/mcp", "/hooks", "/tasks", "/workflows", "/diff", "/export", "/skills", "/help")
}

/** 选择题作答 → 桥的结构化答案（题目 → 回答；多选用逗号连，与 Agents Island 一致）。 */
fun answersJson(questions: List<Question>, picked: List<Set<String>>, other: List<String>): JSONObject {
    val o = JSONObject()
    questions.forEachIndexed { i, q ->
        val chosen = picked.getOrNull(i).orEmpty().toMutableList()
        other.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { chosen += it }
        if (chosen.isNotEmpty()) o.put(q.question, chosen.joinToString(", "))
    }
    return o
}

/** 仪表盘（中枢 /api/stats，hub/usage.py）：token 按天、按账号；工作时长取各会话活动区间的并集。 */
data class StatDay(val date: String, val tokens: Long, val acct: Map<String, Long>, val workS: Int, val sessions: Int, val tools: Int)
data class StatSession(val sid: String, val account: String, val title: String, val cwd: String, val tokens: Long, val activeS: Int)
data class QuotaPoint(val t: Long, val h5: Map<String, Int?>)

data class Stats(
    val ready: Boolean, val progress: Float, val days: List<StatDay>,
    val tokens: Long, val input: Long, val output: Long, val cacheWrite: Long, val cacheRead: Long, val cacheHit: Float,
    val workS: Int, val sessions: Int, val tools: Int, val msgs: Int,
    val toolTop: List<Pair<String, Int>>, val models: List<Pair<String, Int>>, val top: List<StatSession>,
    val trend: List<QuotaPoint>,
) {
    companion object {
        private fun pairs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            a.optJSONArray(i)?.let { it.optString(0) to it.optInt(1) }
        }

        fun parse(o: JSONObject): Stats {
            val t = o.optJSONObject("total") ?: JSONObject()
            return Stats(
                ready = o.optBoolean("ready", false), progress = o.optDouble("progress", 0.0).toFloat(),
                days = o.optJSONArray("days").objects().map { d ->
                    val a = d.optJSONObject("acct")
                    StatDay(d.optString("date"), d.optLong("tokens"),
                        a?.keys()?.asSequence()?.associateWith { a.optLong(it) } ?: emptyMap(),
                        d.optInt("work_s"), d.optInt("sessions"), d.optInt("tools"))
                },
                tokens = t.optLong("tokens"), input = t.optLong("in"), output = t.optLong("out"),
                cacheWrite = t.optLong("cw"), cacheRead = t.optLong("cr"), cacheHit = t.optDouble("cache_hit", 0.0).toFloat(),
                workS = t.optInt("work_s"), sessions = t.optInt("sessions"), tools = t.optInt("tools"), msgs = t.optInt("msgs"),
                toolTop = pairs(o.optJSONArray("tools")), models = pairs(o.optJSONArray("models")),
                top = o.optJSONArray("sessions").objects().map {
                    StatSession(it.optString("sid"), it.optString("account"), it.optString("title"), it.optString("cwd"),
                        it.optLong("tokens"), it.optInt("active_s"))
                },
                trend = o.optJSONArray("trend").objects().map { r ->
                    QuotaPoint(r.optLong("t"), listOf("main").associateWith { a ->
                        r.optJSONArray(a)?.let { if (it.isNull(0)) null else it.optInt(0) }
                    })
                },
            )
        }
    }
}

/** 仪表盘数字：点阵里只放数字，单位用正常字（点阵只承载短内容）。 */
object StatFmt {
    /** token → (数字, 单位)：万以下原样，万 / 亿保留到三位有效数字。 */
    fun tokens(n: Long): Pair<String, String> = when {
        n >= 100_000_000L -> sig(n / 1e8) to "亿"
        n >= 10_000L -> sig(n / 1e4) to "万"
        else -> "$n" to ""
    }

    /** 秒 → (数字, 单位)：不足 1 小时用分钟，否则小时保留一位小数。 */
    /** 排行用：万 / 亿统一保留一位小数（评审意见：15.1亿 与 7.71亿 混排不齐）。 */
    fun tokens1(n: Long): Pair<String, String> = when {
        n >= 100_000_000L -> "%.1f".format(n / 1e8) to "亿"
        n >= 10_000L -> "%.1f".format(n / 1e4) to "万"
        else -> "$n" to ""
    }

    fun hours(s: Int): Pair<String, String> = if (s < 3600) "${s / 60}" to "分钟" else sig(s / 3600.0) to "小时"

    fun count(n: Int): Pair<String, String> = if (n >= 10_000) sig(n / 1e4) to "万" else "$n" to ""

    private fun sig(v: Double): String = when {
        v >= 100 -> "%.0f".format(v)
        v >= 10 -> "%.1f".format(v).removeSuffix(".0")
        else -> "%.2f".format(v).trimEnd('0').removeSuffix(".")
    }

    /** 模型名 → 短名：claude-opus-5-5 → Opus 5.5。 */
    fun model(m: String): String {
        val parts = m.removePrefix("claude-").split('-')
        if (parts.isEmpty() || parts[0].isEmpty()) return m
        val name = parts[0].replaceFirstChar { it.uppercase() }
        val ver = parts.drop(1).takeWhile { it.all(Char::isDigit) && it.length <= 2 }.joinToString(".")
        return if (ver.isEmpty()) name else "$name $ver"
    }
}

/** 手机消息被电脑草稿挡下排队：qid 排队号，chars 电脑输入框里估计的字数，idleS 电脑键盘多久没动（秒），typing 电脑正在打字。 */
data class DraftBlock(val sid: String, val qid: String, val chars: Int, val idleS: Int?, val typing: Boolean) {
    /** 电脑闲置超过 2 分钟：多半是人走了留下的字，推荐“清空后发送”。 */
    val stale get() = !typing && (idleS ?: 0) >= 120

    fun idleText(): String = when {
        typing -> "电脑上正在打字"
        idleS == null -> "电脑输入框里有没发的字"
        idleS < 60 -> "电脑 ${idleS} 秒前打过字"
        idleS < 3600 -> "电脑 ${idleS / 60} 分钟没动"
        else -> "电脑 ${idleS / 3600} 小时没动"
    }
}
