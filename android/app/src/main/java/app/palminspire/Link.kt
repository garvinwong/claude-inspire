package app.palminspire

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class PairConfig(val relay: String, val token: String, val me: String, val peer: String, val key: ByteArray, val pc: String)

/**
 * 手机侧链路：长连接收件、命令 → 回执（按 ref 对上，分片合并）、推送（L1 / L2 / L4）、附件分片。
 * 只在 App 打开时运行，由 AppState 起停。
 */
class Link(
    private val cfg: PairConfig,
    private val t: Transport,
    seqStore: SeqStore,
    private val scope: CoroutineScope,
    private val pollSeconds: Int = 25,
) {
    enum class Conn { OFF, CONNECTING, ONLINE, ERROR }

    val conn = MutableStateFlow(Conn.OFF)
    val pcAge = MutableStateFlow<Int?>(null)          // 中枢最近一次取件距今秒数（null = 从没上线）
    val lastError = MutableStateFlow("")
    // 推送排队等消费（没人订阅时也不丢；只有 AppState 一个消费者）
    private val pushChannel = Channel<Pair<String, JSONObject>>(capacity = 256)
    val pushes: Flow<Pair<String, JSONObject>> = pushChannel.receiveAsFlow()

    private val sealer = Sealer(cfg.key, Crypto.M2PC, cfg.me)
    private val opener = Opener(cfg.key, Crypto.PC2M, seqStore)
    private val blob = BlobCipher(cfg.key, Crypto.BLOB_M2PC, Crypto.BLOB_PC2M)

    private class Pending(val done: CompletableDeferred<JSONObject>, val parts: MutableList<JSONObject> = mutableListOf())
    private val calls = ConcurrentHashMap<String, Pending>()
    private val pushParts = HashMap<String, MutableList<JSONObject>>()
    private var job: Job? = null
    private var peerJob: Job? = null
    var since = 0L; private set
    private var boot: String? = null
    var bad = 0; private set

    fun start() {
        if (job?.isActive == true) return
        conn.value = Conn.CONNECTING
        job = scope.launch(Dispatchers.IO) { loop() }
        peerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val p = t.peers().optJSONObject("peers")
                    pcAge.value = if (p == null || p.isNull(cfg.peer)) null else p.optInt(cfg.peer)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(10_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); peerJob?.cancel()
        job = null; peerJob = null
        conn.value = Conn.OFF
    }

    val running get() = job?.isActive == true

    private suspend fun loop() {
        var backoff = 0L
        while (scope.isActive) {
            try {
                val r = t.inbox(since, pollSeconds)
                val b = r.optString("boot")
                if (boot != null && b != boot) since = 0      // 中转重启过：序号从头
                boot = b
                for (it in r.optJSONArray("items").objects()) {
                    since = maxOf(since, it.optLong("rseq"))
                    it.optJSONObject("env")?.let(::handle)
                }
                conn.value = Conn.ONLINE
                backoff = 0
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                conn.value = Conn.ERROR
                lastError.value = e.message ?: e.javaClass.simpleName
                backoff = minOf(30_000L, backoff * 2 + 2_000)
                delay(backoff)
            }
        }
    }

    fun handle(env: JSONObject) {
        val (kind, msg) = try { opener.open(env) } catch (e: EnvelopeException) { bad++; return }
        route(kind, msg)
    }

    private fun listKey(m: JSONObject) = when {
        m.has("events") -> "events"
        m.has("sessions") -> "sessions"
        m.has("items") -> "items"                    // 斜杠命令清单
        else -> null
    }

    private fun merge(parts: List<JSONObject>, key: String?): JSONObject {
        val sorted = parts.sortedBy { it.optInt("part") }
        val out = JSONObject(sorted.first().toString())
        if (key != null) {
            val all = JSONArray()
            sorted.forEach { p -> p.optJSONArray(key).objects().forEach { all.put(it) } }
            out.put(key, all)
        }
        out.remove("part"); out.remove("parts")
        return out
    }

    private fun route(kind: String, msg: JSONObject) {
        val ref = msg.optString("ref")
        val key = listKey(msg)
        if (ref.isNotEmpty()) {
            val p = calls[ref] ?: return                       // 已超时放弃的回执：丢掉
            // 只有带列表（events / sessions / items）的才是分片回执；图片回执里的 parts 是附件分片数，不能合并（真机实测）
            if (key != null && msg.has("parts")) {
                p.parts += msg
                if (p.parts.size >= msg.optInt("parts", 1)) {
                    calls.remove(ref); p.done.complete(merge(p.parts, key))
                }
            } else {
                calls.remove(ref); p.done.complete(msg)
            }
            return
        }
        if (msg.has("parts") && key != null) {
            val slot = kind + ":" + msg.optString("sid")
            val acc = pushParts.getOrPut(slot) { mutableListOf() }
            if (msg.optInt("part") == 0) acc.clear()
            acc += msg
            if (acc.size >= msg.optInt("parts", 1)) {
                pushParts.remove(slot)
                pushChannel.trySend(kind to merge(acc, key))
            }
            return
        }
        pushChannel.trySend(kind to msg)
    }

    suspend fun call(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = 20_000): JSONObject {
        val id = Crypto.random(4).joinToString("") { "%02x".format(it) }
        val p = Pending(CompletableDeferred())
        calls[id] = p
        return try {
            withContext(Dispatchers.IO) {
                t.send(cfg.peer, sealer.seal("L5", JSONObject().put("id", id).put("op", op).put("args", args)))
            }
            withTimeout(timeoutMs) { p.done.await() }
        } catch (e: TimeoutCancellationException) {
            calls.remove(id)
            JSONObject().put("ok", false).put("reason", "timeout")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            calls.remove(id)
            JSONObject().put("ok", false).put("reason", e.message ?: "error")
        }
    }

    /** 附件上传：整份加密，96 KB 分片；返回给 send_files 用的元数据。 */
    suspend fun upload(data: ByteArray, name: String, mime: String): JSONObject = withContext(Dispatchers.IO) {
        val bid = Crypto.random(16).joinToString("") { "%02x".format(it) }
        val (nonce, ct) = blob.encrypt(bid, data)
        val parts = maxOf(1, (ct.size + PART - 1) / PART)
        for (i in 0 until parts) t.blobPut(bid, i, parts, cfg.peer, ct.copyOfRange(i * PART, minOf(ct.size, (i + 1) * PART)))
        JSONObject().put("blob", bid).put("parts", parts).put("nonce", Crypto.b64(nonce)).put("bytes", data.size)
            .put("mime", mime).put("name", name)
    }

    /** 下载电脑发来的附件（图片）：取完即删。 */
    suspend fun download(meta: JSONObject): ByteArray = withContext(Dispatchers.IO) {
        val bid = meta.getString("blob")
        val parts = meta.getInt("parts")
        val buf = java.io.ByteArrayOutputStream()
        for (i in 0 until parts) buf.write(t.blobGet(bid, i))
        val data = blob.decrypt(bid, Crypto.unb64(meta.getString("nonce")), buf.toByteArray())
        try { t.blobDelete(bid) } catch (_: Exception) { }
        data
    }

    companion object { const val PART = 96 * 1024 }
}
