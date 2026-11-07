package me.weishu.kernelsu.stealth

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 隐藏与呼出设置页。
 *
 * 单独一个 Activity：不依赖 Miuix / Material 两套主界面的设置项结构，
 * 主界面只留一个入口跳过来。
 */
class StealthSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                StealthSettingsScreen(this, onBack = { finish() })
            }
        }
    }
}

private data class AppEntry(val pkg: String, val label: String)

private fun loadLaunchableApps(activity: ComponentActivity): List<AppEntry> {
    val pm = activity.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val list = pm.queryIntentActivities(intent, 0)
    return list.asSequence()
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != activity.packageName }
        .map { AppEntry(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StealthSettingsScreen(
    activity: StealthSettingsActivity,
    onBack: () -> Unit,
) {
    val ctx = activity
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(Stealth.isEnabled(ctx)) }
    var deepHide by remember { mutableStateOf(Stealth.isDeepHide(ctx)) }
    var pkgs by remember { mutableStateOf(Stealth.pkgs(ctx)) }
    var mode by remember { mutableStateOf(Stealth.mode(ctx)) }
    var count by remember { mutableStateOf(Stealth.count(ctx)) }
    var windowMs by remember { mutableStateOf(Stealth.windowMs(ctx)) }
    var holdMs by remember { mutableStateOf(Stealth.holdMs(ctx)) }

    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var running by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    suspend fun refresh() {
        rootOk = withContext(Dispatchers.IO) { Stealth.rootAvailable() }
        running = withContext(Dispatchers.IO) { Stealth.isRunning(ctx) }
    }

    LaunchedEffect(Unit) { refresh() }

    fun applyConfig() {
        Stealth.setEnabled(ctx, enabled)
        Stealth.setPkgs(ctx, pkgs)
        Stealth.setMode(ctx, mode)
        Stealth.setCount(ctx, count)
        Stealth.setWindowMs(ctx, windowMs)
        Stealth.setHoldMs(ctx, holdMs)
        Stealth.setDeepHidePref(ctx, deepHide && enabled && pkgs.isNotEmpty())
        scope.launch {
            statusMsg = "正在应用…"
            val ok = withContext(Dispatchers.IO) {
                runCatching { Stealth.sync(ctx) }.isSuccess
            }
            refresh()
            statusMsg = if (ok) "已应用" else "应用失败（检查 root）"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("隐藏与呼出") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 状态 ----
            val rootText = when (rootOk) {
                null -> "root：检测中…"
                true -> "root：可用"
                false -> "root：不可用（隐藏与手势需要 root，App 本身照常可用）"
            }
            Text(
                text = rootText + "    监听：" + if (running) "运行中" else "未运行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // ---- 隐藏面具 ----
            Text("隐藏面具", fontWeight = FontWeight.SemiBold)
            // 无 root 时无法用音量键呼出兜底，隐藏图标将无法自行恢复 -> 直接禁用
            val canHideNow = enabled && pkgs.isNotEmpty() && rootOk == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("隐藏面具")
                    Text(
                        if (canHideNow)
                            "开启后离开面具即从系统中隐藏；在选中的应用里按音量键可再次唤出。保存后本页会立即关闭"
                        else
                            "需要先启用呼出并选择应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = deepHide,
                    // 开启后仍可关闭；关闭后也可再次开启（前提是呼出已配好）
                    enabled = canHideNow || deepHide,
                    onCheckedChange = { deepHide = it },
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // ---- 呼出方式 ----
            Text("呼出方式", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用音量键呼出")
                    Text(
                        if (deepHide) "已开启隐藏：需先关闭「隐藏面具」才能关闭呼出，否则会进不去"
                        else "只在选中的应用里生效；离开该应用后自动停止监听",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = !deepHide,
                    onCheckedChange = {
                        enabled = it
                        applyConfig()
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手势：", modifier = Modifier.width(56.dp))
                FilterChip(
                    selected = mode == Stealth.MODE_LONG,
                    onClick = { mode = Stealth.MODE_LONG },
                    label = { Text("长按") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == Stealth.MODE_CLICK,
                    onClick = { mode = Stealth.MODE_CLICK },
                    label = { Text("连点") },
                )
            }

            if (mode == Stealth.MODE_LONG) {
                Text("长按时长", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1500, 2000, 3000, 5000).forEach { ms ->
                        FilterChip(
                            selected = holdMs == ms,
                            onClick = { holdMs = ms },
                            label = { Text("${ms / 1000.0}s") },
                        )
                    }
                }
            } else {
                Text("连点次数", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 4).forEach { n ->
                        FilterChip(
                            selected = count == n,
                            onClick = { count = n },
                            label = { Text("$n 次") },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("连点窗口", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(800, 1500, 2500).forEach { w ->
                        FilterChip(
                            selected = windowMs == w,
                            onClick = { windowMs = w },
                            label = { Text("${w / 1000.0}s") },
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // ---- 目标应用 ----
            Text("生效的应用", fontWeight = FontWeight.SemiBold)
            Text(
                if (pkgs.isEmpty()) "未选择（必须至少选一个应用，呼出才会启用）"
                else "已选 ${pkgs.size} 个：" + pkgs.take(6).joinToString("、") + if (pkgs.size > 6) " …" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showPicker = true }) { Text("选择应用") }
                TextButton(onClick = { pkgs = emptySet() }) { Text("清空") }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { applyConfig() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存并应用") }

            if (statusMsg.isNotEmpty()) {
                Text(statusMsg, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "说明：掉 root 后手势监听会自动失效（不会有残留进程），" +
                    "本应用仍可正常打开、重新安装；重新获得 root 后再打开本页保存一次即可恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPicker) {
        AppPickerDialog(
            activity = activity,
            selected = pkgs,
            onDismiss = { showPicker = false },
            onConfirm = {
                pkgs = it
                showPicker = false
                applyConfig()
            },
        )
    }
}

@Composable
private fun AppPickerDialog(
    activity: StealthSettingsActivity,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }
    val apps = remember { loadLaunchableApps(activity) }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用（${picked.size} 已选）") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(filtered) { app ->
                        val checked = picked.contains(app.pkg)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (checked) picked - app.pkg else picked + app.pkg
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (checked) "☑" else "☐", modifier = Modifier.width(28.dp))
                            Column {
                                Text(app.label)
                                Text(
                                    app.pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
