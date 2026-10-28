package app.palminspire.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.sqrt

/**
 * 动效令牌：一套经真机调校的交互手感基线。
 * 弹簧按“质量 1、刚度 k、阻尼 c”换算成 Compose 的阻尼比 ζ = c / (2√k)，与 Web 版同一手感。
 */
object Motion {
    const val SPRING_K = 210f            // 回弹：位移、缩放、弹层入场、按钮松开
    const val SPRING_C = 19f
    const val SOFT_K = 170f              // 平滑：颜色、淡入、进度（颜色永远不回弹）
    const val SOFT_C = 24f
    const val PRESS_MS = 120             // 按下快压
    const val PRESS_SCALE = 0.955f
    const val FADE_MS = 200
    const val COLOR_MS = 250
    const val ENTER_MS = 800             // 入场：上移 14dp 淡入，同组错峰 50ms
    const val ENTER_RISE_DP = 14
    const val STAGGER_MS = 50
    const val TOAST_STAY_MS = 2400       // 轻提示：上方 16dp 落入、停留 2.4s
    const val TOAST_DROP_DP = 16
    const val DIALOG_FROM_SCALE = 0.92f
    const val HOLD_MS = 1100             // 长按确认：只用于不可逆操作；中途松手 2 倍速回卷
    const val SHAKE_MS = 320             // 错误抖动：3dp、3 个来回
    const val SHAKE_DP = 3
    const val DISABLED_ALPHA = 0.45f

    fun dampingRatio(k: Float, c: Float) = c / (2f * sqrt(k))

    val springRatio = dampingRatio(SPRING_K, SPRING_C)   // ≈ 0.656
    val softRatio = dampingRatio(SOFT_K, SOFT_C)         // ≈ 0.920

    fun <T> spring(): SpringSpec<T> = spring(dampingRatio = springRatio, stiffness = SPRING_K)
    fun <T> soft(): SpringSpec<T> = spring(dampingRatio = softRatio, stiffness = SOFT_K)
    fun <T> press(): TweenSpec<T> = tween(PRESS_MS, easing = FastOutSlowInEasing)
    fun <T> fade(): TweenSpec<T> = tween(FADE_MS)
    fun <T> color(): TweenSpec<T> = tween(COLOR_MS)
    fun <T> hold(): TweenSpec<T> = tween(HOLD_MS, easing = LinearEasing)
    fun <T> holdRewind(): TweenSpec<T> = tween(HOLD_MS / 2, easing = LinearEasing)
}
