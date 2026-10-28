package app.palminspire

import app.palminspire.ui.Motion
import org.junit.Assert.assertEquals
import org.junit.Test

/** 动效令牌数值固定（改手感须同时改这里，防止误改）。 */
class MotionTest {
    @Test fun springsMatchUxm() {
        assertEquals(210f, Motion.SPRING_K); assertEquals(19f, Motion.SPRING_C)
        assertEquals(170f, Motion.SOFT_K); assertEquals(24f, Motion.SOFT_C)
        assertEquals(0.656f, Motion.springRatio, 0.002f)        // UXM：ζ≈0.66
        assertEquals(0.920f, Motion.softRatio, 0.002f)          // UXM：ζ≈0.92
    }

    @Test fun durationsMatchUxm() {
        assertEquals(120, Motion.PRESS_MS); assertEquals(0.955f, Motion.PRESS_SCALE)
        assertEquals(200, Motion.FADE_MS); assertEquals(250, Motion.COLOR_MS)
        assertEquals(800, Motion.ENTER_MS); assertEquals(14, Motion.ENTER_RISE_DP); assertEquals(50, Motion.STAGGER_MS)
        assertEquals(2400, Motion.TOAST_STAY_MS); assertEquals(1100, Motion.HOLD_MS)
        assertEquals(320, Motion.SHAKE_MS); assertEquals(3, Motion.SHAKE_DP); assertEquals(0.45f, Motion.DISABLED_ALPHA)
    }
}
