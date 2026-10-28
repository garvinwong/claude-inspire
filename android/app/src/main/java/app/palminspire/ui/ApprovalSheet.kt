package app.palminspire.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.palminspire.AppState
import app.palminspire.Approval
import app.palminspire.answersJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 审批 / 选择题 / 计划审阅（原来整页弹出，不醒目又挡操作，按钮全在最底下不好按）。
 * 新来的一条直接从底部升起抽屉（顶部横幅单手够不着，已取消）：悬浮玻璃卡，高度随内容，
 * 核心信息在上、详情折叠、按钮紧跟内容落在拇指区；下拉收起 = 稍后再说；多条左右切。
 * 画在主界面最上层、页面取样层之外（玻璃不能取样包含自己的层）。
 */
@Composable
fun ApprovalLayer(vm: AppState, open: Approval?, onOpen: (Approval) -> Unit, onClose: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val seen = remember { mutableStateListOf<String>() }                 // 已弹过横幅的
    val fresh = vm.pending.lastOrNull { it.id !in seen }
    if (open != null) {
        LaunchedEffect(open.id) { if (open.id !in seen) seen.add(open.id) }
        LaunchedEffect(vm.pending) { if (vm.pending.none { it.id == open.id }) onClose() }   // 别的设备已处理 / 过期
    }
    // 新来的一律直接从底部升起抽屉（顶部横幅单手够不着；选择题再问一次多余）
    if (open == null && fresh != null) {
        LaunchedEffect(fresh.id) { seen.add(fresh.id); onOpen(fresh) }
    }
    Box(Modifier.fillMaxSize()) {
        if (open != null) Drawer(vm, open, now, onOpen, onClose)
    }
}

private fun kindLabel(a: Approval) = when (a.kind) { "ask" -> "选择题"; "plan" -> "计划审阅"; else -> if (a.danger) "危险操作" else "待批准" }

/** 能在横幅 / 通知上一键批的：普通工具审批（危险操作要进抽屉长按，选择题、计划要看内容）。 */
fun quickApprovable(a: Approval) = a.kind == "perm" && !a.danger

@Composable
private fun KindBadge(a: Approval) {
    val p = LocalPalm.current
    val (fg, bg) = when {
        a.danger -> p.danger to p.dangerSoft
        a.kind == "perm" -> p.warn to p.warnSoft
        else -> p.accent to p.accentSoft
    }
    Badge(kindLabel(a), fg, bg)
}

@Composable
private fun Countdown(a: Approval, now: Long) {
    val p = LocalPalm.current
    a.remainingSeconds(now)?.let {
        Text("${it}s", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = if (it < 20) p.danger else p.ink3)
    }
}

// ── 抽屉 ──────────────────────────────────────────────────────
@Composable
private fun Drawer(vm: AppState, a: Approval, now: Long, onOpen: (Approval) -> Unit, onClose: () -> Unit) {
    val p = LocalPalm.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    val rise = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { rise.animateTo(1f, Motion.spring()) }
    val list = vm.pending
    val idx = list.indexOfFirst { it.id == a.id }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight * 0.78f
        val hidePx = with(LocalDensity.current) { (maxH + 40.dp).toPx() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f * rise.value))
            .pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .graphicsLayer { translationY = hidePx * (1 - rise.value) + drag.value }
            .heightIn(max = maxH)
            .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x22000000), spotColor = Color(0x40000000))
            .glass(RoundedCornerShape(28.dp), GlassSpec.Sheet)
            .pointerInput(Unit) { detectTapGestures { } }) {
            // 把手 + 抬头：可下拉收起
            Column(Modifier.fillMaxWidth().pointerInput(a.id) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (drag.value > 140f) onClose() else drag.animateTo(0f, Motion.spring()) } },
                    onVerticalDrag = { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } })
            }.padding(start = 18.dp, end = 14.dp, top = 8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(p.ink3.copy(alpha = 0.35f)))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(a)
                    Text(listOf(a.title, a.project).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                        color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    if (list.size > 1 && idx >= 0) {                          // 多条：左右切换
                        Box(Modifier.clip(CircleShape).pressable(idx > 0) { onOpen(list[idx - 1]) }.padding(6.dp)) {
                            Ico(AppIcons.chevronLeft, 16.dp, if (idx > 0) p.ink2 else p.line)
                        }
                        Text("${idx + 1}/${list.size}", style = MaterialTheme.typography.labelMedium, color = p.ink2)
                        Box(Modifier.clip(CircleShape).pressable(idx < list.lastIndex) { onOpen(list[idx + 1]) }.padding(6.dp)) {
                            Ico(AppIcons.chevronRight, 16.dp, if (idx < list.lastIndex) p.ink2 else p.line)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Countdown(a, now)
                }
            }
            when (a.kind) {
                "ask" -> AskBody(vm, a, onClose)
                "plan" -> PlanBody(vm, a, onClose)
                else -> PermBody(vm, a, onClose)
            }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    val p = LocalPalm.current
    BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)
        .border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(14.dp),
        textStyle = TextStyle(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
        decorationBox = { inner -> if (value.isEmpty()) Text(hint, color = p.ink3, fontSize = 15.sp); inner() })
}

/** 详情折叠：默认只露 maxH 高，点“展开”看全。 */
@Composable
private fun Folded(long: Boolean, maxH: Dp = 132.dp, content: @Composable () -> Unit) {
    val p = LocalPalm.current
    var open by remember { mutableStateOf(false) }
    Column {
        Box(if (long && !open) Modifier.heightIn(max = maxH).clip(RoundedCornerShape(10.dp)) else Modifier) { content() }
        if (long) Text(if (open) "收起" else "展开全部", style = MaterialTheme.typography.labelMedium, color = p.accent,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).pressable { open = !open }.padding(4.dp))
    }
}

private fun isLong(s: String) = s.length > 240 || s.count { it == '\n' } > 6

/** 命令完整折行显示（不再横向截断），最高约 7 行，超出可在框内上下滚。 */
@Composable
private fun CmdBox(cmd: String) {
    val p = LocalPalm.current
    Box(Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(10.dp)).background(p.code)
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(cmd, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = p.ink)
    }
}

/** 选择题的推荐项：名字里带“推荐”或“Recommended”的；单选只取第一个。 */
fun recommended(q: app.palminspire.Question): Set<String> {
    val hits = q.options.map { it.label }.filter { it.contains("推荐") || it.contains("recommended", ignoreCase = true) }
    return (if (q.multi) hits else hits.take(1)).toSet()
}

/** 抽屉里的正文区（可滚）+ 紧跟其后的按钮区。 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Body(scroll: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { scroll() }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PermBody(vm: AppState, a: Approval, onClose: () -> Unit) {
    val p = LocalPalm.current
    val i = a.input
    // 标题不再重复命令（评审意见：标题和代码框是同一条命令、还都被截断）：Bash 有说明就用说明当标题，命令在下面完整折行
    val desc = if (a.tool == "Bash") i.optString("description") else ""
    Body({
        Text(desc.ifEmpty { a.human }, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(toolIcon(a.tool), 15.dp, p.ink3); Spacer(Modifier.width(6.dp))
            Text(a.tool, style = MaterialTheme.typography.labelMedium, color = p.ink3)
        }
        when (a.tool) {
            "Bash" -> CmdBox(i.optString("command"))
            "Edit", "MultiEdit" -> {
                Text(i.optString("file_path"), fontFamily = Mono, fontSize = 12.sp, color = p.ink2)
                Folded(isLong(i.optString("old_string") + i.optString("new_string")), 160.dp) { Diff(i.optString("old_string"), i.optString("new_string")) }
            }
            "Write" -> {
                Text(i.optString("file_path"), fontFamily = Mono, fontSize = 12.sp, color = p.ink2)
                Folded(true) { CodeBox(i.optString("content").take(3000), "") }
            }
            "WebFetch" -> Text(i.optString("url"), fontFamily = Mono, fontSize = 13.sp, color = p.info)
            else -> if (i.length() > 0) Folded(isLong(i.toString(2))) { CodeBox(i.toString(2).take(4000), "") }
        }
    }) {
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 拒绝改长按（离“始终允许”太近易误触；原来点一下进“确认拒绝”，确认键恰在批准的位置，连点就拒了）
                HoldButton("长按拒绝", Modifier.weight(1f), p.danger) { vm.decide(a, "deny"); onClose() }
                if (a.danger) HoldButton("长按批准", Modifier.weight(1.4f), p.danger) { vm.decide(a, "allow"); onClose() }
                else PrimaryButton("批准", Modifier.weight(1.4f), AppIcons.check, color = p.accent) { vm.decide(a, "allow"); onClose() }
            }
            // 始终允许：常用（外出时一次对话要批好几次）——点一下即生效（与 Agents Island 的 Always 一致）；
            // 深橙字 + 橙描边 + 浅橙底，比原来的灰描边醒目。危险操作不给一键始终允许，仍要长按
            if (a.danger) HoldButton("长按 1 秒：本会话始终允许", Modifier.fillMaxWidth(), Brand.OrangeDeep, p.accentSoft) { vm.decide(a, "always"); onClose() }
            else Text("本会话始终允许", color = Brand.OrangeDeep, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.accentSoft)
                    .border(1.5.dp, Brand.OrangeDeep, RoundedCornerShape(14.dp)).pressable { vm.decide(a, "always"); onClose() }
                    .padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AskBody(vm: AppState, a: Approval, onClose: () -> Unit) {
    val p = LocalPalm.current
    // 推荐项默认选中（八成会选推荐答案）：选项名带“推荐 / Recommended”的预先勾上，改选照旧
    val picked = remember(a.id) { mutableStateListOf(*Array(a.questions.size) { qi -> recommended(a.questions[qi]) }) }
    val other = remember(a.id) { mutableStateListOf(*Array(a.questions.size) { "" }) }
    var shake by remember { mutableIntStateOf(0) }
    Body({
        a.questions.forEachIndexed { qi, q ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (q.header.isNotEmpty()) { Badge(q.header, p.accent, p.accentSoft); Spacer(Modifier.width(6.dp)) }
                    if (q.multi) Text("可多选", style = MaterialTheme.typography.labelSmall, color = p.ink3)
                }
                Text(q.question, style = MaterialTheme.typography.titleMedium, color = p.ink)
                q.options.forEach { o ->
                    val on = o.label in picked[qi]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) p.accentSoft else Color.White.copy(alpha = 0.7f))
                        .border(1.dp, if (on) p.accent else p.line, RoundedCornerShape(14.dp))
                        .pressable { picked[qi] = if (q.multi) (if (on) picked[qi] - o.label else picked[qi] + o.label) else setOf(o.label) }
                        .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(if (q.multi) RoundedCornerShape(5.dp) else CircleShape)
                            .border(1.5.dp, if (on) p.accent else p.ink3, if (q.multi) RoundedCornerShape(5.dp) else CircleShape)
                            .background(if (on) p.accent else p.surface), contentAlignment = Alignment.Center) {
                            if (on) Ico(AppIcons.check, 12.dp, p.onAccent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(o.label, style = MaterialTheme.typography.bodyLarge, color = p.ink, fontWeight = FontWeight.Medium)
                            if (o.description.isNotEmpty()) Text(o.description, style = MaterialTheme.typography.bodySmall, color = p.ink2)
                        }
                    }
                }
                // “其他”与选项同一条左轴：前面补单选圈，写了字就算选上
                val typed = other[qi].isNotBlank()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (typed) p.accentSoft else Color.White.copy(alpha = 0.7f))
                    .border(1.dp, if (typed) p.accent else p.line, RoundedCornerShape(14.dp)).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(if (q.multi) RoundedCornerShape(5.dp) else CircleShape)
                        .border(1.5.dp, if (typed) p.accent else p.ink3, if (q.multi) RoundedCornerShape(5.dp) else CircleShape)
                        .background(if (typed) p.accent else p.surface), contentAlignment = Alignment.Center) {
                        if (typed) Ico(AppIcons.check, 12.dp, p.onAccent)
                    }
                    BasicTextField(other[qi], { v -> other[qi] = v; if (v.isNotBlank() && !q.multi) picked[qi] = emptySet() },
                        Modifier.weight(1f).heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 13.dp),
                        textStyle = TextStyle(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
                        decorationBox = { inner -> if (other[qi].isEmpty()) Text("其他（自己写）", color = p.ink3, fontSize = 15.sp); inner() })
                }
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 到电脑上答是低频操作：降为文字按钮
            Text("到电脑上答", style = MaterialTheme.typography.labelLarge, color = p.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).pressable { vm.decide(a, "allow"); onClose() }.padding(vertical = 12.dp))
            PrimaryButton("提交答案", Modifier.weight(1.8f).shake(shake), AppIcons.send) {
                val ans: JSONObject = answersJson(a.questions, picked.toList(), other.toList())
                if (ans.length() == 0) shake++ else { vm.decide(a, "allow", answers = ans); onClose() }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlanBody(vm: AppState, a: Approval, onClose: () -> Unit) {
    val p = LocalPalm.current
    var rejecting by remember(a.id) { mutableStateOf(false) }
    var reason by remember(a.id) { mutableStateOf("") }
    Body({
        Markdown(a.plan.ifEmpty { "（计划为空）" })
        if (rejecting) Field(reason, "要怎么改？Claude 会按你的意见重拟") { reason = it }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rejecting) {
                OutlineButton("返回", Modifier.weight(1f)) { rejecting = false }
                PrimaryButton("驳回并说明", Modifier.weight(1.4f), AppIcons.send, color = p.danger) { vm.decide(a, "deny", reason); onClose() }
            } else {
                OutlineButton("驳回", Modifier.weight(1f), AppIcons.xCircle, color = p.danger) { rejecting = true }
                PrimaryButton("批准执行", Modifier.weight(1.4f), AppIcons.check, color = p.accent) { vm.decide(a, "allow"); onClose() }
            }
        }
    }
}
