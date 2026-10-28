package app.palminspire.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * 吉祥物 C 案矢量母版（由 tools/gen_bot.py 生成，勿手改；改造型改生成器参数后重跑）。
 * 坐标系 0~100，几何从概念图量出（tools/trace_bot.py）。机身四块是 SVG 路径，天线、腿、屏幕字是锚点参数。
 */
object BotArt {
    const val SIDE = "M71.00,22.60C72.77,21.53 76.28,22.90 78.60,23.60C80.92,24.30 83.23,25.43 84.90,26.80C86.57,28.17 87.72,29.83 88.60,31.80C89.48,33.77 89.85,36.48 90.20,38.60C90.55,40.72 90.58,42.45 90.70,44.50C90.82,46.55 90.87,48.78 90.90,50.90C90.93,53.02 90.93,55.10 90.90,57.20C90.87,59.30 90.92,61.43 90.70,63.50C90.48,65.57 90.32,67.85 89.60,69.60C88.88,71.35 87.73,72.80 86.40,74.00C85.07,75.20 83.23,76.00 81.60,76.80C79.97,77.60 78.20,78.40 76.60,78.80C75.00,79.20 73.43,79.67 72.00,79.20C70.57,78.73 68.67,84.20 68.00,76.00C67.33,67.80 67.50,38.90 68.00,30.00C68.50,21.10 69.23,23.67 71.00,22.60Z"
    const val FRONT = "M21.90,20.61Q43.43,19.20 64.90,21.39C71.23,21.51 76.29,26.77 76.16,33.10Q77.60,50.92 75.44,68.70C75.31,75.03 70.03,80.18 63.70,80.16Q42.37,81.90 21.10,80.04C14.77,80.02 9.67,74.83 9.75,68.50Q8.20,50.18 10.25,31.90C10.33,25.57 15.57,20.49 21.90,20.61Z"
    /** 正面上沿斜面高光：在正面内裁剪，正面向下错开 BEVEL_MID / BEVEL_HI 露出的上沿分别涂过渡色与亮色。 */
    const val BEVEL_HI = 3.8f
    const val BEVEL_MID = 2.0f
    const val SCREEN = "M22.80,29.35Q41.62,28.75 60.40,30.15C64.36,30.23 67.53,33.54 67.44,37.50Q67.99,50.07 66.86,62.60C66.77,66.56 63.46,69.74 59.50,69.66Q40.93,70.30 22.40,68.94C18.44,68.86 15.23,65.56 15.27,61.60Q14.56,48.98 15.53,36.40C15.57,32.44 18.84,29.27 22.80,29.35Z"
    val ANT_L_BASE = Offset(41.6f, 21.0f)
    val ANT_L_TIP = Offset(38.4f, 6.0f)
    val ANT_R_BASE = Offset(57.6f, 21.6f)
    val ANT_R_TIP = Offset(62.6f, 7.0f)
    const val STALK = 3.2f
    const val BALL = 4.5f
    val LEG_L = Offset(37.1f, 72.0f)
    val LEG_R = Offset(63.6f, 72.4f)
    const val LEG_W = 11.0f
    const val LEG_H = 17.2f
    val SHADOW = Offset(51.0f, 98.0f)
    const val SHADOW_RX = 21.5f
    const val SHADOW_RY = 1.6f
    /** 屏幕内框（左上、右下），屏幕字按它排版。 */
    val SCREEN_TL = Offset(15.2f, 29.2f)
    val SCREEN_BR = Offset(67.6f, 69.8f)
    /** 概念图悬浮态的字：3×5 粗方块的 > 起点、格距、方块边长，与实心光标条（x, y, 宽, 高）。 */
    val TEXT_ORIGIN = Offset(25.2f, 40.4f)
    const val PITCH = 2.9f
    const val CELL = 2.8f
    val BAR = floatArrayOf(37.6f, 55.9f, 10.2f, 3.0f)
    /** 机身锚点：挤压拉伸以底边中点为原点。 */
    val BODY_FOOT = Offset(43f, 80f)
    val front = Color(0xFFDF7A53)
    val frontHi = Color(0xFFE8875F)
    val frontLo = Color(0xFFD9714D)
    val top = Color(0xFFF3A97F)
    val side = Color(0xFFBE5F3D)
    val sideHi = Color(0xFFC8673F)
    val bevelMid = Color(0xFFEC9670)
    val screen = Color(0xFF302E37)
    val ink = Color(0xFFF6F2EE)
    val stalk = Color(0xFFC26843)
    val ball = Color(0xFFE17A53)
    val leg = Color(0xFFBF5F3D)
    val amber = Color(0xFFE8A33D)
    val shadow = Color(0xFFE07952)
}
