package app.palminspire

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 中转 v2 信箱的传输。接口抽出来，单元测试换假实现。 */
interface Transport {
    fun send(to: String, env: JSONObject): JSONObject
    fun inbox(since: Long, timeoutSec: Int): JSONObject
    fun peers(): JSONObject
    fun blobPut(bid: String, part: Int, total: Int, to: String, data: ByteArray)
    fun blobGet(bid: String, part: Int): ByteArray
    fun blobDelete(bid: String)
}

class RelayException(val code: Int, msg: String) : Exception(msg)

class RelayHttp(private val base: String, private val token: String) : Transport {
    private fun open(method: String, path: String, readTimeoutMs: Int = 15_000): HttpURLConnection =
        (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            useCaches = false
        }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toByteArray() } ?: ByteArray(0)
        if (code !in 200..299) throw RelayException(code, "信箱返回 $code：${String(bytes).take(120)}")
        return bytes
    }

    private fun json(c: HttpURLConnection) = JSONObject(String(read(c), Charsets.UTF_8).ifEmpty { "{}" })

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open("POST", path)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return json(c)
    }

    override fun send(to: String, env: JSONObject) =
        post("/v2/send", JSONObject().put("to", to).put("items", org.json.JSONArray().put(env)))

    override fun inbox(since: Long, timeoutSec: Int) =
        json(open("GET", "/v2/inbox?since=$since&timeout=$timeoutSec", readTimeoutMs = (timeoutSec + 15) * 1000))

    override fun peers() = json(open("GET", "/v2/peers"))

    override fun blobPut(bid: String, part: Int, total: Int, to: String, data: ByteArray) {
        val c = open("PUT", "/v2/blob/$bid?part=$part", readTimeoutMs = 30_000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-To", to)
        c.setRequestProperty("X-Total", total.toString())
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        read(c)
    }

    override fun blobGet(bid: String, part: Int): ByteArray = read(open("GET", "/v2/blob/$bid?part=$part", readTimeoutMs = 30_000))

    override fun blobDelete(bid: String) { read(open("DELETE", "/v2/blob/$bid")) }
}
