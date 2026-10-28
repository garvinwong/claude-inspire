package app.palminspire

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.palminspire.ui.DockHeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import app.palminspire.ui.rememberBackdrop
import app.palminspire.ui.backdropSource
import app.palminspire.ui.LocalBackdrop
import app.palminspire.ui.Dock
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.palminspire.ui.ApprovalLayer
import app.palminspire.ui.HistoryScreen
import app.palminspire.ui.HomeScreen
import app.palminspire.ui.LocalPalm
import app.palminspire.ui.Motion
import app.palminspire.ui.NewSessionScreen
import app.palminspire.ui.AppIcons
import app.palminspire.ui.PairScreen
import app.palminspire.ui.PalmTheme
import app.palminspire.ui.PrimaryButton
import app.palminspire.ui.SessionScreen
import app.palminspire.ui.SettingsScreen
import app.palminspire.ui.StatsScreen
import app.palminspire.ui.ToastHost

class MainActivity : FragmentActivity() {
    private val vm: AppState get() = (application as PalmApp).state      // 应用级，界面重建不丢连接
    private var locked by mutableStateOf(false)
    private var lockOn by mutableStateOf(true)
    private var leftAt = 0L
    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 正式版默认开锁；调试版默认关（真机迭代时每次重装都要指纹太慢）
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        lockOn = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("lock", !debuggable) && canLock()
        locked = lockOn
        if (savedInstanceState == null) vm.init(freshLink(intent))
        intent?.getStringExtra(Notify.EXTRA_APPROVAL)?.let { vm.openApprovalId = it }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 0) {
            notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { PalmTheme { Root() } }
        // 配对过、还没进省电白名单：问一次（不进的话锁屏后部分厂商系统会把连接掐断，审批到不了手机）；之后只在设置里提示
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        if (vm.pair != null && !Battery.exempt(this) && !sp.getBoolean("battery_asked", false)) {
            sp.edit().putBoolean("battery_asked", true).apply()
            Battery.request(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notify.EXTRA_APPROVAL)?.let { vm.openApprovalId = it }     // 点通知进来：直接开这条的抽屉
        vm.init(freshLink(intent))
    }

    /** 配对深链接只处理一次：从“最近任务”拉起时系统会重放当初的 intent（真机实测），一律忽略；处理后清掉。 */
    private fun freshLink(i: Intent?): android.net.Uri? {
        if (i == null || i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val d = i.data
        i.data = null
        setIntent(i)
        return d
    }

    override fun onStart() {
        super.onStart()
        if (lockOn && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > 3 * 60_000L) locked = true
        if (locked) unlock()
        vm.init(null)                                               // adb 新推的配置（重新配对）
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
        vm.onBackground()
    }

    private fun canLock() = BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun unlock() {
        if (!canLock()) { locked = false; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("掌上灵感").setSubtitle("验证身份后查看会话")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
    }

    private fun setLock(on: Boolean) {
        lockOn = on && canLock()
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("lock", lockOn).apply()
    }

    @Composable
    private fun Root() {
        val p = LocalPalm.current
        var approval by remember { mutableStateOf<Approval?>(null) }
        LaunchedEffect(vm.openApprovalId, vm.pending) {
            val id = vm.openApprovalId ?: return@LaunchedEffect
            vm.pending.firstOrNull { it.id == id }?.let { approval = it; vm.openApprovalId = null }
        }
        var lastDepth by remember { mutableIntStateOf(1) }
        Box(Modifier.fillMaxSize().background(p.bg)) {
            when {
                locked -> Locked { unlock() }
                vm.pair == null -> PairScreen(vm)
                else -> {
                    BackHandler(vm.stack.size > 1) { vm.back() }
                    val depth = vm.stack.size
                    val forward = depth >= lastDepth
                    lastDepth = depth
                    val backdrop = rememberBackdrop()
                    // 页面共用的弹出框（首页长按额度卡等）：画在取样层之外（真机：放在首页里，玻璃取样到自己 → 渲染线程栈溢出闪退）
                    val rootPop = remember { app.palminspire.ui.PopoverState() }
                    CompositionLocalProvider(LocalBackdrop provides backdrop, app.palminspire.ui.LocalPopover provides rootPop) {
                        // 内容层是平面的；只有底栏、输入框、弹出框这些浮层是玻璃，取的就是这一层的模糊（克制）
                        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                            AnimatedContent(vm.page, transitionSpec = {
                                (slideInHorizontally(Motion.spring()) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(Motion.fade()))
                                    .togetherWith(fadeOut(Motion.fade()))
                            }, label = "page") { page ->
                                when (page) {
                                    Page.Home -> HomeScreen(vm) { approval = it }
                                    is Page.Session -> SessionScreen(vm, page.sid) { approval = it }
                                    Page.History -> HistoryScreen(vm)
                                    Page.New -> NewSessionScreen(vm)
                                    Page.Settings -> SettingsScreen(vm, lockOn) { setLock(it) }
                                    Page.Stats -> StatsScreen(vm)
                                }
                            }
                        }
                        val inSession = vm.page is Page.Session
                        if (!inSession) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()     // 底栏下的滚动边缘渐隐
                            .height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + DockHeight + 34.dp)
                            .background(Brush.verticalGradient(0f to p.bg.copy(alpha = 0f), 0.5f to p.bg.copy(alpha = 0.78f), 1f to p.bg.copy(alpha = 0.94f))))
                        Dock(vm, collapsed = inSession, onApproval = { approval = it }, modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                            .let { if (inSession) it.imePadding() else it }.padding(bottom = 10.dp))
                        app.palminspire.ui.PopoverHost(rootPop)
                        app.palminspire.ui.SessionActionsLayer(vm)                // 长按会话卡的操作面板（同审批抽屉，底部升起；审批来了盖在它上面）
                        ApprovalLayer(vm, approval, onOpen = { approval = it }, onClose = { approval = null })   // 横幅 + 抽屉（玻璃，取样页面层）
                    }
                }
            }
            vm.pendingPair?.let { cfg ->                              // 深链接配对：必须人工确认（防网页 / 其他 App 偷换配对）
                androidx.compose.material3.AlertDialog(onDismissRequest = { vm.rejectPair() },
                    title = { Text("与这台电脑配对？") },
                    text = { Text("电脑：${cfg.pc}\n地址：${cfg.relay.substringAfter("://").substringBefore('/')}\n\n只确认你自己刚在电脑上发起的配对。") },
                    confirmButton = { androidx.compose.material3.TextButton({ vm.acceptPair() }) { Text("确认配对") } },
                    dismissButton = { androidx.compose.material3.TextButton({ vm.rejectPair() }) { Text("取消") } })
            }
            ToastHost(vm.toast) { vm.toast = null }
        }
    }

    @Composable
    private fun Locked(onUnlock: () -> Unit) {
        val p = LocalPalm.current
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            app.palminspire.ui.Bot(BotMood.SLEEP, 96.dp)                    // 锁着 = 机器人睡觉
            Spacer(Modifier.height(18.dp))
            Text("已锁定", style = MaterialTheme.typography.titleMedium, color = p.ink2)
            Spacer(Modifier.height(18.dp))
            PrimaryButton("解锁", icon = AppIcons.lock, onClick = onUnlock)
        }
    }
}
