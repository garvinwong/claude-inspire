package app.palminspire.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.palminspire.Ev
import app.palminspire.Img
import app.palminspire.Row as TRow
import app.palminspire.Timeline
import org.json.JSONObject

typealias ImageLoader = suspend (String) -> ImageBitmap?

@Composable
fun EventRow(row: TRow, load: ImageLoader) {
    val e = row.ev
    when (e.k) {
        "user" -> UserBubble(e.text)
        "text" -> Markdown(e.text, Modifier.fillMaxWidth())
        "thinking" -> Thinking(e.text)
        "tool" -> ToolCard(e, row.result, load)
        "group" -> ToolGroup(row, load)
        "helper" -> HelperCard(e, row.result)
        "todo" -> TodoCard(e)
        "tool_result" -> ResultBlock(e, load)
        "image" -> Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { e.images.forEach { Thumb(it, load) } }
        "command" -> Chip(AppIcons.terminal, e.text, alignEnd = true)
        "command_output" -> CodeBox(e.text, "")
        "queued" -> Chip(AppIcons.hourglass, "排队中：${e.text}", alignEnd = true)
        "mode" -> CenterNote("权限模式：${modeName(e.mode)}")
        "system" -> CenterNote(e.text, e.level == "error")
        else -> {}
    }
}

/** 连续工具调用折叠成组：组头“连续 N 项操作”+ 摘要；有出错默认展开并标红；还有没跑完的转圈。 */
@Composable
private fun ToolGroup(row: TRow, load: ImageLoader) {
    val p = LocalPalm.current
    val g = row.group
    val errors = g.count { it.result?.isError == true }
    val running = g.any { it.result == null }
    var open by remember(row.ev.id) { mutableStateOf(errors > 0) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp))
        .background(if (open) p.surface2 else p.surface).animateContentSize(Motion.spring())) {
        Row(Modifier.fillMaxWidth().pressable { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Ico(AppIcons.layers, 18.dp, p.ink2)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("连续 ${g.size} 项操作", style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1)
                Text(Timeline.groupSummary(g) + if (errors > 0) " · $errors 项出错" else "", style = MaterialTheme.typography.bodySmall,
                    color = if (errors > 0) p.danger else p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                running -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = p.accent)
                errors > 0 -> Ico(AppIcons.alertCircle, 18.dp, p.danger)
                else -> Ico(AppIcons.checkCircle, 18.dp, p.ok)
            }
            Spacer(Modifier.width(6.dp))
            Ico(if (open) AppIcons.chevronUp else AppIcons.chevronDown, 18.dp, p.ink3)
        }
        if (open) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.forEach { r -> ToolCard(r.ev, r.result, load) }
        }
    }
}

fun modeName(m: String) = when (m) {
    "default" -> "逐项确认"; "acceptEdits" -> "自动接受改动"; "plan" -> "计划模式"; "auto" -> "自动"
    "bypassPermissions" -> "全部放行"; "dontAsk" -> "不再询问"; else -> m
}

@Composable
private fun UserBubble(text: String) {
    val p = LocalPalm.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = p.ink,
            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(p.userBubble).padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, alignEnd: Boolean = false) {
    val p = LocalPalm.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(p.surface2).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Ico(icon, 14.dp, p.ink2); Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = p.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CenterNote(text: String, error: Boolean = false) {
    val p = LocalPalm.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (error) p.danger else p.ink3)
    }
}

@Composable
private fun Thinking(text: String) {
    val p = LocalPalm.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(Motion.spring())) {
        Row(Modifier.pressable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(AppIcons.sparkles, 14.dp, p.ink3); Spacer(Modifier.width(6.dp))
            Text(if (open) "思考（收起）" else "思考（展开）", style = MaterialTheme.typography.labelMedium, color = p.ink3)
        }
        if (open) Text(text, style = MaterialTheme.typography.bodyMedium, color = p.ink2, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    }
}

fun toolIcon(name: String): ImageVector = when {
    name == "Bash" -> AppIcons.terminal
    name == "Read" || name == "NotebookRead" -> AppIcons.eye
    name in setOf("Edit", "MultiEdit", "NotebookEdit") -> AppIcons.edit
    name == "Write" -> AppIcons.file
    name in setOf("Grep", "Glob") -> AppIcons.search
    name in setOf("WebFetch", "WebSearch") -> AppIcons.globe
    name in setOf("Task", "Agent") -> AppIcons.bot
    name == "AskUserQuestion" -> AppIcons.helpCircle
    name == "ExitPlanMode" -> AppIcons.listCheck
    name.startsWith("mcp__") -> AppIcons.layers
    else -> AppIcons.wrench
}

private fun base(path: String) = path.trimEnd('/').substringAfterLast('/')

fun toolLine(name: String, s: JSONObject): String = when (name) {
    "Bash" -> s.optString("desc").ifEmpty { s.optString("command") }
    "Read", "NotebookRead" -> "读 ${base(s.optString("path"))}"
    "Edit", "MultiEdit", "NotebookEdit" -> "改 ${base(s.optString("path"))}"
    "Write" -> "写 ${base(s.optString("path"))}"
    "Grep", "Glob" -> "搜 ${s.optString("pattern")}"
    "WebFetch" -> s.optString("url").removePrefix("https://").removePrefix("http://").substringBefore('/')
    "WebSearch" -> "搜 ${s.optString("query")}"
    "Task", "Agent" -> "子代理：${s.optString("desc")}"
    "AskUserQuestion" -> "向你提问"
    "ExitPlanMode" -> "提交计划"
    "TodoWrite" -> "更新待办"
    else -> name.removePrefix("mcp__").replace("__", " · ")
}

@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.line, RoundedCornerShape(14.dp))
        .background(p.surface).animateContentSize(Motion.spring())) { content() }
}

@Composable
private fun ToolCard(e: Ev, result: Ev?, load: ImageLoader) {
    val p = LocalPalm.current
    var open by remember { mutableStateOf(false) }
    val s = e.summary
    CardFrame {
        Row(Modifier.fillMaxWidth().pressable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Ico(toolIcon(e.name), 16.dp, p.ink2); Spacer(Modifier.width(8.dp))
            Text(toolLine(e.name, s), style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                result == null -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent)
                result.isError -> Ico(AppIcons.xCircle, 16.dp, p.danger)
                else -> Ico(AppIcons.checkCircle, 16.dp, p.ok)
            }
            Spacer(Modifier.width(6.dp))
            Ico(if (open) AppIcons.chevronUp else AppIcons.chevronDown, 16.dp, p.ink3)
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (e.name) {
                "Bash" -> CodeBox(s.optString("command"), "bash")
                "Edit", "MultiEdit" -> { Path(s.optString("path")); Diff(s.optString("old"), s.optString("new")) }
                "Write" -> { Path(s.optString("path")); CodeBox(s.optString("head"), "") }
                "Read", "NotebookRead" -> Path(s.optString("path"))
                "Task", "Agent" -> Text(s.optString("prompt"), style = MaterialTheme.typography.bodySmall, color = p.ink2)
                else -> if (s.length() > 0) CodeBox(s.toString(2), "")
            }
            if (result != null) ResultBlock(result, load)
        }
    }
}

@Composable
private fun Path(path: String) {
    if (path.isEmpty()) return
    Text(path, fontFamily = Mono, fontSize = 12.sp, color = LocalPalm.current.ink2)
}

@Composable
fun Diff(old: String, new: String) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.code).horizontalScroll(rememberScrollState())
        .padding(vertical = 6.dp)) {
        old.lines().take(40).forEach { Text("- $it", fontFamily = Mono, fontSize = 12.sp, color = p.danger,
            modifier = Modifier.background(p.dangerSoft).padding(horizontal = 8.dp)) }
        new.lines().take(40).forEach { Text("+ $it", fontFamily = Mono, fontSize = 12.sp, color = p.ok,
            modifier = Modifier.background(p.okSoft).padding(horizontal = 8.dp)) }
    }
}

@Composable
private fun ResultBlock(r: Ev, load: ImageLoader) {
    val p = LocalPalm.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.text.isNotBlank()) {
            Text(r.text, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = if (r.isError) p.danger else p.ink2,
                maxLines = 30, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (r.isError) p.dangerSoft else p.code).padding(10.dp))
        }
        if (r.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { r.images.forEach { Thumb(it, load) } }
    }
}

private fun helperName(a: String) = when (a) {
    "codex" -> "Codex"; "kimi" -> "Kimi"; "gemini" -> "Gemini"; else -> a
}

@Composable
private fun HelperCard(e: Ev, result: Ev?) {
    val p = LocalPalm.current
    var open by remember { mutableStateOf(false) }
    CardFrame {
        Row(Modifier.fillMaxWidth().pressable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Badge(helperName(e.agent), p.info, p.infoSoft); Spacer(Modifier.width(8.dp))
            Text(e.desc.ifEmpty { "助手处理中" }, style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (result == null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.info)
            else Ico(if (result.isError) AppIcons.xCircle else AppIcons.checkCircle, 16.dp, if (result.isError) p.danger else p.ok)
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CodeBox(e.command, "")
            if (result != null && result.text.isNotBlank()) Markdown(result.text.take(6000))
        }
    }
}

@Composable
private fun TodoCard(e: Ev) {
    val p = LocalPalm.current
    val done = e.todos.count { it.status == "completed" }
    CardFrame {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("待办 $done / ${e.todos.size}", style = MaterialTheme.typography.labelMedium, color = p.ink2)
            e.todos.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (t.status) {
                        "completed" -> Ico(AppIcons.checkCircle, 16.dp, p.ok)
                        "in_progress" -> Ico(AppIcons.loader, 16.dp, p.accent)
                        else -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).border(1.5.dp, p.ink3, RoundedCornerShape(7.dp)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(t.text, style = MaterialTheme.typography.bodyMedium,
                        color = if (t.status == "completed") p.ink3 else p.ink,
                        fontWeight = if (t.status == "in_progress") FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun Thumb(img: Img, load: ImageLoader) {
    val p = LocalPalm.current
    var bmp by remember(img.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(img.id) { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(img.id) { bmp = load(img.id); failed = bmp == null }
    Box(Modifier.size(132.dp).clip(RoundedCornerShape(12.dp)).background(p.surface2).pressable(bmp != null) { full = true },
        contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            failed -> Ico(AppIcons.image, 22.dp, p.ink3)
            else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.accent)
        }
    }
    val b = bmp
    if (full && b != null) ImageViewer(b) { full = false }
}

@Composable
fun ImageViewer(bmp: ImageBitmap, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val st = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 6f); ox += pan.x; oy += pan.y }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pressable(onClick = onClose), contentAlignment = Alignment.Center) {
            Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 2000.dp)
                .transformable(st).graphicsLayer { scaleX = scale; scaleY = scale; translationX = ox; translationY = oy })
        }
    }
}
