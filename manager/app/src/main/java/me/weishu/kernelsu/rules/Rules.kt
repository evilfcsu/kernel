package me.weishu.kernelsu.rules

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject

/**
 * 触发规则：应用 / 音量键手势 → 拉起面具 或 运行脚本（可喂标准输入）。
 *
 * 规则同时保存在两处：
 * - SharedPreferences（App 内编辑用）
 * - /data/adb/evil/rules.conf（root 侧监听脚本读取，普通应用不可见）
 */
data class Rule(
    var enabled: Boolean = true,
    var pkg: String = "",
    var key: String = "",
    var mode: String = MODE_LONG,
    var count: Int = 2,
    var windowMs: Int = 800,
    var holdMs: Int = 3000,
    var action: String = ACTION_SCRIPT,
    var script: String = "",
    var input: String = "",
    var enter: Boolean = true,
) {
    fun summary(): String {
        val trigger = when {
            pkg.isEmpty() && key.isEmpty() -> "任意应用 · 无手势"
            key.isEmpty() -> "$pkg · 进入即触发"
            else -> listOfNotNull(
                pkg.ifEmpty { null } ?: "任意应用",
                keyName(key) + if (mode == MODE_CLICK) " 连点${count}次" else " 长按${holdMs / 1000.0}s",
            ).joinToString(" · ")
        }
        val act = if (action == ACTION_LAUNCH) "拉起面具" else "运行 " + script.substringAfterLast('/')
        return "$trigger → $act"
    }

    companion object {
        const val MODE_LONG = "long"
        const val MODE_CLICK = "click"
        const val ACTION_LAUNCH = "launch"
        const val ACTION_SCRIPT = "script"
    }
}

fun keyName(key: String) = when (key) {
    "volup" -> "音量+"
    "voldown" -> "音量−"
    else -> "无"
}

object Rules {

    private const val PREFS = "trigger_rules"
    private const val KEY_JSON = "rules"
    private const val TAG = "KsRules"

    /** 与 watch.sh 约定：<data_dir>/rules.conf */
    fun rootDir(): String = "/data/adb/evil"
    fun confPath(): String = rootDir() + "/rules.conf"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Rule> {
        val raw = prefs(c).getString(KEY_JSON, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Rule(
                    enabled = o.optBoolean("enabled", true),
                    pkg = o.optString("pkg", ""),
                    key = o.optString("key", ""),
                    mode = o.optString("mode", Rule.MODE_LONG),
                    count = o.optInt("count", 2),
                    windowMs = o.optInt("window", 800),
                    holdMs = o.optInt("hold", 3000),
                    action = o.optString("action", Rule.ACTION_SCRIPT),
                    script = o.optString("script", ""),
                    input = o.optString("input", ""),
                    enter = o.optBoolean("enter", true),
                )
            }
        }.getOrElse { mutableListOf() }
    }

    fun save(c: Context, list: List<Rule>): Boolean {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("enabled", r.enabled)
                    .put("pkg", r.pkg)
                    .put("key", r.key)
                    .put("mode", r.mode)
                    .put("count", r.count)
                    .put("window", r.windowMs)
                    .put("hold", r.holdMs)
                    .put("action", r.action)
                    .put("script", r.script)
                    .put("input", r.input)
                    .put("enter", r.enter),
            )
        }
        val ok = prefs(c).edit().putString(KEY_JSON, arr.toString()).commit()
        writeRootFile(c, list)
        return ok
    }

    /** 把一个规则序列化成 watch.sh 的 11 段格式 */
    private fun toLine(r: Rule): String {
        val inputB64 = if (r.input.isEmpty()) "-" else
            Base64.encodeToString(r.input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return listOf(
            if (r.enabled) "1" else "0",
            r.pkg,
            r.key,
            r.mode,
            r.count.toString(),
            r.windowMs.toString(),
            r.holdMs.toString(),
            r.action,
            r.script,
            inputB64,
            if (r.enter) "1" else "0",
        ).joinToString("|")
    }

    /** 写规则文件到 root 侧（脚本据此执行），返回是否成功 */
    fun writeRootFile(c: Context, list: List<Rule>): Boolean = runCatching {
        // 结尾必须带换行：脚本用 while read 解析，缺换行的最后一行会被当成 EOF 丢掉
        val body = list.filter { it.enabled }.joinToString("\n") { toLine(it) } + "\n"
        val escaped = body.replace("'", "'\\''")
        val r = Shell.cmd(
            "mkdir -p ${rootDir()}; printf '%s' '$escaped' > ${confPath()}; chmod 600 ${confPath()}",
        ).exec()
        Log.i(TAG, "write rules (${list.count { it.enabled }} 条启用) ok=${r.isSuccess}")
        r.isSuccess
    }.getOrDefault(false)
}
