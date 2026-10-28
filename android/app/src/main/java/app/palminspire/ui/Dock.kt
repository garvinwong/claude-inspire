package app.palminspire.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.palminspire.AppState
import app.palminspire.Approval
import app.palminspire.BotMood
import app.palminspire.Page
import app.palminspire.R
import kotlin.math.abs

/** 悬浮底栏留出的高度：页面内容底部要空出这么多，最后一行才不会被底栏挡住。 */
val DockClearance = 104.dp
val DockHeight = 64.dp
val DockBubble = 64.dp
val DockMini = 56.dp                                 // 会话页里收拢成的机器人圆钮

/** 可滚动页面底部要留的空：底栏 + 系统手势条。 */
@Composable
fun dockBottomPad(): Dp = DockClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** 一级页面（首页 / 新建 / 历史 / 设置）的大标题：没有返回箭头，切页靠底栏。 */
@Composable
fun TabHeader(title: String, sub: String = "", trailing: @Composable () -> Unit = {}) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.ink, modifier = Modifier.weight(1f))
            trailing()
        }
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = p.ink3, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 机器人状态：有事等你 → 等你；两个以上在跑 → 爆发；一个在跑 → 悬浮；都闲着 → 睡觉（与 Agents Island 同一套）。 */
fun botMood(vm: AppState): BotMood {
    val busy = vm.sessions.count { it.busy }
    return when {
        vm.pending.isNotEmpty() -> BotMood.WAIT
        busy >= 2 -> BotMood.BUSY
        busy == 1 -> BotMood.RUN
        else -> BotMood.SLEEP
    }
}

private data class Slot(val icon: ImageVector?, val label: String, val page: Page?, val action: (() -> Unit)? = null)

/**
 * 悬浮玻璃底栏：新建、历史、[机器人首页]、数据、设置，在一级页面常驻。
 * 10-27 “刷新”换成“数据”（仪表盘）：再点一次首页即刷新。
 * 选中气泡（参照 iOS 26 底栏）：静止时是纯色磨砂的暖色胶囊，不取样后面的内容；
 * 换页移动时“抬起”成透镜——变大溢出底栏、全清透、放大 + 折射 + 色散，像在底栏上开了扇窗，折射的是后面页面的文字
 * （不是底栏自己的图标；图标画在透镜之上、按距离放大）；到位后缩回纯色磨砂。按 UXM 回弹弹簧滑动，速度越快拉得越长。
 * 进会话后收拢成左下角一颗机器人圆钮（collapsed），输入框从它旁边分离出来（见 SessionScreen）。
 */
@Composable
fun Dock(vm: AppState, collapsed: Boolean, modifier: Modifier = Modifier, onApproval: (Approval) -> Unit = {}) {
    val p = LocalPalm.current
    val slots = listOf(
        Slot(AppIcons.plus, "新建", Page.New),
        Slot(AppIcons.history, "历史", Page.History),
        Slot(null, "首页", Page.Home),
        Slot(AppIcons.chartBar, "数据", Page.Stats),
        Slot(AppIcons.settings, "设置", Page.Settings),
    )
    val sel = slots.indexOfFirst { it.page == vm.page }.let { if (it < 0) 2 else it }
    val x = remember { Animatable(sel.toFloat()) }
    val lift = remember { Animatable(0f) }                               // 0 = 静止纯色磨砂，1 = 抬起的清透透镜
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(sel) {
        if (first) { first = false; x.snapTo(sel.toFloat()); return@LaunchedEffect }   // 首次出现不抬
        launch { lift.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f)) }
        x.animateTo(sel.toFloat(), Motion.spring())
        lift.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 240f))
    }
    val stretch = (abs(x.velocity) * 0.09f).coerceAtMost(0.55f)
    val itemsAlpha by animateFloatAsState(if (collapsed) 0f else 1f, Motion.fade(), label = "items")
    val mood = botMood(vm)
    var wake by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    // 审批探头：有待审批时机器人从底栏里向上探出，回弹略过冲；点它直接打开审批卡
    val peek by animateDpAsState(if (vm.pending.isNotEmpty() && !collapsed) (-16).dp else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 260f), label = "peek")

    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val full = maxWidth
        val w by animateDpAsState(if (collapsed) DockMini else full, Motion.spring(), label = "dockW")
        val h by animateDpAsState(if (collapsed) DockMini else DockHeight, Motion.spring(), label = "dockH")
        val slotW = full / slots.size
        val l = lift.value
        Box(Modifier.width(w).height(h)) {
            // 底栏本体（玻璃：磨砂主体 + 收窄的清透折射边 + 色散）
            Box(Modifier.fillMaxWidth().fillMaxHeight().shadow(18.dp, RoundedCornerShape(32.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
                .glass(RoundedCornerShape(32.dp)))
            if (itemsAlpha > 0.01f) Box(Modifier.width(w).height(DockHeight).clip(RoundedCornerShape(32.dp))) {
                // 静止气泡：纯色磨砂暖色胶囊（不取样后面的内容）；透镜抬起时淡出
                val dropW = slotW * 0.86f * (1f + stretch)
                Box(Modifier.offset(x = slotW * x.value + (slotW - dropW) / 2, y = 6.dp).width(dropW).height(DockHeight - 12.dp)
                    .graphicsLayer { alpha = itemsAlpha * (1f - l * 0.9f); scaleY = 1f - stretch * 0.18f }
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.55f), 1f to p.accent.copy(alpha = 0.16f)))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(26.dp)))
            }
            // 移动中抬起的透镜：变大溢出底栏（×1.3 宽、×1.42 高）。溢出底栏的部分清透 + 放大 + 折射 + 色散，透出后面页面的文字；
            // 压在底栏上的部分保持磨砂（只有移出工具栏的部分透视）
            if (l > 0.01f && itemsAlpha > 0.01f) {
                val w0 = slotW * 0.86f * (1f + stretch); val h0 = DockHeight - 12.dp
                val lw = w0 * (1f + 0.35f * l); val lh = h0 * (1f + 0.75f * l)   // 上下各溢出约 12dp，透视部分看得见
                val cx = slotW * x.value + slotW / 2
                val ox = cx - lw / 2; val oy = (DockHeight - lh) / 2
                val la = (l / 0.35f).coerceIn(0f, 1f) * itemsAlpha
                // 溢出底栏的部分：清透透镜，透出并折射后面页面的文字
                Box(Modifier.offset(x = ox, y = oy).width(lw).height(lh).graphicsLayer { alpha = la }
                    .clip(LensPart(DpOffset(-ox, -oy), w, DockHeight, 32.dp, inside = false))
                    .glass(RoundedCornerShape(lh / 2), GlassSpec.Lens))
                // 压在底栏上的部分：保持磨砂暖色（与静止气泡同质感），不透视
                Box(Modifier.offset(x = ox, y = oy).width(lw).height(lh).graphicsLayer { alpha = la }
                    .clip(LensPart(DpOffset(-ox, -oy), w, DockHeight, 32.dp, inside = true))
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.62f), 1f to p.accent.copy(alpha = 0.18f)))
                    .border(1.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(lh / 2)))
            }
            // 图标在透镜之上，保持清晰不被折射；透镜经过时按距离放大（iOS 实测约 16%）
            if (itemsAlpha > 0.01f) Row(Modifier.width(full).height(DockHeight).graphicsLayer { alpha = itemsAlpha }) {
                slots.forEachIndexed { i, s ->
                    Box(Modifier.width(slotW).fillMaxHeight().pressable(enabled = !collapsed) {
                        s.action?.invoke() ?: s.page?.let { vm.tab(it) }
                    }, contentAlignment = Alignment.Center) {
                        if (s.icon != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val on = i == sel
                            val rest by animateFloatAsState(if (on) 1.14f else 1f, Motion.spring(), label = "mag")
                            val near = (1f - abs(i - x.value)).coerceIn(0f, 1f)
                            val mag = rest + 0.16f * l * near
                            Ico(s.icon, 22.dp, if (on) p.accent else p.ink2,
                                Modifier.graphicsLayer { scaleX = mag; scaleY = mag })
                            Text(s.label, fontSize = 10.5.sp, color = if (on) p.accent else p.ink3,
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.graphicsLayer { scaleX = mag; scaleY = mag })
                        }
                    }
                }
            }
            // 机器人首页钮：展开时在正中，收拢时就是那颗圆钮本身
            // 待审批条数打在机器人屏幕上（?3），不再另挂红点；探头时不裁切，让它真的“探”出底栏
            // 彩蛋：睡着时 400ms 内连点两下 → 惊醒、左右看、又睡下（第一下照常回首页，第二下只叫醒，不拖慢单击）
            Box(Modifier.offset(x = (w - h) / 2, y = peek).size(h)
                .pressable {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (mood == BotMood.SLEEP && now - lastTap < 400) { wake++; lastTap = 0L; return@pressable }
                    lastTap = now
                    vm.pending.firstOrNull()?.let(onApproval) ?: vm.tab(Page.Home)
                }, contentAlignment = Alignment.Center) {
                Bot(mood, h * 0.8f, pending = vm.pending.size, flash = vm.botFlash, wake = wake)
            }
        }
    }
}


/** 透镜分两半：inside = 透镜 ∩ 底栏（磨砂），否则 = 透镜 − 底栏（清透）。坐标在透镜自身里，bar 为底栏相对透镜的位置。 */
private class LensPart(val bar: DpOffset, val barW: Dp, val barH: Dp, val barR: Dp, val inside: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val lens = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.height / 2))) }
        val b = Path().apply {
            addRoundRect(RoundRect(Rect(Offset(bar.x.toPx(), bar.y.toPx()), Size(barW.toPx(), barH.toPx())), CornerRadius(barR.toPx())))
        }
        Outline.Generic(Path.combine(if (inside) PathOperation.Intersect else PathOperation.Difference, lens, b))
    }
}
