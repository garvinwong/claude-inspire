package app.palminspire

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSpecTest {
    @Test fun everyScreenCharacterHasAGlyph() {
        val used = BotMood.values().flatMap { m -> (0..12).flatMap { BotPoses.screen(m, it, 900f) + BotPoses.screen(m, it, 0f) } }.joinToString("") +
            "ok" + "!!" + "> _ _" + ">new" + "0123456789+" + BotGlyphs.CODE + "z" +
            listOf(0f, 800f, 1800f, 2500f, 3000f).joinToString("") { BotWake.at(it)!!.screen }
        used.forEach { assertTrue("缺字形：$it", BotGlyphs.has(it)) }
    }

    @Test fun glyphDotsAndExtent() {
        val prompt = BotGlyphs.dots(">")
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2, 1 to 3, 0 to 4), prompt)  // 与概念图一致：5 个对角相接的方块
        assertEquals((7 to 5), BotGlyphs.extent(listOf(">_")))              // 两字 = 3 + 1 + 3 格
        assertEquals((11 to 17), BotGlyphs.extent(listOf(">>>", ">>>", ">>>")))
        assertTrue(BotGlyphs.dots("_").all { it.second == 4 })
    }

    @Test fun pitchFitsScreenAndNeverExceedsConcept() {
        assertEquals(2.9f, BotGlyphs.pitch(listOf("?"), 51f, 40f, 0.6f, 2.9f))      // 大屏也不超过原图格距
        val p = BotGlyphs.pitch(listOf(">>>", ">>>", ">>>"), 51f, 40f, 0.8f, 2.9f)
        assertTrue(11 * p <= 51f * 0.8f + 1e-3 && 17 * p <= 40f * 0.8f + 1e-3)
    }

    @Test fun posesAndScreens() {
        assertEquals(listOf("?"), BotPoses.screen(BotMood.WAIT, 1, 900f))
        assertEquals(listOf("?"), BotPoses.screen(BotMood.WAIT, 3, 100f))     // ? 与条数交替
        assertEquals(listOf("3"), BotPoses.screen(BotMood.WAIT, 3, 900f))
        assertEquals(listOf("9+"), BotPoses.screen(BotMood.WAIT, 12, 900f))
        assertTrue(BotPoses.of(BotMood.SLEEP).antennaDeg > 45f && BotPoses.of(BotMood.SLEEP).bodyY > 0.05f)   // 睡觉落地
        val amp = (0 until 400).maxOf { kotlin.math.abs(BotMotion.jitter(BotMood.BUSY, it * 2.5f)) }
        assertTrue("爆发抖幅 $amp", amp > 1.0f)                                                            // 高能抖动
        assertEquals(0f, BotMotion.jitterRot(BotMood.RUN, 100f))
        // 等你、睡觉不冻住：摇摆与呼吸随时间变化
        assertTrue(BotMotion.sway(BotMood.WAIT, 700f) > 5f && BotMotion.sway(BotMood.WAIT, 2100f) < -5f)
        assertTrue(BotMotion.breathe(BotMood.SLEEP, 1050f) > 0.02f && BotMotion.breathe(BotMood.SLEEP, 3150f) < -0.02f)
        assertEquals(0f, BotMotion.sway(BotMood.RUN, 700f))
        assertTrue(BotPoses.of(BotMood.WAIT).bodyY < 0f)                   // 等你：抬头
        assertTrue(BotPoses.of(BotMood.BUSY).antennaDeg > BotPoses.of(BotMood.RUN).antennaDeg)
        assertTrue(BotPoses.tilt(BotMood.WAIT) < 0f && BotPoses.tilt(BotMood.RUN) == 0f)
    }

    @Test fun wakeEasterEgg() {
        assertEquals("!", BotWake.at(100f)!!.screen)                              // 惊醒
        assertTrue(BotWake.at(1000f)!!.look < 0f && BotWake.at(1800f)!!.look > 0f)  // 先左后右
        assertEquals("...", BotWake.at(2500f)!!.screen)                           // 没事
        assertEquals(0f, BotWake.at(BotWake.SETTLE_AT + 10f)!!.awake)             // 又睡下
        assertEquals(null, BotWake.at(BotWake.DUR))                               // 演完交还睡觉态
        assertEquals(null, BotWake.at(-1f))
    }

    @Test fun finishedMeansWasBusyNowIdleAndStillThere() {
        fun row(sid: String, status: String) = SessionRow.parse(JSONObject("""{"sid":"$sid","status":"$status"}"""))
        val now = listOf(row("a", "idle"), row("b", "busy"), row("c", "idle"))
        assertEquals(setOf("a"), finishedSessions(setOf("a", "b", "gone"), now))
        assertEquals(emptySet<String>(), finishedSessions(emptySet(), now))
    }
}
