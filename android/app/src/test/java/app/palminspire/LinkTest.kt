package app.palminspire

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 假中转 + 假电脑（同一套加密），验证：回执按 ref 对上、分片合并、推送、篡改丢弃、附件往返。 */
class LinkTest {
    private val key = ByteArray(32) { (it + 3).toByte() }
    private val cfg = PairConfig("https://x", "tok", "phone", "palm-pc", key, "pc")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    inner class FakeRelay : Transport {
        val toPhone = LinkedBlockingQueue<JSONObject>()
        val blobs = HashMap<String, ByteArray>()
        var rseq = 0L
        val pcSeal = Sealer(key, Crypto.PC2M, "palm-pc")
        val pcOpen = Opener(key, Crypto.M2PC, MemSeqStore())
        var onCommand: (JSONObject) -> List<JSONObject> = { emptyList() }

        fun push(kind: String, msg: JSONObject) = toPhone.put(pcSeal.seal(kind, msg))

        override fun send(to: String, env: JSONObject): JSONObject {
            val (_, m) = pcOpen.open(env)
            onCommand(m).forEach { toPhone.put(it) }
            return JSONObject().put("ok", true)
        }

        override fun inbox(since: Long, timeoutSec: Int): JSONObject {
            val items = JSONArray()
            toPhone.poll(200, TimeUnit.MILLISECONDS)?.let { items.put(JSONObject().put("rseq", ++rseq).put("env", it)) }
            while (true) { val e = toPhone.poll() ?: break; items.put(JSONObject().put("rseq", ++rseq).put("env", e)) }
            return JSONObject().put("boot", "b1").put("items", items).put("dropped", 0)
        }

        override fun peers() = JSONObject().put("peers", JSONObject().put("palm-pc", 3))
        override fun blobPut(bid: String, part: Int, total: Int, to: String, data: ByteArray) { blobs["$bid.$part"] = data }
        override fun blobGet(bid: String, part: Int) = blobs.getValue("$bid.$part")
        override fun blobDelete(bid: String) { blobs.keys.removeIf { it.startsWith(bid) } }
    }

    @After fun tearDown() = scope.cancel()

    @Test fun callGetsMergedMultiPartAck() = runBlocking {
        val relay = FakeRelay()
        relay.onCommand = { m ->
            val ref = m.getString("id")
            listOf(0, 1).map { i ->
                relay.pcSeal.seal("ack", JSONObject().put("ref", ref).put("ok", true).put("part", i).put("parts", 2)
                    .put("sessions", JSONArray().put(JSONObject().put("sid", "s$i"))))
            }
        }
        val link = Link(cfg, relay, MemSeqStore(), scope, pollSeconds = 1)
        link.start()
        val r = link.call("sessions")
        assertEquals(listOf("s0", "s1"), r.getJSONArray("sessions").objects().map { it.getString("sid") })
        assertTrue(!r.has("part"))
        link.stop()
    }

    /** 真机实测：图片回执里的 parts（附件分片数）曾被当成“分片回执”合并并删掉，下载报 No value for parts。 */
    @Test fun ackWithBlobPartsIsNotTreatedAsMultiPart() = runBlocking {
        val relay = FakeRelay()
        relay.onCommand = { m ->
            listOf(relay.pcSeal.seal("ack", JSONObject().put("ref", m.getString("id")).put("ok", true)
                .put("blob", "ab".repeat(16)).put("parts", 3).put("nonce", "x")))
        }
        val link = Link(cfg, relay, MemSeqStore(), scope, pollSeconds = 1)
        link.start()
        val r = link.call("image", JSONObject().put("id", "x"))
        assertEquals(3, r.getInt("parts"))
        link.stop()
    }

    @Test fun pushesAreMergedAndEmitted() = runBlocking {
        val relay = FakeRelay()
        val link = Link(cfg, relay, MemSeqStore(), scope, pollSeconds = 1)
        link.start()
        relay.push("L4", JSONObject().put("sid", "a").put("push", true).put("part", 0).put("parts", 2)
            .put("events", JSONArray().put(JSONObject().put("id", "1").put("k", "text"))))
        relay.push("L4", JSONObject().put("sid", "a").put("push", true).put("part", 1).put("parts", 2)
            .put("events", JSONArray().put(JSONObject().put("id", "2").put("k", "text"))))
        relay.push("L2", JSONObject().put("pending", JSONArray()))
        val got = withTimeout(5000) { link.pushes.first { it.first == "L4" } }
        assertEquals(2, got.second.getJSONArray("events").length())
        withTimeout(5000) { link.pushes.first { it.first == "L2" } }
        link.stop()
    }

    @Test fun tamperedEnvelopeIsDroppedAndTimeoutReturnsReason() = runBlocking {
        val relay = FakeRelay()
        val link = Link(cfg, relay, MemSeqStore(), scope, pollSeconds = 1)
        val env = relay.pcSeal.seal("L2", JSONObject().put("pending", JSONArray()))
        env.put("kind", "L1")
        link.handle(env)
        assertEquals(1, link.bad)
        link.start()
        val r = link.call("pending", timeoutMs = 600)                                   // 假电脑不回
        assertEquals("timeout", r.getString("reason"))
        link.stop()
    }

    @Test fun uploadAndDownloadRoundTrip() = runBlocking {
        val relay = FakeRelay()
        val link = Link(cfg, relay, MemSeqStore(), scope)
        val data = ByteArray(250_000) { (it % 97).toByte() }
        val meta = link.upload(data, "照片.jpg", "image/jpeg")
        assertEquals(3, meta.getInt("parts"))
        // 电脑侧解开手机上传的
        val bid = meta.getString("blob")
        val ct = (0 until 3).map { relay.blobs.getValue("$bid.$it") }.reduce { a, b -> a + b }
        val pc = BlobCipher(key, Crypto.BLOB_PC2M, Crypto.BLOB_M2PC)
        assertArrayEquals(data, pc.decrypt(bid, Crypto.unb64(meta.getString("nonce")), ct))
        // 电脑发给手机的
        val (n2, ct2) = pc.encrypt("ab".repeat(16), data)
        relay.blobs["${"ab".repeat(16)}.0"] = ct2
        val back = link.download(JSONObject().put("blob", "ab".repeat(16)).put("parts", 1).put("nonce", Crypto.b64(n2)))
        assertArrayEquals(data, back)
        assertTrue(relay.blobs.keys.none { it.startsWith("ab".repeat(16)) })           // 取完即删
    }
}
