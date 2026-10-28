package app.palminspire.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.palminspire.AppState
import app.palminspire.Page
import app.palminspire.SessionRow
import kotlinx.coroutines.launch

/**
 * 长按会话卡弹出的操作面板（“在电脑上打开”“关闭会话”藏在会话页右上角太深）。
 * 与审批抽屉同一套：底部升起的玻璃卡，单手拇指够得着；下拉或点外面收起。只放 2~4 个最常用的：
 * - 经宿主的：在电脑上打开 /（在跑时）中断当前操作 /（电脑终端里起的）保持后台 / 关闭会话（滑动确认，记录保留可续接）
 * - 只能看的：打开会话 / 分叉接着聊（电脑上的普通终端手机关不了）
 * - 都有：YOLO 模式开关（按住开、点一下关）
 */
@Composable
fun SessionActionsLayer(vm: AppState) {
    val sid = vm.actionsFor ?: return
    val s = vm.sessions.firstOrNull { it.sid == sid }
    if (s == null) { LaunchedEffect(sid) { vm.actionsFor = null }; return }     // 会话已结束：收起
    ActionSheet(vm, s) { vm.actionsFor = null }
}

@Composable
private fun ActionSheet(vm: AppState, s: SessionRow, onClose: () -> Unit) {
    val p = LocalPalm.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    val rise = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { rise.animateTo(1f, Motion.spring()) }
    fun run(action: () -> Unit) { action(); onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hidePx = with(LocalDensity.current) { (maxHeight * 0.6f).toPx() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f * rise.value))
            .pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .graphicsLayer { translationY = hidePx * (1 - rise.value) + drag.value }
            .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x22000000), spotColor = Color(0x40000000))
            .glass(RoundedCornerShape(28.dp), GlassSpec.Sheet)
            .pointerInput(Unit) { detectTapGestures { } }) {
            // 把手 + 抬头（哪个会话）：可下拉收起
            Column(Modifier.fillMaxWidth().pointerInput(s.sid) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (drag.value > 120f) onClose() else drag.animateTo(0f, Motion.spring()) } },
                    onVerticalDrag = { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } })
            }.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(p.ink3.copy(alpha = 0.35f)))
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title.ifEmpty { s.sid.take(8) }, style = MaterialTheme.typography.titleMedium, color = p.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Text(midEllipsis(s.shortCwd, 44), style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s.hosted) {
                    if (!s.starting) Action(AppIcons.terminal, "在电脑上打开", "弹终端窗口接上本会话，关窗口不影响会话") { run { vm.openOnPc(s.sid) } }
                    else Action(AppIcons.keyboard, "进入作答", "启动时停在确认框，进终端视图选择") { run { vm.go(Page.Session(s.sid)) } }
                    if (s.busy) Action(AppIcons.stop, "中断当前操作", "相当于在电脑上按 Esc，会话不关", p.warn) { run { vm.keys(s.sid, listOf("esc")) } }
                    if (s.attached && !s.starting) {
                        if (s.keepAlive) Action(AppIcons.rotateCcw, "取消保持后台", "现在：关电脑终端也不结束") { run { vm.keepAlive(s.sid, false) } }
                        else Action(AppIcons.monitor, "保持后台", "关掉电脑上的终端窗口，会话也继续跑") { run { vm.keepAlive(s.sid, true) } }
                    }
                    YoloAction(vm, s, ::run)
                    // 关闭改滑动（与“按住开启 YOLO”挨着，两个都是按住太危险）
                    SlideAction("滑动关闭会话", if (s.busy) "正在跑的操作会中断 · 记录保留可续接" else "记录保留，可在“历史”里续接") {
                        run { vm.closeSession(s.sid) }
                    }
                } else {
                    Action(AppIcons.eye, "打开会话", "看对话与进度") { run { vm.go(Page.Session(s.sid)) } }
                    Action(AppIcons.gitBranch, "分叉接着聊", "电脑后台另起一个手机能插话的副本，原会话不动") { run { vm.resume(s.sid, true) } }
                    YoloAction(vm, s, ::run)
                }
            }
        }
    }
}

@Composable
private fun ActionBody(icon: ImageVector, title: String, sub: String, tint: Color) {
    val p = LocalPalm.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Ico(icon, 20.dp, tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (tint == p.danger || tint == Brand.OrangeDeep) tint else p.ink)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, sub: String, tint: Color = LocalPalm.current.accent, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).pressable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        ActionBody(icon, title, sub, tint)
    }
}

/**
 * YOLO 模式：该会话的工具审批全部自动放行（含危险命令），选择题与计划照常问；与桌面岛 ⚡ 同一开关。
 * 开要按住（放权大），关点一下就行。
 */
@Composable
private fun YoloAction(vm: AppState, s: SessionRow, run: (() -> Unit) -> Unit) {
    if (s.yolo) Action(AppIcons.zap, "关闭 YOLO", "现在：工具审批自动放行中。点一下恢复逐条审批", Brand.OrangeDeep) { run { vm.setYolo(s.sid, false) } }
    else HoldAction(AppIcons.zap, "YOLO 模式", "按住 1 秒开启 · 工具审批全部自动放行（含危险命令），选择题和计划照常问",
        Brand.OrangeDeep) { run { vm.setYolo(s.sid, true) } }
}

/** 放权大 / 不可逆的项：按住 1.1 秒，颜色从左往右灌满才生效，中途松手回卷（同审批里的危险操作）。 */
@Composable
private fun HoldAction(icon: ImageVector, title: String, sub: String, color: Color = LocalPalm.current.danger, onConfirm: () -> Unit) {
    val p = LocalPalm.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.08f))
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, Motion.hold())
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                }
                tryAwaitRelease()
                if (progress.value < 1f) { job.cancel(); scope.launch { progress.animateTo(0f, Motion.holdRewind()) } }
            })
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(color.copy(alpha = 0.22f)))
        }
        Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { ActionBody(icon, title, sub, color) }
    }
}

/**
 * 滑动确认（关闭会话专用）：红色圆钮拖到右端（≥85%）才生效，松手不到位弹回；
 * 与“按住”区分开，旁边的 YOLO 按住开启时手指一滑也不会误关。
 */
@Composable
private fun SlideAction(title: String, sub: String, onConfirm: () -> Unit) {
    val p = LocalPalm.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp).height(60.dp).clip(RoundedCornerShape(30.dp)).background(p.dangerSoft)) {
        val knob = 52.dp
        val pad = 4.dp
        val travel = with(density) { (maxWidth - knob - pad * 2).toPx() }.coerceAtLeast(1f)
        val f = (x.value / travel).coerceIn(0f, 1f)
        Box(Modifier.fillMaxHeight().width(with(density) { x.value.toDp() } + knob + pad * 2).clip(RoundedCornerShape(30.dp))
            .background(p.danger.copy(alpha = 0.16f)))
        Column(Modifier.fillMaxSize().padding(start = knob + pad + 14.dp, end = 16.dp).graphicsLayer { alpha = (1f - f * 1.6f).coerceIn(0f, 1f) },
            verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.danger)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(pad).offset { IntOffset(x.value.roundToInt(), 0) }.size(knob).clip(CircleShape).background(p.danger)
            .pointerInput(travel) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (!done && x.value >= travel * 0.85f) {
                                done = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                x.animateTo(travel, Motion.spring())
                                onConfirm()
                            } else x.animateTo(0f, Motion.spring())
                        }
                    },
                    onDragCancel = { scope.launch { x.animateTo(0f, Motion.spring()) } },
                    onHorizontalDrag = { ch, dx -> ch.consume(); scope.launch { x.snapTo((x.value + dx).coerceIn(0f, travel)) } })
            }, contentAlignment = Alignment.Center) {
            Ico(AppIcons.power, 22.dp, Color.White)
        }
    }
}
