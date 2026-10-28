package app.palminspire

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 跨语言对照：读电脑端 tests/vectors/envelope_v2.json（固定密钥、随机数、时间），两边必须逐字节一致。 */
class CryptoTest {
    private val vector = JSONObject(javaClass.classLoader!!.getResource("envelope_v2.json")!!.readText())
    private val key = Crypto.unb64(vector.getString("pair_key_b64"))
    private val env = vector.getJSONObject("envelope")
    private val clock = { 1_800_000_000_000L }

    @Test fun hkdfMatchesPython() {
        assertArrayEquals(Crypto.unb64(vector.getString("derived_key_b64")), Crypto.hkdf(key, vector.getString("info")))
    }

    @Test fun sealProducesSameCiphertextAsPython() {
        val s = Sealer(key, Crypto.M2PC, "phone", clock) { n -> ByteArray(n) { 7 } }
        val out = s.sealText("L5", vector.getString("plaintext"))
        assertEquals(env.getString("ct"), out.getString("ct"))
        assertEquals(env.getLong("seq"), out.getLong("seq"))
        assertEquals(env.getString("n"), out.getString("n"))
    }

    @Test fun openPythonEnvelope() {
        val (kind, msg) = Opener(key, Crypto.M2PC, MemSeqStore(), clock).open(env)
        assertEquals("L5", kind)
        assertEquals("你好", msg.getString("text"))
    }

    @Test fun replayTamperWindowAndDirection() {
        val store = MemSeqStore()
        val o = Opener(key, Crypto.M2PC, store, clock)
        o.open(env)
        expectFail { o.open(env) }                                                    // 重放
        expectFail { Opener(key, Crypto.PC2M, MemSeqStore(), clock).open(env) }        // 反方向
        val tampered = JSONObject(env.toString()).put("kind", "L1")
        expectFail { Opener(key, Crypto.M2PC, MemSeqStore(), clock).open(tampered) }   // 改头部
        expectFail { Opener(key, Crypto.M2PC, MemSeqStore()) { 1_800_000_301_000L }.open(env) }   // 超时间窗
        val forged = JSONObject(env.toString()).put("seq", env.getLong("seq") + 1_000_000_000L)
        val s2 = MemSeqStore()
        expectFail { Opener(key, Crypto.M2PC, s2, clock).open(forged) }
        assertEquals(0L, s2.get("phone"))                                             // 伪造的推不高序号
    }

    /** 真机实测：中枢推送线程与回执线程并发发送，到达顺序与序号不一致，严格递增会把后到的小序号当重放丢掉。 */
    @Test fun outOfOrderWithinWindowAcceptedReplayRejected() {
        var now = 1_800_000_000_000L
        val s = Sealer(key, Crypto.PC2M, "palm-pc", { now })
        val store = MemSeqStore()
        val o = Opener(key, Crypto.PC2M, store, { now })
        val a = s.seal("L4", JSONObject().put("n", 1)); val b = s.seal("L4", JSONObject().put("n", 2))
        assertEquals(2, o.open(b).second.getInt("n"))
        assertEquals(1, o.open(a).second.getInt("n"))                                 // 乱序：照收
        expectFail { o.open(a) }; expectFail { o.open(b) }                             // 重放
        expectFail { Opener(key, Crypto.PC2M, store, { now }).open(b) }                // App 重启后窗口内收过的仍拒收
        val old = s.seal("L4", JSONObject().put("n", 3))
        now += Crypto.REORDER_MS + 5_000
        o.open(s.seal("L4", JSONObject().put("n", 4)))
        expectFail { o.open(old) }                                                     // 早于窗口：拒收
    }

    @Test fun roundTripBothDirectionsAndMonotonicSeq() {
        var now = 1_800_000_000_000L
        val s = Sealer(key, Crypto.PC2M, "palm-pc", { now })
        val o = Opener(key, Crypto.PC2M, MemSeqStore(), { now })
        val a = s.seal("L4", JSONObject().put("t", "掌上灵感"))
        now -= 60_000                                                                 // 时钟回拨
        val b = s.seal("L4", JSONObject().put("t", 2))
        assertTrue(b.getLong("seq") > a.getLong("seq"))
        now += 60_000
        assertEquals("掌上灵感", o.open(a).second.getString("t"))
        assertEquals(2, o.open(b).second.getInt("t"))
    }

    @Test fun blobRoundTripMatchesDirectionKeys() {
        val phone = BlobCipher(key, Crypto.BLOB_M2PC, Crypto.BLOB_PC2M)
        val pc = BlobCipher(key, Crypto.BLOB_PC2M, Crypto.BLOB_M2PC)
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val (n, ct) = phone.encrypt("ab".repeat(16), data)
        assertArrayEquals(data, pc.decrypt("ab".repeat(16), n, ct))
        expectFail { pc.decrypt("cd".repeat(16), n, ct) }                             // 编号不对解不开
    }

    private fun expectFail(block: () -> Unit) {
        try { block(); fail("应当失败") } catch (e: EnvelopeException) { } catch (e: javax.crypto.AEADBadTagException) { }
    }
}
