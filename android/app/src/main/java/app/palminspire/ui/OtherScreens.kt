package app.palminspire.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.palminspire.AppState
import app.palminspire.BotMood
import app.palminspire.NewOptions
import app.palminspire.historyBucket
import app.palminspire.R

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    val p = LocalPalm.current
    Row(Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).pressable(onClick = onBack).padding(10.dp)) { Ico(AppIcons.chevronLeft, 22.dp) }
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
    }
}

@Composable
private fun Input(value: String, hint: String, single: Boolean = true, onChange: (String) -> Unit) {
    val p = LocalPalm.current
    BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)
        .border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(14.dp), singleLine = single,
        textStyle = TextStyle(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
        decorationBox = { inner -> if (value.isEmpty()) Text(hint, color = p.ink3, fontSize = 15.sp); inner() })
}

@Composable
fun HistoryScreen(vm: AppState) {
    val p = LocalPalm.current
    var q by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TabHeader("历史续接", "历史会话：续接或分叉续接到电脑后台")
        // 搜索框：填充底 + 放大镜，不再和下面的卡片长得一样
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surface2)
            .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(AppIcons.search, 17.dp, p.ink3)
            BasicTextField(q, { q = it }, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp), singleLine = true,
                textStyle = TextStyle(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
                decorationBox = { inner -> if (q.isEmpty()) Text("搜标题或目录", color = p.ink3, fontSize = 15.sp); inner() })
        }
        val list = vm.history.filter { q.isBlank() || it.title.contains(q, true) || it.cwd.contains(q, true) }
        if (vm.history.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = p.accent)
        }
        val secOfDay = java.time.LocalTime.now().toSecondOfDay()
        val buckets = list.groupBy { historyBucket(it.ageSeconds, secOfDay) }      // 今天 / 昨天 / 更早
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, dockBottomPad()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for (b in listOf("今天", "昨天", "更早")) {
            val rows = buckets[b] ?: continue
            item(key = "h-$b") { SectionTitle(b, rows.size) }
            items(rows, key = { it.sid }) { s ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(14.dp))
                    .pressable { pick = s.sid }.padding(14.dp)) {
                    Text(s.title.ifEmpty { s.sid.take(8) }, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text("${s.shortCwd} · ${ago(s.ageSeconds)}", style = MaterialTheme.typography.bodySmall, color = p.ink3, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
          }
        }
    }
    pick?.let { sid ->
        AlertDialog(onDismissRequest = { pick = null },
            title = { Text("续接这个会话") },
            text = { Text("会在电脑后台拉起，电脑上可用 palm attach 接入、两端同时打字。会话还在别处跑时请用“分叉续接”（另开一条，不影响原会话）。") },
            confirmButton = { TextButton({ pick = null; vm.resume(sid, false) }) { Text("续接") } },
            dismissButton = { TextButton({ pick = null; vm.resume(sid, true) }) { Text("分叉续接") } })
    }
}

fun ago(sec: Int?): String = when {
    sec == null -> ""
    sec < 3600 -> "${sec / 60} 分钟前"
    sec < 86400 -> "${sec / 3600} 小时前"
    else -> "${sec / 86400} 天前"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    val p = LocalPalm.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            val on = v == selected
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) p.onAccent else p.ink2,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) p.accent else p.surface)
                    .border(1.dp, if (on) p.accent else p.line, RoundedCornerShape(12.dp)).pressable { onPick(v) }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}

@Composable
fun NewSessionScreen(vm: AppState) {
    val p = LocalPalm.current
    val opts = vm.newOptions
    var cwd by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var dirFilter by remember { mutableStateOf("全部") }
    var dirsOpen by remember { mutableStateOf(false) }            // 目录默认只列 4 条（评审意见：撑满一屏，开始按钮被挤到第二屏）
    LaunchedEffect(opts.defaultDir) { if (cwd.isBlank() && opts.defaultDir.isNotEmpty()) cwd = opts.defaultDir }   // 缺省目录（中枢给，默认家目录）
    val acct = opts.accounts.firstOrNull()
    val dirs = (listOf(opts.defaultDir).filter { it.isNotEmpty() } + vm.recentDirs).distinct()
    Box(Modifier.fillMaxSize().background(p.bg).imePadding()) {
      Column(Modifier.fillMaxSize()) {
        TabHeader("新建会话", "在电脑后台拉起一个 Claude Code 会话")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("目录")
            val tags = dirs.map { opts.dirTags[it] ?: "目录" }.distinct()
            if (tags.size > 1) Choices(listOf("全部" to "全部") + tags.map { it to it }, dirFilter) { dirFilter = it }
            val shown = dirs.filter { dirFilter == "全部" || (opts.dirTags[it] ?: "目录") == dirFilter }
            shown.take(if (dirsOpen) 12 else 4).forEach { d ->
                val on = d == cwd
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) p.accentSoft else p.surface)
                    .border(1.dp, if (on) p.accent else p.line, RoundedCornerShape(12.dp)).pressable { cwd = d }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(app.palminspire.shortPath(d), style = MaterialTheme.typography.bodyMedium, color = if (on) p.accent else p.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    val tag = opts.dirTags[d] ?: "目录"
                    val (fg, bg) = when (tag) { "仓库" -> p.accent to p.accentSoft; "工作树" -> p.info to p.infoSoft; "评测" -> p.warn to p.warnSoft; else -> p.ink3 to p.surface2 }
                    Badge(tag, fg, bg)
                }
            }
            if (shown.size > 4) Text(if (dirsOpen) "收起" else "展开全部目录（${minOf(shown.size, 12)}）", style = MaterialTheme.typography.labelLarge,
                color = p.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { dirsOpen = !dirsOpen }.padding(6.dp))
            // 手填路径：填充底 + 文件夹图标，和上面的目录列表区分开（评审把它看成了重复的一项）
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface2).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Ico(AppIcons.folderOpen, 16.dp, p.ink3)
                BasicTextField(cwd, { cwd = it }, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp), singleLine = true,
                    textStyle = TextStyle(color = p.ink2, fontSize = 13.sp, fontFamily = Mono), cursorBrush = SolidColor(p.accent),
                    decorationBox = { inner -> if (cwd.isEmpty()) Text("或输入完整路径", color = p.ink3, fontSize = 13.sp); inner() })
            }
            SectionTitle("模型")
            val defModel = NewOptions.modelLabel(acct?.model.orEmpty())
            Choices(buildList {
                add((if (defModel.isEmpty()) "默认" else "默认（$defModel）") to "")
                if (acct?.fable == true) add("Fable" to "fable")
                add("Opus" to "opus"); add("Sonnet" to "sonnet"); add("Haiku" to "haiku")
            }, model) { model = it }
            SectionTitle("思考程度")
            val defEffort = acct?.effort.orEmpty()
            Choices(listOf((if (defEffort.isEmpty()) "默认" else "默认（${NewOptions.effortLabel(defEffort)}）") to "") +
                opts.efforts.map { NewOptions.effortLabel(it) to it }, effort) { effort = it }
            SectionTitle("权限模式")
            Choices(listOf("默认" to "", "逐项确认" to "default", "自动接受改动" to "acceptEdits", "计划模式" to "plan", "自动" to "auto"), mode) { mode = it }
            SectionTitle("第一句话（可不填）"); Input(prompt, "比如：先看看这个目录在做什么", single = false) { prompt = it }
            Text("新目录第一次会停在“信任此目录”，到会话的终端视图里按“↓、回车”确认。",
                style = MaterialTheme.typography.bodySmall, color = p.ink3)
            Spacer(Modifier.height(dockBottomPad() + 64.dp))
        }
      }
      // 开始按钮浮在底栏上方，一屏就能按到
      Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
          .background(Brush.verticalGradient(0f to p.bg.copy(alpha = 0f), 0.35f to p.bg.copy(alpha = 0.92f), 1f to p.bg))
          .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = dockBottomPad())) {
          PrimaryButton("在电脑上新建", Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(14.dp), spotColor = Color(0x40D97757)),
              AppIcons.plus, enabled = cwd.isNotBlank() && !vm.busyAction) {
              vm.newSession("main", cwd.trim(), model, effort, mode, prompt)
          }
      }
    }
}

@Composable
fun SettingsScreen(vm: AppState, lockOn: Boolean, onLock: (Boolean) -> Unit) {
    // 评审意见：加分组标题；配对区用设置列表行（右侧箭头）；“解除配对”放最底、纯红字不加框；吉祥物只留四态
    val p = LocalPalm.current
    var confirm by remember { mutableStateOf(false) }
    val cfg = vm.pair
    val switchColors = SwitchDefaults.colors(                        // 关闭态要看得清（原来浅灰底浅灰钮看不见）
        checkedTrackColor = p.accent, checkedThumbColor = Color.White, checkedBorderColor = p.accent,
        uncheckedTrackColor = Color(0xFFE3E1DE), uncheckedThumbColor = Color(0xFF8C8883), uncheckedBorderColor = Color(0xFF8C8883))
    @Composable fun Group(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp)),
            content = content)
    @Composable fun Divider() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(p.line))
    @Composable fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = p.ink)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = p.ink3, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Switch(on, onChange, colors = switchColors)
        }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TabHeader("设置")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("配对")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("电脑：${cfg?.pc ?: "—"}", style = MaterialTheme.typography.bodyLarge, color = p.ink)
                    Text("地址：${cfg?.relay?.substringAfter("://")?.substringBefore('/') ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = p.ink2)
                    Text("内容端到端加密：信箱只看得到密文", style = MaterialTheme.typography.bodySmall, color = p.ink3)
                }
                Divider()
                Row(Modifier.fillMaxWidth().pressable { vm.init(null) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(AppIcons.refresh, 18.dp, p.ink2)
                    Spacer(Modifier.width(12.dp))
                    Text("重新配对（读取电脑推来的配置）", style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.weight(1f))
                    Ico(AppIcons.chevronRight, 16.dp, p.ink3)
                }
            }
            SectionTitle("安全与提醒")
            Group {
                SwitchRow("打开时验证身份", "指纹 / 面容 / 锁屏密码；离开 3 分钟后再打开要重验", lockOn, onLock)
                Divider()
                // 省电白名单（10-28）：没进的话锁屏 / 切走后部分厂商系统会断掉连接，审批收不到
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var exempt by remember { mutableStateOf(app.palminspire.Battery.exempt(ctx)) }
                androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); exempt = app.palminspire.Battery.exempt(ctx) } }
                Row(Modifier.fillMaxWidth().then(if (exempt) Modifier else Modifier.pressable { app.palminspire.Battery.request(ctx) }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("锁屏后保持连接", style = MaterialTheme.typography.bodyLarge, color = p.ink)
                        Text(if (exempt) "已允许在后台运行：锁屏、切到别的 App 也能收到审批"
                            else "未允许：锁屏或切走后系统会断开连接，审批收不到。点此允许", style = MaterialTheme.typography.bodySmall,
                            color = if (exempt) p.ink3 else p.danger, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    if (exempt) Badge("已允许", Color(0xFF15734E), p.okSoft) else Ico(AppIcons.chevronRight, 16.dp, p.ink3)
                }
                Divider()
                SwitchRow("在电脑前时手机不响", "电脑终端 2 分钟内有输入时，审批只进通知栏和状态栏胶囊，不弹出、不响；关掉则一律弹出并响",
                    vm.quietAtPc) { vm.changeQuietAtPc(it) }
            }
            SectionTitle("吉祥物")
            BotGallery()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).pressable { confirm = true }.padding(14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Ico(AppIcons.logout, 17.dp, p.danger)
                Spacer(Modifier.width(8.dp))
                Text("解除配对", style = MaterialTheme.typography.labelLarge, color = p.danger)
            }
            // 署名：点开作者主页
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Text("Powered by Garvin Wong", style = MaterialTheme.typography.labelSmall, color = p.ink3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                    .pressable { uri.openUri("https://github.com/garvinwong") }.padding(10.dp))
            Spacer(Modifier.height(dockBottomPad()))
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("解除配对？") },
        text = { Text("手机上删除密钥与令牌；之后要在电脑上重新运行 palm pair。") },
        confirmButton = { TextButton({ confirm = false; vm.unpair() }) { Text("解除", color = p.danger) } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}

/** 吉祥物样张：四态并排 + 尺寸阶梯（设计探索.md），点一只演示一次 ok 反应。也供真机目检各状态。 */
@Composable
private fun BotGallery() {
    val p = LocalPalm.current
    var flash by remember { mutableStateOf<app.palminspire.BotFlash?>(null) }
    var wake by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(BotMood.WAIT to "等你", BotMood.RUN to "悬浮", BotMood.BUSY to "爆发", BotMood.SLEEP to "睡觉").forEach { (m, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 睡觉那只点一下就演彩蛋（底栏上要双击，见 Dock）；其余三只演 ok 反应
                    Bot(m, 72.dp, Modifier.pressable {
                        if (m == BotMood.SLEEP) wake++ else flash = app.palminspire.BotFlash(app.palminspire.BotFlash.OK, System.currentTimeMillis())
                    }, pending = 3, flash = if (m == BotMood.SLEEP) null else flash, wake = wake)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = p.ink3)
                }
            }
        }
        Text("点一只看它的反应；睡觉那只会被叫醒（底栏上睡着时双击也行）", style = MaterialTheme.typography.bodySmall, color = p.ink3)
    }
}

@Composable
fun PairScreen(vm: AppState) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Bot(BotMood.WAIT, 112.dp)                                          // 等配对 = 等你
        Spacer(Modifier.height(20.dp))
        Text("掌上灵感", style = MaterialTheme.typography.titleLarge, color = p.ink)
        Spacer(Modifier.height(8.dp))
        Text("还没和电脑配对", style = MaterialTheme.typography.bodyLarge, color = p.ink2)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("在电脑终端运行：", style = MaterialTheme.typography.bodyMedium, color = p.ink)
            CodeBox("palm pair --qr", "")
            Text("用手机相机扫终端里的二维码；同一 Wi-Fi 或 Tailscale 下即可连上。", style = MaterialTheme.typography.bodySmall, color = p.ink3)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("我已在电脑上推送，读取配置", Modifier.fillMaxWidth(), AppIcons.refresh) { vm.init(null) }
    }
}

