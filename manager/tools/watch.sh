#!/system/bin/sh
# =====================================================================
#  Evil 规则引擎（root 常驻，setsid 脱离会话）
#
#  用法: watch.sh <规则文件> <工作目录> <管理器组件> <APK 路径>
#
#  规则文件每行 11 段，用 | 分隔：
#    enabled|包名|按键|模式|次数|窗口ms|长按ms|动作|脚本|输入b64|回车
#      enabled : 1/0
#      包名    : 留空 = 任意应用
#      按键    : volup / voldown；留空 = 不要求手势（进入应用即触发）
#      模式    : click / long
#      动作    : launch（拉起管理器） / script（跑脚本）
#      脚本    : 绝对路径（动作=script 时必填）
#      输入b64 : 喂给脚本 stdin 的内容，base64 编码；- 表示无
#      回车    : 1 = 每行补换行
# =====================================================================

RULES="${1:-/data/adb/evil/rules.conf}"
WORK="${2:-/data/adb/evil/work}"
COMP="${3:-}"
APK="${4:-/data/adb/evil/evil.apk}"

if [ "$(id -u 2>/dev/null)" != "0" ]; then
	echo "ERROR NO_ROOT"
	exit 1
fi

mkdir -p "$WORK" 2>/dev/null
PIDFILE="$WORK/watch.pid"
STOPFLAG="$WORK/stop"
EVFILE="$WORK/ev.log"
CHUNK="$WORK/ev.chunk"
EV_LIMIT=262144
ROOT_DIR=$(dirname "$APK")

# SELinux 看门狗：每秒检查一次，发现被切成 permissive 就处理
#   SELINUX_ACTION=reboot → 立即硬重启（发行版默认）
#   SELINUX_ACTION=log    → 只写日志，不重启（测试用）
SELINUX_ACTION="${SELINUX_ACTION:-reboot}"
SELINUX_HIT=0

# 单实例保护：只认确实是本脚本的进程（pid 会被复用）
if [ -f "$PIDFILE" ]; then
	_old=$(cat "$PIDFILE" 2>/dev/null)
	if [ -n "$_old" ] && [ -r "/proc/$_old/cmdline" ] && \
		tr '\0' ' ' < "/proc/$_old/cmdline" 2>/dev/null | grep -q "watch.sh"; then
		echo "已有一个实例在运行 (pid=$_old)，退出"
		exit 0
	fi
	rm -f "$PIDFILE" 2>/dev/null
fi
echo $$ > "$PIDFILE"
rm -f "$STOPFLAG" 2>/dev/null

LOG="$WORK/watch.log"

# 这套 ROM 的 toybox `sleep 0.2` 会不定期卡住几十秒，优先用 KernelSU 的 busybox
# 净化环境：宿主 shell 的 LD_* 会让 am / monkey / app_process 链接失败
unset LD_LIBRARY_PATH LD_PRELOAD LD_LIBRARY_PATH_64 2>/dev/null
export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin

BB=""
for _bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
	[ -x "$_bb" ] && { BB=$_bb; break; }
done
nap() {
	if [ -n "$BB" ]; then "$BB" sleep 0.2 2>/dev/null; else sleep 1; fi
}
log() { echo "[$(date '+%H:%M:%S')] $*"; }
now_ms() {
	set -- $(cat /proc/uptime 2>/dev/null)
	[ -n "$1" ] || { echo 0; return; }
	echo $(( ${1%.*} * 1000 + ${1#*.} * 10 ))
}

cleanup() {
	stop_ge
	rm -f "$WORK"/ev.*.log "$CHUNK" "$PIDFILE" 2>/dev/null
	log "watcher 退出"
	exit 0
}
trap 'cleanup' TERM INT HUP

log "watcher 启动 pid=$$ rules=$RULES"

# ---------- 前台应用 ----------
fg_pkg() {
	_o=$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity')
	[ -n "$_o" ] || _o=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp')
	[ -n "$_o" ] || return 1
	printf '%s' "$_o" | grep -oE '[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+' | head -n1 | cut -d/ -f1
}

# 通过 su 执行：uid=0 直接调 binder 会被拒（Failed transaction），套一层 su 才正常
asroot() {
	_o=$(eval "$1" 2>/dev/null </dev/null)
	_rc=$?
	if [ "$_rc" -eq 0 ] && [ -n "$_o" ]; then
		printf '%s\n' "$_o"
		return 0
	fi
	# 直接调用被拒（非 su 会话启动时会 Failed transaction）才套一层 su
	su -c "$1" </dev/null 2>/dev/null
}

pkg_installed() { asroot "pm list packages" | grep -q "^package:$1$"; }

# 前台包名：按需查询 + 1 秒缓存（后台常驻 dumpsys 会把 system_server 压垮，
# 进而让 pm / am 报 "Failed transaction"）
FG_CACHE=""
FG_TS=0
cached_fg() {
	_now=$(now_ms)
	if [ -n "$FG_CACHE" ] && [ $((_now - FG_TS)) -lt 1000 ]; then
		printf '%s' "$FG_CACHE"
		return 0
	fi
	FG_CACHE=$(fg_pkg 2>/dev/null)
	FG_TS=$_now
	[ -n "$FG_CACHE" ] && printf '%s' "$FG_CACHE" > "$WORK/fg" 2>/dev/null
	printf '%s' "$FG_CACHE"
}

# ---------- getevent ----------
# 这台 ROM 上 `getevent -l`（不带设备）只打印设备头、不流式输出事件，
# 必须自己找出带音量键的设备再显式监听。
GE_PIDS=""
VOLDEVS=""
EV_COUNT=0

find_vol_devs() {
	_out=""
	for _d in /dev/input/event*; do
		[ -e "$_d" ] || continue
		if getevent -p "$_d" 2>/dev/null | grep -E "^ +KEY \(0001\):.*(0072|0073)" >/dev/null; then
			_out="$_out $_d"
		fi
	done
	printf '%s' "$_out"
}

start_ge() {
	stop_ge
	VOLDEVS=$(find_vol_devs)
	if [ -z "$VOLDEVS" ]; then
		log "未找到音量键输入设备，稍后重试"
		return 1
	fi
	EV_COUNT=0
	for _d in $VOLDEVS; do
		EV_COUNT=$((EV_COUNT + 1))
		_ef="$WORK/ev.$EV_COUNT.log"
		: >"$_ef" 2>/dev/null
		eval "EV_OFF_$EV_COUNT=0"
		# 直接重定向到文件会被 stdio 缓冲（4KB 才刷），必须走伪终端按行输出
		if [ -n "$BB" ]; then
			"$BB" script -q -c "getevent -l $_d" /dev/null >>"$_ef" 2>/dev/null &
		else
			getevent -l "$_d" >>"$_ef" 2>/dev/null &
		fi
		GE_PIDS="$GE_PIDS $!"
	done
	log "监听音量键设备:$VOLDEVS"
	return 0
}

stop_ge() {
	for _p in $GE_PIDS; do kill "$_p" 2>/dev/null; done
	GE_PIDS=""
}

ge_alive() {
	[ -n "$GE_PIDS" ] || return 1
	for _p in $GE_PIDS; do
		kill -0 "$_p" 2>/dev/null || return 1
	done
	return 0
}

drain_events() {
	_i=1
	while [ "$_i" -le "$EV_COUNT" ]; do
		_f="$WORK/ev.$_i.log"
		eval "_off=\$EV_OFF_$_i"
		_sz=$(stat -c %s "$_f" 2>/dev/null)
		[ -n "$_sz" ] || _sz=0
		if [ "$_sz" -lt "$_off" ]; then _off=0; fi
		if [ "$_sz" -gt "$_off" ]; then
			tail -c +$((_off + 1)) "$_f" >"$CHUNK" 2>/dev/null
			while IFS= read -r _line; do handle_line "$_line"; done <"$CHUNK"
			_off=$_sz
		fi
		if [ "$_sz" -gt "$EV_LIMIT" ]; then
			: >"$_f" 2>/dev/null
			_off=0
		fi
		eval "EV_OFF_$_i=$_off"
		_i=$((_i + 1))
	done
}

# ---------- 动作执行 ----------
# 拉起管理器：没装则装回；拉起后监听退出（等下次隐藏时重启）
do_launch() {
	_dp=${COMP%%/*}
	if ! pkg_installed "$_dp"; then
		[ -f "$APK" ] || APK=/data/adb/evil/evil.apk
		if [ -f "$APK" ]; then
			mkdir -p /data/local/tmp/evil 2>/dev/null
			cp -f "$APK" /data/local/tmp/evil/evil.apk 2>/dev/null
			chmod 644 /data/local/tmp/evil/evil.apk 2>/dev/null
			echo 1 >"$WORK/self_install" 2>/dev/null
			log "安装中…"
			_il=$(asroot "pm install -r /data/local/tmp/evil/evil.apk" 2>&1 </dev/null)
			log "安装结果: $(printf '%s' "$_il" | tail -1)"
			# 等包注册 + 等活动组件可解析（只等 pm list 不够，装完立刻启动会失败）
			_i=0
			while [ "$_i" -lt 50 ]; do
				pkg_installed "$_dp" && break
				nap
				_i=$((_i + 1))
			done
			_i=0
			while [ "$_i" -lt 50 ]; do
				[ -n "$(asroot "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $_dp")" ] && break
				nap
				_i=$((_i + 1))
			done
			_i=0
			while [ "$_i" -lt 25 ]; do
				asroot "dumpsys package $_dp" | grep -q "installed=true" && break
				nap
				_i=$((_i + 1))
			done
			sleep 1
			rm -f "$WORK/self_install" 2>/dev/null
		fi
	fi
	_ok=0
	_try=0
	while [ "$_try" -lt 8 ]; do
		_try=$((_try + 1))
		# cmd activity 与 am 走不同入口，安装刚结束时前者更稳
		if asroot "cmd activity start-activity -n $COMP --es stealth 1" >/dev/null 2>&1 \
			|| asroot "am start -W -n $COMP --es stealth 1" >/dev/null 2>&1 \
			|| asroot "am start -n $COMP --es stealth 1" >/dev/null 2>&1; then
			_ok=1
			break
		fi
		[ -n "$(pidof "$_dp" 2>/dev/null)" ] && { _ok=1; break; }
		[ "$_try" = "8" ] && log "启动失败($_try 次): $COMP"
		sleep 2
	done
	if [ "$_ok" != "1" ]; then
		monkey -p "$_dp" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && _ok=2
	fi
	[ "$_ok" = "1" ] && log "已拉起（pid=$(pidof "$_dp" 2>/dev/null)）"
	log "已拉起管理器，监听退出（等待下次隐藏时重启）"
	cleanup
}

# 跑脚本：把输入内容按行喂进 stdin
do_script() {
	_s=$1; _b64=$2; _enter=$3
	[ -f "$_s" ] || { log "脚本不存在: $_s"; return 1; }
	_in=""
	if [ -n "$_b64" ] && [ "$_b64" != "-" ]; then
		_in=$(printf '%s' "$_b64" | base64 -d 2>/dev/null)
	fi
	[ "$_enter" = "1" ] && [ -n "$_in" ] && _in=$(printf '%s\n' "$_in")
	log "运行脚本 $(basename "$_s")（输入：$([ -n "$_in" ] && echo 有 || echo 无)）"
	{
		printf '\n===== %s =====\nscript: %s\n' "$(date '+%F %T')" "$_s"
	} >>"$WORK/exec.log" 2>/dev/null
	if [ -n "$_in" ]; then
		printf '%s' "$_in" | ( cd "$(dirname "$_s")" 2>/dev/null; sh "$_s" ) >>"$WORK/exec.log" 2>&1
	else
		( cd "$(dirname "$_s")" 2>/dev/null; sh "$_s" ) >>"$WORK/exec.log" 2>&1
	fi
	return 0
}

run_action() { # action script input_b64 enter
	log "触发动作: $1 ${2:-}"
	case "$1" in
		launch) do_launch ;;
		script) do_script "$2" "$3" "$4" ;;
	esac
}

# ---------- 规则匹配 ----------
# 退出码 0 = 有规则命中；命中时逐条执行
match_rules() { # pkg key mode count
	_p=$1; _k=$2; _m=$3; _n=$4
	[ -f "$RULES" ] || return 1
	_hit=0
	while IFS='|' read -r r_en r_pkg r_key r_mode r_count r_win r_hold r_act r_scr r_inp r_enter || [ -n "$r_en" ]; do
		case "$r_en" in 1) ;; *) continue ;; esac
		[ "$r_key" = "$_k" ] || continue
		[ "$r_mode" = "$_m" ] || continue
		if [ "$_m" = click ]; then
			[ "${r_count:-0}" = "$_n" ] || continue
		fi
		if [ -n "$r_pkg" ]; then
			[ "$_p" = "$r_pkg" ] || continue
		fi
		_hit=1
		run_action "$r_act" "$r_scr" "$r_inp" "$r_enter"
	done <"$RULES"
	return $((_hit == 1 ? 0 : 1))
}

# 进入应用即触发（无手势规则）
match_app_rules() {
	_p=$1
	[ -f "$RULES" ] || return 1
	while IFS='|' read -r r_en r_pkg r_key r_mode r_count r_win r_hold r_act r_scr r_inp r_enter || [ -n "$r_en" ]; do
		case "$r_en" in 1) ;; *) continue ;; esac
		[ -z "$r_key" ] || continue
		[ -n "$r_pkg" ] || continue
		[ "$r_pkg" = "$_p" ] || continue
		run_action "$r_act" "$r_scr" "$r_inp" "$r_enter"
	done <"$RULES"
}

# 某键的长按阈值（取最小）
hold_for_key() {
	_min=""
	[ -f "$RULES" ] || return 1
	while IFS='|' read -r r_en r_pkg r_key r_mode r_count r_win r_hold r_act r_scr r_inp r_enter || [ -n "$r_en" ]; do
		case "$r_en" in 1) ;; *) continue ;; esac
		[ "$r_key" = "$1" ] || continue
		[ "$r_mode" = long ] || continue
		_h=${r_hold:-0}
		[ "$_h" -le 0 ] && continue
		if [ -z "$_min" ] || [ "$_h" -lt "$_min" ]; then _min=$_h; fi
	done <"$RULES"
	[ -n "$_min" ] && printf '%s\n' "$_min"
}

click_window_for() { # key count
	_w=800
	[ -f "$RULES" ] || { echo "$_w"; return; }
	while IFS='|' read -r r_en r_pkg r_key r_mode r_count r_win r_hold r_act r_scr r_inp r_enter || [ -n "$r_en" ]; do
		case "$r_en" in 1) ;; *) continue ;; esac
		[ "$r_key" = "$1" ] || continue
		[ "$r_mode" = click ] || continue
		[ "${r_count:-0}" = "$2" ] || continue
		[ -n "$r_win" ] && [ "$r_win" -gt 0 ] && _w=$r_win
	done <"$RULES"
	echo "$_w"
}

has_higher_click_rule() {
	[ -f "$RULES" ] || return 1
	while IFS='|' read -r r_en r_pkg r_key r_mode r_count r_win r_hold r_act r_scr r_inp r_enter || [ -n "$r_en" ]; do
		case "$r_en" in 1) ;; *) continue ;; esac
		[ "$r_key" = "$1" ] || continue
		[ "$r_mode" = click ] || continue
		[ "${r_count:-0}" -gt "$2" ] && return 0
	done <"$RULES"
	return 1
}

# ---------- 手势状态 ----------
PRESS_KEY=""; PRESS_TS=0; LONG_FIRED=0

reset_gesture() {
	PRESS_KEY=""; LONG_FIRED=0
}

handle_line() {
	case "$1" in
		*KEY_VOLUMEUP*) _k=volup ;;
		*KEY_VOLUMEDOWN*) _k=voldown ;;
		*) return 0 ;;
	esac
	case "$1" in
		*" DOWN"*)
			PRESS_KEY=$_k; PRESS_TS=$(now_ms); LONG_FIRED=0
			;;
		*" UP"*)
			[ "$PRESS_KEY" = "$_k" ] || return 0
			_elapsed=$(( $(now_ms) - PRESS_TS ))
			_h=$(hold_for_key "$_k")
			PRESS_KEY=""
			if [ -z "$_h" ]; then
				return 0
			fi
			if [ "$LONG_FIRED" = "1" ]; then
				# 按住期间已经触发过，松手只复位，不再触发一次
				LONG_FIRED=0
			elif [ "$_elapsed" -ge "$_h" ]; then
				LONG_FIRED=0
				_fg=$(cached_fg)
				match_rules "$_fg" "$_k" long 0 || log "长按 ${_k} 未命中规则（前台=${_fg:-未知}）"
			else
				log "短按 ${_k}（${_elapsed}ms < ${_h}ms，不触发）"
			fi
			;;
	esac
}

# ---------- 主循环 ----------
EV_OFF=0
GE_FAIL=0
start_ge
ACTIVE_FG=""
TICK=0

# 记录初始包状态（只记录，不修复）
_dp=${COMP%%/*}
if [ -n "$_dp" ]; then
	pkg_installed "$_dp" && echo present >"$WORK/pkg_state" 2>/dev/null || echo absent >"$WORK/pkg_state" 2>/dev/null
fi

while :; do
	if [ -f "$STOPFLAG" ]; then rm -f "$STOPFLAG"; cleanup; fi

	if ! ge_alive; then
		GE_FAIL=$((GE_FAIL + 1))
		if [ $((GE_FAIL % 5)) -eq 1 ]; then
			log "getevent 掉线，重启（第 $GE_FAIL 次）"
			start_ge
			EV_OFF=0
		fi
	else
		GE_FAIL=0
	fi
	drain_events

	# 长按判定
	if [ -n "$PRESS_KEY" ] && [ "$LONG_FIRED" = "0" ]; then
		_h=$(hold_for_key "$PRESS_KEY")
		if [ -n "$_h" ] && [ $(( $(now_ms) - PRESS_TS )) -ge "$_h" ]; then
			LONG_FIRED=1
			_fg=$(cached_fg)
			match_rules "$_fg" "$PRESS_KEY" long 0 || log "长按 ${PRESS_KEY} 未命中规则（前台=${_fg:-未知}）"
		fi
	fi

	# 每 5 tick（1s）：SELinux 防提权（默认关闭，需在设置里手动开启）
	if [ $((TICK % 5)) -eq 0 ] && [ "$SELINUX_HIT" = "0" ] \
		&& [ "$(cat "$WORK/selinux_guard" 2>/dev/null)" = "1" ]; then
		_se=$(getenforce 2>/dev/null)
		[ -n "$_se" ] || _se=$(cat /sys/fs/selinux/enforce 2>/dev/null)
		case "$_se" in
			Enforcing|1|"") ;;
			*)
				SELINUX_HIT=1
				log "警告: SELinux 已不是 Enforcing（当前=$_se）"
				if [ "$SELINUX_ACTION" = "reboot" ]; then
					log "按策略执行硬重启"
					sync
					reboot -f
				else
					log "测试模式（SELINUX_ACTION=$SELINUX_ACTION），仅记录不重启"
				fi
				;;
		esac
	fi

	# 每 5 tick（1s）：应用触发
	if [ $((TICK % 5)) -eq 0 ]; then
		_fg=$(cached_fg)
		if [ -n "$_fg" ] && [ "$_fg" != "$ACTIVE_FG" ]; then
			ACTIVE_FG="$_fg"
			match_app_rules "$_fg"
		fi
	fi

	# 每 10 tick（2s）：手动安装检测（只认 从无到有 且 App 未运行）
	if [ -n "$_dp" ] && [ $((TICK % 10)) -eq 0 ]; then
		if pkg_installed "$_dp"; then
			_prev=$(cat "$WORK/pkg_state" 2>/dev/null)
			[ "$_prev" = "present" ] || echo present >"$WORK/pkg_state" 2>/dev/null
			if [ "$_prev" = "absent" ] && [ ! -f "$WORK/self_install" ] && [ -z "$(pidof "$_dp" 2>/dev/null)" ]; then
				log "检测到手动安装：恢复显示并清除数据"
				asroot "pm enable "$_dp"" >/dev/null 2>&1
				asroot "pm unhide "$_dp"" >/dev/null 2>&1
				asroot "pm enable "$_dp/me.weishu.kernelsu.LauncherAlias"" >/dev/null 2>&1
				asroot "pm clear "$_dp"" >/dev/null 2>&1
				rm -f "$ROOT_DIR/stealth.conf" 2>/dev/null
				echo 0 >"$WORK/deep_hide" 2>/dev/null
				log "已恢复；监听退出，等待重新配置"
				cleanup
			fi
		else
			[ "$(cat "$WORK/pkg_state" 2>/dev/null)" = "absent" ] || echo absent >"$WORK/pkg_state" 2>/dev/null
		fi
	fi

	nap
	TICK=$((TICK + 1))
done
