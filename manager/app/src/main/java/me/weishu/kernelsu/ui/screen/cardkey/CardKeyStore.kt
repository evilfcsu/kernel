package me.weishu.kernelsu.ui.screen.cardkey

import android.content.Context
import me.weishu.kernelsu.ksuApp

/**
 * 卡密页的本地状态。
 *
 * **卡密本身不在这里校验** —— 交给 [CardKeyApi] 走网络验证（鸽子网络验证）。
 * 这里只负责三件事：
 *   1. 记住上次验证通过的卡密，下次启动预填（用户按一下就能进）；
 *   2. 记住"记住卡密"这个开关本身，免得每次启动都被重置回打开；
 *   3. 给设备标识（device_id）提供存放位置。
 *
 * 注意：**这里不保存任何"已通过"的状态**。闸门每次启动都要过（见 CardKeyGate）。
 */
object CardKeyStore {

    /** SharedPreferences 名。设备标识也存在这里，[CardKeyApi] 会引用。 */
    const val PREFS_NAME = "pigeon_su_cardkey"

    private const val KEY_REMEMBERED = "remembered_key"
    private const val KEY_REMEMBER_ENABLED = "remember_enabled"

    private fun prefs(context: Context = ksuApp) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次验证通过、并且用户勾了"记住卡密"的那一张；没有就是 null。 */
    fun rememberedKey(context: Context = ksuApp): String? =
        prefs(context).getString(KEY_REMEMBERED, null)?.takeIf { it.isNotBlank() }

    fun remember(key: String, context: Context = ksuApp) {
        prefs(context).edit().putString(KEY_REMEMBERED, key.trim()).apply()
    }

    fun forget(context: Context = ksuApp) {
        prefs(context).edit().remove(KEY_REMEMBERED).apply()
    }

    fun isRememberEnabled(context: Context = ksuApp): Boolean =
        prefs(context).getBoolean(KEY_REMEMBER_ENABLED, true)

    fun setRememberEnabled(enabled: Boolean, context: Context = ksuApp) {
        prefs(context).edit().putBoolean(KEY_REMEMBER_ENABLED, enabled).apply()
    }
}
