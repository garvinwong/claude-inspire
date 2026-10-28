package app.palminspire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder

/**
 * 系统层提醒与后台在线（融合到通知栏、灵动岛、弹出通知；有会话时在线）。
 * 只有一条常驻通知（LIVE_ID），同时是前台服务的通知，跟着会话与待办更新：
 * - 有待办：第一条要干什么、共几条；普通审批带“拒绝 / 始终允许 / 批准”（锁屏时要先解锁），危险操作、选择题、计划只能点开 App。
 * - 没待办但有会话在跑：“N 个会话在跑”+ 第一个在做什么；都空闲：“会话都空闲，10 分钟后下线”。
 * - 状态栏胶囊：按安卓 16 实时通知申请提升，胶囊里是“待批 N”或“N 在跑”。
 * - 弹出并响：只在有新待办、App 在后台、人不在电脑前时；其余静默更新（App 内有横幅）。
 * - 锁屏只显示“有 N 条待处理”，不露命令内容。
 */
object Notify {
    // 渠道建好后震动等设置 App 改不了（部分厂商系统新渠道默认不开震动）→ 换新渠道号，旧的删掉
    private const val CH = "approvals_v2"
    private const val CH_OLD = "approvals"
    private val VIBE = longArrayOf(0, 180, 120, 260)                // 短-长两下，和普通消息区分开
    private const val CH_QUIET = "approvals_quiet"             // 静默更新、在线状态走这个
    const val LIVE_ID = 7001
    const val ALERT_ID = 7002
    const val EXTRA_APPROVAL = "approval_id"
    private const val BRAND = 0xFFD97757.toInt()                    // 通知图标 / 状态栏胶囊染 Claude 橙（评审意见：胶囊黑白看不出紧急度）

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_OLD) != null) nm.deleteNotificationChannel(CH_OLD)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "审批与选择题", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = VIBE
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            })
        }
        if (nm.getNotificationChannel(CH_QUIET) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, "在线状态与静默更新", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun what(a: Approval) = when (a.kind) { "ask" -> "选择题"; "plan" -> "计划待审"; else -> if (a.danger) "危险操作待批" else "待批准" }

    private fun openIntent(ctx: Context, id: String?): PendingIntent =
        PendingIntent.getActivity(ctx, (id ?: "open").hashCode(), Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { if (id != null) putExtra(EXTRA_APPROVAL, id) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun actionIntent(ctx: Context, id: String, decision: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, (id + decision).hashCode(), Intent(ctx, ApprovalAction::class.java)
            .putExtra(EXTRA_APPROVAL, id).putExtra("decision", decision),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * 常驻通知（前台服务那条，永远静默）：只报在线状态——“有 N 条待处理”“N 个会话在跑”或“都空闲”。
     * 审批本身另发一条提醒通知（alert）：前台服务的通知会被系统降级、不弹横幅（真机实测 importance 4 → 3），
     * 所以要弹出的东西不能挂在它上面。
     */
    fun build(ctx: Context, pending: List<Approval>, busy: List<SessionRow>): Notification {
        ensureChannel(ctx)
        val b = Notification.Builder(ctx, CH_QUIET)
            .setSmallIcon(R.drawable.ic_notify).setColor(BRAND).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS)
        val a = pending.firstOrNull()
        val s = busy.firstOrNull()
        val chip: String
        when {
            a != null -> {
                // 不再复述问题（评审意见：与下面的审批提醒是同一件事说两遍）：常驻条只报状态，问题只在审批提醒里
                b.setContentTitle(if (busy.isNotEmpty()) "${busy.size} 个会话在跑 · 待批 ${pending.size}" else "待批 ${pending.size} 条")
                    .setContentText("点开处理，或在下面的提醒上直接批")
                    .setContentIntent(openIntent(ctx, a.id))
                chip = "待批 ${pending.size}"
            }
            s != null -> {
                val doing = app.palminspire.ui.activityText(s)?.first ?: s.shortCwd
                b.setContentTitle("${busy.size} 个会话在跑").setContentText(listOf(s.title, doing).filter { it.isNotEmpty() }.joinToString(" · "))
                    .setContentIntent(openIntent(ctx, null))
                chip = "${busy.size} 在跑"
            }
            else -> {
                b.setContentTitle("掌上灵感在线").setContentText("会话都空闲，10 分钟后自动下线").setContentIntent(openIntent(ctx, null))
                chip = "在线"
            }
        }
        // 安卓 16 实时通知：申请提升到状态栏胶囊（不支持的系统忽略，支持后自动生效）
        b.addExtras(Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            putCharSequence("android.shortCriticalText", chip)
        })
        return b.build()
    }

    /**
     * 审批提醒（可划掉的普通通知，带“批准 / 拒绝”）。ring = 有新的一条、App 在后台、人不在电脑前：走高优先级渠道弹横幅 + 响 + 震；
     * 否则走静默渠道只进通知栏。待办清空就撤掉。App 在前台时不发（App 里有抽屉 / 横幅和提示音）。
     */
    fun alert(ctx: Context, pending: List<Approval>, ring: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val a = pending.firstOrNull() ?: run { nm.cancel(ALERT_ID); return }
        ensureChannel(ctx)
        val where = a.title.ifEmpty { a.project }
        val text = a.questions.firstOrNull()?.question ?: a.human
        val b = Notification.Builder(ctx, if (ring) CH else CH_QUIET)
            .setSmallIcon(R.drawable.ic_notify).setColor(BRAND).setOnlyAlertOnce(!ring)
            .setContentTitle(what(a) + if (where.isNotEmpty()) " · $where" else "").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(if (pending.size > 1) "共 ${pending.size} 条" else null)
            .setCategory(Notification.CATEGORY_REMINDER).setContentIntent(openIntent(ctx, a.id))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(ctx, CH_QUIET).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("掌上灵感").setContentText("有 ${pending.size} 条待处理").build())
        val left: Int? = a.remainingSeconds(System.currentTimeMillis())
        if (left != null) b.setTimeoutAfter(left * 1000L + 5_000)
        fun act(label: String, decision: String) = Notification.Action.Builder(null, label, actionIntent(ctx, a.id, decision))
            .apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build()
        if (app.palminspire.ui.quickApprovable(a)) {                  // 通知上也能“始终允许”（外出时一次对话要批好几次）
            // 通知上不放“拒绝”（与“始终允许”挨着易误触；通知按钮做不了长按）——要拒绝点开 App 长按
            b.addAction(act("始终允许", "always")); b.addAction(act("批准", "allow"))
        } else b.addAction(Notification.Action.Builder(null, if (a.kind == "ask") "去作答" else "去查看", openIntent(ctx, a.id)).build())
        try { nm.notify(ALERT_ID, b.build()) } catch (_: SecurityException) { }
    }

    fun post(ctx: Context, n: Notification) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(LIVE_ID, n) } catch (_: SecurityException) { }   // 没给通知权限：静默
    }

    fun clear(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(LIVE_ID); nm.cancel(ALERT_ID)
    }

    /**
     * App 在前台时新待办的提醒（没有音效和震动会错过）：震两下 + 系统通知音。
     * 跟随手机的响铃模式：静音模式不出声，振动模式只震。
     */
    fun ping(ctx: Context) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val mode = am?.ringerMode ?: android.media.AudioManager.RINGER_MODE_NORMAL
        if (mode != android.media.AudioManager.RINGER_MODE_SILENT) {
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") ctx.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createWaveform(VIBE, -1))
        }
        if (mode == android.media.AudioManager.RINGER_MODE_NORMAL) {
            try {
                android.media.RingtoneManager.getRingtone(ctx, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)?.play()
            } catch (_: Exception) { }
        }
    }
}

/**
 * 前台服务：只为让进程在后台保有网络（安卓把后台缓存进程断网，真机实测 procState 17），
 * 自己不干活——连接、待办、通知内容都在 AppState。由 AppState 在 App 处于前台时启动（后台起不了前台服务），空闲 10 分钟后停。
 */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vm = AppState.live?.get()
        val n = Notify.build(this, vm?.pending.orEmpty(), vm?.sessions.orEmpty().filter { it.busy })
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else startForeground(Notify.LIVE_ID, n)
        } catch (e: Exception) {                                       // 后台起不来（系统限制）：放弃，等下次回前台
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        var running = false; private set

        fun start(ctx: Context) {
            if (running) return
            try { ctx.startForegroundService(Intent(ctx, LiveService::class.java)); running = true } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            ctx.stopService(Intent(ctx, LiveService::class.java))
        }
    }
}

/**
 * 省电白名单：部分厂商系统的后台网络管控在 App 退到后台几秒后
 * 直接关掉它的连接并拉进断网名单（日志“Close socket cause:App bg(IMMEDIATELY)”），前台服务也挡不住——
 * 手机待机后审批根本到不了。进了系统“不优化电池”名单就不再被断网（adb 实测锁屏后照常每几秒取件）。
 */
object Battery {
    fun exempt(ctx: Context) = ctx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** 弹系统对话框“允许在后台运行”；个别系统不认这个意图时退到电池优化列表页。 */
    fun request(ctx: Context) {
        val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
        try { ctx.startActivity(direct) } catch (_: Exception) {
            try { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
        }
    }
}

/** 通知上的“批准 / 拒绝”：交给在线的 AppState 发出去；不在线就打开 App。 */
class ApprovalAction : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Notify.EXTRA_APPROVAL) ?: return
        val decision = intent.getStringExtra("decision") ?: return
        val vm = AppState.live?.get()
        if (vm == null || !vm.decideById(id, decision)) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Notify.EXTRA_APPROVAL, id))
        }
    }
}
