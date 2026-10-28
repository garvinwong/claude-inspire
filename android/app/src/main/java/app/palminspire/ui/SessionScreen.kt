package app.palminspire.ui

import android.net.Uri
import androidx.compose.ui.graphics.Brush
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.palminspire.Suggest
import app.palminspire.TermGlyphs
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.palminspire.AppState
import app.palminspire.Approval
import app.palminspire.Screen
import app.palminspire.SessionRow
import app.palminspire.Timeline
import java.io.File

@Composable
fun SessionScreen(vm: AppState, sid: String, openApproval: (Approval) -> Unit) {
    val p = LocalPalm.current
    val s = vm.session(sid)
    var tab by remember(sid) { mutableIntStateOf(if (sid.startsWith("host:")) 1 else 0) }   // 启动中：先看终端（可能在等确认）
    var termSize by remember { mutableIntStateOf(11) }            // 终端字号：在右上角菜单里调（原来的 A-/A+ 浮钮挡状态行）
    // 内容一直铺到屏幕底；输入区浮在上面（玻璃），左边是底栏收拢成的机器人圆钮（MainActivity 里的 Dock）
    val inset = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    val floatH = DockMini + 10.dp + (if (tab == 0 && s?.hosted == true) 46.dp else 0.dp) + 12.dp
    // 浮层玻璃（输入区、弹出框）取样本页单独建的一层（标题 + 内容），自己放在这层外面：
    // 不能取样包含它自己的根内容层，显示列表会成环，hwui 递归崩溃（真机实测）
    val local = rememberBackdrop()
    val pop = remember(sid) { PopoverState() }
    BackHandler(pop.items != null) { pop.dismiss() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().backdropSource(local).background(p.bg)) {
            Header(vm, s, sid, tab, pop, onFont = { termSize = (termSize + it).coerceIn(7, 16) }) { tab = it }
            vm.pending.filter { it.sid == sid }.forEach { a ->
                ApprovalRow(a, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { openApproval(a) }
            }
            Box(Modifier.weight(1f)) {
                if (tab == 0) Conversation(vm, sid, s, inset + floatH) else TerminalPane(vm, sid, inset + floatH, termSize)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(inset + floatH + 28.dp)
            .background(Brush.verticalGradient(0f to p.bg.copy(alpha = 0f), 0.45f to p.bg.copy(alpha = 0.82f), 1f to p.bg.copy(alpha = 0.96f))))
        val float = Modifier.align(Alignment.BottomStart).windowInsetsPadding(
            WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)).padding(bottom = 10.dp)
        CompositionLocalProvider(LocalBackdrop provides local, LocalPopover provides pop) {
            when {
                s == null -> {}
                s.hosted -> Composer(vm, s, showQuick = tab == 0, float) { tab = it }
                s.control == "history" -> HistoryActions(vm, sid, float)
                else -> LiveOnly(vm, sid, float)
            }
            PopoverHost(pop)
        }
    }
}

/** 浮层输入区的起点：让出底栏收拢后的机器人圆钮。 */
private val BesideBubble = 16.dp + DockMini + 10.dp

@Composable
private fun Header(vm: AppState, s: SessionRow?, sid: String, tab: Int, pop: PopoverState, onFont: (Int) -> Unit, onTab: (Int) -> Unit) {
    val p = LocalPalm.current
    var moreRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val title = Timeline.latestTitle(vm.events[sid].orEmpty()).ifEmpty { s?.title ?: "" }.ifEmpty { sid.take(8) }
    Column(Modifier.fillMaxWidth().background(p.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).pressable { vm.back() }.padding(10.dp)) { Ico(AppIcons.chevronLeft, 22.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s != null) { StatusDot(s.status, 7.dp); Spacer(Modifier.width(6.dp)) }
                    Text(s?.shortCwd ?: "", style = MaterialTheme.typography.labelSmall, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (s?.hosted == true) Box(Modifier.anchorTo { moreRect = it }.clip(CircleShape).pressable {
                pop.show(moreRect, listOfNotNull(
                    MenuItem(AppIcons.terminal, "在电脑上打开（弹终端窗口接上本会话）") { vm.openOnPc(sid) },
                    MenuItem(AppIcons.monitor, "保持后台（关电脑终端也不结束）") { vm.keepAlive(sid, true) },
                    MenuItem(AppIcons.rotateCcw, "恢复：关终端即结束") { vm.keepAlive(sid, false) },
                    if (tab == 1) MenuItem(AppIcons.plus, "终端字号放大") { onFont(1) } else null,
                    if (tab == 1) MenuItem(AppIcons.minus, "终端字号缩小") { onFont(-1) } else null,
                    MenuItem(AppIcons.stop, "中断当前操作（Esc）", danger = true) { vm.keys(sid, listOf("esc")) },
                ), above = false, alignEnd = true)
            }.padding(10.dp)) { Ico(AppIcons.moreVertical, 20.dp) }
        }
        if (s?.hosted == true) Segmented(listOf("对话", "终端"), tab, onTab, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        if (s != null && s.control != "history" && tab == 0) MetricRings(s, vm.usage.firstOrNull(),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
    }
}

/**
 * 会话指标（模型、思考、上下文、5h、7d）。评审意见：原来五个大圆环占 76dp，
 * 且模型、思考不是比例量却画成进度环、思考环的蓝色与其他颜色相撞——改成一行：左边“Opus 5.5 · 思考 高”文字胶囊，
 * 右边三个 16dp 小环 + 百分比。70% 起琥珀、90% 起红；终端页不显示（终端状态行已有同样读数）。
 */
@Composable
private fun MetricRings(s: SessionRow, u: app.palminspire.Usage?, modifier: Modifier = Modifier) {
    val p = LocalPalm.current
    val model = app.palminspire.NewOptions.modelLabel(s.model).ifEmpty { "—" }
    val effort = if (s.effort.isEmpty()) "默认" else app.palminspire.NewOptions.effortLabel(s.effort)
    fun pctColor(v: Int?) = when { v == null -> p.ink3; v >= 90 -> p.danger; v >= 70 -> p.warn; else -> p.accent }
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$model · 思考 $effort", style = MaterialTheme.typography.labelMedium, color = p.ink2, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).background(p.surface2)
                .padding(horizontal = 9.dp, vertical = 5.dp))
        Spacer(Modifier.weight(0.01f).widthIn(min = 10.dp))
        listOf("上下文" to s.contextPct, "5h" to u?.h5?.pct, "7d" to u?.d7?.pct).forEachIndexed { i, (label, v) ->
            if (i > 0) Spacer(Modifier.width(12.dp))
            Ring((v ?: 0) / 100f, pctColor(v))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = p.ink3)
            Spacer(Modifier.width(3.dp))
            Text(if (v == null) "—" else "$v%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = if ((v ?: 0) >= 90) p.danger else p.ink)
        }
    }
}

@Composable
private fun Ring(fraction: Float, color: Color) {
    val p = LocalPalm.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.soft(), label = "ring")
    Canvas(Modifier.size(16.dp)) {
        val w = 2.5.dp.toPx()
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(p.surface2, 0f, 360f, false, tl, arcSize, style = Stroke(w))
        if (f > 0f) drawArc(color, -90f, 360f * f, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** 分段控件：拇指弹簧跟随（UXM 分段控件 k=300 c=26 的近似：用基线回弹），文字色走平滑曲线。 */
@Composable
fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalm.current
    BoxWithConstraints(modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(p.surface2).padding(3.dp)) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, Motion.spring(), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(p.surface))
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, t ->
                Box(Modifier.weight(1f).fillMaxHeight().pressable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(t, style = MaterialTheme.typography.labelLarge, color = if (i == selected) p.ink else p.ink3)
                }
            }
        }
    }
}

@Composable
private fun Conversation(vm: AppState, sid: String, s: SessionRow?, bottomPad: Dp) {
    val p = LocalPalm.current
    val events = vm.events[sid]
    val rows = remember(events) { Timeline.group(Timeline.build(events.orEmpty())) }   // 连续工具调用折叠成组
    val state = rememberLazyListState()
    // 自动翻到最新：停在底部附近时跟着走；刚从手机发过消息（20 秒内）则不管翻到哪都拉到底，
    // 自己那条和 Claude 的回复都直接露出来。目标取列表真正的末尾（含“正在处理”一行）
    val sent = vm.sentAt[sid] ?: 0L
    LaunchedEffect(rows.size, sent, s?.busy) {
        val total = state.layoutInfo.totalItemsCount
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val justSent = System.currentTimeMillis() - sent < 20_000
        if (rows.isNotEmpty() && (justSent || last >= total - 4 || state.firstVisibleItemIndex == 0)) {
            state.animateScrollToItem((total - 1).coerceAtLeast(0))
        }
    }
    if (events == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, bottomPad),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (vm.olderCursor[sid] != null) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("加载更早", style = MaterialTheme.typography.labelMedium, color = p.info,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { vm.loadOlder(sid) }.padding(10.dp))
            }
        }
        if (rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(if (s?.hosted == true) "还没有消息：在下面给 Claude 发第一句" else "还没有消息", style = MaterialTheme.typography.bodyMedium, color = p.ink3)
            }
        }
        items(rows, key = { it.ev.id.ifEmpty { it.hashCode().toString() } }) { r -> EventRow(r, vm::image) }
        if (s?.busy == true) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = p.accent)
                Spacer(Modifier.width(8.dp))
                Text("Claude 正在处理…", style = MaterialTheme.typography.labelMedium, color = p.ink3)
            }
        }
        item { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
private fun Composer(vm: AppState, s: SessionRow, showQuick: Boolean, modifier: Modifier, onTab: (Int) -> Unit) {
    val p = LocalPalm.current
    val ctx = LocalContext.current
    var tf by remember(s.sid) { mutableStateOf(TextFieldValue("")) }
    val files = remember(s.sid) { mutableStateListOf<Uri>() }
    val pop = LocalPopover.current
    var attachRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { files.addAll(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files.addAll(it) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { files.add(it) } }

    // 补全：开头 “/” → 命令面板；光标前 “@片段” → 会话目录里的文件
    val mode = Suggest.mode(tf.text, tf.selection.start)
    var cmds by remember(s.sid) { mutableStateOf(vm.cachedCommands(s.sid)) }
    var fileHits by remember(s.sid) { mutableStateOf(listOf<String>()) }
    LaunchedEffect(s.sid, mode is Suggest.Mode.Slash) {
        if (mode is Suggest.Mode.Slash && cmds.isEmpty()) cmds = vm.commands(s.sid)
    }
    val fileQ = (mode as? Suggest.Mode.File)?.q
    LaunchedEffect(s.sid, fileQ) {
        if (fileQ == null) { fileHits = emptyList(); return@LaunchedEffect }
        delay(250)                                                 // 打字时不逐字请求
        fileHits = vm.files(s.sid, fileQ)
    }
    fun put(t: String, c: Int) { tf = TextFieldValue(t, TextRange(c)) }
    fun send(text: String, fs: List<Uri>, clear: Boolean) {
        vm.send(s.sid, text, fs) { ok ->
            if (ok && clear) { tf = TextFieldValue(""); files.clear() }
            if (ok && Suggest.opensMenu(text, cmds)) onTab(1)       // 弹菜单的命令：到终端里选
        }
    }
    val sugRows: List<SugRow>? = when (mode) {
        is Suggest.Mode.Slash -> Suggest.filter(cmds, mode.q).take(60).map { c ->
            SugRow(c.name, c.desc, c.src, c.menu) { val (t, i) = Suggest.applyCommand(tf.text, tf.selection.start, c.name); put(t, i) }
        }
        is Suggest.Mode.File -> fileHits.map { f ->
            SugRow(f, "", "", false, file = true) { val (t, i) = Suggest.applyFile(tf.text, tf.selection.start, f); put(t, i) }
        }
        else -> null
    }
    val empty = when {
        mode is Suggest.Mode.Slash && cmds.isEmpty() -> "正在取命令清单…"
        mode is Suggest.Mode.Slash -> "没有匹配的命令"
        else -> null
    }
    // 输入框从机器人圆钮旁“分离”出来：进会话时从左侧弹开（回弹弹簧，略过冲 = 液体感）
    val reveal = remember(s.sid) { Animatable(0f) }
    LaunchedEffect(s.sid) { reveal.animateTo(1f, Motion.spring()) }
    Column(modifier.fillMaxWidth()) {
        vm.draftBlock?.takeIf { it.sid == s.sid }?.let { DraftCard(vm, it) }
        SuggestPanel(sugRows?.takeIf { it.isNotEmpty() || empty != null }, empty)
        if (showQuick && mode == Suggest.Mode.None) Box(Modifier.fillMaxWidth()) {
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 28.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.busy) Quick("中断", p.danger) { vm.keys(s.sid, listOf("esc")) }
            Quick("/ 命令", p.accent) { put("/", 1) }
            Quick("⇧Tab 模式") { vm.keys(s.sid, listOf("shift_tab")) }
            listOf("/compact", "/clear", "/context", "/usage", "/model").forEach { c -> Quick(c) { send(c, emptyList(), clear = false) } }
          }
          // 右端渐隐：最后一颗被裁时看得出还能往左滑
          Box(Modifier.align(Alignment.CenterEnd).width(32.dp).height(44.dp)
              .background(Brush.horizontalGradient(0f to p.bg.copy(alpha = 0f), 1f to p.bg)))
        }
        if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = BesideBubble, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEachIndexed { i, u ->
                Row(Modifier.shadow(6.dp, RoundedCornerShape(12.dp)).glass(RoundedCornerShape(12.dp), GlassSpec.Pill)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(AppIcons.paperclip, 14.dp, p.ink2)
                    Text("附件 ${i + 1}", style = MaterialTheme.typography.labelSmall, color = p.ink2, modifier = Modifier.padding(horizontal = 4.dp))
                    Box(Modifier.clip(CircleShape).pressable { files.removeAt(i) }.padding(4.dp)) { Ico(AppIcons.close, 12.dp, p.ink3) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = BesideBubble, end = 16.dp)
            .graphicsLayer {
                val r = reveal.value
                scaleX = 0.25f + 0.75f * r; alpha = r.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .heightIn(min = DockMini).shadow(16.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .glass(RoundedCornerShape(28.dp), if (showQuick) GlassSpec.Pill   // 终端页底下是深色：染色加到近乎不透明，保持亮白（太灰）
                else GlassSpec.Pill.copy(tint = Color.White.copy(alpha = 0.93f))).padding(6.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(44.dp).anchorTo { attachRect = it }.clip(CircleShape).pressable {
                pop?.show(attachRect, listOf(
                    MenuItem(AppIcons.camera, "拍照") {
                        val f = File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot-${System.currentTimeMillis()}.jpg") }
                        cameraUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        takePhoto.launch(cameraUri!!)
                    },
                    MenuItem(AppIcons.image, "相册") { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    MenuItem(AppIcons.file, "文件") { pickFiles.launch(arrayOf("*/*")) },
                ), above = true, alignEnd = false, minWidth = 136.dp)                // 三项都是两个字：收窄
            }, contentAlignment = Alignment.Center) {
                Ico(AppIcons.paperclip, 20.dp, p.ink2)
            }
            BasicTextField(tf, { tf = it }, Modifier.weight(1f).heightIn(min = 44.dp, max = 160.dp).padding(horizontal = 6.dp, vertical = 11.dp),
                textStyle = TextStyle(color = p.ink, fontSize = 15.sp, lineHeight = 21.sp), cursorBrush = SolidColor(p.accent),
                decorationBox = { inner ->
                    if (tf.text.isEmpty()) Text(if (s.busy) "Claude 忙时发的话会排在后面" else "发消息（/ 命令，@ 文件）",
                        style = MaterialTheme.typography.bodyLarge, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                })
            val can = (tf.text.isNotBlank() || files.isNotEmpty()) && !vm.busyAction
            val sendBg by animateColorAsState(if (can) p.accent else Color.Transparent, Motion.color(), label = "send")
            Box(Modifier.size(44.dp).clip(CircleShape).background(sendBg)
                .pressable(can) { send(tf.text, files.toList(), clear = true) }, contentAlignment = Alignment.Center) {
                if (vm.busyAction) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.onAccent)
                else Ico(AppIcons.send, 19.dp, if (can) p.onAccent else p.ink3)
            }
        }
    }
}

private class SugRow(val label: String, val desc: String, val src: String, val menu: Boolean, val file: Boolean = false, val pick: () -> Unit)

/** 补全面板：弹层入场走回弹（UXM），收起走平滑；收起动画期间保留上一份内容，不闪空。 */
@Composable
private fun SuggestPanel(rows: List<SugRow>?, empty: String?) {
    val p = LocalPalm.current
    var last by remember { mutableStateOf<Pair<List<SugRow>, String?>>(emptyList<SugRow>() to null) }
    if (rows != null) last = rows to empty
    AnimatedVisibility(rows != null, enter = fadeIn(Motion.fade()) + expandVertically(Motion.spring(), expandFrom = Alignment.Bottom),
        exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.soft(), shrinkTowards = Alignment.Bottom)) {
        val (list, hint) = last
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .shadow(20.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .glass(RoundedCornerShape(20.dp), GlassSpec.Sheet)) {
            if (list.isEmpty()) Text(hint ?: "", style = MaterialTheme.typography.bodySmall, color = p.ink3, modifier = Modifier.padding(14.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 264.dp)) {
                items(list, key = { it.label }) { r -> SuggestRow(r) }
            }
        }
    }
}

@Composable
private fun SuggestRow(r: SugRow) {
    val p = LocalPalm.current
    Row(Modifier.fillMaxWidth().pressable(onClick = r.pick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (r.file) {
            Ico(AppIcons.file, 16.dp, p.ink3)
            Spacer(Modifier.width(10.dp))
            val cut = r.label.lastIndexOf('/') + 1
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = p.ink3)) { append(r.label.substring(0, cut)) }
                withStyle(SpanStyle(color = p.ink)) { append(r.label.substring(cut)) }
            }, fontFamily = Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            return@Row
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, fontFamily = Mono, fontSize = 14.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                val (fg, bg) = when {
                    r.src == "内置" -> p.ink3 to p.surface2
                    r.src.startsWith("插件") -> p.warn to p.warnSoft
                    r.src.endsWith("技能") -> p.info to p.infoSoft
                    else -> p.ok to p.okSoft
                }
                Badge(r.src, fg, bg)
            }
            if (r.desc.isNotEmpty()) Text(r.desc, style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (r.menu) { Spacer(Modifier.width(8.dp)); Ico(AppIcons.terminal, 15.dp, p.ink3) }
    }
}

@Composable
private fun Quick(label: String, color: Color = LocalPalm.current.ink2, onClick: () -> Unit) {
    val p = LocalPalm.current
    Text(label, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.shadow(6.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x1A000000), spotColor = Color(0x26000000))
            .glass(RoundedCornerShape(16.dp), GlassSpec.Pill).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

/**
 * 在电脑上直接开的会话（没经掌上灵感的宿主）：手机进不了它的终端，只能看和审批。
 * 想在手机上接着聊：分叉续接——电脑后台另起一个手机可控的副本，带着原来的上下文往下聊，原会话不受影响。
 */
@Composable
private fun LiveOnly(vm: AppState, sid: String, modifier: Modifier) {
    val p = LocalPalm.current
    Row(modifier.fillMaxWidth().padding(start = BesideBubble, end = 16.dp).heightIn(min = DockMini)
        .shadow(12.dp, RoundedCornerShape(28.dp)).glass(RoundedCornerShape(28.dp), GlassSpec.Pill).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("电脑上直接开的会话，手机只能看和审批", style = MaterialTheme.typography.bodySmall, color = p.ink2, modifier = Modifier.weight(1f))
        PrimaryButton("分叉接着聊", icon = AppIcons.gitBranch, enabled = !vm.busyAction) { vm.resume(sid, true) }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier) {
    val p = LocalPalm.current
    Box(modifier.fillMaxWidth().padding(start = BesideBubble, end = 16.dp).heightIn(min = DockMini)
        .shadow(12.dp, RoundedCornerShape(28.dp)).glass(RoundedCornerShape(28.dp), GlassSpec.Pill).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = p.ink2)
    }
}

@Composable
private fun HistoryActions(vm: AppState, sid: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(start = BesideBubble, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton("续接到电脑后台", Modifier.weight(1f), AppIcons.play, enabled = !vm.busyAction) { vm.resume(sid, false) }
        OutlineButton("分叉续接", Modifier.weight(1f), AppIcons.gitBranch, enabled = !vm.busyAction) { vm.resume(sid, true) }
    }
}

// ── 终端视图 ──────────────────────────────────────────────

private val TERM_BG = Color(0xFF141414)
private val TERM_FG = Color(0xFFE4E4E4)
private val ANSI = mapOf(
    "black" to Color(0xFF3A3A3A), "red" to Color(0xFFE5605B), "green" to Color(0xFF5CC27A), "brown" to Color(0xFFD9A441),
    "yellow" to Color(0xFFD9A441), "blue" to Color(0xFF5B8CF0), "magenta" to Color(0xFFC678DD), "cyan" to Color(0xFF4FC1C9),
    "white" to Color(0xFFD8D8D8),
)

fun termColor(name: String, fallback: Color): Color {
    if (name.isEmpty() || name == "default") return fallback
    ANSI[name]?.let { return it }
    ANSI[name.removePrefix("bright")]?.let { return it.copy(alpha = 1f) }
    if (name.length == 6) name.toLongOrNull(16)?.let { return Color(0xFF000000 or it) }
    return fallback
}

@Composable
private fun TerminalPane(vm: AppState, sid: String, bottomPad: Dp, size: Int) {
    DisposableEffect(sid) { vm.startScreen(sid); onDispose { vm.stopScreen() } }
    val scr = vm.screen
    Column(Modifier.fillMaxSize().background(TERM_BG)) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            .padding(8.dp)) {
            if (scr == null) Text("正在取终端画面…", color = TERM_FG, fontSize = 12.sp)
            else TermText(scr, size)
        }
        KeyPad { vm.keys(sid, it) }
        Spacer(Modifier.fillMaxWidth().height(bottomPad).background(Color(0xFF1E1E1E)))
    }
}

@Composable
private fun TermText(scr: Screen, size: Int) {
    val text = buildAnnotatedString {
        scr.lines.forEachIndexed { y, segs ->
            segs.forEach { sg ->
                var fg = termColor(sg.fg, TERM_FG)
                var bg = termColor(sg.bg, Color.Transparent)
                if (sg.reverse) { val t = fg; fg = if (bg == Color.Transparent) TERM_BG else bg; bg = t }
                withStyle(SpanStyle(color = fg, background = bg, fontWeight = if (sg.bold) FontWeight.Bold else FontWeight.Normal)) { append(TermGlyphs.fix(sg.text)) }
            }
            if (y < scr.lines.lastIndex) append('\n')
        }
    }
    Text(text, fontFamily = Mono, fontSize = size.sp, lineHeight = (size * 1.25f).sp, softWrap = false)
}

@Composable
private fun KeyPad(send: (List<String>) -> Unit) {
    // 评审意见：回车是最高频的确认键，放右手拇指位、加宽、橙字；^C 会中断任务，移到左边、红字，避开拇指自然落点
    Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to "esc", "Tab" to "tab", "⇧Tab" to "shift_tab", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right")
                .forEach { (label, key) -> TermKey(label, Modifier.weight(1f)) { send(listOf(key)) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermKey("^C", Modifier.weight(1f), color = Color(0xFFFF8A80)) { send(listOf("ctrl_c")) }
            listOf("1", "2", "3", "4", "y", "n").forEach { k -> TermKey(k, Modifier.weight(1f)) { send(listOf(k)) } }
            TermKey("回车", Modifier.weight(1.7f), color = Brand.Orange) { send(listOf("enter")) }
        }
        // 第三排：删字（外出时电脑输入框留了字，手机上删不掉）。清空输入 = Ctrl+U、Ctrl+K 各按 8 下，光标在哪都清得干净
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermKey("⌫ 退格", Modifier.weight(1f)) { send(listOf("backspace")) }
            TermKey("空格", Modifier.weight(1f)) { send(listOf("space")) }
            TermKey("清空输入框", Modifier.weight(2f)) { send(List(8) { "ctrl_u" } + List(8) { "ctrl_k" }) }
        }
    }
}

/** 手机这条被电脑草稿挡下：说清楚为什么，给三条路。电脑闲置久了（多半是人走了留下的字）推荐“清空后发送”。 */
@Composable
private fun DraftCard(vm: AppState, b: app.palminspire.DraftBlock) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
        .shadow(16.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x22000000), spotColor = Color(0x38000000))
        .clip(RoundedCornerShape(18.dp)).background(p.surface).border(1.5.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(AppIcons.keyboard, 18.dp, p.accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("电脑输入框里还有约 ${b.chars} 字没发出", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = p.ink)
                Text("${b.idleText()}；你这条已排队，输入框清空后自动发出", style = MaterialTheme.typography.bodySmall, color = p.ink3)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (b.stale) PrimaryButton("清空后发送", Modifier.weight(1.3f), color = p.accent) { vm.flushQueued("clear") }
            else OutlineButton("清空后发送", Modifier.weight(1.3f)) { vm.flushQueued("clear") }
            OutlineButton("合并发送", Modifier.weight(1f)) { vm.flushQueued("force") }
            OutlineButton("排队等", Modifier.weight(0.9f)) { vm.draftBlock = null }
        }
        Text("清掉的字留在电脑上，回到电脑按 Ctrl+Y 可以找回", style = MaterialTheme.typography.labelSmall, color = p.ink3)
    }
}

@Composable
private fun TermKey(label: String, modifier: Modifier = Modifier, color: Color = TERM_FG, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF323232)).border(1.dp, Color(0xFF474747), RoundedCornerShape(9.dp))
        .pressable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
