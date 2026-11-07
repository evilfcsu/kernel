package me.weishu.kernelsu.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 隐藏呼出（stealth）配置与运行时控制。
 *
 * 存储布局（重启/卸载都不受影响，且普通应用读不到）：
 * ```
 * /data/adb/evil/evil.apk        管理器 APK 副本（uninstall 模式下用它装回来）
 * /data/adb/evil/watch.sh       监听脚本（root 运行，setsid 脱离会话）
 * /data/adb/evil/rules.conf 等           脚本参数
 * /data/adb/evil/work/{watch.pid, watch.log, deep_hide, hide_mode}
 * /data/adb/service.d/evil-watch.sh   开机钩子（应用被卸载后仍能拉起监听）
 * ```
 *
 * 所有 root 调用都包在 runCatching 里：掉 root 后静默失败，App 本身照常可用。
 */
object Stealth {

    private const val PREFS = "stealth"
    private const val TAG = "KsStealth"

    const val KEY_ENABLED = "enabled"
    const val KEY_HIDE_ICON = "hide_icon"
    const val KEY_DEEP_HIDE = "deep_hide"
    const val KEY_HIDE_MODE = "hide_mode"
    const val KEY_PKGS = "pkgs"
    const val KEY_MODE = "mode"
    const val KEY_COUNT = "count"
    const val KEY_WINDOW = "window_ms"
    const val KEY_HOLD = "hold_ms"
    const val KEY_GESTURE_KEY = "gesture_key"
    const val KEY_SELINUX_GUARD = "selinux_guard"

    /** MainActivity 读取它决定是否走"无痕"路径 */
    const val EXTRA_STEALTH = "stealth"

    /** 必须与 AndroidManifest 里的 activity-alias 名字一致 */
    const val LAUNCHER_ALIAS = "me.weishu.kernelsu.LauncherAlias"

    const val MODE_LONG = "long"
    const val MODE_CLICK = "click"

    /** 隐藏方式 */
    const val HIDE_MODE_AUTO = "auto"
    const val HIDE_MODE_HIDE = "hide"
    const val HIDE_MODE_DISABLE = "disable"
    const val HIDE_MODE_COMBO = "combo"

    /** 卸载保数据：平时真的卸掉，触发时 pm install 装回来 */
    const val HIDE_MODE_UNINSTALL = "uninstall"

    /** root 私有目录（普通应用不可读） */
    const val ROOT_DIR = "/data/adb/evil"
    private const val WORK_DIR = "$ROOT_DIR/work"
    private const val SCRIPT = "$ROOT_DIR/watch.sh"
    private const val APK_COPY = "$ROOT_DIR/evil.apk"
    /** 安装中转：system_server 读不了 /data/adb（adb_data_file），必须放这里 */
    private const val APK_STAGE = "/data/local/tmp/evil/evil.apk"
    private const val ARGS_FILE = "$ROOT_DIR/args"
    private const val RULES_FILE = "$ROOT_DIR/rules.conf"

    /** 隐藏呼出默认用音量下键（与旧版行为一致） */
    private const val DEFAULT_GESTURE_KEY = "voldown"
    private const val BOOT_HOOK = "/data/adb/service.d/evil-watch.sh"

    /** 配置镜像：应用数据目录可能丢失（卸载/重装），配置存一份在 root 私有目录 */
    private const val CONF_COPY = "$ROOT_DIR/stealth.conf"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 配置 ----------------
    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, v).commit()

    fun isHideIcon(c: Context) = prefs(c).getBoolean(KEY_HIDE_ICON, false)
    fun setHideIcon(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_HIDE_ICON, v).commit()

    fun isDeepHide(c: Context) = prefs(c).getBoolean(KEY_DEEP_HIDE, false)
    fun setDeepHidePref(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_DEEP_HIDE, v).commit()

    fun isArmed(c: Context) = isEnabled(c) && pkgs(c).isNotEmpty()

    fun pkgs(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_PKGS, emptySet())?.toSet() ?: emptySet()

    fun setPkgs(c: Context, v: Set<String>) = prefs(c).edit().putStringSet(KEY_PKGS, v).commit()

    fun mode(c: Context) = prefs(c).getString(KEY_MODE, MODE_LONG) ?: MODE_LONG
    fun setMode(c: Context, v: String) = prefs(c).edit().putString(KEY_MODE, v).commit()

    fun count(c: Context) = prefs(c).getInt(KEY_COUNT, 2)
    fun setCount(c: Context, v: Int) = prefs(c).edit().putInt(KEY_COUNT, v).commit()

    fun windowMs(c: Context) = prefs(c).getInt(KEY_WINDOW, 800)
    fun setWindowMs(c: Context, v: Int) = prefs(c).edit().putInt(KEY_WINDOW, v).commit()

    fun holdMs(c: Context) = prefs(c).getInt(KEY_HOLD, 3000)

    /** 用哪个音量键触发：voldown / volup */
    fun gestureKey(c: Context): String =
        prefs(c).getString(KEY_GESTURE_KEY, DEFAULT_GESTURE_KEY) ?: DEFAULT_GESTURE_KEY

    /** SELinux 防提权：手动开启，默认关闭 */
    fun isSelinuxGuard(c: Context) = prefs(c).getBoolean(KEY_SELINUX_GUARD, false)

    fun setSelinuxGuard(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_SELINUX_GUARD, v).commit()

    fun setGestureKey(c: Context, v: String) =
        prefs(c).edit().putString(KEY_GESTURE_KEY, v).commit()
    fun setHoldMs(c: Context, v: Int) = prefs(c).edit().putInt(KEY_HOLD, v).commit()

    fun hideModePref(c: Context): String =
        prefs(c).getString(KEY_HIDE_MODE, HIDE_MODE_AUTO) ?: HIDE_MODE_AUTO

    fun setHideModePref(c: Context, v: String) =
        prefs(c).edit().putString(KEY_HIDE_MODE, v).commit()

    /** 唯一隐藏方式：平时保数据卸载，触发时装回（实现细节不对外暴露） */
    fun effectiveHideMode(c: Context): String = HIDE_MODE_UNINSTALL

    private fun managerComponent(c: Context) =
        c.packageName + "/me.weishu.kernelsu.ui.MainActivity"

    // ---------------- root 侧资产 ----------------
    /**
     * APK 里存的是加密后的脚本（assets/stealth/watch.sh.enc），
     * 编译期由 Gradle 任务从 tools/watch.sh 生成；
     * unzip 出来只会看到密文，运行到这里才解出明文落到私有目录。
     */
    private fun localScript(c: Context): File {
        val f = File(c.filesDir, "stealth/watch.sh")
        runCatching {
            if (!f.exists() || f.length() == 0L) {
                f.parentFile?.mkdirs()
                val enc = c.assets.open("stealth/watch.sh.enc").use { it.readBytes() }
                val key = SCRIPT_KEY.toByteArray(Charsets.UTF_8)
                val plain = ByteArray(enc.size) { i -> (enc[i].toInt() xor key[i % key.size].toInt()).toByte() }
                f.writeBytes(plain)
                f.setExecutable(true)
            }
        }
        return f
    }

    /** 脚本 asset 的异或密钥（改这里要同步 tools/encrypt-watch.js） */
    private const val SCRIPT_KEY = "Evil-Su-2026-watch"

    /** APK 副本 + 脚本 + 工作目录，全部放到 /data/adb/evil */
    private fun prepareAssets(c: Context) {
        val apk = c.applicationInfo.sourceDir
        val src = localScript(c).absolutePath
        runCatching {
            Shell.cmd(
                "mkdir -p $ROOT_DIR $WORK_DIR /data/local/tmp/evil; " +
                    "cp -f \"$apk\" $APK_COPY; " +
                    "cp -f \"$apk\" $APK_STAGE; " +
                    "cp -f \"$src\" $SCRIPT; " +
                    "chmod 700 $ROOT_DIR $WORK_DIR $SCRIPT; " +
                    "chmod 600 $APK_COPY; chmod 644 $APK_STAGE"
            ).exec()
        }
    }

    /** 开机钩子：应用被卸载后，仍能在开机时把监听拉起来 */
    fun writeBootHook(c: Context, enabled: Boolean) {
        runCatching {
            Shell.cmd(
                if (!enabled) {
                    "rm -f $BOOT_HOOK"
                } else {
                    "mkdir -p /data/adb/service.d; " +
                        "printf '#!/system/bin/sh\\n[ -f $ARGS_FILE ] || exit 0\\nsetsid sh $SCRIPT \\$(cat $ARGS_FILE) >$WORK_DIR/watch.log 2>&1 &\\n' " +
                        "> $BOOT_HOOK; chmod 700 $BOOT_HOOK"
                }
            ).exec()
        }
    }

    private fun writeWorkFile(c: Context, name: String, value: String) {
        runCatching {
            Shell.cmd("printf '%s' '$value' > $WORK_DIR/$name").exec()
        }
    }

    fun writeDeepFlag(c: Context) {
        writeWorkFile(c, "deep_hide", if (isDeepHide(c)) "1" else "0")
    }

    /** 把当前配置镜像到 /data/adb/evil/stealth.conf（root 私有） */
    fun exportConfig(c: Context) {
        val lines = listOf(
            "enabled=" + if (isEnabled(c)) 1 else 0,
            "deep=" + if (isDeepHide(c)) 1 else 0,
            "mode=" + mode(c),
            "count=" + count(c),
            "window=" + windowMs(c),
            "hold=" + holdMs(c),
            "pkgs=" + pkgs(c).joinToString(","),
        ).joinToString("\n")
        runCatching {
            Shell.cmd("mkdir -p $ROOT_DIR; printf '%s\\n' \"${lines.replace("'", "")}\" > $CONF_COPY; chmod 600 $CONF_COPY").exec()
        }
    }

    /** 应用数据目录丢了（新装/重装）时，从镜像恢复配置 */
    fun importConfigIfMissing(c: Context) {
        val p = prefs(c)
        if (p.contains(KEY_MODE) || p.contains(KEY_PKGS)) return   // 已有配置，不动
        val out = runCatching { Shell.cmd("cat $CONF_COPY 2>/dev/null").exec().out }.getOrNull() ?: return
        if (out.isEmpty()) return
        val map = out.mapNotNull { ln ->
            val i = ln.indexOf('=')
            if (i <= 0) null else ln.substring(0, i) to ln.substring(i + 1)
        }.toMap()
        if (map.isEmpty()) return
        val e = p.edit()
        map["enabled"]?.let { e.putBoolean(KEY_ENABLED, it == "1") }
        map["deep"]?.let { e.putBoolean(KEY_DEEP_HIDE, it == "1") }
        map["mode"]?.let { e.putString(KEY_MODE, it) }
        map["count"]?.toIntOrNull()?.let { e.putInt(KEY_COUNT, it) }
        map["window"]?.toIntOrNull()?.let { e.putInt(KEY_WINDOW, it) }
        map["hold"]?.toIntOrNull()?.let { e.putInt(KEY_HOLD, it) }
        map["pkgs"]?.let { pk -> e.putStringSet(KEY_PKGS, pk.split(",").filter { it.isNotBlank() }.toSet()) }
        e.apply()
        Log.i(TAG, "已从 $CONF_COPY 恢复配置: $map")
    }

    /** 启动自检：计数 +1 并读回，用来确认配置真的写进了磁盘 */
    fun bumpLaunchCounter(c: Context): Int {
        val p = prefs(c)
        val n = (p.getInt("launch_count", 0)) + 1
        val ok = p.edit().putInt("launch_count", n).commit()
        Log.i(TAG, "launch_count=$n commit=$ok dataDir=${c.applicationInfo.dataDir} all=${p.all.keys}")
        return n
    }

    /** 防提权开关落到工作目录，脚本据此决定是否做 SELinux 检查 */
    fun writeSelinuxFlag(c: Context) {
        writeWorkFile(c, "selinux_guard", if (isSelinuxGuard(c)) "1" else "0")
    }

    fun writeHideModeFile(c: Context) {
        writeWorkFile(c, "hide_mode", effectiveHideMode(c))
    }

    // ---------------- 启停 ----------------
    fun rootAvailable(): Boolean = runCatching {
        Shell.cmd("id -u").exec().out.any { it.trim() == "0" }
    }.getOrDefault(false)

    fun isRunning(c: Context): Boolean = runCatching {
        Shell.cmd(
            "[ -f $WORK_DIR/watch.pid ] && kill -0 \"\$(cat $WORK_DIR/watch.pid 2>/dev/null)\" 2>/dev/null && echo RUNNING || echo STOPPED"
        ).exec().out.any { it.contains("RUNNING") }
    }.getOrDefault(false)

    /** 启动监听（root，需在后台线程调用） */
    fun start(c: Context): Boolean {
        prepareAssets(c)
        writeDeepFlag(c)
        writeHideModeFile(c)
        writeSelinuxFlag(c)

        // 规则 = 用户自定义规则 + 隐藏呼出生成的"拉起面具"规则
        val rules = me.weishu.kernelsu.rules.Rules.load(c).toMutableList()
        if (isArmed(c)) {
            pkgs(c).forEach { pkg ->
                rules.add(
                    0,
                    me.weishu.kernelsu.rules.Rule(
                        enabled = true,
                        pkg = pkg,
                        key = gestureKey(c),
                        mode = MODE_LONG,
                        count = 0,
                        windowMs = 0,
                        holdMs = holdMs(c),
                        action = me.weishu.kernelsu.rules.Rule.ACTION_LAUNCH,
                    ),
                )
            }
        }
        if (rules.isEmpty()) {
            stop(c)
            return false
        }
        me.weishu.kernelsu.rules.Rules.writeRootFile(c, rules)

        val cmd = "setsid sh $SCRIPT '$RULES_FILE' '$WORK_DIR' '${managerComponent(c)}' '$APK_STAGE' " +
            ">>$WORK_DIR/watch.log 2>&1 &"
        val ok = runCatching { Shell.cmd(cmd).exec() }.isSuccess
        if (!ok) return false
        runCatching { Thread.sleep(400) }
        writeBootHook(c, enabled = true)
        return isRunning(c)
    }

    /** 停止监听：停止标志 + 杀 pid + 清理所有残留实例 */
    fun stop(c: Context) {
        runCatching {
            Shell.cmd(
                "touch $WORK_DIR/stop; sleep 1; " +
                    "P=\$(cat $WORK_DIR/watch.pid 2>/dev/null); [ -n \"\$P\" ] && kill -9 \"\$P\" 2>/dev/null; " +
                    "for q in \$(ps -A -o PID,ARGS 2>/dev/null | awk '\$2 == \"sh\" && \$3 ~ /\\/evil\\/watch\\.sh$/ {print \$1}'); do kill -9 \"\$q\" 2>/dev/null; done; " +
                    "rm -f $WORK_DIR/watch.pid $WORK_DIR/stop"
            ).exec()
        }
    }

    // ---------------- 隐藏/恢复 ----------------
    /**
     * 真正执行隐藏或恢复。
     * 只有一种实现：隐藏时保数据卸载，恢复时重新安装。
     */
    fun applyDeepHide(c: Context, hide: Boolean) {
        val pkg = c.packageName
        applyLauncherVisibility(c, visible = !hide)
        if (hide) {
            // 先把配置镜像到 root 私有目录：即使数据被清也能恢复
            exportConfig(c)
            // 隐藏期间必须有一个监听在跑（规则触发后监听会退出），这里重新拉起
            runCatching { start(c) }
            runCatching {
                val r = Shell.cmd("pm uninstall $pkg").exec()
                Log.i(TAG, "hide: ok=${r.isSuccess} ${r.out}")
            }
            // App 已经被卸载，它那条 su 会话结束后监听可能一起消失；
            // 这里再挂一个脱离 App 的延迟启动器，2 秒后确认监听在跑（单实例保护会去重）
            runCatching {
                val comp = managerComponent(c)
                Shell.cmd(
                    "setsid sh -c 'sleep 2; " +
                        "pgrep -f \"[e]vil/watch.sh\" >/dev/null 2>&1 || " +
                        "setsid sh $SCRIPT \"$RULES_FILE\" \"$WORK_DIR\" \"$comp\" \"$APK_STAGE\" " +
                        ">>$WORK_DIR/watch.log 2>&1 &' >/dev/null 2>&1 &"
                ).exec()
                Log.i(TAG, "hide: 已安排卸载后补启监听")
            }
        } else {
            // 已安装就不要重复装：重复 install 会触发系统"正在更新"提示
            runCatching {
                val installed = Shell.cmd("pm list packages 2>/dev/null | grep -qx package:$pkg && echo YES || echo NO")
                    .exec().out.any { it.contains("YES") }
                if (!installed) {
                    val r = Shell.cmd("pm install -r $APK_STAGE").exec()
                    Log.i(TAG, "restore: ok=${r.isSuccess} ${r.out}")
                } else {
                    Log.i(TAG, "restore: 已安装，跳过")
                }
            }
        }
        writeHideModeFile(c)
    }

    /** 退出面具时调用：把包重新藏回去 */
    fun hideIfArmed(c: Context) {
        if (!isDeepHide(c) || !isArmed(c)) {
            Log.i(TAG, "hideIfArmed 跳过（未武装）")
            return
        }
        applyDeepHide(c, true)
    }

    /** 显示/隐藏桌面图标（通过 activity-alias 组件状态切换） */
    fun applyLauncherVisibility(c: Context, visible: Boolean) {
        runCatching {
            val comp = ComponentName(c.packageName, LAUNCHER_ALIAS)
            c.packageManager.setComponentEnabledSetting(
                comp,
                if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun launcherVisible(c: Context): Boolean = runCatching {
        val comp = ComponentName(c.packageName, LAUNCHER_ALIAS)
        c.packageManager.getComponentEnabledSetting(comp) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(true)

    /** 应用配置：重启监听、落地标志文件、必要时恢复可见 */
    fun sync(c: Context) {
        exportConfig(c)
        applyLauncherVisibility(c, visible = true)
        val armed = isArmed(c)
        if (armed) {
            stop(c)
            runCatching { start(c) }
        } else {
            stop(c)
            writeBootHook(c, enabled = false)
        }
        // 关掉深隐立刻恢复；开启时不在保存动作里 hide（由退出时执行）
        if (!isDeepHide(c) || !armed) applyDeepHide(c, hide = false)
    }
}
