package app.palminspire.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 按压手感：按下 .12s 快压到 .955，松开走回弹（UXM 原则 3）。 */
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) Motion.PRESS_SCALE else 1f,
        if (pressed) Motion.press() else Motion.spring(), label = "press")
    this.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else Motion.DISABLED_ALPHA }
        .then(if (enabled) Modifier.clickableNoRipple(src, role, onClick) else Modifier)
}

private fun Modifier.clickableNoRipple(src: MutableInteractionSource, role: Role, onClick: () -> Unit) =
    this.clickable(interactionSource = src, indication = null, role = role, onClick = onClick)

@Composable
fun Ico(v: ImageVector, size: Dp = 20.dp, tint: Color = LocalPalm.current.ink, modifier: Modifier = Modifier) =
    Icon(v, contentDescription = null, tint = tint, modifier = modifier.size(size))

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true,
                  color: Color = LocalPalm.current.accent, onClick: () -> Unit) {
    val p = LocalPalm.current
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(color).pressable(enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Ico(icon, 18.dp, p.onAccent); Spacer(Modifier.width(8.dp)) }
        Text(text, color = p.onAccent, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true,
                  color: Color = LocalPalm.current.ink, onClick: () -> Unit) {
    val p = LocalPalm.current
    Row(modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp)).background(p.surface)
        .pressable(enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Ico(icon, 18.dp, color); Spacer(Modifier.width(8.dp)) }
        Text(text, color = color, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

/** 长按确认（UXM 13 节）：按住 1.1s 从左向右线性填充，完成才触发；中途松手 2 倍速回卷、不生效。 */
@Composable
fun HoldButton(text: String, modifier: Modifier = Modifier, color: Color = LocalPalm.current.warn,
               fill: Color = LocalPalm.current.surface, onConfirm: () -> Unit) {
    val p = LocalPalm.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Box(modifier.clip(RoundedCornerShape(14.dp)).border(1.5.dp, color, RoundedCornerShape(14.dp)).background(fill)
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, Motion.hold())
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                    progress.snapTo(0f)
                }
                tryAwaitRelease()
                if (progress.value < 1f) { job.cancel(); scope.launch { progress.animateTo(0f, Motion.holdRewind()) } }
            })
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().padding(0.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(color.copy(alpha = 0.22f)))
        }
        Text(text, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable
fun Badge(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(text, color = fg, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** 状态点：工作中 = 主色呼吸（颜色不回弹，走平滑曲线的往返）；等你 = 琥珀；空闲 = 绿；其他 = 灰。 */
@Composable
fun StatusDot(status: String, size: Dp = 9.dp) {
    val p = LocalPalm.current
    val busy = status in app.palminspire.SessionRow.BUSY
    val color = when {
        busy -> p.accent
        status in setOf("waiting", "waiting_input", "needs_approval", "starting") -> p.warn
        status in setOf("idle", "ready") -> p.ok
        else -> p.ink3
    }
    val a = if (busy) rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath").value else 1f
    Box(Modifier.size(size).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

@Composable
fun UsageBar(label: String, pct: Int?, modifier: Modifier = Modifier) {
    val p = LocalPalm.current
    val v = (pct ?: 0).coerceIn(0, 100)
    val color = when { v >= 90 -> p.danger; v >= 70 -> p.warn; else -> p.accent }
    val w by animateFloatAsState(v / 100f, Motion.soft(), label = "usage")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = p.ink3, modifier = Modifier.width(22.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.surface2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(w).clip(RoundedCornerShape(3.dp)).background(color))
        }
        Text(if (pct == null) "—" else "$v%", style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = if (v >= 90) p.danger else p.ink2, fontWeight = FontWeight.Medium, modifier = Modifier.width(40.dp).padding(start = 6.dp))
    }
}

/** 入场：上移 14dp 淡入，同组按序号错峰 50ms（只在第一次出现时播）。 */
fun Modifier.enter(index: Int = 0): Modifier = composed {
    val rise = with(LocalDensity.current) { Motion.ENTER_RISE_DP.dp.toPx() }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(12) * Motion.STAGGER_MS).toLong())
        anim.animateTo(1f, tween(Motion.ENTER_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    graphicsLayer { alpha = anim.value; translationY = (1f - anim.value) * rise }
}

/** 轻提示：上方 16dp 落入（回弹），淡入；停留 2.4s 自动收起。 */
@Composable
fun ToastHost(text: String?, onDone: () -> Unit) {
    val p = LocalPalm.current
    var shown by remember { mutableStateOf<String?>(null) }
    val dropPx = with(LocalDensity.current) { Motion.TOAST_DROP_DP.dp.roundToPx() }
    LaunchedEffect(text) {
        if (text != null) { shown = text; delay(Motion.TOAST_STAY_MS.toLong()); shown = null; delay(300); onDone() }
    }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(shown != null,
            enter = slideInVertically(Motion.spring()) { -dropPx } + fadeIn(tween(250)),
            exit = fadeOut(tween(Motion.FADE_MS))) {
            Text(shown ?: "", color = if (p.dark) p.ink else Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (p.dark) p.surface2 else Color(0xE61C1B1A)).padding(horizontal = 16.dp, vertical = 11.dp))
        }
    }
}

/** 错误抖动：横向 3dp、3 个来回、.32s（与错误提示同时出现）。 */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val dx = with(LocalDensity.current) { Motion.SHAKE_DP.dp.toPx() }
    val a = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            val step = Motion.SHAKE_MS / 6
            for (t in listOf(1f, -1f, 1f, -1f, 1f, 0f)) a.animateTo(t, tween(step))
        }
    }
    graphicsLayer { translationX = a.value * dx }
}
