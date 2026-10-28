package app.palminspire

import app.palminspire.ui.recommended
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendedTest {
    private fun q(multi: Boolean, vararg labels: String) = Question("问", "", multi, labels.map { Option(it, "") })

    @Test fun preselectRecommended() {
        assertEquals(setOf("长图（推荐）"), recommended(q(false, "长图（推荐）", "单页仪表盘")))
        assertEquals(setOf("Long image (Recommended)"), recommended(q(false, "Short", "Long image (Recommended)")))
        assertEquals(setOf("A（推荐）"), recommended(q(false, "A（推荐）", "B（推荐）")))          // 单选只取第一个
        assertEquals(setOf("A（推荐）", "B（推荐）"), recommended(q(true, "A（推荐）", "B（推荐）", "C")))
        assertEquals(emptySet<String>(), recommended(q(false, "A", "B")))                    // 没有推荐项就不预选
    }
}
