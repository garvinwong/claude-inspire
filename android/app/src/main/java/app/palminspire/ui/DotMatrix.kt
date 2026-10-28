package app.palminspire.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 点阵数字（仪表盘 KPI）。
 * 用法：点阵只当装饰件，承载数字、时间这类短内容；单位与说明用正常字。
 * 配色：亮点 Claude 橙 + 极淡的熄灭点，新点亮 / 被手指扫过的点带琥珀余辉（点阵 demo 的 LED 色）再褪回橙；
 * 数字变化时按列错开点亮；手指按住扫过，附近的点被弹簧推开、放大、发热；轻点起一圈波纹。静止时不重绘。
 */
object DotFont {
    const val ROWS = 7
    private val G: Map<Char, List<String>> = mapOf(
        '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        '.' to listOf(".", ".", ".", ".", ".", ".", "#"),
        ':' to listOf(".", ".", "#", ".", "#", ".", "."),
        '%' to listOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        '-' to listOf("...", "...", "...", "###", "...", "...", "..."),
        '+' to listOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
    )

    fun has(c: Char) = c in G

    /** 文字 → (列数, 行数, 点阵)。字间空一列，四周各留一圈熄灭点当“屏幕边”。缺字按空格处理。 */
    fun mask(text: String): Triple<Int, Int, BooleanArray> {
        val chars = text.map { if (it in G) it else ' ' }
        // 小数点前不空列（评审意见：“49.1”的点前后各空一格，远看像“49  1”）
        fun gapBefore(i: Int) = if (i == 0 || chars[i] == '.') 0 else 1
        val inner = chars.indices.sumOf { G.getValue(chars[it])[0].length + gapBefore(it) }
        val cols = inner + 2
        val rows = ROWS + 2
        val m = BooleanArray(cols * rows)
        var x = 1
        chars.forEachIndexed { i, ch ->
            x += gapBefore(i)
            val g = G.getValue(ch)
            for (r in 0 until ROWS) for (c in g[r].indices) if (g[r][c] == '#') m[(r + 1) * cols + x + c] = true
            x += g[0].length
        }
        return Triple(cols, rows, m)
    }
}

private val DOT_TRAIL = Color(0xFFFFB020)

private class DotField(val cols: Int, val rows: Int) {
    val n = cols * rows
    val lit = FloatArray(n); val tgt = FloatArray(n); val start = LongArray(n); val heat = FloatArray(n)
    val dx = FloatArray(n); val dy = FloatArray(n); val vx = FloatArray(n); val vy = FloatArray(n)
    val sc = FloatArray(n) { 1f }; val vs = FloatArray(n)
    var pointer: Offset? = null
    val ripples = ArrayList<Pair<Offset, Long>>()
    var pendingRipple: Offset? = null
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun set(m: BooleanArray, now: Long, animate: Boolean) {
        for (i in 0 until n) {
            tgt[i] = if (m[i]) 1f else 0f
            if (animate) start[i] = now + (i % cols) * 16L + (i / cols) * 5L else { lit[i] = tgt[i]; start[i] = 0 }
        }
        wake.trySend(Unit)
    }

    /** 走一帧；返回是否还在动（不动就停帧，等下次触摸或换数字）。 */
    fun step(now: Long, pitch: Float, reduced: Boolean): Boolean {
        pendingRipple?.let { if (!reduced) ripples.add(it to now); pendingRipple = null }
        ripples.removeAll { now - it.second > 900 }
        val p = if (reduced) null else pointer
        val radius = pitch * 5f
        var busy = p != null || ripples.isNotEmpty()
        for (i in 0 until n) {
            if (lit[i] != tgt[i]) {
                if (now >= start[i]) {
                    if (tgt[i] > lit[i] && lit[i] == 0f) heat[i] = maxOf(heat[i], 0.85f)    // 新点亮：带余辉
                    val d = tgt[i] - lit[i]
                    lit[i] = if (abs(d) < 0.04f) tgt[i] else lit[i] + d * 0.28f
                }
                busy = true
            }
            val cx = (i % cols + 0.5f) * pitch
            val cy = (i / cols + 0.5f) * pitch
            var tx = 0f; var ty = 0f; var ts = 1f; var h = heat[i] * 0.93f
            if (p != null) {
                val ex = cx - p.x; val ey = cy - p.y; val d = hypot(ex, ey)
                if (d < radius) {
                    val f = 1 - d / radius; val f2 = f * f
                    if (d > 0.5f) { tx = ex / d * f2 * pitch * 0.85f; ty = ey / d * f2 * pitch * 0.85f }
                    ts = 1 + f2 * 0.75f; h = maxOf(h, f2)
                }
            }
            for ((o, t0) in ripples) {
                val age = (now - t0).toFloat(); val rad = age * pitch * 0.06f
                val band = abs(hypot(cx - o.x, cy - o.y) - rad)
                if (band < pitch * 2.2f) {
                    val f = (1 - band / (pitch * 2.2f)) * (1 - age / 900f)
                    h = maxOf(h, f); ts = maxOf(ts, 1 + f * 0.5f)
                }
            }
            heat[i] = if (h < 0.01f) 0f else h
            if (heat[i] > 0f) busy = true
            vx[i] = (vx[i] + (tx - dx[i]) * 0.2f) * 0.7f; dx[i] += vx[i]
            vy[i] = (vy[i] + (ty - dy[i]) * 0.2f) * 0.7f; dy[i] += vy[i]
            vs[i] = (vs[i] + (ts - sc[i]) * 0.2f) * 0.7f; sc[i] += vs[i]
            if (abs(dx[i]) > 0.02f || abs(dy[i]) > 0.02f || abs(sc[i] - 1) > 0.002f) busy = true
            else if (p == null) { dx[i] = 0f; dy[i] = 0f; sc[i] = 1f; vx[i] = 0f; vy[i] = 0f; vs[i] = 0f }
        }
        return busy
    }
}

/**
 * 点阵数字。pitch = 点距（整块高 = 9 × pitch）。
 * ink 亮点、off 熄灭点、trail 余辉；reduced = 系统减弱动效时不扫、不波纹、不错开。
 */
@Composable
fun DotNumber(text: String, pitch: Dp, modifier: Modifier = Modifier, ink: Color = Brand.Orange,
              off: Color = LocalPalm.current.line.copy(alpha = 0.55f), trail: Color = DOT_TRAIL, reduced: Boolean = false) {
    val (cols, rows, mask) = remember(text) { DotFont.mask(text) }
    val field = remember(cols, rows) { DotField(cols, rows) }
    val px = with(LocalDensity.current) { pitch.toPx() }
    var frame by remember { mutableLongStateOf(0L) }
    var shown by remember { mutableLongStateOf(0L) }                    // 0 = 头一次出现（也按列点亮）
    LaunchedEffect(field, text) {
        val now = withFrameMillis { it }
        field.set(mask, now, animate = !reduced)
        shown = now
    }
    LaunchedEffect(field) {
        while (true) {
            val busy = withFrameMillis { t -> field.step(t, px, reduced) }
            frame++
            if (!busy) field.wake.receive()
        }
    }
    Canvas(modifier.size(pitch * cols, pitch * rows).pointerInput(field) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)          // 不消费：页面照样能滚
                val c = e.changes.firstOrNull() ?: continue
                if (e.type == PointerEventType.Press) field.pendingRipple = c.position
                field.pointer = if (c.pressed) c.position else null
                field.wake.trySend(Unit)
            }
        }
    }) {
        frame.let { }                                                     // 读帧号：每帧重画
        val r0 = px * 0.38f
        for (i in 0 until field.n) {
            val l = field.lit[i]; val h = field.heat[i].coerceIn(0f, 1f)
            val base = lerp(off, ink, l)
            val col = if (h > 0f) lerp(base, trail, h * (0.35f + 0.65f * l)) else base
            drawCircle(col, r0 * field.sc[i],
                Offset((i % cols + 0.5f) * px + field.dx[i], (i / cols + 0.5f) * px + field.dy[i]))
        }
    }
}
