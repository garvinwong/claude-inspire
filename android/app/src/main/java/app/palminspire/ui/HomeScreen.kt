package app.palminspire.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.EaseInOutSine as SineEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.palminspire.AppState
import app.palminspire.BotMood
import app.palminspire.Approval
import app.palminspire.Link
import app.palminspire.Page
import app.palminspire.R
import app.palminspire.SessionRow
import app.palminspire.Usage
import androidx.compose.foundation.layout.fillMaxHeight
import app.palminspire.usageState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: AppState, openApproval: (Approval) -> Unit) {
    val p = LocalPalm.current
    val pop = LocalPopover.current                                  // 长按额度小卡：“刷新使用率”；由 MainActivity 画在取样层外
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TopBar(vm)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, dockBottomPad()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val u = vm.usage.firstOrNull()
                UsageCard(u, vm.sessions.count { it.busy }, "main" in vm.refreshingUsage) { rect ->
                    pop?.show(rect, listOf(MenuItem(AppIcons.refresh, "刷新使用率") { vm.refreshUsage("main") }), above = false, alignEnd = false)
                }
            }
            if (vm.pending.isNotEmpty()) {
                item { SectionTitle("等你处理", vm.pending.size) }
                items(vm.pending, key = { "a" + it.id }) { a -> ApprovalRow(a, Modifier.enter(0)) { openApproval(a) } }
            }
            // 不再按账号分组：一个列表按最近活动排，账号标签进卡片；点额度小卡仍可只看某个账号
            if (vm.sessions.isEmpty()) item { Empty(vm) }
            val rows = app.palminspire.sortByRecent(vm.sessions, vm.pending.mapTo(HashSet()) { it.sid }, System.currentTimeMillis())
            if (rows.isNotEmpty()) item { SectionTitle("会话", rows.size) }
            items(rows, key = { it.sid }) { s ->
                SessionCard(s, vm.pending.count { it.sid == s.sid }, Modifier.animateItem(), onLongPress = { vm.actionsFor = s.sid }) { vm.go(Page.Session(s.sid)) }
            }
        }
    }
}

@Composable
private fun TopBar(vm: AppState) {
    val p = LocalPalm.current
    val (dot, label) = when {
        vm.conn == Link.Conn.ERROR -> p.danger to "连不上电脑"
        vm.conn != Link.Conn.ONLINE -> p.warn to "连接中…"
        vm.pcAge == null || (vm.pcAge ?: 999) > 60 -> p.warn to "电脑离线"
        else -> p.ok to "电脑在线"
    }
    TabHeader("掌上灵感", if (vm.pcActive) "你在电脑前：手机不响提醒" else "") {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (dot == p.ok) Color(0xFF15734E) else p.ink2)
        }
    }
}

@Composable
fun SectionTitle(text: String, count: Int? = null) {
    val p = LocalPalm.current
    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.ink2)
        if (count != null) { Spacer(Modifier.width(6.dp)); Text("$count", style = MaterialTheme.typography.labelMedium, color = p.ink3) }
    }
}

/**
 * 额度卡：5 小时与 7 天用量两条细条（<70% 主色、70~89% 琥珀、≥90% 红）+ 状态（预警 / 活跃 / 空闲）。
 * 长按 = “刷新使用率”（电脑上无头探一次）；还没有数据时也显示，提示长按刷新。
 */
@Composable
private fun UsageCard(u: Usage?, busy: Int, refreshing: Boolean, onLongPick: (androidx.compose.ui.geometry.Rect) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val p = LocalPalm.current
    val now = System.currentTimeMillis() / 1000
    val state = usageState(u, busy)
    val dot = when (state) { "预警" -> p.danger; "活跃" -> p.ok; else -> p.ink3 }
    val rectRef = remember { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }   // 弹出框对齐用
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)
        .border(1.dp, if (state == "预警") p.danger.copy(alpha = 0.35f) else p.line, RoundedCornerShape(16.dp))
        .anchorTo { rectRef[0] = it }.pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onLongPick(rectRef[0])
            })
        }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用量", style = MaterialTheme.typography.labelLarge, color = p.ink2)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(4.dp))
            Text(state, style = MaterialTheme.typography.labelSmall, color = if (state == "预警") p.danger else p.ink2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { MiniBar("5 小时", u?.h5?.pct, p.accent, u?.h5?.reset == true) }
            Box(Modifier.weight(1f)) { MiniBar("7 天", u?.d7?.pct, p.accent, u?.d7?.reset == true) }
        }
        when {
            refreshing -> Text("刷新中…", style = MaterialTheme.typography.labelSmall, color = p.accent, maxLines = 1)
            u == null || u.at == 0L -> Text("长按刷新使用率", style = MaterialTheme.typography.labelSmall, color = p.ink3, maxLines = 1)
            else -> Text(updatedAgo(now - u.at), style = MaterialTheme.typography.labelSmall, color = p.ink3, maxLines = 1)
        }
    }
}

@Composable
private fun MiniBar(label: String, pct: Int?, base: Color, reset: Boolean = false) {
    val p = LocalPalm.current
    val v = (pct ?: 0).coerceIn(0, 100)
    val color = when { v >= 90 -> p.danger; v >= 70 -> p.warn; else -> base }
    val w by androidx.compose.animation.core.animateFloatAsState(v / 100f, Motion.soft(), label = "mini")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = p.ink3, modifier = Modifier.weight(1f))
            // 待刷新：过了重置时间，旧数作废；无头会话照样在用却不刷缓存，不能当 0——长按卡片实测
            if (reset) Text("待刷新", style = MaterialTheme.typography.labelSmall, color = p.ink3)
            else Text(if (pct == null) "—" else "$v%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = if (v >= 90) p.danger else p.ink)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.surface2)) {
            Box(Modifier.fillMaxWidth(w).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** 会话此刻在做什么：在跑 = “正在 读 HomeScreen.kt”；闲着 = “上一步 … · 3 分钟前”。 */
fun activityText(s: SessionRow): Pair<String, Boolean>? {
    if (s.actTool.isEmpty()) return null
    val what = toolLine(s.actTool, s.actSummary)
    if (s.busy) return "正在 $what" to true
    val ago = runCatching { (System.currentTimeMillis() - java.time.Instant.parse(s.actTs).toEpochMilli()) / 1000 }.getOrNull()
    val whenText = when { ago == null -> ""; ago < 60 -> " · 刚刚"; else -> " · " + ago(ago.toInt()) }
    return "上一步 $what$whenText" to false
}

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

@Composable
fun ApprovalRow(a: Approval, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalm.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(a.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val left = a.remainingSeconds(now)
    val tint = if (a.danger) p.danger else p.warn
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (a.danger) p.dangerSoft else p.warnSoft)
        .pressable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(when (a.kind) { "ask" -> AppIcons.helpCircle; "plan" -> AppIcons.listCheck; else -> if (a.danger) AppIcons.shieldAlert else AppIcons.shieldCheck }, 22.dp, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(when (a.kind) { "ask" -> "选择题"; "plan" -> "计划待审"; else -> a.human }, style = MaterialTheme.typography.titleMedium,
                color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 副标题：选择题放问题本身，其余放会话名（原来露出内部工具名 AskUserQuestion）
            val sub = listOfNotNull(a.questions.firstOrNull()?.question, a.title.ifEmpty { a.project }.ifEmpty { null })
                .joinToString(" · ").ifEmpty { if (a.tool == "AskUserQuestion") "" else a.tool }
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (left != null) Text("${left}s", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (a.danger) p.danger else Color(0xFF8F6200))
    }
}

/**
 * 会话卡。长按 = 底部升起操作面板（在电脑上打开、关闭会话等原来藏在会话页右上角）：震一下、卡片先压后弹。
 * 在跑 vs 等你（评审结论：静态区分 + 只留一处局部动效）：
 * - 在跑：固定的淡暖底 + 细橙边（一眼区分，不动）；“正在…”这一行一道暖白微光左→右掠过 + 句末 2dp 细光标呼吸
 *   （Gemini 方案；codex 的行首呼吸圆点也做过，真机对比后留微光）；
 * - 等你批：琥珀描边整圈脉冲（脉冲只留给要你动手的）；
 * - 空闲：静止。系统关了动画时一律静止（暖底、细边、实色点照旧在）。
 */
@Composable
private fun SessionCard(s: SessionRow, pendingCount: Int, modifier: Modifier = Modifier, onLongPress: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalm.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val still = remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val mode = when { pendingCount > 0 -> 2; s.busy -> 1; else -> 0 }
    val shape = RoundedCornerShape(16.dp)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fx = rememberInfiniteTransition(label = "card")
    val pulse = if (mode == 2 && !still) fx.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse") else null
    Row(modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(shape)
        .drawBehind {
            drawRect(p.surface)
            if (mode == 1) drawRect(p.accent.copy(alpha = 0.05f))
            if (mode == 2) drawRect(p.warnSoft.copy(alpha = 0.6f))
        }
        .drawWithContent {
            drawContent()
            val r = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
            fun outline(c: Color, w: Float) = drawRoundRect(c, topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w), cornerRadius = r, style = Stroke(w))
            when (mode) {
                2 -> outline(p.warn.copy(alpha = 0.45f + 0.55f * (pulse?.value ?: 1f)), 2.dp.toPx())
                1 -> outline(p.accent.copy(alpha = 0.32f), 1.2.dp.toPx())
                else -> outline(p.line, 1.dp.toPx())
            }
        }
        .pointerInput(s.sid) {
            detectTapGestures(
                onPress = {
                    val down = scope.launch { scale.animateTo(Motion.PRESS_SCALE, Motion.press()) }
                    tryAwaitRelease()
                    down.cancel(); scope.launch { scale.animateTo(1f, Motion.spring()) }
                },
                onTap = { onClick() },
                onLongPress = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    scope.launch { scale.animateTo(1.03f, Motion.press()); scale.animateTo(1f, Motion.spring()) }
                    onLongPress()
                })
        }
        .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.title.ifEmpty { if (s.starting) "启动中（可能在等确认）" else s.sid.take(8) }, style = MaterialTheme.typography.titleMedium,
                color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(midEllipsis(s.shortCwd, 40), style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            activityText(s)?.let { (t, live) ->
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sweep = if (live && !still) fx.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing),
                        initialStartOffset = androidx.compose.animation.core.StartOffset((s.sid.hashCode() and 0x7ff))), label = "sweep") else null
                    Text(t, style = MaterialTheme.typography.bodySmall, color = if (live) p.accent else p.ink3, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(
                            if (sweep == null) Modifier else Modifier
                                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    // Gemini 方案：暖白微光带（行宽 35%）左→右掠过文字，2.2 秒一轮、前 1.8 秒扫、后 0.4 秒歇；只染在字上（SrcAtop）
                                    val k = (sweep.value / 0.82f).coerceAtMost(1.0001f)
                                    if (k <= 1f) {
                                        val band = size.width * 0.35f
                                        val x = -band + (size.width + band * 2) * FastOutSlowInEasing.transform(k)
                                        drawRect(Brush.horizontalGradient(listOf(Color(0x00FFF8F4), Color(0xF2FFF8F4), Color(0x00FFF8F4)),
                                            startX = x - band / 2, endX = x + band / 2), blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                                    }
                                }))
                    if (live) {                                              // 句末细光标柔和呼吸（原来的粗方块闪烁太重）
                        val c = if (still) null else fx.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900, easing = SineEasing), RepeatMode.Reverse), label = "caret")
                        Box(Modifier.padding(start = 3.dp).size(2.dp, 14.dp).graphicsLayer { alpha = c?.value ?: 1f }
                            .clip(RoundedCornerShape(1.dp)).background(p.accent))
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 一个状态标识就够（评审意见：左圆点 + 右胶囊说两遍）：运行状态的点并进胶囊
            val (txt, fg, bg) = when {
                s.starting -> Triple("启动中", p.warn, p.warnSoft)
                s.hosted -> Triple("可插话", Color(0xFF15734E), p.okSoft)
                else -> Triple("只能看", p.ink2, p.surface2)
            }
            // 右侧几枚胶囊同一形状（YOLO 用了通用小徽标，圆角 / 高度与“可插话”对不上）
            Pill(txt, fg, bg) { StatusDot(s.status, 6.dp) }
            if (pendingCount > 0) Pill("待批 $pendingCount", p.danger, p.dangerSoft)
            if (s.yolo) Pill("YOLO", Color.White, Brand.OrangeDeep) { Ico(AppIcons.zap, 11.dp, Color.White) }   // 审批自动放行中：实色醒目
        }
    }
}

/** 会话卡右侧的状态胶囊：圆角 8dp、内边距 7×3dp、labelSmall，可带一个前置小标（状态点 / 图标）。 */
@Composable
private fun Pill(text: String, fg: Color, bg: Color, lead: (@Composable () -> Unit)? = null) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) { lead(); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun Empty(vm: AppState) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 空状态：机器人在自己屏幕里打 new，点它就是新建
        Bot(BotMood.RUN, 96.dp, Modifier.pressable { vm.tab(Page.New) }, typing = if (vm.conn == Link.Conn.ONLINE) "new" else null)
        Spacer(Modifier.height(16.dp))
        Text(if (vm.conn == Link.Conn.ONLINE) "电脑上还没有在跑的会话" else "正在连电脑…", style = MaterialTheme.typography.titleMedium, color = p.ink2)
        Spacer(Modifier.height(6.dp))
        Text("点底栏的“新建”或“历史”开始", style = MaterialTheme.typography.bodySmall, color = p.ink3)
    }
}

/** 长路径中间省略：保留开头和结尾（结尾通常是最能认出目录的那段）。 */
fun midEllipsis(s: String, max: Int): String = if (s.length <= max) s else s.take(max / 3) + "…" + s.takeLast(max - max / 3 - 1)

/** 额度数据多久前更新：1 分钟内“刚刚更新”，1 小时内按分钟，再久按小时、天。 */
fun updatedAgo(sec: Long): String = when {
    sec < 60 -> "刚刚更新"
    sec < 3600 -> "${sec / 60} 分钟前更新"
    sec < 86400 -> "${sec / 3600} 小时前更新"
    else -> "${sec / 86400} 天前更新"
}
