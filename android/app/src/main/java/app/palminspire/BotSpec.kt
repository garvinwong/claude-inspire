package app.palminspire

/**
 * 吉祥物 C 案「矢量壳 + 点阵屏」的纯数据部分（
 * 造型母版由 tools/gen_bot.py 从概念图量取生成 ui/BotArt.kt）。
 * 屏幕永远是终端：只打点阵字，绝不画眼睛嘴巴（吉祥物五条禁令）。绘制在 ui/Bot.kt。
 */
enum class BotMood { SLEEP, RUN, BUSY, WAIT }

object BotGlyphs {
    /** 3×5 粗方块字（概念图 C 案原样：> 就是 5 个对角相接的方块）。每字 5 行、每行 3 位。 */
    private val TABLE: Map<Char, List<String>> = mapOf(
        '>' to listOf("100", "010", "001", "010", "100"),
        '_' to listOf("000", "000", "000", "000", "111"),
        '?' to listOf("111", "001", "010", "000", "010"),
        '!' to listOf("010", "010", "010", "000", "010"),
        'o' to listOf("000", "111", "101", "101", "111"),
        'k' to listOf("100", "101", "110", "101", "101"),
        'n' to listOf("000", "110", "101", "101", "101"),
        'e' to listOf("010", "101", "111", "100", "011"),
        'w' to listOf("000", "101", "101", "111", "010"),
        '0' to listOf("111", "101", "101", "101", "111"),
        '1' to listOf("010", "110", "010", "010", "111"),
        '2' to listOf("111", "001", "111", "100", "111"),
        '3' to listOf("111", "001", "111", "001", "111"),
        '4' to listOf("101", "101", "111", "001", "001"),
        '5' to listOf("111", "100", "111", "001", "111"),
        '6' to listOf("111", "100", "111", "101", "111"),
        '7' to listOf("111", "001", "001", "001", "001"),
        '8' to listOf("111", "101", "111", "101", "111"),
        '9' to listOf("111", "101", "111", "001", "111"),
        '+' to listOf("000", "010", "111", "010", "000"),
        // 代码雨用（爆发态屏幕里向上滚的代码字符）
        '<' to listOf("001", "010", "100", "010", "001"),
        '/' to listOf("001", "001", "010", "100", "100"),
        '=' to listOf("000", "111", "000", "111", "000"),
        '{' to listOf("011", "010", "110", "010", "011"),
        '}' to listOf("110", "010", "011", "010", "110"),
        '(' to listOf("010", "100", "100", "100", "010"),
        ')' to listOf("010", "001", "001", "001", "010"),
        ';' to listOf("000", "010", "000", "010", "100"),
        '#' to listOf("101", "111", "101", "111", "101"),
        '*' to listOf("000", "101", "010", "101", "000"),
        'a' to listOf("000", "011", "101", "101", "011"),
        'f' to listOf("011", "100", "110", "100", "100"),
        'i' to listOf("010", "000", "010", "010", "010"),
        'r' to listOf("000", "011", "100", "100", "100"),
        't' to listOf("010", "111", "010", "010", "011"),
        'z' to listOf("111", "001", "010", "100", "111"),
        '.' to listOf("000", "000", "000", "000", "010"),
        ' ' to List(5) { "000" },
    )

    fun has(c: Char) = c in TABLE

    /** 爆发态代码雨的字符集。 */
    const val CODE = "{}()<>/=;#*+01234567890aefiknortw"

    /** 单个字的亮格（不带字距）。 */
    fun glyph(c: Char): List<Pair<Int, Int>> = dots(c.toString())

    /** 一行文字 → 亮格坐标（列, 行），字距 1 格；不认识的字符按空格处理。 */
    fun dots(line: String): List<Pair<Int, Int>> = buildList {
        line.forEachIndexed { i, c ->
            val g = TABLE[c] ?: TABLE.getValue(' ')
            g.forEachIndexed { y, row -> row.forEachIndexed { x, b -> if (b == '1') add(i * 4 + x to y) } }
        }
    }

    /** 多行文字的格数宽高（行距 1 格）。 */
    fun extent(lines: List<String>): Pair<Int, Int> =
        ((lines.maxOfOrNull { it.length } ?: 0) * 4 - 1).coerceAtLeast(1) to (lines.size * 6 - 1).coerceAtLeast(1)

    /** 格距：放进 w×h、按 fill 留白，且不超过 maxPitch（概念图悬浮态的格距，字不比原图大）。 */
    fun pitch(lines: List<String>, w: Float, h: Float, fill: Float, maxPitch: Float): Float {
        val (cols, rows) = extent(lines)
        return minOf(w * fill / cols, h * fill / rows, maxPitch)
    }
}

/** 各状态的姿态（与尺寸无关，单位是机器人边长 S 的比例或角度）。 */
data class BotPose(val antennaDeg: Float, val bodyY: Float, val screenDim: Float)

object BotPoses {
    /** 天线角：0 = 竖直，正 = 向外张；睡觉时下垂到 105°。身体位移：负 = 抬高。 */
    fun of(m: BotMood) = when (m) {
        BotMood.WAIT -> BotPose(-8f, -0.03f, 1f)          // 天线收拢竖起（母版本身外张约 10°）
        BotMood.RUN -> BotPose(0f, 0f, 1f)                // 母版姿态
        BotMood.BUSY -> BotPose(14f, 0f, 1f)
        BotMood.SLEEP -> BotPose(55f, 0.08f, 0.35f)       // 落地（腿踩到地影）、不压缩；天线弯垂两侧；头顶空出来给 Zz
    }

    /** 屏幕默认内容：等你 = ?（多条时 ? 与条数每 0.8s 交替，“?3”挤在一起像“73”，真机实见）；悬浮 = >_；爆发 = 三行 >>>；睡觉 = _。 */
    fun screen(m: BotMood, pending: Int = 0, tMs: Float = 0f): List<String> = when (m) {
        BotMood.WAIT -> listOf(if (pending <= 1 || (tMs / 800f).toInt() % 2 == 0) "?" else if (pending > 9) "9+" else "$pending")
        BotMood.RUN -> listOf(">_")
        BotMood.BUSY -> listOf(">>>", ">>>", ">>>")
        BotMood.SLEEP -> listOf("_")
    }

    /** 机身基准倾斜（度，负 = 向左歪）；等你时在此基础上慢慢左右摇摆（BotMotion.sway）。 */
    fun tilt(m: BotMood) = if (m == BotMood.WAIT) -2f else 0f
}

/** 各状态的持续动作（不能冻住：等你、睡觉也要有呼吸感）。tMs 为毫秒。 */
object BotMotion {
    private fun wave(tMs: Float, periodMs: Float) = kotlin.math.sin(2 * Math.PI * tMs / periodMs).toFloat()

    /** 等你：绕脚底慢慢左右摇摆 ±6°，2.8s 一个来回（摇，不是平移）。 */
    fun sway(m: BotMood, tMs: Float) = if (m == BotMood.WAIT) 6f * wave(tMs, 2800f) else 0f

    /** 呼吸：挤压拉伸的目标量（正 = 拉长）。等你 3.2s ±2%，睡觉 4.2s ±3%（慢、深）。 */
    fun breathe(m: BotMood, tMs: Float) = when (m) {
        BotMood.WAIT -> 0.02f * wave(tMs, 3200f)
        BotMood.SLEEP -> 0.03f * wave(tMs, 4200f)
        else -> 0f
    }

    private fun buzz(tMs: Float, a: Double, b: Double, c: Double) =
        (0.5 * kotlin.math.sin(2 * Math.PI * tMs * a / 1000) + 0.3 * kotlin.math.sin(2 * Math.PI * tMs * b / 1000 + 1.3) +
            0.2 * kotlin.math.sin(2 * Math.PI * tMs * c / 1000 + 2.1)).toFloat()

    /** 爆发：机身高能抖动（再抖一点，像处于高能状态）——横、纵、扭三路，各自三频叠加避免机械感。 */
    fun jitter(m: BotMood, tMs: Float) = if (m == BotMood.BUSY) 1.4f * buzz(tMs, 13.0, 19.7, 31.0) else 0f
    fun jitterY(m: BotMood, tMs: Float) = if (m == BotMood.BUSY) 0.8f * buzz(tMs, 17.0, 23.3, 37.0) else 0f
    fun jitterRot(m: BotMood, tMs: Float) = if (m == BotMood.BUSY) 1.8f * buzz(tMs, 11.0, 15.1, 27.0) else 0f

}

/**
 * 彩蛋：睡着时双击底栏机器人 → 惊醒、左右张望、发现没事、又睡下。
 * 只是时间线（毫秒 → 这一刻的目标）；姿态由 Bot.kt 的弹簧去追，所以各段之间自然过渡。
 * awake：0 = 睡姿（天线垂、落地、屏暗），1 = 惊醒（天线竖起、抬身、屏亮）；look：-1 左看、+1 右看；screen：屏幕上的字。
 */
object BotWake {
    const val DUR = 3800f
    const val SETTLE_AT = 2900f                    // 从这一刻起又睡下（叹口气：往下一沉）

    data class Frame(val awake: Float, val look: Float, val screen: String)

    fun at(ms: Float): Frame? = when {
        ms < 0f || ms >= DUR -> null
        ms < 600f -> Frame(1f, 0f, "!")            // 惊醒
        ms < 1400f -> Frame(1f, -1f, "?")          // 往左看
        ms < 2200f -> Frame(1f, 1f, "?")           // 往右看
        ms < SETTLE_AT -> Frame(1f, 0f, "...")     // 没事……
        else -> Frame(0f, 0f, "_")                 // 又睡下
    }
}

/** 机器人一次性反应：审批后 ok / !!，会话完成落地一跳。 */
data class BotFlash(val kind: String, val atMs: Long) {
    companion object {
        const val OK = "ok"
        const val DENY = "!!"
        const val DONE = "done"
    }
}

/** 这一轮总览里“刚做完”的会话：上一轮在忙、这一轮还在但不忙了（会话消失不算完成）。 */
fun finishedSessions(prevBusy: Set<String>, rows: List<SessionRow>): Set<String> =
    rows.filter { it.sid in prevBusy && !it.busy }.mapTo(HashSet()) { it.sid }
