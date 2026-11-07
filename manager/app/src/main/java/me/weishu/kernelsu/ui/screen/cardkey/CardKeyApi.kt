package me.weishu.kernelsu.ui.screen.cardkey

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 鸽子网络验证（https://sad.nogay.cn）客户端。
 *
 * 每个请求带 `app_key` / `device_id` / `timestamp` / `nonce` / `sign`：
 *
 *     sign = md5(app_key + device_id + timestamp + nonce + app_secret)   // 32 位小写
 *
 * 接口：
 *   POST /api/activate   首次使用：卡密与设备绑定并算有效期，返回 expire_at / bound_count / max_devices
 *   POST /api/verify     运行中校验；code 可省（按已绑定设备识别），返回 expire_at / heartbeat
 *   POST /api/heartbeat  保活，按返回的 heartbeat 秒数定时调用
 *   POST /api/logout     标记设备离线
 *   GET  /api/notice     公告，无需签名
 *
 * 服务端错误码：0 成功 / 400 参数 / 401 签名或时间戳 / 403 封禁·过期·未绑定·设备数满 /
 * 404 应用或卡密不存在 / 429 频率限制 / 500 服务端错误。
 */
object CardKeyApi {

    private const val BASE = "https://sad.nogay.cn"

    /**
     * 控制台「应用管理」里的凭证。
     *
     * 这里**不写明文**：明文常量会原样留在 dex 里，`strings` 一搜就出来（我自己就是这样验的）。
     * 存成异或后的十六进制串，运行时才拼回字符串；反编译看到的只是三段无意义数据。
     * 注意这只挡住"翻字符串"，挡不住真正的逆向（挂钩 [_key] / [_secret] 的调用点照样能拿到
     * 明文）—— 强度主要还是靠服务端的"每台设备要绑卡 + 可随时封禁"。
     *
     * 换应用时用 `node tools/encode-credentials.mjs <appKey> <appSecret>` 重新生成这三行。
     */
    private const val MASK_KEY_HEX = "39dd321b8bfb393e"
    private const val APP_KEY_HEX = "78967128bfb8017d0a990722b8b97c0808ec"
    private const val APP_SECRET_HEX =
        "58ed017ab9ca0d5d0beb0a2eb8cf015b01b9022fbecb0f0e0abc042ebacf005f"

    private fun unmask(hex: String): String {
        val mask = hexToBytes(MASK_KEY_HEX)
        val data = hexToBytes(hex)
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    // by lazy：别在类初始化时就把明文拼出来常驻堆里
    private val _key: String by lazy { unmask(APP_KEY_HEX) }
    private val _secret: String by lazy { unmask(APP_SECRET_HEX) }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 不用 ksuApp.okhttpClient：那个是在 Application.onCreate 里建的，设备未解锁时不会初始化；
    // 卡密页是进 App 的第一屏，自己建一个更稳，也方便单独设超时。
    private val client by lazy {
        OkHttpClient.Builder()
            // callTimeout 覆盖含 DNS 解析在内的整次调用；只设 connect/read 时，
            // DNS 卡住会让 execute() 一直阻塞，协程的 withTimeout 也打断不了它，
            // 表现就是"一直转圈"。
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 一次接口调用的结果。 */
    data class Outcome(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val expireAt: String? = null,
        val heartbeat: Int? = null,
        /** true = 压根没连上服务端，和"服务端明确拒绝"要分开提示 */
        val networkError: Boolean = false,
    )

    /**
     * 设备唯一标识。优先用 ANDROID_ID（无需权限，同签名同用户下稳定），
     * 取不到就随机生成一个并持久化 —— 两者都会记住，避免每次启动换一个设备把名额占满。
     */
    val deviceId: String by lazy {
        val prefs = ksuApp.getSharedPreferences(CardKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: run {
            val raw = runCatching {
                Settings.Secure.getString(ksuApp.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
            val seed = if (raw.isNotBlank()) raw else UUID.randomUUID().toString().replace("-", "")
            val value = "sad-" + seed.take(28)
            prefs.edit().putString("device_id", value).apply()
            value
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 公共参数（签名含 nonce，与线上服务端一致；文档里那句不含 nonce 的说法已过时）。 */
    private fun buildParams(code: String?): LinkedHashMap<String, String> {
        val ts = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString().replace("-", "").take(24)
        val params = linkedMapOf(
            "app_key" to _key,
            "device_id" to deviceId,
            "timestamp" to ts.toString(),
            "nonce" to nonce,
            "sign" to md5(_key + deviceId + ts + nonce + _secret),
        )
        if (code != null) params["code"] = code
        return params
    }

    /**
     * 一次请求。[useGet] = true 时参数走 query string。
     *
     * 为什么要有 GET：这台服务端的 nginx 只给 /api/verify、/api/activate 配了 GET 路由，
     * POST 直接 404（返回 nginx 的 HTML）。所以先 POST，拿到非 JSON 响应就自动退回 GET。
     */
    private suspend fun call(path: String, code: String?, useGet: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            try {
                val params = buildParams(code)
                val request = if (useGet) {
                    val query = params.entries.joinToString("&") { (k, v) ->
                        k + "=" + URLEncoder.encode(v, "UTF-8")
                    }
                    Request.Builder().url(BASE + path + "?" + query).get().build()
                } else {
                    val json = JSONObject().apply { params.forEach { (k, v) -> put(k, v) } }
                    Request.Builder().url(BASE + path)
                        .post(json.toString().toRequestBody(JSON)).build()
                }
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) {
                        return@withContext Outcome(false, -1, "HTTP ${response.code}", networkError = true)
                    }
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: // 不是 JSON（nginx 404 之类），当作"这条通道不可用"，交给上层换 GET 重试
                        return@withContext Outcome(
                            false, -1, "HTTP ${response.code}", networkError = true,
                        )
                    val data = json.optJSONObject("data")
                    val hb = data?.optInt("heartbeat", 0) ?: 0
                    Outcome(
                        ok = json.optInt("code", -1) == 0,
                        code = json.optInt("code", -1),
                        msg = json.optString("msg", "").ifBlank { "HTTP ${response.code}" },
                        expireAt = data?.optString("expire_at", null)?.takeIf { it.isNotBlank() && it != "null" },
                        heartbeat = if (hb > 0) hb else null,
                    )
                }
            } catch (t: Throwable) {
                Outcome(false, -1, t.message ?: t.javaClass.simpleName, networkError = true)
            }
        }

    /** 服务端这两条只开了 GET 路由（POST 会 404），所以先走 GET，失败再试 POST。 */
    private val getFirst = setOf("/api/verify", "/api/activate")

    private suspend fun post(path: String, code: String?): Outcome {
        val first = call(path, code, useGet = path in getFirst)
        if (!first.networkError) {
            android.util.Log.w("CardKey", "$path -> code=${first.code} msg=${first.msg}")
            return first
        }
        val second = call(path, code, useGet = path !in getFirst)
        android.util.Log.w("CardKey", "$path -> 回退后 code=${second.code} msg=${second.msg}")
        return if (second.networkError) first else second
    }

    suspend fun verify(code: String? = null): Outcome = post("/api/verify", code)

    suspend fun activate(code: String): Outcome = post("/api/activate", code)

    suspend fun heartbeat(): Outcome = post("/api/heartbeat", null)

    suspend fun logout(): Outcome = post("/api/logout", null)

    /**
     * 卡密页的入口：先用 [verify]（已绑定的设备走这条），不行再 [activate]（首次绑定这台设备）。
     * 两个都失败时优先报 activate 的话 —— 它更具体（已过期 / 已封禁 / 设备数已达上限）。
     */
    suspend fun authenticate(code: String): Outcome {
        val verified = verify(code)
        if (verified.ok) return verified
        if (verified.networkError) return verified

        val activated = activate(code)
        return when {
            activated.ok -> activated
            activated.networkError -> activated
            else -> activated.copy(msg = activated.msg.ifBlank { verified.msg })
        }
    }

    // ── 心跳保活 ────────────────────────────────────────────────────────
    // 进管理器之后按服务端给的间隔定时报到；断网就跳过一次，授权没了（403/404）就停。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    fun startHeartbeat(intervalSeconds: Int) {
        val interval = intervalSeconds.coerceIn(30, 3600)
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(interval * 1000L)
                val result = heartbeat()
                if (result.networkError) continue
                if (result.code == 403 || result.code == 404) break
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
