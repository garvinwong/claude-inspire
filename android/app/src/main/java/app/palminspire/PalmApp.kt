package app.palminspire

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 应用级状态：AppState 挂在进程上而不是界面上。有会话时前台服务保活进程，
 * 用户从“最近任务”划掉界面后，连接、待办、通知按钮照样有人接。
 */
class PalmApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val state: AppState by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[AppState::class.java]
    }
}
