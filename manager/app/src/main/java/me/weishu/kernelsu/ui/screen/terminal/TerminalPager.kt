package me.weishu.kernelsu.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.createRootShell
import java.io.BufferedWriter
import java.io.OutputStreamWriter

private data class RootEntry(val name: String, val directory: Boolean)
private data class RootResult(val code: Int, val stdout: String, val stderr: String)

@Composable
fun TerminalPager(bottomPadding: Dp) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val terminalScroll = rememberScrollState()
    var cwd by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf(emptyList<RootEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingScript by remember { mutableStateOf<String?>(null) }
    var terminalVisible by remember { mutableStateOf(false) }
    var terminalOutput by remember { mutableStateOf("") }
    var terminalTitle by remember { mutableStateOf("Evil Root Terminal") }
    var command by remember { mutableStateOf("") }
    var sessionRunning by remember { mutableStateOf(false) }
    val session = remember {
        InteractiveRootSession(
            onOutput = { chunk ->
                mainHandler.post {
                    terminalOutput = (terminalOutput + chunk).takeLast(MAX_TERMINAL_CHARS)
                }
            },
            onExit = { code ->
                mainHandler.post {
                    terminalOutput = (terminalOutput + "\n[进程退出，退出码：$code]\n").takeLast(MAX_TERMINAL_CHARS)
                    sessionRunning = false
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { session.close() }
    }

    suspend fun reload() {
        loading = true
        error = ""
        runCatching { listRootDirectory(cwd) }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "无法读取目录" }
        loading = false
    }

    fun openTerminal(title: String = "Evil Root Terminal", initialCommand: String? = null) {
        terminalVisible = true
        terminalTitle = title
        terminalOutput = ""
        session.close()
        sessionRunning = session.start(cwd)
        if (sessionRunning && initialCommand != null) {
            terminalOutput = "root@evil:$cwd # $initialCommand\n"
            session.send(initialCommand)
        }
    }

    fun run(commandText: String) {
        if (commandText.isBlank()) return
        if (!sessionRunning) openTerminal()
        terminalOutput = (terminalOutput + "\nroot@evil:$cwd # $commandText\n").takeLast(MAX_TERMINAL_CHARS)
        session.send(commandText)
    }

    LaunchedEffect(cwd, refreshKey, terminalVisible) {
        if (!terminalVisible) reload()
    }

    LaunchedEffect(terminalOutput) {
        if (terminalVisible) terminalScroll.scrollTo(terminalScroll.maxValue)
    }

    pendingScript?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingScript = null },
            title = { Text("执行脚本") },
            text = { Text("是否执行此脚本？\n$script") },
            dismissButton = { TextButton(onClick = { pendingScript = null }) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    pendingScript = null
                    openTerminal(script.substringAfterLast('/'), "sh ${shellQuote(script)}")
                }) { Text("确定") }
            },
        )
    }

    if (terminalVisible) {
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { session.close(); sessionRunning = false; terminalVisible = false; refreshKey++ }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text(terminalTitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Night terminal output", stripAnsi(terminalOutput)))
                }) { Text("复制") }
            }
            Text(
                text = ansiText(terminalOutput.ifBlank { "root@evil:$cwd #" }),
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .padding(10.dp).verticalScroll(terminalScroll),
                color = Color(0xFFE8E8E8),
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("root@evil:$cwd") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(onClick = { terminalOutput = "" }, modifier = Modifier.weight(1f)) { Text("清屏") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { val value = command; command = ""; run(value) },
                    enabled = command.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("执行") }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("执行", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { refreshKey++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
        }
        Row(
            Modifier.fillMaxWidth().clickable {
                if (cwd != "/") cwd = cwd.substringBeforeLast('/').ifBlank { "/" }
            }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "上级目录")
            Spacer(Modifier.width(8.dp))
            Text(cwd, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        }
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            error.isNotBlank() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Text("请确认已安装 v2 签名的管理器且内核已授权。")
                Button(onClick = { refreshKey++ }) { Text("重试") }
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.name }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val full = if (cwd == "/") "/${entry.name}" else "$cwd/${entry.name}"
                            if (entry.directory) cwd = full else pendingScript = full
                        }.padding(horizontal = 6.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (entry.directory) Color(0xFFFFC857) else Color(0xFF4EA1FF),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Button(onClick = { openTerminal() }, modifier = Modifier.fillMaxWidth()) { Text("打开交互终端") }
    }
}

private suspend fun listRootDirectory(path: String): List<RootEntry> = withContext(Dispatchers.IO) {
    val quoted = shellQuote(path)
    val command = """
        test "${'$'}(id -u)" = 0 || { echo '未获得 Root 管理器授权' >&2; exit 126; }
        cd $quoted || exit 1
        for item in .[!.]* ..?* *; do
          [ -e "${'$'}item" ] || continue
          if [ -d "${'$'}item" ]; then printf 'D\t%s\n' "${'$'}item"; else printf 'F\t%s\n' "${'$'}item"; fi
        done
    """.trimIndent()
    val result = runRoot(command)
    if (result.code != 0) error(result.stderr.ifBlank { "Root 目录读取失败（${result.code}）" })
    result.stdout.lineSequence().mapNotNull { line ->
        val type = line.substringBefore('\t', "")
        val name = line.substringAfter('\t', "")
        if (name.isBlank() || name == "." || name == "..") null else RootEntry(name, type == "D")
    }.sortedWith(compareBy<RootEntry> { !it.directory }.thenBy { it.name.lowercase() }).toList()
}

private fun runRoot(command: String): RootResult {
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = createRootShell(globalMnt = true).use { shell ->
        shell.newJob().add(command).to(stdout, stderr).exec()
    }
    return RootResult(result.code, stdout.joinToString("\n"), stderr.joinToString("\n"))
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private const val MAX_TERMINAL_CHARS = 250_000

private class InteractiveRootSession(
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null

    fun start(cwd: String): Boolean = runCatching {
        val child = ProcessBuilder("su").redirectErrorStream(true).start()
        process = child
        writer = BufferedWriter(OutputStreamWriter(child.outputStream))
        send("cd ${shellQuote(cwd)}")
        Thread {
            runCatching {
                child.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        onOutput(String(buffer, 0, count))
                    }
                }
            }
            val code = runCatching { child.waitFor() }.getOrDefault(-1)
            if (process === child) {
                process = null
                writer = null
                onExit(code)
            }
        }.apply { name = "EvilRootTerminal"; isDaemon = true }.start()
        true
    }.getOrElse { error ->
        onOutput("无法启动 su 终端：${error.message}\n请在设置中启用传统 SU 命令支持。\n")
        false
    }

    @Synchronized fun send(text: String) {
        runCatching {
            writer?.apply { write(text); newLine(); flush() }
                ?: onOutput("终端会话未运行。\n")
        }.onFailure { onOutput("写入终端失败：${it.message}\n") }
    }

    @Synchronized fun close() {
        val child = process ?: return
        runCatching { writer?.apply { write("exit"); newLine(); flush() } }
        runCatching { child.destroy() }
        process = null
        writer = null
    }
}

private fun stripAnsi(value: String): String = value.replace(Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")

private fun ansiText(value: String): AnnotatedString {
    val regex = Regex("\\u001B\\[([0-9;]*)m")
    var cursor = 0
    var color = Color(0xFFE8E8E8)
    return buildAnnotatedString {
        regex.findAll(value).forEach { match ->
            if (match.range.first > cursor) {
                pushStyle(SpanStyle(color = color))
                append(value.substring(cursor, match.range.first))
                pop()
            }
            match.groupValues[1].split(';').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }.forEach { code ->
                color = when (code) {
                    0, 39 -> Color(0xFFE8E8E8)
                    30 -> Color(0xFF888888)
                    31, 91 -> Color(0xFFFF5F56)
                    32, 92 -> Color(0xFF52D273)
                    33, 93 -> Color(0xFFFFD75F)
                    34, 94 -> Color(0xFF5FAFFF)
                    35, 95 -> Color(0xFFFF5FFF)
                    36, 96 -> Color(0xFF5FFFFF)
                    37, 97 -> Color.White
                    else -> color
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < value.length) {
            pushStyle(SpanStyle(color = color))
            append(value.substring(cursor))
            pop()
        }
    }
}
