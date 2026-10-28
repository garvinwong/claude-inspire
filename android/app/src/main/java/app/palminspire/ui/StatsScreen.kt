package app.palminspire.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.palminspire.AppState
import app.palminspire.Page
import app.palminspire.StatDay
import app.palminspire.StatFmt
import app.palminspire.Stats
import java.time.LocalDate

/**
 * 仪表盘（把用 agent 过程中有意义的数据展示出来——token、时长等）。
 * 数据来自中枢 /api/stats（hub/usage.py，本机会话记录）。只看 token，不折算金额（订阅制，金额无参考意义）。
 * 顶部几个大数字用点阵（与机器人屏幕同一家族；点阵只放数字，单位用正常字）；图表平面、细线、留 2dp 间隙。
 */
private val ACCOUNTS = listOf("main")                           // 单账号；接口按账号分组，留作以后扩展

@Composable
private fun acctColor(a: String): Color {
    val p = LocalPalm.current
    return if (a == "main") p.accent else p.ink3
}

private fun acctName(a: String) = "当前"

@Composable
fun StatsScreen(vm: AppState) {
    val p = LocalPalm.current
    val s = vm.stats
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TabHeader("数据", "Claude Code 用量 · 本机会话记录") { RangeSwitch(vm.statsDays) { vm.loadStats(it) } }
        when {
            s == null -> Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                Text("正在取数据…", style = MaterialTheme.typography.bodyMedium, color = p.ink3)
            }
            !s.ready -> Warming(s.progress)
            else -> {
              val scroll = rememberScrollState()
              // 内容滚上去时标题下沿出一条细线，卡片不再被一刀切
              if (scroll.value > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
              Column(Modifier.verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = dockBottomPad()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TokenHero(s, vm.statsDays)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("工作时长", StatFmt.hours(s.workS), Modifier.weight(1f))
                    Kpi("会话", StatFmt.count(s.sessions).let { it.first to (it.second + "个") }, Modifier.weight(1f))
                    Kpi("工具调用", StatFmt.count(s.tools).let { it.first to (it.second + "次") }, Modifier.weight(1f))
                }
                if (s.days.size > 1) DailyCard(s.days)
                QuotaCard(s)
                ToolCard(s.toolTop, s.tools)
                SessionCard(s) { vm.go(Page.Session(it)) }
                Text("工作时长：相邻两条消息间隔不超过 5 分钟才算在干活，并行的会话只算一次；token 含缓存读取。当前显示${if (vm.statsDays == 1) "今天" else "近 ${vm.statsDays} 天"}。",
                    style = MaterialTheme.typography.bodySmall, color = p.ink3, modifier = Modifier.padding(horizontal = 4.dp))
              }
            }
        }
    }
}

@Composable
private fun RangeSwitch(days: Int, onPick: (Int) -> Unit) {
    val p = LocalPalm.current
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(p.surface2).padding(3.dp)) {
        listOf("今天" to 1, "7天" to 7, "30天" to 30).forEach { (label, d) ->
            val on = d == days
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) p.ink else p.ink3,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) p.surface else Color.Transparent)
                    .pressable { onPick(d) }.padding(horizontal = 11.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalm.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(18.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun CardTitle(text: String, trailing: String = "") {
    val p = LocalPalm.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.ink2, modifier = Modifier.weight(1f))
        if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.labelMedium, color = p.ink3)
    }
}

/** 中枢头一次扫近 30 天记录（约半分钟）：点阵显示进度。 */
@Composable
private fun Warming(progress: Float) {
    val p = LocalPalm.current
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DotNumber("${(progress * 100).toInt()}%", 7.dp)
        Text("电脑正在头一次统计近 30 天的会话记录，约半分钟", style = MaterialTheme.typography.bodyMedium, color = p.ink3)
    }
}

/** 主卡：token 总量（点阵）+ 构成条（缓存读 / 缓存写 / 输入 / 输出）+ 缓存命中与主力模型。 */
@Composable
private fun TokenHero(s: Stats, days: Int) {
    val p = LocalPalm.current
    val (num, unit) = StatFmt.tokens(s.tokens)
    Card {
        CardTitle("token 总量", if (days == 1) "今天" else "近 $days 天")
        Row(verticalAlignment = Alignment.Bottom) {
            DotNumber(num, 7.dp, Modifier.padding(start = 0.dp))
            Text(unit, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.ink2, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
        }
        // 暖色阶梯（评审意见：原来两种灰分不开，输入蓝 / 输出橙又撞账号色）：浅暖灰 → 深暖灰 → 橙 → 深橙
        val parts = listOf(
            Triple("缓存读取", s.cacheRead, Color(0xFFDAD4CD)), Triple("缓存写入", s.cacheWrite, Color(0xFF8E857C)),
            Triple("输入", s.input, p.accent), Triple("输出", s.output, Color(0xFF9E4A2C)))
        val total = parts.sumOf { it.second }.coerceAtLeast(1)
        CompositionBar(parts.map { it.second.toFloat() / total to it.third })
        parts.chunked(2).forEach { row ->
            Row {
                row.forEach { (label, v, c) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Text(label, style = MaterialTheme.typography.bodySmall, color = p.ink2, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        val (n, u) = StatFmt.tokens(v)
                        Text("$n$u", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = p.ink,
                            modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
        Text("缓存命中 ${"%.1f".format(s.cacheHit * 100)}%", style = MaterialTheme.typography.bodySmall, color = p.ink3)
        ModelShare(s.models)
    }
}

/**
 * 模型占比（按回复条数）。“Opus 5.5 93%”里版本号和占比都是数字，读着混。
 * 改成模型名装进灰底小标签、占比单独加粗跟在后面，两种数字一眼分开；不足 1% 写“<1%”。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModelShare(models: List<Pair<String, Int>>) {
    val p = LocalPalm.current
    if (models.isEmpty()) return
    val total = models.sumOf { it.second }.coerceAtLeast(1)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("模型占比", style = MaterialTheme.typography.bodySmall, color = p.ink3, modifier = Modifier.align(Alignment.CenterVertically))
        models.take(3).forEach { (m, n) ->
            val pct = n * 100 / total
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(StatFmt.model(m), style = MaterialTheme.typography.labelMedium, color = p.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.surface2).padding(horizontal = 7.dp, vertical = 2.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (pct < 1) "<1%" else "$pct%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = p.ink)
            }
        }
    }
}

/** 构成条：各段之间留 2dp 底色间隙；极小的段至少画 5dp，看得见。 */
@Composable
private fun CompositionBar(parts: List<Pair<Float, Color>>) {
    val p = LocalPalm.current
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.soft()) }
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val gap = 2.dp.toPx(); val minW = 5.dp.toPx()
        val shown = parts.filter { it.first > 0f }
        val avail = size.width - gap * (shown.size - 1).coerceAtLeast(0)
        val widths = shown.map { maxOf(minW, it.first * avail) }
        val k = avail / widths.sum().coerceAtLeast(1f)
        var x = 0f
        shown.forEachIndexed { i, (_, c) ->
            val w = widths[i] * k * grow.value
            drawRoundRect(c, Offset(x, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
            x += w + gap
        }
        if (shown.isEmpty()) drawRoundRect(p.surface2, size = size, cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

@Composable
private fun Kpi(label: String, v: Pair<String, String>, modifier: Modifier) {
    val p = LocalPalm.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp))
        .padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.ink3)
        DotNumber(v.first, 3.6.dp)
        Text(v.second.ifEmpty { " " }, style = MaterialTheme.typography.labelMedium, color = p.ink2)
    }
}

private fun weekday(date: String): String = runCatching {
    "一二三四五六日"[LocalDate.parse(date).dayOfWeek.value - 1].let { "周$it" }
}.getOrDefault("")

/** 每日 token：按账号叠柱（单账号即一种颜色），点一根柱看当天明细；默认选中最近一天。 */
@Composable
private fun DailyCard(days: List<StatDay>) {
    val p = LocalPalm.current
    var sel by remember(days.size) { mutableIntStateOf(days.lastIndex) }
    val d = days[sel.coerceIn(0, days.lastIndex)]
    val grow = remember(days.size) { Animatable(0f) }
    LaunchedEffect(days.size) { grow.animateTo(1f, Motion.soft()) }
    val colors = ACCOUNTS.map { acctColor(it) }
    Card {
        CardTitle("每日 token", "点柱子看当天")
        val (n, u) = StatFmt.tokens(d.tokens)
        Text("${d.date.substring(5)} ${weekday(d.date)}　$n$u　·　工作 ${StatFmt.hours(d.workS).let { it.first + it.second }}　·　${d.sessions} 个会话",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = p.ink)
        val max = days.maxOf { it.tokens }.coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(132.dp).pointerInput(days.size) {
            detectTapGestures { o -> sel = (o.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.lastIndex) }
        }) {
            val slot = size.width / days.size
            val gap = 2.dp.toPx()
            val bw = (slot - gap).coerceAtMost(28.dp.toPx())
            val r = minOf(4.dp.toPx(), bw / 2)
            days.forEachIndexed { i, day ->
                val x = i * slot + (slot - bw) / 2
                var top = size.height
                val alpha = if (i == sel) 1f else 0.55f
                val segs = ACCOUNTS.mapIndexedNotNull { k, a -> day.acct[a]?.takeIf { it > 0 }?.let { it to colors[k] } }
                segs.forEachIndexed { j, (v, c) ->
                    val h = v.toFloat() / max * size.height * grow.value
                    val y = top - h
                    if (j == segs.lastIndex) {                             // 顶段圆头，其余平头
                        drawRoundRect(c.copy(alpha = alpha), Offset(x, y), Size(bw, h), CornerRadius(r))
                        if (h > r) drawRect(c.copy(alpha = alpha), Offset(x, y + h - r), Size(bw, r))
                    } else drawRect(c.copy(alpha = alpha), Offset(x, y), Size(bw, h))
                    top = y - gap
                }
                if (segs.isEmpty()) drawRect(p.line, Offset(x, size.height - 1.dp.toPx()), Size(bw, 1.dp.toPx()))
            }
        }
        Row {                                                                  // 横轴：7 天每天标；30 天每 5 天标一次
            days.forEachIndexed { i, day ->
                val show = days.size <= 7 || (days.lastIndex - i) % 5 == 0
                val on = i == sel
                Box(Modifier.weight(1f).wrapContentWidth(unbounded = true), contentAlignment = Alignment.Center) {   // 30 天时一列很窄，两位数不能被截
                    Text(if (show || on) (if (days.size <= 7) weekday(day.date).removePrefix("周") else day.date.substring(8).trimStart('0')) else "",
                        style = MaterialTheme.typography.labelSmall, color = if (on) Brand.OrangeDeep else p.ink3,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false,
                        modifier = if (on) Modifier.clip(RoundedCornerShape(8.dp)).background(p.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp)
                            else Modifier)
                }
            }
        }
    }
}

@Composable
private fun Legend(items: List<Triple<String, Color, String>>) {
    val p = LocalPalm.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (name, c, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text("$name $v", style = MaterialTheme.typography.labelMedium, color = p.ink2, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

/** 额度走势：各账号 5 小时用量随时间变化（中枢每 10 分钟记一次，留 7 天）；右上是现值。 */
@Composable
private fun QuotaCard(s: Stats) {
    val p = LocalPalm.current
    val pts = s.trend
    Card {
        CardTitle("5 小时额度走势", if (pts.size >= 2) "近 ${maxOf(1, ((pts.last().t - pts.first().t) / 3600).toInt())} 小时" else "")
        if (pts.size < 2) {
            Text("中枢运行后每 10 分钟记一个点，攒够两个点就画出来。", style = MaterialTheme.typography.bodySmall, color = p.ink3)
            return@Card
        }
        val colors = ACCOUNTS.associateWith { acctColor(it) }
        val t0 = pts.first().t; val span = (pts.last().t - t0).coerceAtLeast(1).toFloat()   // 先用 Long 求差：十亿级秒数转 Float 只精确到 128 秒
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val tick = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = p.ink3)
        // 评审意见：原来没刻度、99% 贴着标题像分割线、0% 压底线——上下留白，加 0/50/100% 虚线与刻度，线末端加圆点
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val padV = 10.dp.toPx(); val labelW = 30.dp.toPx()
            val w = size.width - labelW; val h = size.height - padV * 2
            fun yOf(v: Float) = padV + h * (1 - v)
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            for (g in listOf(0f, 0.5f, 1f)) {
                drawLine(p.line, Offset(0f, yOf(g)), Offset(w, yOf(g)), 1.dp.toPx(), pathEffect = dash)
                val t = tm.measure("${(g * 100).toInt()}%", tick)
                drawText(t, topLeft = Offset(w + 6.dp.toPx(), yOf(g) - t.size.height / 2f))
            }
            ACCOUNTS.forEach { a ->
                val path = Path(); var pen = false; var lastPt: Offset? = null
                pts.forEach { q ->
                    val v = q.h5[a]
                    if (v == null) { pen = false; return@forEach }
                    val pt = Offset((q.t - t0).toFloat() / span * w, yOf(v.coerceIn(0, 100) / 100f))
                    if (pen) path.lineTo(pt.x, pt.y) else path.moveTo(pt.x, pt.y)
                    pen = true; lastPt = pt
                }
                drawPath(path, colors.getValue(a), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                lastPt?.let { drawCircle(Color.White, 4.5.dp.toPx(), it); drawCircle(colors.getValue(a), 3.dp.toPx(), it) }
            }
        }
        val hrs = maxOf(1, ((pts.last().t - t0) / 3600).toInt())
        Row(Modifier.fillMaxWidth().padding(end = 30.dp)) {
            Text("${hrs} 小时前", style = MaterialTheme.typography.labelSmall, color = p.ink3, modifier = Modifier.weight(1f))
            Text("现在", style = MaterialTheme.typography.labelSmall, color = p.ink3)
        }
        val last = pts.last()
        Legend(ACCOUNTS.map { a -> Triple(acctName(a), acctColor(a), last.h5[a]?.let { "$it%" } ?: "—") })
    }
}

/** 工具前六：单一序列，横条用主色，数值在右。 */
@Composable
private fun ToolCard(top: List<Pair<String, Int>>, total: Int) {
    val p = LocalPalm.current
    if (top.isEmpty()) return
    val max = top.maxOf { it.second }.coerceAtLeast(1)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.soft()) }
    Card {
        CardTitle("常用工具", "共 $total 次")
        top.forEach { (name, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodySmall, color = p.ink2, maxLines = 1, modifier = Modifier.width(76.dp))
                // 底槽 + 最小 6dp（评审意见：WebFetch 等只剩 1px，像渲染丢了）
                BoxWithConstraints(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.surface2)) {
                    Box(Modifier.width(maxOf(6.dp, maxWidth * (n.toFloat() / max) * grow.value)).fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp)).background(p.accent.copy(alpha = 0.85f)))
                }
                Text("$n", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold,
                    color = p.ink, modifier = Modifier.padding(start = 10.dp).width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/** 会话排行：按 token 排前八，点进去看会话。 */
@Composable
private fun SessionCard(s: Stats, open: (String) -> Unit) {
    val p = LocalPalm.current
    if (s.top.isEmpty()) return
    Card {
        CardTitle("会话排行", "按 token")
        s.top.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 2.dp).height(1.dp).background(p.line.copy(alpha = 0.7f)))
            Row(Modifier.fillMaxWidth().pressable { open(r.sid) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title.ifEmpty { r.sid.take(8) }, style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        Text("工作 ${StatFmt.hours(r.activeS).let { it.first + it.second }}", style = MaterialTheme.typography.labelSmall, color = p.ink3)
                    }
                }
                val (n, u) = StatFmt.tokens1(r.tokens)                      // 排行统一一位小数，数字等宽右对齐
                Text("$n$u", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold, color = p.ink)
            }
        }
    }
}
