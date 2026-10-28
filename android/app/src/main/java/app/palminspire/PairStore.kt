package app.palminspire

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对信息（中转地址、手机令牌、配对密钥）只存 App 私有目录，并用安卓密钥库里不可导出的 AES 密钥再加一层。
 * 来源：palm pair --adb 写到外部目录的 pair.json（读完即删），或二维码深链接 claudeinspire://pair#…。
 */
object PairStore {
    private const val ALIAS = "palm_pair_v1"
    private const val FILE = "pair.bin"

    fun parse(json: JSONObject): PairConfig {
        require(json.optInt("v") == 1) { "配对格式版本不对" }
        val key = Base64.getDecoder().decode(json.getString("key"))
        require(key.size == 32) { "配对密钥长度不对" }
        val relay = json.getString("relay")
        require(relay.startsWith("http://") || relay.startsWith("https://")) { "电脑地址必须以 http:// 或 https:// 开头" }
        return PairConfig(relay, json.getString("token"), json.optString("me", "phone"), json.optString("peer", "palm-pc"), key,
            json.optString("pc"))
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun save(ctx: Context, json: JSONObject) {
        parse(json)                                               // 先验格式，坏的不落盘
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val ct = c.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        File(ctx.filesDir, FILE).writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + ct)
        ctx.getSharedPreferences("seq", Context.MODE_PRIVATE).edit().clear().apply()   // 新配对：序号从头
    }

    fun load(ctx: Context): PairConfig? = try {
        val raw = File(ctx.filesDir, FILE).readBytes()
        val ivLen = raw[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, raw.copyOfRange(1, 1 + ivLen)))
        }
        parse(JSONObject(String(c.doFinal(raw.copyOfRange(1 + ivLen, raw.size)), Charsets.UTF_8)))
    } catch (e: Exception) {
        null
    }

    /** 旧办法：读 App 外部目录里的 pair.json（较新安卓上 adb 写的文件 App 读不了，读不了就跳过）。 */
    fun importExternal(ctx: Context): Boolean = try {
        val f = File(ctx.getExternalFilesDir(null) ?: throw IllegalStateException(), "pair.json")
        if (!f.exists()) false else try { save(ctx, JSONObject(f.readText())); true } finally { runCatching { f.delete() } }
    } catch (e: Exception) {
        false
    }

    /** 深链接 claudeinspire://pair#…（扫码或 adb am start）：只解析，不落盘——必须用户在 App 里确认（MainActivity）。 */
    fun parseLink(uri: Uri?): JSONObject? {
        if (uri == null || uri.scheme != "claudeinspire" || uri.host != "pair") return null
        val frag = uri.fragment ?: return null
        return try {
            val pad = "=".repeat((4 - frag.length % 4) % 4)
            JSONObject(String(Base64.getUrlDecoder().decode(frag + pad), Charsets.UTF_8)).also { parse(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}

/** 收到的最大序号落 SharedPreferences（App 重启后旧信封依然拒收）。 */
class PrefsSeqStore(ctx: Context) : SeqStore {
    private val sp = ctx.getSharedPreferences("seq", Context.MODE_PRIVATE)
    override fun get(dev: String) = sp.getLong(dev, 0L)
    override fun put(dev: String, seq: Long) { sp.edit().putLong(dev, seq).apply() }
    override fun recent(dev: String): Set<Long> =
        sp.getString("recent:$dev", "")!!.split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
    override fun putRecent(dev: String, s: Set<Long>) { sp.edit().putString("recent:$dev", s.joinToString(",")).apply() }
}
