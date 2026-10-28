package app.palminspire

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    private val cmds = listOf(
        Cmd("/clear", "清空上下文", "内置", false),
        Cmd("/compact", "压缩上下文", "内置", false),
        Cmd("/model", "切换模型", "内置", true),
        Cmd("/code-animation", "码上动画", "项目技能", false),
        Cmd("/doc:docx", "Word 文档", "插件 doc", false),
    )

    @Test fun modeDetection() {
        assertEquals(Suggest.Mode.Slash("co"), Suggest.mode("/co", 3))
        assertEquals(Suggest.Mode.Slash(""), Suggest.mode("/", 1))
        assertEquals(Suggest.Mode.None, Suggest.mode("/compact 总结一下", 12))     // 命令后面在写参数
        assertEquals(Suggest.Mode.None, Suggest.mode("看看 /co", 6))               // 斜杠只在开头才算命令
        assertEquals(Suggest.Mode.File("src/ma"), Suggest.mode("读一下 @src/ma", 11))
        assertEquals(Suggest.Mode.File(""), Suggest.mode("@", 1))
        assertEquals(Suggest.Mode.None, Suggest.mode("邮箱 a@b.com 不是", 10))
        assertEquals(Suggest.Mode.Slash("cl"), Suggest.mode("/clear", 3))          // 只看光标前
    }

    @Test fun filterRanksNameBeforeDescription() {
        assertEquals(listOf("/compact", "/code-animation"), Suggest.filter(cmds, "co").map { it.name }.take(2))
        assertEquals(listOf("/doc:docx"), Suggest.filter(cmds, "DOCX").map { it.name })
        assertEquals(listOf("/clear", "/compact"), Suggest.filter(cmds, "上下文").map { it.name })
        assertEquals(cmds, Suggest.filter(cmds, ""))
    }

    @Test fun applyCommandAndFile() {
        assertEquals("/compact " to 9, Suggest.applyCommand("/co", 3, "/compact"))
        assertEquals("/compact 留下结论" to 9, Suggest.applyCommand("/co 留下结论", 3, "/compact"))
        val (t, c) = Suggest.applyFile("读一下 @src/ma 再说", 11, "src/main.py")
        assertEquals("读一下 @src/main.py  再说", t)
        assertEquals("读一下 @src/main.py ".length, c)
    }

    @Test fun menuCommands() {
        assertTrue(Suggest.opensMenu("/model", cmds))
        assertTrue(Suggest.opensMenu(" /resume ", emptyList()))                     // 还没取到清单时用兜底表
        assertFalse(Suggest.opensMenu("/compact 总结", cmds))
        assertFalse(Suggest.opensMenu("把 /model 换掉", cmds))
        assertEquals(true, Cmd.parse(JSONObject("""{"name":"/model","desc":"切换模型","src":"内置","menu":true}""")).menu)
    }
}

class TermGlyphsTest {
    /** 真机实测：“⏵⏵ auto mode on” 显示成两个方框——随包等宽字体与部分系统字体都没有 U+23F5。 */
    @Test fun missingGlyphsAreReplacedOneForOne() {
        assertEquals("▸▸ auto mode on", TermGlyphs.fix("⏵⏵ auto mode on"))
        assertEquals("  └ 结果", TermGlyphs.fix("  ⎿ 结果"))
        assertEquals("● Bash(ls)", TermGlyphs.fix("⏺ Bash(ls)"))
        val all = "⏵⏴⎿⏺⧉⏸⏹⎯⧖"
        assertEquals(all.length, TermGlyphs.fix(all).length)                       // 一格换一格，不破坏对齐
        assertTrue(TermGlyphs.fix(all).none { it in all })
        assertEquals("普通文字 ❯ ✻", TermGlyphs.fix("普通文字 ❯ ✻"))
    }
}

class NewOptionsTest {
    @Test fun labelsSayWhatDefaultMeans() {
        assertEquals("Opus 1M", NewOptions.modelLabel("opus[1m]"))
        assertEquals("Opus", NewOptions.modelLabel("opus"))
        assertEquals("Sonnet 5.5", NewOptions.modelLabel("claude-sonnet-5-5"))
        assertEquals("", NewOptions.modelLabel(""))
        assertEquals("超高", NewOptions.effortLabel("xhigh"))
        assertEquals("weird", NewOptions.effortLabel("weird"))
    }

    @Test fun parse() {
        val o = NewOptions.parse(JSONObject("""{"accounts":[{"account":"main","model":"opus[1m]","effort":"high","fable":true}],
            "efforts":["low","high"],"default_dir":"/home/alice/projects","dirs":["/a"]}"""))
        assertEquals("opus[1m]", o.accounts.first().model)
        assertTrue(o.of("main")!!.fable)
        assertEquals(listOf("low", "high"), o.efforts)
        assertEquals("/home/alice/projects", o.defaultDir)
        assertEquals(null, o.of("other"))
    }

    @Test fun shortPathDropsHomeAndDrivePrefix() {
        assertEquals("wt/x", app.palminspire.shortPath("/home/alice/wt/x"))
        assertEquals("code/app", app.palminspire.shortPath("/Users/bob/code/app"))
        assertEquals("work/repo", app.palminspire.shortPath("/mnt/d/work/repo"))
        assertEquals("/opt/x", app.palminspire.shortPath("/opt/x"))
    }
}
