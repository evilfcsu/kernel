package me.weishu.kernelsu.stealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * 开机后恢复隐藏呼出监听。
 *
 * 只在用户开启过开关且配置了目标应用时才动；root 不可用时静默跳过，
 * 不影响 App 本身的启动与安装。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!Stealth.isEnabled(context)) return
        if (Stealth.pkgs(context).isEmpty()) return

        val app = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            runCatching { Stealth.sync(app) }
        }
    }
}
