package app.palminspire

import app.palminspire.ui.midEllipsis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test fun pathMiddleEllipsis() {
        assertEquals("my-workspace", midEllipsis("my-workspace", 40))
        val m = midEllipsis(".claude/projects/-home-alice-workspace/memory/very/deep", 30)
        assertEquals(30, m.length)
        assertTrue(m.startsWith(".claude/") && m.endsWith("very/deep") && "…" in m)
    }
}

class UpdatedAgoTest {
    @Test fun text() {
        org.junit.Assert.assertEquals("刚刚更新", app.palminspire.ui.updatedAgo(30))
        org.junit.Assert.assertEquals("12 分钟前更新", app.palminspire.ui.updatedAgo(12 * 60 + 5))
        org.junit.Assert.assertEquals("23 小时前更新", app.palminspire.ui.updatedAgo(23 * 3600 + 100))
        org.junit.Assert.assertEquals("1 天前更新", app.palminspire.ui.updatedAgo(24 * 3600 + 100))
        org.junit.Assert.assertEquals("2 天前更新", app.palminspire.ui.updatedAgo(2 * 86400 + 5))
    }
}
