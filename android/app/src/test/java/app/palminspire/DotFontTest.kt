package app.palminspire

import app.palminspire.ui.DotFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotFontTest {
    @Test fun everyKpiCharacterHasAGlyph() {
        val shown = listOf(StatFmt.tokens(4_786_450_295L), StatFmt.hours(196_973), StatFmt.count(12_847), StatFmt.hours(2_700))
            .joinToString("") { it.first } + "0123456789.%:-+ "
        shown.forEach { assertTrue("缺字形：$it", DotFont.has(it)) }
    }

    @Test fun maskLayout() {
        val (cols, rows, m) = DotFont.mask("1.5")
        assertEquals(5 + 1 + 1 + 5 + 2, cols)                           // 字宽 + 字间空列（小数点前不空）+ 两侧边
        assertEquals(9, rows)
        assertTrue((0 until cols).none { m[it] || m[(rows - 1) * cols + it] })   // 上下边一圈熄灭
        assertTrue(m[7 * cols + 6])                                     // 小数点紧贴“1”：第 7 行（1 + 6）、第 6 列
        assertEquals(5 + 1 + 5 + 2, DotFont.mask("12").first)            // 普通字之间仍空一列
        assertEquals(DotFont.mask(" ").first, DotFont.mask("字").first)  // 缺字按空格
    }
}
