package app.palminspire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftBlockTest {
    @Test fun staleAndText() {
        assertTrue(DraftBlock("s", "q", 12, 3600, false).stale)                  // 出门留下的字：推荐清空后发送
        assertFalse(DraftBlock("s", "q", 12, 30, false).stale)
        assertFalse(DraftBlock("s", "q", 12, 600, true).stale)                   // 正在打字：不推荐清空
        assertEquals("电脑 5 分钟没动", DraftBlock("s", "q", 1, 300, false).idleText())
        assertEquals("电脑 2 小时没动", DraftBlock("s", "q", 1, 7300, false).idleText())
        assertEquals("电脑上正在打字", DraftBlock("s", "q", 1, 3, true).idleText())
    }
}
