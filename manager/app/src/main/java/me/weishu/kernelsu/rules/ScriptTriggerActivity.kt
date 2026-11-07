package me.weishu.kernelsu.rules

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本触发（内置规则引擎）
 *
 * 规则 = 触发条件（应用 / 音量键手势）+ 动作（运行脚本 / 拉起面具）。
 * 规则写入 /data/adb/evil/rules.conf，由 root 侧 watch.sh 执行。
 */
class ScriptTriggerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { RulesScreen(this, onBack = { finish() }) }
        }
    }
}

private data class PkgEntry(val pkg: String, val label: String)

private fun loadApps(activity: ComponentActivity): List<PkgEntry> {
    val pm = activity.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0).asSequence()
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .map { PkgEntry(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RulesScreen(activity: ScriptTriggerActivity, onBack: () -> Unit) {
    val ctx = activity
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Rule>().apply { addAll(Rules.load(ctx)) } }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var status by remember { mutableStateOf("") }

    fun persist() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Rules.save(ctx, list.toList()) }
            status = if (ok) "已保存，规则已生效" else "保存失败（需要 root）"
            withContext(Dispatchers.IO) {
                runCatching { me.weishu.kernelsu.stealth.Stealth.sync(ctx) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("脚本触发") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = {
                        editing = Rule()
                    }) { Text("新增") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (status.isNotEmpty()) {
                Text(status, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (list.isEmpty()) {
                Text(
                    "还没有规则。新增一条：选择应用（或留空=任意应用）→ 选音量键手势 → 指定要运行的脚本。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                items(list) { rule ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = {
                                    rule.enabled = it
                                    list[list.indexOf(rule)] = rule
                                    persist()
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).clickable { editing = rule }) {
                                Text(rule.summary(), fontWeight = FontWeight.Medium)
                                if (rule.script.isNotEmpty() && rule.action == Rule.ACTION_SCRIPT) {
                                    Text(rule.script, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = {
                                list.remove(rule); persist()
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        RuleEditor(
            activity = activity,
            initial = rule,
            isNew = list.none { it === rule },
            onDismiss = { editing = null },
            onSave = { updated ->
                val idx = list.indexOfFirst { it === rule }
                if (idx >= 0) list[idx] = updated else list.add(updated)
                editing = null
                persist()
            },
        )
    }
}

@Composable
private fun RuleEditor(
    activity: ScriptTriggerActivity,
    initial: Rule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    var rule by remember { mutableStateOf(initial.copy()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增规则" else "编辑规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("触发条件", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rule.pkg.isEmpty()) "任意应用" else rule.pkg,
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showAppPicker = true }) { Text("选择应用") }
                }
                Text("音量键手势", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "无", "volup" to "音量+", "voldown" to "音量−").forEach { (v, l) ->
                        FilterChip(selected = rule.key == v, onClick = { rule = rule.copy(key = v) },
                            label = { Text(l) })
                    }
                }
                if (rule.key.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1500, 2000, 3000, 5000).forEach { ms ->
                            FilterChip(
                                selected = rule.holdMs == ms,
                                onClick = {
                                    rule = rule.copy(mode = Rule.MODE_LONG, holdMs = ms)
                                },
                                label = { Text("长按 ${ms / 1000.0}s") },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("执行动作", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = rule.action == Rule.ACTION_SCRIPT,
                        onClick = { rule = rule.copy(action = Rule.ACTION_SCRIPT) },
                        label = { Text("运行脚本") })
                    FilterChip(selected = rule.action == Rule.ACTION_LAUNCH,
                        onClick = { rule = rule.copy(action = Rule.ACTION_LAUNCH) },
                        label = { Text("拉起面具") })
                }

                if (rule.action == Rule.ACTION_SCRIPT) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = rule.script,
                        onValueChange = { rule = rule.copy(script = it) },
                        label = { Text("脚本路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { showFilePicker = true }) { Text("浏览选择") }
                    OutlinedTextField(
                        value = rule.input,
                        onValueChange = { rule = rule.copy(input = it) },
                        label = { Text("自动输入（按行喂给脚本）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = rule.enter,
                            onCheckedChange = { rule = rule.copy(enter = it) })
                        Spacer(Modifier.width(8.dp))
                        Text("每行末尾补回车", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(rule) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showAppPicker) {
        AppPickDialog(activity, rule.pkg, { showAppPicker = false }) { picked ->
            rule = rule.copy(pkg = picked); showAppPicker = false
        }
    }
    if (showFilePicker) {
        FilePickDialog(rule.script, { showFilePicker = false }) { picked ->
            rule = rule.copy(script = picked); showFilePicker = false
        }
    }
}

@Composable
private fun AppPickDialog(
    activity: ScriptTriggerActivity,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val apps = remember { loadApps(activity) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, true) || it.pkg.contains(query, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text("搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 10.dp)) {
                    Text("任意应用（不限制）")
                }
                LazyColumn(Modifier.height(380.dp)) {
                    items(filtered) { app ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(app.pkg) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (app.pkg == current) "●" else "○", Modifier.width(24.dp))
                            Column {
                                Text(app.label)
                                Text(app.pkg, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun FilePickDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var cwd by remember { mutableStateOf(current.substringBeforeLast('/', "/sdcard").ifEmpty { "/" }) }
    var entries by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var err by remember { mutableStateOf("") }

    suspend fun reload() {
        val out = withContext(Dispatchers.IO) {
            runCatching {
                Shell.cmd("ls -1p '${cwd.replace("'", "'\\''")}' 2>/dev/null").exec().out
            }.getOrElse { emptyList() }
        }
        entries = out.filter { it.isNotBlank() }.map { it.trimEnd() to it.endsWith("/") }
            .sortedWith(compareBy({ !it.second }, { it.first.lowercase() }))
        err = if (entries.isEmpty()) "无法读取目录（需要 root）" else ""
    }

    LaunchedEffect(cwd) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择脚本") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (cwd != "/") cwd = cwd.substringBeforeLast('/').ifBlank { "/" } }) {
                        Text("↑ 上级")
                    }
                    Text(cwd, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onPick(cwd.trimEnd('/') + "/") }) { Text("选当前目录") }
                if (err.isNotEmpty()) Text(err, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.height(340.dp)) {
                    items(entries) { (name, dir) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val full = if (cwd == "/") "/$name" else "$cwd/$name"
                            if (dir) cwd = full.trimEnd('/') else onPick(full)
                        }.padding(vertical = 10.dp)) {
                            Text(if (dir) "📁 $name" else "📄 $name")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
