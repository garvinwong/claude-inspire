package app.palminspire

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密信封，与电脑端 hub/envelope.py 逐字节一致：
 * HKDF-SHA256（salt = "palm-inspire"，info 分方向）→ AES-256-GCM；
 * 头部 v|dev|kind|seq|ts 作附加认证数据；seq 用“毫秒 × 1000 + 计数”，接收方要求严格递增、时间差 ≤ 300 秒。
 */
object Crypto {
    const val PC2M = "palm v2 pc->m"
    const val M2PC = "palm v2 m->pc"
    const val BLOB_PC2M = "palm v2 blob pc->m"
    const val BLOB_M2PC = "palm v2 blob m->pc"
    val KINDS = setOf("L1", "L2", "L3", "L4", "L5", "L6", "ack")
    const val WINDOW_SECONDS = 300L
    const val REORDER_MS = 60_000L        // 乱序容忍窗口（与 envelope.py REORDER 一致）
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** RFC 5869，只要 32 字节输出（一轮）。 */
    fun hkdf(key: ByteArray, info: String, salt: String = "palm-inspire"): ByteArray {
        require(key.size == 32) { "配对密钥须为 32 字节" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = mac.doFinal(key)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    }

    fun gcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun gcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(ct)
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun aad(v: Int, dev: String, kind: String, seq: Long, ts: Long) = "$v|$dev|$kind|$seq|$ts".toByteArray(Charsets.UTF_8)
}

class EnvelopeException(msg: String) : Exception(msg)

/** 发送方：一个方向一个。 */
class Sealer(
    pairKey: ByteArray, info: String, private val dev: String,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val random: (Int) -> ByteArray = Crypto::random,
) {
    private val key = Crypto.hkdf(pairKey, info)
    private var last = 0L

    @Synchronized private fun nextSeq(): Long {
        val seq = maxOf(clockMs() * 1000, last + 1)
        last = seq
        return seq
    }

    fun sealText(kind: String, plaintext: String): JSONObject {
        require(kind in Crypto.KINDS) { "未知类型 $kind" }
        val seq = nextSeq()
        val ts = clockMs() / 1000
        val n = random(12)
        val ct = Crypto.gcmEncrypt(key, n, plaintext.toByteArray(Charsets.UTF_8), Crypto.aad(2, dev, kind, seq, ts))
        return JSONObject().put("v", 2).put("dev", dev).put("kind", kind).put("seq", seq).put("ts", ts)
            .put("n", Crypto.b64(n)).put("ct", Crypto.b64(ct))
    }

    fun seal(kind: String, payload: JSONObject) = sealText(kind, payload.toString())
}

/** 各方向的防重放状态：下限（≤ 它的一律拒收）与窗口内已收序号（App 里落 SharedPreferences，测试用内存）。
 *  旧版只存“最大序号”，升级后当下限用，不因窗口放宽而多收旧信封。 */
interface SeqStore {
    fun get(dev: String): Long
    fun put(dev: String, seq: Long)
    fun recent(dev: String): Set<Long>
    fun putRecent(dev: String, s: Set<Long>)
}

class MemSeqStore : SeqStore {
    private val m = HashMap<String, Long>()
    private val r = HashMap<String, Set<Long>>()
    override fun get(dev: String) = m[dev] ?: 0L
    override fun put(dev: String, seq: Long) { m[dev] = seq }
    override fun recent(dev: String) = r[dev].orEmpty()
    override fun putRecent(dev: String, s: Set<Long>) { r[dev] = s }
}

/** 接收方：先验真伪再比序号（伪造信封推不高序号）。滑动窗口：每个序号只收一次，早于最新 60 秒的拒收，窗口内乱序照收。 */
class Opener(
    pairKey: ByteArray, info: String, private val store: SeqStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val key = Crypto.hkdf(pairKey, info)

    @Synchronized
    fun open(env: JSONObject): Pair<String, JSONObject> {
        val v: Int; val dev: String; val kind: String; val seq: Long; val ts: Long; val n: ByteArray; val ct: ByteArray
        try {
            v = env.getInt("v"); dev = env.getString("dev"); kind = env.getString("kind")
            seq = env.getLong("seq"); ts = env.getLong("ts")
            n = Crypto.unb64(env.getString("n")); ct = Crypto.unb64(env.getString("ct"))
        } catch (e: Exception) {
            throw EnvelopeException("格式坏：${e.message}")
        }
        if (v != 2 || kind !in Crypto.KINDS || n.size != 12) throw EnvelopeException("版本、类型或随机数不对")
        if (kotlin.math.abs(clockMs() / 1000 - ts) > Crypto.WINDOW_SECONDS) throw EnvelopeException("超出时间窗")
        val pt = try {
            Crypto.gcmDecrypt(key, n, ct, Crypto.aad(v, dev, kind, seq, ts))
        } catch (e: Exception) {
            throw EnvelopeException("解不开（密钥不对或被篡改）")
        }
        val floor = store.get(dev)
        val seen = store.recent(dev)
        if (seq <= floor || seq in seen) throw EnvelopeException("重放（序号已收过或早于窗口）")
        val all = seen + seq
        val newFloor = maxOf(floor, all.max() - Crypto.REORDER_MS * 1000)
        store.putRecent(dev, all.filterTo(HashSet()) { it > newFloor })
        store.put(dev, newFloor)
        return try {
            kind to JSONObject(String(pt, Charsets.UTF_8))
        } catch (e: Exception) {
            throw EnvelopeException("明文不是 JSON")
        }
    }
}

/** 附件：整份 AES-256-GCM（附加认证数据 = 附件编号），与 relay_link.py 一致。 */
class BlobCipher(pairKey: ByteArray, upInfo: String, downInfo: String) {
    private val up = Crypto.hkdf(pairKey, upInfo)
    private val down = Crypto.hkdf(pairKey, downInfo)
    fun encrypt(bid: String, data: ByteArray, nonce: ByteArray = Crypto.random(12)): Pair<ByteArray, ByteArray> =
        nonce to Crypto.gcmEncrypt(up, nonce, data, bid.toByteArray(Charsets.UTF_8))

    fun decrypt(bid: String, nonce: ByteArray, ct: ByteArray): ByteArray =
        Crypto.gcmDecrypt(down, nonce, ct, bid.toByteArray(Charsets.UTF_8))
}
