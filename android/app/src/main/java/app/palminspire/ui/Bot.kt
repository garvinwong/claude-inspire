package app.palminspire.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import app.palminspire.BotFlash
import app.palminspire.BotGlyphs
import app.palminspire.BotMood
import app.palminspire.BotMotion
import app.palminspire.BotPoses
import app.palminspire.BotWake
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 吉祥物 C 案（要求 1:1 还原概念图）：造型 = tools/gen_bot.py 从概念图量出的矢量母版（BotArt），
 * 动作 = 代码里的“骨骼”——业界（多邻国用 Rive、早年用 Lottie）同一思路：画风来自原画的矢量形状，流畅来自动画原则：
 *   挤压拉伸：机身以脚底中点为支点，上升拉长、到底压扁（保体积 sx·sy≈1）；等你、睡觉也有呼吸（不冻住）；
 *   跟随动作：两根天线各挂一个欠阻尼弹簧，机身一动（浮、摇、抖）就慢半拍晃两下；
 *   预备动作：进入“等你”先往下一缩再弹起（底栏探头）；完成时落地压扁再回弹；
 *   各态：等你 = 大 ? 与待审批条数交替 + 绕脚底慢摇；爆发 = 屏幕里向上滚的代码雨 + 机身高频细抖；
 *         睡觉 = 天线弯垂、慢深呼吸、头顶飘 Zz（机器人不缩不沉）。
 * 屏幕永远是终端（3×5 粗方块字，悬浮态 >_ 的位置与方块大小按原图），绝不画五官。
 */
@Composable
fun Bot(mood: BotMood, size: Dp, modifier: Modifier = Modifier, pending: Int = 0, flash: BotFlash? = null,
        typing: String? = null, wake: Int = 0) {
    val rig = remember { BotRig() }
    val frame by produceState(0L) { while (true) withFrameNanos { value = it } }
    LaunchedEffect(mood) { rig.onMood(mood) }
    LaunchedEffect(wake) { if (wake > 0 && mood == BotMood.SLEEP) rig.wake() }      // 彩蛋：睡着时双击
    LaunchedEffect(flash?.atMs) { if (flash?.kind == BotFlash.DONE) rig.land() else if (flash != null) rig.nod() }
    Canvas(modifier.size(size)) {
        rig.step(frame, mood, typing != null)
        val wall = System.currentTimeMillis()
        val showFlash = flash != null && wall - flash.atMs in 0..900
        drawRig(rig, mood, pending, if (showFlash) (if (flash!!.kind == BotFlash.DENY) "!!" else "ok") else null, typing)
    }
}

/** 一只机器人的“骨骼”状态（单位：母版坐标 0~100 / 度），每帧积分一次。 */
private class BotRig {
    var t = 0f                                   // 毫秒
    private var last = 0L
    var antenna = 0f; private var antennaV = 0f   // 姿态天线角（弹簧追目标）
    var bodyY = 0f; private var bodyYV = 0f       // 姿态身体高度
    var tilt = 0f; private var tiltV = 0f
    var dim = 1f
    var squash = 0f; private var squashV = 0f     // 挤压拉伸（正 = 拉长）
    var wobL = 0f; private var wobLV = 0f         // 天线跟随（左右各一）
    var wobR = 0f; private var wobRV = 0f
    var bob = 0f                                  // 悬浮位移
    var jx = 0f; var jy = 0f; var jr = 0f         // 爆发抖动：横、纵、扭
    private var prevBobV = 0f
    private var prevSwayV = 0f
    private var prevJx = 0f; private var prevJv = 0f
    var look = 0f; private var lookV = 0f         // 彩蛋张望：-1 左、+1 右（屏幕字跟着挪）
    private var wakeStart = -1f
    private var settled = false
    val wakeFrame get() = if (wakeStart >= 0f) BotWake.at(t - wakeStart) else null

    /** 彩蛋：惊醒——猛一拉长、天线大甩。 */
    fun wake() { wakeStart = t; settled = false; squashV += 3.4f; wobLV += 260f; wobRV -= 260f }

    fun onMood(m: BotMood) {
        wakeStart = -1f                            // 换状态就不演了
        if (m == BotMood.WAIT) squashV -= 2.6f    // 预备：先往下一缩，弹簧自然回弹成拉长（探头）
        wobLV += 120f; wobRV -= 120f               // 换状态天线晃一下
    }

    fun land() { squashV -= 3.2f; bodyYV += 40f; wobLV += 160f; wobRV -= 160f }   // 落地压扁 + 天线甩
    fun nod() { squashV -= 1.6f; wobLV += 90f; wobRV -= 90f }                      // 批准 / 拒绝：点一下头

    fun step(frameNanos: Long, m: BotMood, floating: Boolean) {
        val now = frameNanos / 1_000_000
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0f, 1 / 30f)
        last = now
        t += dt * 1000f
        val wf = wakeFrame
        if (wf == null) wakeStart = -1f
        if (wf != null && !settled && t - wakeStart >= BotWake.SETTLE_AT) { settled = true; squashV -= 1.8f; wobLV += 90f; wobRV -= 90f }   // 叹口气，沉下去
        val base = BotPoses.of(m)
        val alert = BotPoses.of(BotMood.WAIT)
        val pose = if (wf == null) base else app.palminspire.BotPose(           // 惊醒姿态：天线竖起、抬身、屏亮
            base.antennaDeg + (alert.antennaDeg - base.antennaDeg) * wf.awake,
            base.bodyY + (alert.bodyY - base.bodyY) * wf.awake,
            base.screenDim + (1f - base.screenDim) * wf.awake)
        val fl = floating || m == BotMood.RUN || m == BotMood.BUSY
        // 悬浮：2.4s 正弦，幅度 2.2；速度驱动拉伸，加速度驱动天线
        val w = (2 * PI / 2.4).toFloat()
        bob = if (fl) -2.2f * sin(w * t / 1000f) else 0f
        val bobV = if (fl) -2.2f * w * cos(w * t / 1000f) else 0f
        val safe = dt.coerceAtLeast(1e-3f)
        val bobA = (bobV - prevBobV) / safe; prevBobV = bobV
        // 摇摆（等你）与细抖（爆发）：角加速度 / 加速度同样喂给天线
        val sway = BotMotion.sway(m, t)
        val swayV = (sway - (tilt - BotPoses.tilt(m))) / safe
        val swayA = (swayV - prevSwayV) / safe; prevSwayV = swayV.coerceIn(-200f, 200f)
        jx = BotMotion.jitter(m, t); jy = BotMotion.jitterY(m, t); jr = BotMotion.jitterRot(m, t)
        val jv = (jx - prevJx) / safe; val ja = (jv - prevJv) / safe; prevJx = jx; prevJv = jv
        // 弹簧（k, c）：姿态用 UXM 回弹 210/19；挤压用更脆的 420/14；天线欠阻尼 90/4.5（晃两下）
        val n = 4; val h = dt / n
        val stretchTarget = (-bobV * 0.012f).coerceIn(-0.05f, 0.05f) + BotMotion.breathe(m, t)   // 上升拉长、下落压扁、呼吸
        val tiltTarget = BotPoses.tilt(m) + sway + look * 9f                   // 张望：机身朝看的那边歪
        val push = -bobA * 0.9f + swayA.coerceIn(-400f, 400f) * 0.25f + ja.coerceIn(-4000f, 4000f) * 0.012f
        repeat(n) {
            antennaV += (-210f * (antenna - pose.antennaDeg) - 19f * antennaV) * h; antenna += antennaV * h
            bodyYV += (-210f * (bodyY - pose.bodyY * 100f) - 19f * bodyYV) * h; bodyY += bodyYV * h
            tiltV += (-120f * (tilt - tiltTarget) - 16f * tiltV) * h; tilt += tiltV * h
            lookV += (-150f * (look - (wf?.look ?: 0f)) - 18f * lookV) * h; look += lookV * h
            squashV += (-420f * (squash - stretchTarget) - 14f * squashV) * h; squash += squashV * h
            wobLV += (-90f * wobL - 4.5f * wobLV + push) * h; wobL += wobLV * h
            wobRV += (-90f * wobR - 4.5f * wobRV - push) * h; wobR += wobRV * h
        }
        dim += (pose.screenDim - dim) * (1f - kotlin.math.exp(-dt * 6f))
    }
}

private val pathCache = HashMap<String, Path>()
private fun art(d: String): Path = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private fun Offset.rot(deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    val c = cos(a).toFloat(); val s = sin(a).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}

private fun DrawScope.drawRig(rig: BotRig, mood: BotMood, pending: Int, flash: String?, typing: String?) {
    val u = size.minDimension / 100f
    val c = BotArt
    scale(u, pivot = Offset.Zero) {
        // 地影：留在地面，身体越高越小越淡
        val lift = (-(rig.bodyY + rig.bob)).coerceAtLeast(-8f)          // 负 = 落地压在地上：影子更实更宽
        val rx = c.SHADOW_RX * (1f - lift * 0.02f) * (1f - rig.squash * 0.6f)
        // 柔边径向渐变（椭圆 = 圆按纵向压扁），不再是实心椭圆
        val sa = (0.8f - lift * 0.03f).coerceIn(0.25f, 0.9f)
        scale(1f, (c.SHADOW_RY * 1.6f) / rx, pivot = c.SHADOW) {
            drawCircle(Brush.radialGradient(0f to c.shadow.copy(alpha = sa), 0.55f to c.shadow.copy(alpha = sa * 0.7f),
                1f to c.shadow.copy(alpha = 0f), center = c.SHADOW, radius = rx * 1.08f), rx * 1.08f, c.SHADOW)
        }
        val dy = rig.bodyY + rig.bob + rig.jy
        // 腿：跟身体平移，不参与挤压
        translate(left = rig.jx, top = dy) {
            listOf(c.LEG_L, c.LEG_R).forEach { l ->                      // 上浅下深，像圆柱受光
                drawRoundRect(Brush.verticalGradient(0f to LEG_HI, 1f to LEG_LO, startY = l.y + 6f, endY = l.y + c.LEG_H),
                    Offset(l.x - c.LEG_W / 2, l.y), Size(c.LEG_W, c.LEG_H), CornerRadius(c.LEG_W / 2))
            }
        }
        // 机身组：平移（含爆发细抖）→ 绕脚底倾斜（含等你摇摆）→ 绕脚底挤压拉伸（保体积）
        val sy = 1f + rig.squash
        val sx = 1f / sy
        translate(left = rig.jx, top = dy) {
            rotate(rig.tilt + rig.jr, pivot = c.BODY_FOOT) {
                scale(sx, sy, pivot = c.BODY_FOOT) {
                    drawAntenna(c.ANT_L_BASE, c.ANT_L_TIP, -(rig.antenna + rig.wobL))
                    drawAntenna(c.ANT_R_BASE, c.ANT_R_TIP, rig.antenna + rig.wobR)
                    drawPath(art(c.SIDE), Brush.horizontalGradient(0f to c.sideHi, 1f to c.side, startX = 72f, endX = 91f))
                    // 正面 + 上沿斜面高光：在正面内裁剪，正面下错 MID / HI 露出的上沿涂过渡色与亮色——贴合轮廓与圆角
                    val front = art(c.FRONT)
                    // 上沿两级亮带（高光）+ 下沿一道暗边（背光），都由正面路径错位裁出，贴合轮廓与圆角
                    clipPath(front) {
                        drawPath(front, c.top)
                        translate(top = c.BEVEL_MID) { drawPath(front, c.bevelMid) }
                        translate(top = c.BEVEL_HI) { drawPath(front, FRONT_SHADE) }
                        translate(top = c.BEVEL_HI) {
                            clipPath(front) {
                                translate(top = -c.BEVEL_HI - SHADE_BAND) {
                                    drawPath(front, Brush.verticalGradient(0f to c.frontHi, 1f to c.frontLo, startY = 22f, endY = 80f))
                                }
                            }
                        }
                    }
                    // 屏幕：底色 + 上沿内阴影（屏幕嵌在机身里）→ 屏幕字 → 左上一抹玻璃反光（像真显像管）
                    val screen = art(c.SCREEN)
                    drawPath(screen, c.screen)
                    clipPath(screen) {
                        drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 1f to Color.Transparent,
                            startY = c.SCREEN_TL.y, endY = c.SCREEN_TL.y + 5f), c.SCREEN_TL, Size(c.SCREEN_BR.x - c.SCREEN_TL.x, 6f))
                        drawScreen(rig, mood, pending, flash, typing)
                        val g = Offset(c.SCREEN_TL.x + 9f, c.SCREEN_TL.y + 8f)
                        scale(1.6f, 1f, pivot = g) {
                            drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.10f), 1f to Color.Transparent,
                                center = g, radius = 9f), 9f, g)
                        }
                    }
                }
            }
        }
        if (mood == BotMood.SLEEP && flash == null && rig.wakeFrame == null) drawZz(rig)
    }
}

/** 天线：从母版方向整体旋转 deg；角度大时杆弯成拱形（睡觉垂下），球跟着末端。 */
private fun DrawScope.drawAntenna(base: Offset, tip0: Offset, deg: Float) {
    val c = BotArt
    val dir0 = tip0 - base
    val tip = base + dir0.rot(deg)
    val ctrl = base + dir0.rot(deg * 0.35f) * 0.65f
    val p = Path().apply { moveTo(base.x, base.y); quadraticTo(ctrl.x, ctrl.y, tip.x, tip.y) }
    drawPath(p, c.stalk, style = Stroke(c.STALK, cap = StrokeCap.Round))
    // 球体受光：左上亮、右下暗（125° 光源，与玻璃同向）
    drawCircle(Brush.radialGradient(0f to BALL_HI, 0.55f to c.ball, 1f to BALL_LO,
        center = tip + Offset(-c.BALL * 0.35f, -c.BALL * 0.38f), radius = c.BALL * 1.35f), c.BALL, tip)
}

private val LEG_HI = Color(0xFFCB6A44)
private val LEG_LO = Color(0xFFA9502F)
private val BALL_HI = Color(0xFFF2A07A)
private val BALL_LO = Color(0xFFC25F3D)
private val FRONT_SHADE = Color(0xFFC9663F)        // 正面下沿暗边
private const val SHADE_BAND = 2.4f

/** 睡觉：头顶右上方飘出像素 Z，由小变大、边飘边淡；三个错开 0.9s，循环 2.7s。不压缩机器人本体。 */
private fun DrawScope.drawZz(rig: BotRig) {
    val zColor = Color(0xFF4A4651)
    repeat(3) { i ->
        val ph = ((rig.t + i * 900f) % 2700f) / 2700f
        val p = 1.8f + ph * 1.6f                                      // 落地后头顶空出来：格距 1.8→3.4（72dp 下最大约 12dp 高）
        val x = 72f + ph * 12f + sin(ph * 2f * PI.toFloat()) * 1.6f    // 从右天线外侧起，向右上飘，带一点左右晃，不出画框
        val y = 26f - ph * 25f
        val a = (if (ph < 0.15f) ph / 0.15f else 1f) * (1f - ph).coerceIn(0f, 1f)
        BotGlyphs.glyph('z').forEach { (gx, gy) ->
            drawRect(zColor.copy(alpha = a * 0.85f), Offset(x + gx * p, y + gy * p), Size(p * 0.92f, p * 0.92f))
        }
    }
}

private fun screenLines(rig: BotRig, mood: BotMood, pending: Int, flash: String?, typing: String?): List<String>? {
    if (flash != null) return listOf(flash)
    rig.wakeFrame?.let { return listOf(it.screen) }
    if (typing != null) {                                             // 空状态打字：每字 120ms，打完停 1.6s，清屏重来
        val cycle = typing.length * 120f + 1600f + 160f
        val n = ((rig.t % cycle) / 120f).toInt().coerceAtMost(typing.length)
        return listOf(">" + typing.take(n))
    }
    return when (mood) {
        BotMood.WAIT -> BotPoses.screen(mood, pending, rig.t)          // 大 ?；有多条待审批时与实时条数交替
        else -> null                                                   // 悬浮 / 睡觉：母版排版；爆发：代码雨
    }
}

private fun DrawScope.drawScreen(rig: BotRig, mood: BotMood, pending: Int, flash: String?, typing: String?) {
    val c = BotArt
    val blink = (rig.t / 530f).toInt() % 2 == 0
    val lines = screenLines(rig, mood, pending, flash, typing)
    val ink = if ((mood == BotMood.WAIT && flash == null) || flash == "!!" || rig.wakeFrame?.screen == "!") c.amber else c.ink
    if (lines == null) {
        when (mood) {
            BotMood.BUSY -> drawCodeRain(rig)
            BotMood.RUN -> {                                          // 悬浮态：概念图原位的 > 与光标条
                BotGlyphs.dots(">").forEach { (x, y) ->
                    drawRect(ink, Offset(c.TEXT_ORIGIN.x + x * c.PITCH, c.TEXT_ORIGIN.y + y * c.PITCH), Size(c.CELL, c.CELL))
                }
                if (blink) drawRect(ink, Offset(c.BAR[0], c.BAR[1]), Size(c.BAR[2], c.BAR[3]))
            }
            else -> {                                                 // 睡觉：只剩光标条随呼吸明暗
                val breathe = 0.45f + 0.55f * (0.5f + 0.5f * sin(2 * PI * rig.t / 4200.0).toFloat())
                drawRect(ink.copy(alpha = (rig.dim * breathe).coerceIn(0f, 1f)), Offset(c.BAR[0], c.BAR[1]), Size(c.BAR[2], c.BAR[3]))
            }
        }
        return
    }
    val tl = c.SCREEN_TL; val br = c.SCREEN_BR
    val w = br.x - tl.x; val h = br.y - tl.y
    val p = BotGlyphs.pitch(lines, w, h, 0.6f, c.PITCH * 1.2f)
    val (cols, rows) = BotGlyphs.extent(lines)
    val ox = tl.x + (w - cols * p) / 2 + rig.look * 9f; val oy = tl.y + (h - rows * p) / 2   // 张望时字往那边挪
    val cell = p * (c.CELL / c.PITCH)
    lines.forEachIndexed { row, line ->
        BotGlyphs.dots(line).forEach { (x, y) ->
            drawRect(ink.copy(alpha = rig.dim.coerceIn(0f, 1f)), Offset(ox + x * p, oy + (row * 6 + y) * p), Size(cell, cell))
        }
    }
    if (typing != null && blink) {                                    // 打字时光标条跟在字后
        drawRect(c.ink, Offset(ox + cols * p + p, oy + 4 * p), Size(p * 3f, p))
    }
}

/** 稳定伪随机（同一列、同一格、同一时刻结果固定，帧间不乱跳）。 */
private fun hash(a: Int, b: Int, c: Int): Int {
    var x = a * 73856093 xor b * 19349663 xor c * 83492791
    x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15)
    return x and 0x7fffffff
}

/**
 * 爆发：屏幕里向上滚的代码雨（类似黑客帝国，但向上冒、要有代码感和速度感）。
 * 约 10 列，每列一串 3~6 个代码字符以各自速度向上冲，头亮（琥珀）尾渐暗；字符每 90ms 换一次。
 */
private fun DrawScope.drawCodeRain(rig: BotRig) {
    val c = BotArt
    val tl = c.SCREEN_TL; val br = c.SCREEN_BR
    val p = 1.25f                                                    // 格距：3×5 字 → 约 3.75 × 6.25
    val colW = 4f * p; val rowH = 6.5f * p
    val cols = ((br.x - tl.x - 2f) / colW).toInt()
    val x0 = tl.x + (br.x - tl.x - cols * colW) / 2 + p * 0.5f
    val h = br.y - tl.y
    val tick = floor(rig.t / 90f).toInt()
    for (col in 0 until cols) {
        val speed = 34f + hash(col, 1, 7) % 30                       // 单位 / 秒
        val len = 3 + hash(col, 2, 7) % 4
        val span = h + len * rowH + 6f
        val travel = (rig.t / 1000f * speed + hash(col, 3, 7) % 97) % span
        val headY = br.y + rowH - travel                              // 头在最上面，往上冲
        for (k in 0 until len) {
            val y = headY + k * rowH
            if (y > br.y || y < tl.y - rowH) continue
            val code = BotGlyphs.CODE
            val ch = code[hash(col, k + (travel / rowH).toInt(), tick + col) % code.length]
            val a = (1f - k.toFloat() / len) * 0.95f + 0.05f
            val color = if (k == 0) c.amber else c.ink.copy(alpha = a * 0.85f)
            BotGlyphs.glyph(ch).forEach { (gx, gy) ->
                drawRect(color, Offset(x0 + col * colW + gx * p, y + gy * p), Size(p * 0.95f, p * 0.95f))
            }
        }
    }
}
