#!/system/bin/sh
# =====================================================================
#  面具隐藏呼出：特定应用内「音量键手势」拉起管理器
#  用法:
#    watch.sh <包名逗号列表> <long|click> <连点次数> <窗口ms> <长按ms> <工作目录> <组件名>
#  行为:
#    - 只在目标应用位于前台时才开启 getevent（离开目标应用立刻关闭 -> 无额外开销）
#    - 命中手势后执行 am start 拉起管理器，随后自行回到待机
#    - 由 root 以 setsid 脱离会话运行；停止标志 = <工作目录>/stop
#  输出: 逐行写日志（调用方把 stdout 重定向到 watch.log）
# =====================================================================

PKGS="$1"
MODE="${2:-long}"
COUNT="${3:-2}"
WIN="${4:-800}"
HOLD="${5:-3000}"
WORK="${6:-/data/local/tmp}"
COMP="${7:-}"
DEEPHIDE_ARG="${8:-0}"
APK="${9:-/data/adb/evil/evil.apk}"
ROOT_DIR=$(dirname "$APK")

if [ "$(id -u 2>/dev/null)" != "0" ]; then
	echo "ERROR NO_ROOT"
	exit 1
fi

mkdir -p "$WORK" 2>/dev/null
PIDFILE="$WORK/watch.pid"
# 单实例保护：已有存活实例就直接退出，避免多个 watcher 抢着解隐藏/隐藏
if [ -f "$PIDFILE" ]; then
	_old=$(cat "$PIDFILE" 2>/dev/null)
	# 只认"确实是本脚本"的进程：pid 会被系统复用，光看 kill -0 会误判
	if [ -n "$_old" ] && [ -r "/proc/$_old/cmdline" ] && \
		tr '\0' ' ' < "/proc/$_old/cmdline" 2>/dev/null | grep -q "sad/watch.sh"; then
		echo "已有一个实例在运行 (pid=$_old)，退出"
		exit 0
	fi
	rm -f "$PIDFILE" 2>/dev/null
fi
STOPFLAG="$WORK/stop"
FIFO="$WORK/ev.fifo"
echo $$ > "$PIDFILE"
rm -f "$STOPFLAG" 2>/dev/null

log() { echo "[$(date '+%H:%M:%S')] $*"; }

cleanup() {
	[ -n "$GE_PID" ] && kill "$GE_PID" 2>/dev/null
	exec 3>&- 2>/dev/null
	rm -f "$FIFO" "$PIDFILE" 2>/dev/null
	log "watcher 退出"
	exit 0
}
trap 'cleanup' TERM INT HUP

log "watcher 启动 pid=$$ mode=$MODE count=$COUNT win=${WIN}ms hold=${HOLD}ms 目标=[$PKGS]"

# ---------- 前台应用 ----------
fg_pkg() {
	_o=$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity')
	[ -n "$_o" ] || _o=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp')
	[ -n "$_o" ] || return 1
	printf '%s' "$_o" | grep -oE '[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+' | head -n1 | cut -d/ -f1
}

is_target() {
	[ -n "$1" ] || return 1
	case ",$PKGS," in
		*",$1,"*) return 0 ;;
	esac
	return 1
}

# ---------- getevent 生命周期 ----------
GE_PID=""
start_ge() {
	stop_ge
	rm -f "$FIFO" 2>/dev/null
	mkfifo "$FIFO" 2>/dev/null || { log "无法创建 FIFO"; return 1; }
	exec 3<>"$FIFO" 2>/dev/null || { log "无法打开 FIFO"; return 1; }
	getevent -l >"$FIFO" 2>/dev/null &
	GE_PID=$!
	log "开始监听音量键 (pid=$GE_PID)"
	return 0
}
stop_ge() {
	if [ -n "$GE_PID" ]; then
		kill "$GE_PID" 2>/dev/null
		GE_PID=""
		log "停止监听音量键"
	fi
	exec 3>&- 2>/dev/null
	rm -f "$FIFO" 2>/dev/null
}

# ---------- 手势状态 ----------
PRESS_KEY=""
PRESS_TICK=0
LONG_FIRED=0
CLICKS=0
CLICK_KEY=""
FIRST_TICK=0
LAST_TICK=0

reset_state() {
	PRESS_KEY=""
	LONG_FIRED=0
	CLICKS=0
	CLICK_KEY=""
	FIRST_TICK=0
	LAST_TICK=0
}

fire() {
	_gesture=$1
	log "触发 $MODE/$_gesture → 拉起管理器"
	if [ -n "$COMP" ]; then
		_pkg=${COMP%%/*}
		# 深隐标志以文件为准，改配置无需重启监听
		DEEPHIDE=$DEEPHIDE_ARG
		[ -f "$WORK/deep_hide" ] && DEEPHIDE=$(cat "$WORK/deep_hide" 2>/dev/null)
		if [ "$DEEPHIDE" = "1" ]; then
			[ -f "$APK" ] || APK=/data/adb/evil/evil.apk
			# 已经装着就不要再装：否则系统每次弹"正在更新"，还会闪一下新旧状态
			if ! pm list packages 2>/dev/null | grep -q "^package:$_pkg$"; then
				if [ -f "$APK" ]; then
					mkdir -p /data/local/tmp/evil 2>/dev/null
					cp -f "$APK" /data/local/tmp/evil/sad.apk 2>/dev/null
					chmod 644 /data/local/tmp/evil/sad.apk 2>/dev/null
					log "安装中…"
					echo 1 > "$WORK/self_install" 2>/dev/null
					pm install -r /data/local/tmp/evil/sad.apk >/dev/null 2>&1
				fi
				# 等系统注册完成：ColorOS 上装完立刻启动会被拦到应用管理
				_i=0
				while [ "$_i" -lt 25 ]; do
					pm list packages 2>/dev/null | grep -q "^package:$_pkg$" && break
					sleep 0.2
					_i=$((_i + 1))
				done
				sleep 1
				sleep 4
				rm -f "$WORK/self_install" 2>/dev/null
			fi
		fi
		am start -W -n "$COMP" --es stealth 1 >/dev/null 2>&1 \
			|| am start -n "$COMP" --es stealth 1 >/dev/null 2>&1 \
			|| monkey -p "$_pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
			|| log "启动失败: $COMP"
		# 规则触发完成：监听自动退出，避免重复安装/误判；
		# 下次点"结束进程并隐藏"时由 App 重新拉起监听。
		log "已拉起面具，监听退出（等待下次隐藏时重启）"
		cleanup
	fi
	reset_state
	# 管理器到前台后 fg 不再是目标应用，下一轮自动 stop_ge
}

handle_line() {
	case "$1" in
		*KEY_VOLUMEUP*) _k=volup ;;
		*KEY_VOLUMEDOWN*) _k=voldown ;;
		*) return 0 ;;
	esac
	case "$1" in
		*" DOWN"*)
			PRESS_KEY=$_k
			PRESS_TICK=$TICK
			LONG_FIRED=0
			;;
		*" UP"*)
			[ "$PRESS_KEY" = "$_k" ] || return 0
			_was_long=$LONG_FIRED
			PRESS_KEY=""
			LONG_FIRED=0
			[ "$_was_long" = "1" ] && return 0    # 长按已触发，松手不再当连点
			[ "$MODE" = "click" ] || return 0
			if [ "$CLICK_KEY" != "$_k" ]; then
				CLICKS=0
				CLICK_KEY=$_k
			fi
			# 超出窗口则重新计数
			if [ "$CLICKS" -gt 0 ] && [ $(( (TICK - FIRST_TICK) * 200 )) -gt "$WIN" ]; then
				CLICKS=0
			fi
			[ "$CLICKS" = "0" ] && FIRST_TICK=$TICK
			CLICKS=$((CLICKS + 1))
			LAST_TICK=$TICK
			[ "$CLICKS" -ge "$COUNT" ] && fire "click${COUNT}"
			;;
	esac
}

# ---------- 主循环 ----------
ACTIVE=0
TICK=0

# 初始化包状态：只做记录，绝不在这里修复（否则会把正在运行的 App 当成"手动安装"）
if [ -n "$COMP" ]; then
	_ip=${COMP%%/*}
	if pm list packages 2>/dev/null | grep -q "^package:$_ip$"; then
		echo present > "$WORK/pkg_state" 2>/dev/null
	else
		echo absent > "$WORK/pkg_state" 2>/dev/null
	fi
fi

while :; do
	if [ -f "$STOPFLAG" ]; then
		rm -f "$STOPFLAG"
		cleanup
	fi

	# 每 5 个 tick（约 1 秒）检查一次前台
	if [ $((TICK % 5)) -eq 0 ]; then
		_fg=$(fg_pkg)
		if is_target "$_fg"; then
			if [ "$ACTIVE" != "1" ]; then
				ACTIVE=1
				log "进入目标应用: $_fg"
				start_ge
			fi
		else
			if [ "$ACTIVE" != "0" ]; then
				ACTIVE=0
				log "离开目标应用 (当前: ${_fg:-未知})"
				stop_ge
			fi
			reset_state
		fi
	fi

	if [ "$ACTIVE" = "1" ] && [ -n "$GE_PID" ]; then
		if IFS= read -r -t 0.2 _line <&3 2>/dev/null; then
			handle_line "$_line"
		fi
	else
		sleep 0.2
	fi

	TICK=$((TICK + 1))

	# ---- 手动安装检测（每 2 秒轮询一次）----
	# 只有"本监听运行期间 从不存在变成存在"才算手动安装；
	# 启动时就已在的包不处理（否则会把正在用的 App 当成手动安装并 pm clear 掉）。
	if [ -n "$COMP" ] && [ $((TICK % 10)) -eq 0 ]; then
		_dp=${COMP%%/*}
		if pm list packages 2>/dev/null | grep -q "^package:$_dp$"; then
			_prev=$(cat "$WORK/pkg_state" 2>/dev/null)
			[ "$_prev" = "present" ] || echo present > "$WORK/pkg_state" 2>/dev/null
			if [ "$_prev" = "absent" ] && [ ! -f "$WORK/self_install" ] && [ -z "$(pidof "$_dp" 2>/dev/null)" ]; then
				log "检测到手动安装：恢复显示并清除数据"
				pm enable "$_dp" >/dev/null 2>&1
				pm unhide "$_dp" >/dev/null 2>&1
				pm enable "$_dp/me.weishu.kernelsu.LauncherAlias" >/dev/null 2>&1
				pm clear "$_dp" >/dev/null 2>&1
				rm -f "$ROOT_DIR/stealth.conf" 2>/dev/null
				echo 0 > "$WORK/deep_hide" 2>/dev/null
				echo manual > "$WORK/manual_install" 2>/dev/null
				log "已恢复；监听退出，等待重新配置"
				cleanup
			fi
		else
			[ "$(cat "$WORK/pkg_state" 2>/dev/null)" = "absent" ] || echo absent > "$WORK/pkg_state" 2>/dev/null
		fi
	fi

	# 长按判定：按住不放达到阈值即触发
	if [ "$ACTIVE" = "1" ] && [ "$MODE" = "long" ] && [ -n "$PRESS_KEY" ] && [ "$LONG_FIRED" = "0" ]; then
		if [ $(( (TICK - PRESS_TICK) * 200 )) -ge "$HOLD" ]; then
			LONG_FIRED=1
			fire "long"
		fi
	fi
done
