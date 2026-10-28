package app.palminspire.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** 弹出框的一项。 */
data class MenuItem(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 自封装的玻璃弹出框（不用安卓原生菜单）。从按钮位置“长”出来：
 * 回弹弹簧缩放 + 淡入，缩放原点是按钮那个角；点外面任意处收起。由页面放一个 PopoverHost，按钮调 show。
 */
@Stable
class PopoverState {
    var items by mutableStateOf<List<MenuItem>?>(null); private set
    var anchor by mutableStateOf(Rect.Zero); private set
    var above by mutableStateOf(true); private set
    var alignEnd by mutableStateOf(false); private set
    var serial by mutableStateOf(0); private set

    var minWidth by mutableStateOf(176.dp); private set

    fun show(anchor: Rect, items: List<MenuItem>, above: Boolean, alignEnd: Boolean, minWidth: androidx.compose.ui.unit.Dp = 176.dp) {
        this.anchor = anchor; this.items = items; this.above = above; this.alignEnd = alignEnd; this.minWidth = minWidth; serial++
    }

    fun dismiss() { items = null }
}

val LocalPopover = staticCompositionLocalOf<PopoverState?> { null }

/** 记下按钮在根坐标里的位置，供弹出框对齐。 */
fun Modifier.anchorTo(onRect: (Rect) -> Unit): Modifier = onGloballyPositioned { onRect(it.boundsInRoot()) }

/** 放在页面根 Box 里（根坐标 = 本 Box 坐标），且在页面内容的取样层之外（玻璃不能取样包含自己的层）。 */
@Composable
fun PopoverHost(state: PopoverState) {
    val items = state.items ?: return
    val p = LocalPalm.current
    val grow = remember(state.serial) { Animatable(0f) }
    LaunchedEffect(state.serial) { grow.animateTo(1f, Motion.spring()) }
    val gap = 8.dp
    Box(Modifier.fillMaxSize().pointerInput(state.serial) { detectTapGestures { state.dismiss() } }) {
        Column(Modifier
            .layout { m, c ->
                val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
                val g = gap.roundToPx()
                val margin = 12.dp.roundToPx()
                val x = (if (state.alignEnd) state.anchor.right.toInt() - pl.width else state.anchor.left.toInt())
                    .coerceIn(margin, (c.maxWidth - pl.width - margin).coerceAtLeast(margin))
                val y = if (state.above) state.anchor.top.toInt() - g - pl.height else state.anchor.bottom.toInt() + g
                layout(c.maxWidth, c.maxHeight) { pl.place(x, y.coerceAtLeast(margin)) }
            }
            .graphicsLayer {
                val v = grow.value
                scaleX = 0.6f + 0.4f * v; scaleY = 0.6f + 0.4f * v; alpha = v.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(if (state.alignEnd) 1f else 0f, if (state.above) 1f else 0f)
            }
            .width(IntrinsicSize.Max).widthIn(min = state.minWidth)
            .shadow(22.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x22000000), spotColor = Color(0x38000000))
            .glass(RoundedCornerShape(20.dp), GlassSpec.Sheet)
            .pointerInput(Unit) { detectTapGestures { } }              // 点在弹出框空白处不收起
            .padding(vertical = 6.dp)) {
            items.forEachIndexed { i, it ->
                // 危险项与上面的项之间加分隔线（评审意见：红色“中断”紧挨普通项，容易误点）
                if (it.danger && i > 0 && !items[i - 1].danger) Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth()
                    .height(1.dp).background(p.line))
                Row(Modifier.fillMaxWidth().pressable { state.dismiss(); it.onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Ico(it.icon, 19.dp, if (it.danger) p.danger else p.ink2)
                    Spacer(Modifier.width(12.dp))
                    Text(it.label, style = MaterialTheme.typography.bodyLarge, color = if (it.danger) p.danger else p.ink)
                }
            }
        }
    }
}
