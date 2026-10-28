package app.palminspire

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class Page {
    data object Home : Page()
    data class Session(val sid: String) : Page()
    data object History : Page()
    data object New : Page()
    data object Settings : Page()
    data object Stats : Page()
}

/** App 的状态与动作。只在打开 App 时在线：回前台即连，退后台 3 分钟后断。 */
class AppState(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    companion object {
        const val IDLE_OFF_MS = 10 * 60_000L
        /** 通知按钮（ApprovalAction）、前台服务找在线的状态用。 */
        var live: java.lang.ref.WeakReference<AppState>? = null
    }

    init { live = java.lang.ref.WeakReference(this) }

    var pair by mutableStateOf<PairConfig?>(null); private set
    var pendingPair by mutableStateOf<PairConfig?>(null); private set
    private var pendingPairJson: JSONObject? = null
    var link: Link? = null; private set
    var conn by mutableStateOf(Link.Conn.OFF); private set
    var pcAge by mutableStateOf<Int?>(null); private set
    var sessions by mutableStateOf(listOf<SessionRow>()); private set
    var pending by mutableStateOf(listOf<Approval>()); private set
    var usage by mutableStateOf(listOf<Usage>()); private set
    var pcActive by mutableStateOf(false); private set
    val events = mutableStateMapOf<String, List<Ev>>()
    private val cursors = HashMap<String, Long>()
    val olderCursor = mutableStateMapOf<String, Long>()
    var screen by mutableStateOf<Screen?>(null); private set
    var history by mutableStateOf(listOf<SessionRow>()); private set
    var recentDirs by mutableStateOf(listOf<String>()); private set
    var toast by mutableStateOf<String?>(null)
    var busyAction by mutableStateOf(false); private set
    val stack = mutableStateListOf<Page>(Page.Home)
    val page get() = stack.last()
    private val images = LruCache<String, ImageBitmap>(48)
    var foreground = true; private set
    private var stopJob: Job? = null
    private var watchJob: Job? = null
    private var screenJob: Job? = null
    private val notified = HashSet<String>()
    private var primed = false
    /** 在电脑前时手机不响（默认开；嫌错过可关）。 */
    var quietAtPc by mutableStateOf(ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean("quiet_at_pc", true))
        private set

    fun changeQuietAtPc(on: Boolean) {
        quietAtPc = on
        ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("quiet_at_pc", on).apply()
    }                    // 头一次拿到待办列表之后才响（刚打开 App 时已在的不算新来的）
    /** 机器人一次性反应（审批 ok / !!、会话完成 done），由底栏机器人读。 */
    var botFlash by mutableStateOf<BotFlash?>(null); private set
    private var prevBusy = emptySet<String>()

    /** 更新总览；顺带找出“刚做完”的会话，让机器人落地一跳。 */
    private fun putSessions(rows: List<SessionRow>) {
        if (finishedSessions(prevBusy, rows).isNotEmpty()) botFlash = BotFlash(BotFlash.DONE, System.currentTimeMillis())
        prevBusy = rows.filter { it.busy }.mapTo(HashSet()) { it.sid }
        sessions = rows
        updateLive(alert = false)
    }

    // ── 后台在线──────────────────────────────────────
    private var idleSince = 0L

    /**
     * 有会话在跑或有待办 → 保持在线：前台服务 + 常驻通知；都空闲满 10 分钟 → 下线（停服务、撤通知；在后台就断开连接）。
     * 前台服务只能在 App 处于前台时启动；在后台下线后，要等下次打开 App 才会再上线。
     */
    private fun updateLive(alert: Boolean) {
        if (pair == null) return
        val busy = sessions.filter { it.busy }
        val now = System.currentTimeMillis()
        if (busy.isNotEmpty() || pending.isNotEmpty()) idleSince = 0L else if (idleSince == 0L) idleSince = now
        val stay = idleSince == 0L || now - idleSince < IDLE_OFF_MS
        if (stay) {
            if (foreground) LiveService.start(ctx)
            if (LiveService.running) Notify.post(ctx, Notify.build(ctx, pending, busy))           // 在线状态（静默）
            if (foreground) Notify.alert(ctx, emptyList(), false)                                // 前台：App 里有抽屉 + 提示音
            else Notify.alert(ctx, pending, ring = alert)                                        // 后台：审批提醒，新来的弹出并响
        } else {
            LiveService.stop(ctx)
            Notify.clear(ctx)
            if (!foreground) link?.stop()
        }
    }

    // ── 配对与在线 ─────────────────────────────────────────────
    fun init(deepLink: Uri?) {
        val imported = PairStore.importExternal(ctx)
        val cfg = PairStore.load(ctx)
        if (cfg != null && (imported || pair == null)) {
            link?.stop()
            pair = cfg
            startLink(cfg)
            if (imported) toast = "已配对电脑 ${cfg.pc}"
        }
        offerLink(deepLink)
    }

    /** 配对深链接：每条只处理一次（系统重建页面时会重放旧 intent，真机实测）；与当前配对相同则不打扰。 */
    private fun offerLink(deepLink: Uri?) {
        val frag = deepLink?.fragment ?: return
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(frag.toByteArray()).joinToString("") { "%02x".format(it) }
        val sp = ctx.getSharedPreferences("links", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean(digest, false)) return
        sp.edit().putBoolean(digest, true).apply()
        val j = PairStore.parseLink(deepLink) ?: return
        val c = PairStore.parse(j)
        val same = pair?.let { it.relay == c.relay && it.token == c.token && it.key.contentEquals(c.key) } == true
        if (!same) { pendingPairJson = j; pendingPair = c }
    }

    private fun startLink(cfg: PairConfig) {
        val l = Link(cfg, RelayHttp(cfg.relay, cfg.token), PrefsSeqStore(ctx), viewModelScope)
        link = l
        viewModelScope.launch { l.pushes.collect { (k, m) -> onPush(k, m) } }
        viewModelScope.launch { l.conn.collect { conn = it } }
        viewModelScope.launch { l.pcAge.collect { pcAge = it } }
        l.start()
        refresh()
    }

    fun acceptPair() {
        val j = pendingPairJson ?: return
        pendingPair = null; pendingPairJson = null
        try { PairStore.save(ctx, j) } catch (e: Exception) { toast = "配对信息不对：${e.message}"; return }
        val cfg = PairStore.load(ctx) ?: return
        link?.stop()
        pair = cfg
        startLink(cfg)
        toast = "已配对电脑 ${cfg.pc}"
    }

    fun rejectPair() { pendingPair = null; pendingPairJson = null }

    fun onForeground() {
        foreground = true
        stopJob?.cancel()
        link?.let { if (!it.running) it.start() }
        idleSince = 0L                                                  // 打开 App 算一次活动：重新计 10 分钟
        updateLive(alert = false)
        refresh()
        (page as? Page.Session)?.let { watch(it.sid) }
    }

    fun onBackground() {
        foreground = false
        stopJob?.cancel()
        updateLive(alert = false)                                       // 离开 App：还没处理的审批放进通知栏（不响，之前已提醒过）
        // 前台服务在跑：靠它保持在线，每分钟复核一次是否该下线；没在跑（如未配对完）：照旧 3 分钟后断开
        stopJob = viewModelScope.launch {
            if (!LiveService.running) { delay(3 * 60_000L); link?.stop(); Notify.clear(ctx); return@launch }
            while (isActive && !foreground) { delay(60_000L); updateLive(alert = false) }
        }
    }

    fun unpair() {
        link?.stop(); link = null; pair = null
        PairStore.clear(ctx)
        sessions = emptyList(); pending = emptyList(); events.clear()
    }

    // ── 导航 ──────────────────────────────────────────────────
    fun go(p: Page) {
        stack.add(p)
        if (p is Page.Session) openSession(p.sid)
        if (p is Page.History) loadHistory()
        if (p is Page.New) loadRecentDirs()
        if (p is Page.Stats) loadStats()
    }

    /** 底栏切一级页面：首页 / 新建 / 历史 / 数据 / 设置。栈重置为“首页 + 该页”，返回键回首页；再点首页或数据 = 刷新。 */
    fun tab(p: Page) {
        if (page == p) { if (p == Page.Home) refresh(); if (p == Page.Stats) loadStats(); return }
        (page as? Page.Session)?.let { leaving -> watchJob?.cancel(); stopScreen(); unwatch(leaving.sid) }
        stack.clear(); stack.add(Page.Home)
        if (p != Page.Home) go(p)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        val leaving = stack.removeAt(stack.lastIndex)
        if (leaving is Page.Session) { watchJob?.cancel(); stopScreen(); unwatch(leaving.sid) }
        return true
    }

    // ── 推送 ──────────────────────────────────────────────────
    private fun onPush(kind: String, m: JSONObject) {
        when (kind) {
            "L1" -> {
                putSessions(m.optJSONArray("sessions").objects().map(SessionRow::parse))
                followHost()
                usage = Usage.parseAll(m.optJSONObject("usage"))
                pcActive = m.optBoolean("pc_active")
            }
            "L2" -> setPending(m.optJSONArray("pending"))
            "L4" -> {
                val sid = m.optString("sid")
                val evs = m.optJSONArray("events").objects().mapNotNull(Ev::parse)
                events[sid] = Timeline.merge(events[sid].orEmpty(), evs)
                cursors[sid] = m.optLong("cursor", cursors[sid] ?: 0L)
            }
        }
    }

    private fun setPending(arr: JSONArray?) {
        val list = arr.objects().map { Approval.parse(it) }
        val fresh = list.filter { it.id !in notified }
        notified.addAll(fresh.map { it.id })
        pending = list
        updateLive(alert = fresh.isNotEmpty() && !foreground && !(pcActive && quietAtPc))   // 后台才弹；在电脑前时按设置决定响不响
        if (fresh.isNotEmpty() && foreground && primed) Notify.ping(ctx)     // 在 App 里：震两下 + 提示音（打开 App 时已有的不响）
        primed = true
    }

    /** 通知按钮来的决定：待办里还有这条且在线才发，返回是否已处理。 */
    fun decideById(id: String, decision: String): Boolean {
        val a = pending.firstOrNull { it.id == id } ?: return false
        if (link == null || conn != Link.Conn.ONLINE) return false
        decide(a, decision)
        return true
    }

    /** 点通知进来：打开这条的抽屉（MainActivity 读）。 */
    var openApprovalId by mutableStateOf<String?>(null)

    fun refresh() {
        val l = link ?: return
        // 三个请求同时发（原先串行，打开 App 要等十来秒才出总览，真机实测）
        viewModelScope.launch {
            val r = l.call("sessions")
            if (r.optBoolean("ok")) { putSessions(r.optJSONArray("sessions").objects().map(SessionRow::parse)); followHost() }
        }
        viewModelScope.launch {
            val p = l.call("pending")
            if (p.optBoolean("ok")) setPending(p.optJSONArray("pending"))
        }
        viewModelScope.launch {
            val u = l.call("usage")
            if (u.optBoolean("ok")) { usage = Usage.parseAll(u.optJSONObject("usage")); pcActive = u.optBoolean("pc_active") }
        }
    }

    /** 启动中的会话先以 host:<宿主号> 出现（信任对话框、续接 / 分叉时还没会话号）；拿到正式会话号后页面跟过去（真机实测）。 */
    private fun followHost() {
        val cur = page as? Page.Session ?: return
        if (!cur.sid.startsWith("host:")) return
        val real = sessions.firstOrNull { it.hostId == cur.sid.removePrefix("host:") && !it.starting } ?: return
        stack[stack.lastIndex] = Page.Session(real.sid)
        openSession(real.sid)
    }

    // ── 会话 ──────────────────────────────────────────────────
    fun session(sid: String) = sessions.firstOrNull { it.sid == sid } ?: history.firstOrNull { it.sid == sid }

    private fun openSession(sid: String) {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("events", JSONObject().put("sid", sid).put("tail", 200))
            if (r.has("events")) {
                events[sid] = Timeline.merge(emptyList(), r.optJSONArray("events").objects().mapNotNull(Ev::parse))
                cursors[sid] = r.optLong("cursor")
                if (r.optBoolean("has_more_before") && !r.isNull("before")) olderCursor[sid] = r.optLong("before") else olderCursor.remove(sid)
            } else if (r.optString("reason").isNotEmpty()) toast = "取不到会话内容：${r.optString("reason")}"
            watch(sid)
        }
    }

    private fun watch(sid: String) {
        val l = link ?: return
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (isActive) {                                    // 中枢 120 秒不续订自动退订
                l.call("watch", JSONObject().put("sid", sid).put("cursor", cursors[sid] ?: 0L))
                delay(60_000)
            }
        }
    }

    private fun unwatch(sid: String) {
        val l = link ?: return
        viewModelScope.launch { l.call("unwatch", JSONObject().put("sid", sid), timeoutMs = 5_000) }
    }

    fun loadOlder(sid: String) {
        val l = link ?: return
        val before = olderCursor[sid] ?: return
        olderCursor.remove(sid)
        viewModelScope.launch {
            val r = l.call("events", JSONObject().put("sid", sid).put("before", before).put("tail", 150))
            val older = r.optJSONArray("events").objects().mapNotNull(Ev::parse)
            events[sid] = Timeline.merge(older, events[sid].orEmpty())
            if (r.optBoolean("has_more_before") && !r.isNull("before")) olderCursor[sid] = r.optLong("before")
        }
    }

    /**
     * 电脑输入框里有没发的字，手机这条被挡下排队（实际遇到过：出门前电脑留了字，手机消息一直排队）。
     * 会话页据此弹一张小卡：清空电脑草稿后发 / 与草稿合并发 / 排队等。
     */
    var draftBlock by mutableStateOf<DraftBlock?>(null)

    /** 在电脑上弹 Windows Terminal 窗口接上本会话（手机上开的会话回电脑接着用，原来要自己敲 palm attach）。 */
    fun openOnPc(sid: String) {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("open_on_pc", JSONObject().put("sid", sid))
            toast = when {
                r.optBoolean("ok") -> "已在电脑上打开终端窗口（关窗口只是断开，会话照常在后台跑）"
                r.optString("reason") == "not_hosted" -> "这个会话不是经宿主启动的，电脑上接不了"
                r.optString("reason") == "no_terminal" -> "电脑上没找到能弹出的终端：请在电脑上执行 ${r.optString("command")}"
                else -> "没打开：${r.optString("reason")}"
            }
        }
    }

    /** 各会话最近一次从手机发出的时刻：发完之后对话页直接翻到最新（一般习惯，发了就该看到最新）。 */
    val sentAt = mutableStateMapOf<String, Long>()

    fun flushQueued(mode: String) {
        val b = draftBlock ?: return
        val l = link ?: return
        draftBlock = null
        viewModelScope.launch {
            val r = l.call("outbox_flush", JSONObject().put("qid", b.qid).put("mode", mode))
            toast = when {
                r.optBoolean("ok") && mode == "clear" -> "已清空电脑输入框里没发的字（电脑上按 Ctrl+Y 可找回）并发出"
                r.optBoolean("ok") -> "已和电脑输入框里的字合并发出"
                r.optString("reason") == "not_waiting" -> "这条已经发出或取消了"
                else -> "没发出去：${r.optString("reason")}"
            }
        }
    }

    fun send(sid: String, text: String, files: List<Uri>, force: Boolean = false, done: (Boolean) -> Unit = {}) {
        val l = link ?: return done(false)
        busyAction = true
        viewModelScope.launch {
            try {
                val r = if (files.isEmpty()) {
                    l.call("inject", JSONObject().put("sid", sid).put("text", text).put("queue", true).put("force", force))
                } else {
                    val metas = JSONArray()
                    for (u in files) {
                        val p = withContext(Dispatchers.IO) { Media.read(ctx, u) }
                        metas.put(l.upload(p.bytes, p.name, p.mime))
                    }
                    l.call("send_files", JSONObject().put("sid", sid).put("text", text).put("files", metas)
                        .put("queue", true).put("force", force), timeoutMs = 60_000)
                }
                val ok = r.optBoolean("ok")
                when {
                    r.optBoolean("queued") -> draftBlock = DraftBlock(sid, r.optString("qid"), r.optInt("draft_chars"),
                        if (r.isNull("idle_s")) null else r.optDouble("idle_s").toInt(), r.optString("reason") == "local_typing")
                    r.optString("reason") == "not_hosted" -> toast = "这个会话不是经宿主启动的，只能看和审批"
                    !ok -> toast = "没发出去：${r.optString("reason")}"
                }
                if (ok || r.optBoolean("queued")) sentAt[sid] = System.currentTimeMillis()   // 会话页据此翻到最新
                done(ok || r.optBoolean("queued"))
            } catch (e: Exception) {
                toast = "没发出去：${e.message}"
                done(false)
            } finally {
                busyAction = false
            }
        }
    }

    fun keys(sid: String, keys: List<String>) {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("keys", JSONObject().put("sid", sid).put("keys", JSONArray(keys)))
            if (!r.optBoolean("ok")) toast = "按键没送到：${r.optString("reason")}"
        }
    }

    fun keepAlive(sid: String, on: Boolean) {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("keep_alive", JSONObject().put("sid", sid).put("on", on))
            toast = if (r.optBoolean("ok")) (if (on) "关掉电脑终端也会保留这个会话" else "已恢复：关终端即结束") else "没设上：${r.optString("reason")}"
            if (r.optBoolean("ok")) sessions = sessions.map { if (it.sid == sid) it.copy(keepAlive = on) else it }
        }
    }

    /** 正在“刷新使用率”的账号（额度卡上显示“刷新中…”）。 */
    val refreshingUsage = mutableStateListOf<String>()

    /**
     * 长按额度卡“刷新使用率”：电脑上无头探一次该账号（约 10~30 秒），之后拉一次额度直到数据时间变新。
     * 每账号 60 秒限一次（中枢把关）；不做任何定时刷新（订阅制 CLI 定时探测有停用风险）。
     */
    fun refreshUsage(acct: String) {
        val l = link ?: return
        if (acct in refreshingUsage) return
        val before = usage.firstOrNull { it.account == acct }?.at ?: 0L
        viewModelScope.launch {
            val r = l.call("usage_refresh", JSONObject().put("account", acct))
            if (!r.optBoolean("ok")) {
                toast = when (r.optString("reason")) {
                    "cooldown" -> "刚刷新过，${r.optInt("wait_s")} 秒后再试"
                    else -> "刷新不了：${r.optString("reason")}"
                }
                return@launch
            }
            refreshingUsage.add(acct)
            try {
                repeat(30) {                                         // 最多等 90 秒
                    delay(3_000)
                    val u = l.call("usage")
                    if (u.optBoolean("ok")) {
                        usage = Usage.parseAll(u.optJSONObject("usage"))
                        if ((usage.firstOrNull { it.account == acct }?.at ?: 0L) > before) return@launch
                    }
                }
                toast = "没取到使用率，稍后再试"
            } finally { refreshingUsage.remove(acct) }
        }
    }

    /** 会话级 YOLO：该会话的工具审批由Agents Island 桥秒放行，选择题 / 计划照常问；与 Agents Island 桌面界面的 ⚡ 开关同一个。 */
    fun setYolo(sid: String, on: Boolean) {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("yolo", JSONObject().put("sid", sid).put("on", on))
            if (r.optBoolean("ok")) {
                sessions = sessions.map { if (it.sid == sid) it.copy(yolo = on) else it }
                toast = if (on) "YOLO 已开：这个会话的工具审批自动放行（选择题、计划照常问）" else "YOLO 已关：恢复逐条审批"
            } else toast = "没切换成功：${r.optString("reason")}"
        }
    }

    /** 长按会话卡弹出的操作面板对应哪个会话（常用操作藏在会话页右上角太深）；MainActivity 画在最上层。 */
    var actionsFor by mutableStateOf<String?>(null)

    /** 关闭经宿主跑的会话：结束电脑上的会话进程，记录保留，可在“历史”里续接。 */
    fun closeSession(sid: String) {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("close", JSONObject().put("sid", sid))
            if (r.optBoolean("ok")) {
                sessions = sessions.filterNot { it.sid == sid }
                toast = "已关闭会话（记录保留，可在“历史”里续接）"
                delay(1_500); refresh()
            } else toast = when (r.optString("reason")) {
                "not_hosted" -> "这个会话不经掌上灵感宿主，只能在电脑上关"
                "timeout" -> "电脑没回应，稍后再试"
                else -> "没关掉：${r.optString("reason")}"
            }
        }
    }

    // ── 审批 ──────────────────────────────────────────────────
    fun decide(a: Approval, decision: String, reason: String = "", answers: JSONObject? = null) {
        val l = link ?: return
        pending = pending.filterNot { it.id == a.id }                 // 先收起卡片，失败再提示
        viewModelScope.launch {
            val body = JSONObject().put("id", a.id).put("decision", decision)
            if (reason.isNotBlank()) body.put("reason", reason)
            if (answers != null && answers.length() > 0) body.put("answers", answers)
            val r = l.call("decision", body)
            if (r.optBoolean("ok")) botFlash = BotFlash(if (decision == "deny") BotFlash.DENY else BotFlash.OK, System.currentTimeMillis())
            if (!r.optBoolean("ok")) toast = "没送到：${r.optString("reason")}（可能已在别的设备处理）"
            val p = l.call("pending")
            if (p.optBoolean("ok")) setPending(p.optJSONArray("pending"))
        }
    }

    // ── 终端视图 ──────────────────────────────────────────────
    fun startScreen(sid: String) {
        val l = link ?: return
        screenJob?.cancel()
        screenJob = viewModelScope.launch {
            while (isActive) {
                val r = l.call("screen", JSONObject().put("sid", sid), timeoutMs = 8_000)
                if (r.optBoolean("ok")) screen = Screen.parse(r)
                else if (r.optString("reason") == "not_hosted") { screen = null; toast = "终端视图只对经宿主的会话可用"; break }
                delay(700)
            }
        }
    }

    fun stopScreen() { screenJob?.cancel(); screenJob = null }

    // ── 历史、续接、新建 ──────────────────────────────────────
    fun loadHistory() {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("sessions", JSONObject().put("scope", "all"))
            history = r.optJSONArray("sessions").objects().map(SessionRow::parse)
                .filter { it.control == "history" }.sortedBy { it.ageSeconds ?: Int.MAX_VALUE }
        }
    }

    fun resume(sid: String, fork: Boolean) {
        val l = link ?: return
        busyAction = true
        viewModelScope.launch {
            val r = l.call("resume", JSONObject().put("sid", sid).put("fork", fork), timeoutMs = 30_000)
            busyAction = false
            if (!r.optBoolean("ok")) {
                toast = when (r.optString("reason")) {
                    "session_live" -> "这个会话还在跑：直接到会话里操作，或用“分叉续接”"
                    "too_many_background" -> "后台会话已达上限（6 个）"
                    else -> "续接失败：${r.optString("reason")}"
                }
                return@launch
            }
            toast = if (fork) "已在电脑后台分叉续接" else "已在电脑后台续接"
            stack.removeAll { it is Page.History }
            repeat(30) {                                           // 等会话在总览里出现
                val s = l.call("sessions")
                if (s.optBoolean("ok")) putSessions(s.optJSONArray("sessions").objects().map(SessionRow::parse))
                sessions.firstOrNull { it.hostId == r.optString("host_id") }?.let { go(Page.Session(it.sid)); return@launch }
                delay(1_000)
            }
        }
    }

    var newOptions by mutableStateOf(NewOptions.EMPTY); private set

    // ── 仪表盘 ────────────────────────────────────────────────
    var stats by mutableStateOf<Stats?>(null); private set
    var statsDays by mutableStateOf(7); private set
    private var statsJob: Job? = null

    /** 拉统计；中枢首次扫描（约半分钟）时返回进度，每 2 秒再问，直到就绪或离开本页。 */
    fun loadStats(days: Int = statsDays) {
        val l = link ?: return
        if (days != statsDays) { statsDays = days; stats = stats?.takeIf { !it.ready } }
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (isActive && page == Page.Stats) {
                val r = l.call("stats", JSONObject().put("days", days), timeoutMs = 20_000)
                if (!r.optBoolean("ok")) { if (stats == null) toast = "统计没取到：${r.optString("reason")}"; return@launch }
                val s = Stats.parse(r)
                if (s.ready || stats?.ready != true) stats = s
                if (s.ready) return@launch
                delay(2_000)
            }
        }
    }

    fun loadRecentDirs() {
        val l = link ?: return
        viewModelScope.launch {
            val r = l.call("new_options")
            if (r.optBoolean("ok")) { newOptions = NewOptions.parse(r); recentDirs = newOptions.dirs; return@launch }
            recentDirs = l.call("recent_dirs").optJSONArray("dirs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        }
    }

    fun newSession(account: String, cwd: String, model: String, effort: String, mode: String, prompt: String) {
        val l = link ?: return
        busyAction = true
        viewModelScope.launch {
            val body = JSONObject().put("account", account).put("cwd", cwd)
            if (model.isNotEmpty()) body.put("model", model)
            if (effort.isNotEmpty()) body.put("effort", effort)
            if (mode.isNotEmpty()) body.put("permission_mode", mode)
            if (prompt.isNotBlank()) body.put("prompt", prompt)
            val r = l.call("new", body, timeoutMs = 30_000)
            busyAction = false
            if (!r.optBoolean("ok")) {
                toast = when (r.optString("reason")) {
                    "bad_cwd" -> "目录不在家目录下或不存在"
                    "too_many_background" -> "后台会话已达上限（6 个）"
                    else -> "没建成：${r.optString("reason")}"
                }
                return@launch
            }
            toast = "已在电脑后台新建"
            stack.removeAll { it is Page.New }
            refresh()
            go(Page.Session("host:" + r.optString("host_id")))       // 直接进会话；启动中默认开终端视图，可答信任对话框
        }
    }

    // ── 斜杠命令与 @ 文件补全 ─────────────────────────────────
    private val cmdCache = HashMap<String, List<Cmd>>()

    /** 命令清单按会话缓存（内置 + 该会话目录与账号下的技能、自定义命令）。 */
    suspend fun commands(sid: String): List<Cmd> {
        cmdCache[sid]?.let { return it }
        val l = link ?: return emptyList()
        val list = l.call("commands", JSONObject().put("sid", sid)).optJSONArray("items").objects().map(Cmd::parse)
        if (list.isNotEmpty()) cmdCache[sid] = list
        return list
    }

    fun cachedCommands(sid: String): List<Cmd> = cmdCache[sid].orEmpty()

    suspend fun files(sid: String, q: String): List<String> {
        val l = link ?: return emptyList()
        val a = l.call("files", JSONObject().put("sid", sid).put("q", q), timeoutMs = 8_000).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    // ── 图片 ──────────────────────────────────────────────────
    suspend fun image(id: String): ImageBitmap? {
        images.get(id)?.let { return it }
        val l = link ?: return null
        val meta = l.call("image", JSONObject().put("id", id), timeoutMs = 30_000)
        if (!meta.optBoolean("ok")) { android.util.Log.w("palm", "取图失败 $id：${meta.optString("reason")}"); return null }
        return try {
            val bytes = l.download(meta)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                ?.also { images.put(id, it) } ?: null.also { android.util.Log.w("palm", "图片解码失败 $id（${bytes.size} 字节）") }
        } catch (e: Exception) {
            android.util.Log.w("palm", "取图失败 $id：$e")
            null
        }
    }
}
