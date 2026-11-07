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
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.ksuApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 卡密网络验证客户端（服务地址与凭据由构建期 -P 属性注入，源码不含明文）。
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

    /** 校验服务基址，构建期由 -PPSU_CARDKEY_BASE 注入（BuildConfig）。 */
    private val BASE: String get() = BuildConfig.CARDKEY_BASE

    /**
     * 控制台「应用管理」里的凭证。
     *
     * 这里**不写明文**：明文常量会原样留在 dex 里，`strings` 一搜就出来（我自己就是这样验的）。
     * 存成异或后的十六进制串，运行时才拼回字符串；反编译看到的只是三段无意义数据。
     * 注意这只挡住"翻字符串"，挡不住真正的逆向（挂钩 [_key] / [_secret] 的调用点照样能拿到
     * 明文）—— 强度主要还是靠服务端的"每台设备要绑卡 + 可随时封禁"。
     *
     * 这三个值由构建期属性注入（见 manager/app/build.gradle.kts 的 PSU_CARDKEY_*），
     * 源码里不含任何明文或可逆掩码。
     */
    private val MASK_KEY_HEX: String get() = BuildConfig.CARDKEY_MASK
    private val APP_KEY_HEX: String get() = BuildConfig.CARDKEY_KEY
    private val APP_SECRET_HEX: String get() = BuildConfig.CARDKEY_SECRET

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
     * 设备双码（官方文档「设备双码」章节）：
     * - A 码 device_id = android_id，永远可得，参与签名
     * - B 码 device_b  = IMEI，需要 root；拿不到就留空，之后能读到会自动补进服务端空槽
     *
     * 后缀 -a / -b 只是给人看的约定，服务端原样存储、按字段名归槽。
     */
    private val androidId: String by lazy {
        val prefs = ksuApp.getSharedPreferences(CardKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString("android_id", null) ?: run {
            val raw = runCatching {
                Settings.Secure.getString(ksuApp.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("android_id", raw).commit()
            raw
        }
    }

    /** A 码：AndroidID 一类 */
    val deviceId: String by lazy { "evil-$androidId-a" }

    /** B 码：IMEI 一类；读不到返回 null（不放进请求） */
    fun deviceB(): String? {
        val prefs = ksuApp.getSharedPreferences(CardKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString("imei", null)?.let { return "evil-$it-b" }
        val imei = readImei() ?: return null
        prefs.edit().putString("imei", imei).commit()
        android.util.Log.i("KsCardKey", "B 码已缓存")
        return "evil-$imei-b"
    }

    /** 通过 root 读 IMEI：先试属性（快），再用 iphonesubinfo 兜底 */
    private fun readImei(): String? = runCatching {
        val cmd = "for p in persist.radio.imei1 persist.radio.imei ro.ril.oem.imei ro.ril.oem.imei1; do " +
            "v=\$(getprop \$p 2>/dev/null); " +
            "case \"\$v\" in [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]*) echo \$v; exit 0;; esac; done; " +
            "service call iphonesubinfo 1 s16 com.android.shell 2>/dev/null"
        val r = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
        val out = r.out.joinToString(" ")
        Regex("[0-9]{15}").find(out)?.value?.let { return@runCatching it }
        Regex("0x([0-9a-fA-F]{8})").findAll(out)
            .map { it.groupValues[1].takeLast(4) }
            .mapNotNull { h -> h.toIntOrNull(16)?.takeIf { c -> c in 0x30..0x39 }?.toChar() }
            .joinToString("")
            .takeIf { it.length >= 14 }
    }.getOrNull()

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
        deviceB()?.let { params["device_b"] = it }
        if (code != null) params["code"] = code
        return params
    }

    /**
     * 一次请求。[useGet] = true 时参数走 query string。
     *
     * 为什么要有 GET：这台服务端的 nginx 只给 /api/verify、/api/activate 配了 GET 路由，
     * POST 直接 404（返回 nginx 的 HTML）。所以先 POST，拿到非 JSON 响应就自动退回 GET。
     */
    /**
     * GZ1 加密通道（官方文档「加密通道（GZ1）」）：
     * - key = HKDF-SHA256(app_secret, salt="gzv1|app_key|device_id", info="gz-payload", 32)
     * - AES-256-GCM，12 字节 IV，AAD = "gzv1|app_key|device_id|timestamp"
     * - 报文 `GZ1.` + base64url(IV‖TAG‖密文)，响应用 AAD "gzv1|resp|timestamp" 解密
     *
     * 明文通道在部分部署上被关闭（直接返回“参数不完整”），所以优先走这里。
     */
    private suspend fun callGz(path: String, code: String?): Outcome = withContext(Dispatchers.IO) {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val dev = deviceId
        val payload = JSONObject().apply {
            put("timestamp", ts)
            put("device_id", dev)
            deviceB()?.let { put("device_b", it) }
            put("nonce", UUID.randomUUID().toString().replace("-", "").take(32))
            if (code != null) put("code", code)
        }
        val key = hkdfSha256(_secret.toByteArray(), "gzv1|$_key|$dev".toByteArray(), "gz-payload".toByteArray(), 32)
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        // 长度混淆：尾部随机补 0~96 个空格
        val plain = payload.toString() + " ".repeat((0..96).random())
        cipher.updateAAD("gzv1|$_key|$dev|$ts".toByteArray())
        // Java 的 GCM doFinal() 返回「密文‖标签」，协议要求「IV‖标签‖密文」，这里要拆开重排
        val sealed = cipher.doFinal(plain.toByteArray())
        val tagLen = 16
        val ct = sealed.copyOfRange(0, sealed.size - tagLen)
        val tag = sealed.copyOfRange(sealed.size - tagLen, sealed.size)
        val body = "GZ1." + b64u(iv + tag + ct)
        val request = Request.Builder().url(BASE + path)
            .post(body.toRequestBody("text/plain".toMediaType()))
            .header("X-GZ-App", _key)
            .header("X-GZ-Device", dev)
            .header("X-GZ-Ts", ts)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val plainText = if (text.startsWith("GZ1.")) decryptGz(text, key, ts) ?: text else text
                val json = runCatching { JSONObject(plainText) }.getOrNull()
                    ?: return@withContext Outcome(false, -1, "HTTP ${response.code}", networkError = true)
                val data = json.optJSONObject("data")
                val hb = data?.optInt("heartbeat", 0) ?: 0
                Outcome(
                    ok = json.optInt("code", -1) == 0,
                    code = json.optInt("code", -1),
                    msg = json.optString("msg", "").ifBlank { "HTTP ${response.code}" },
                    expireAt = data?.optString("expire_at", null)
                        ?.takeIf { it.isNotBlank() && it != "null" },
                    heartbeat = if (hb > 0) hb else null,
                )
            }
        } catch (t: Throwable) {
            Outcome(false, -1, t.message ?: t.javaClass.simpleName, networkError = true)
        }
    }

    private fun b64u(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    private fun decryptGz(text: String, key: ByteArray, ts: String): String? = runCatching {
        val b64 = text.removePrefix("GZ1.").trim()
            .replace('-', '+').replace('_', '/')
            .let { if (it.length % 4 == 0) it else it + "=".repeat(4 - it.length % 4) }
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(12, 28)
        val ct = raw.copyOfRange(28, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("gzv1|resp|$ts".toByteArray())
        String(cipher.doFinal(ct + tag)).trim()
    }.getOrNull()

    /** HKDF-SHA256（extract + expand），Android 上没有现成 API */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        var t = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < len) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(i.toByte()))
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }

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
        val gz = callGz(path, code)
        if (!gz.networkError) {
            android.util.Log.w("CardKey", "$path GZ1 -> code=${gz.code} msg=${gz.msg}")
            return gz
        }
        android.util.Log.w("CardKey", "$path GZ1 不可用（${gz.msg}），退回明文通道")
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
