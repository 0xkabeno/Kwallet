package app.kwallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** v3.99.50: TextAuth engine (KwEngineCore), JVM only. Runs in CI (gradle :app:testReleaseUnitTest), never on a dev box. */
class KwEngineCoreTest {
    private fun h(s: String) = KwEngineCore.unhex(s.lowercase())
    private val master = ByteArray(32) { (it * 7 + 1).toByte() }
    private val id = "00112233445566778899aabbccddeeff"
    private val pt = ByteArray(228) { (it % 251).toByte() }

    /** a TextAuth 1.0.43 record: iv | AES-GCM(item key, plaintext), no AAD (what kacrypto.js seal() wrote) */
    private fun v1(kind: Int, idHex: String, p: ByteArray): ByteArray {
        val k = KwEngineCore.hkdf(master, "kwauth/$kind/$idHex")
        val iv = ByteArray(12) { 9 }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(p)
    }

    @Test fun v2RecordOpensOnlyForItsOwnKindIdVersionAndDeletedFlag() {
        val b = KwEngineCore.sealRec(master, 1, id, "1730600000000", false, pt)
        assertEquals(KwEngineCore.REC_V2, b[0])
        val ok = KwEngineCore.openRec(master, 1, id, "1730600000000", false, b, null)
        assertArrayEquals(pt, ok.pt); assertEquals(2, ok.format)
        fun refused(kind: Int, i: String, ver: String, del: Boolean) {
            try { KwEngineCore.openRec(master, kind, i, ver, del, b, null); fail("tampered $kind $i $ver $del opened") } catch (_: KwEngineCore.Refused) {}
        }
        refused(1, id, "1730600000001", false)          // newer version (replay / rollback label)
        refused(1, id, "1730599999999", false)
        refused(1, id, "1730600000000", true)           // marked deleted
        refused(2, id, "1730600000000", false)          // other kind
        refused(1, "ffeeddccbbaa99887766554433221100", "1730600000000", false)   // other id
        val bad = b.copyOf(); bad[20] = (bad[20].toInt() xor 1).toByte()
        try { KwEngineCore.openRec(master, 1, id, "1730600000000", false, bad, null); fail("flipped bit opened") } catch (_: KwEngineCore.Refused) {}
    }

    @Test fun oldUnboundRecordOpensBeforeMigrationAndIsRefusedAfter() {
        val old = v1(2, id, pt)
        assertArrayEquals(pt, KwEngineCore.openRec(master, 2, id, "500", false, old, null).pt)
        assertEquals(1, KwEngineCore.openRec(master, 2, id, "500", false, old, null).format)
        // migrated at 1000: an unbound record at or below the point still reads, a newer one is a downgrade
        assertArrayEquals(pt, KwEngineCore.openRec(master, 2, id, "1000", false, old, BigInteger("1000")).pt)
        try { KwEngineCore.openRec(master, 2, id, "1001", false, old, BigInteger("1000")); fail("downgrade opened") } catch (_: KwEngineCore.Refused) {}
    }

    @Test fun filePartsAndKeyedBlobsKeepTheirFormat() {
        val p = KwEngineCore.sealPart(master, id, 3, pt)
        assertArrayEquals(pt, KwEngineCore.openPart(master, id, 3, p))
        try { KwEngineCore.openPart(master, id, 4, p); fail("part index not bound") } catch (_: Exception) {}
        val aad = "kwauth/pwfill/$id".toByteArray()
        val f = KwEngineCore.sealKeyed(master, "kwauth/pwfill/$id", "pw".toByteArray(), aad)
        assertEquals("pw", String(KwEngineCore.openKeyed(master, "kwauth/pwfill/$id", f, aad)))
    }

    @Test fun onlyResultsLeaveTheEngine() {
        // item keys, the vault master and the outbox / index keys are never derivable through derive()
        for (info in listOf("kwauth/1/$id", "kwauth/outbox", "kwauth/cloud-index", "kwauth/pwfill/$id", "vault-master", "", "kwauth/pw/")) {
            try { KwEngineCore.derive(master, info, 32); fail("derive($info) allowed") } catch (_: KwEngineCore.Refused) {}
        }
        assertEquals(20, KwEngineCore.derive(master, "kwauth/pw/example.com/u/0", 20).size)
        assertTrue(KwEngineCore.deriveOk("kwauth/log/v1/3")); assertTrue(KwEngineCore.deriveOk("kwauth/cloud-ns"))
        assertFalse(KwEngineCore.keyedOk("kwauth/1/$id")); assertFalse(KwEngineCore.macOk("kwauth/outbox"))
        try { KwEngineCore.sealKeyed(master, "kwauth/4/$id", pt, null); fail("item key via keyed seal") } catch (_: KwEngineCore.Refused) {}
    }

    @Test fun icpSignsRequestIdsOnly() {
        // RFC 8032 test 1
        val sk = h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", KwEngineCore.hex(KwEngineCore.edPub(sk)))
        val msg = byteArrayOf(0x0A) + "ic-request".toByteArray() + ByteArray(32) { 1 }
        assertEquals(64, KwEngineCore.icpSign(sk, msg).size)
        for (m in listOf(ByteArray(43), "hello".toByteArray(), byteArrayOf(0x0A) + "ic-request".toByteArray() + ByteArray(33))) {
            try { KwEngineCore.icpSign(sk, m); fail("signed a non-request") } catch (_: KwEngineCore.Refused) {}
        }
    }

    @Test fun bip340Vectors() {
        val v = listOf(
            arrayOf("0000000000000000000000000000000000000000000000000000000000000003", "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9", "0000000000000000000000000000000000000000000000000000000000000000", "0000000000000000000000000000000000000000000000000000000000000000", "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"),
            arrayOf("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF", "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659", "0000000000000000000000000000000000000000000000000000000000000001", "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89", "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"),
            arrayOf("C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9", "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8", "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906", "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C", "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7"),
            arrayOf("0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710", "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3"))
        for (x in v) {
            assertEquals(x[1].lowercase(), KwEngineCore.hex(KwEngineCore.nostrPub(h(x[0]))))
            assertEquals(x[4].lowercase(), KwEngineCore.hex(KwEngineCore.schnorrSign(h(x[0]), h(x[3]), h(x[2]))))
        }
    }

    @Test fun nostrSignsOnlyTextAuthSyncKindsAndSerializesLikeJavaScript() {
        assertEquals("[0,\"ab\",1,30078,[[\"d\",\"x\\\"y\"]],\"a\\nb\\u0001\\\\/é\"]",
            KwEngineCore.nostrSerialize("ab", 1, 30078, listOf(listOf("d", "x\"y")), "a\nb\u0001\\/é"))
        val sk = h("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val e = KwEngineCore.nostrSign(sk, 1730600000, 30078, listOf(listOf("d", "abc")), "x")
        assertEquals(64, e.id.length); assertEquals(128, e.sig.length)
        assertEquals("dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659", e.pubkey)
        for (k in listOf(1, 0, 3, 4, 7, 9734, 10002)) {
            try { KwEngineCore.nostrSign(sk, 1730600000, k, emptyList(), ""); fail("kind $k signed") } catch (_: KwEngineCore.Refused) {}
        }
    }

    @Test fun engineRateLimitHolds() {
        var now = 1_000_000L
        val L = KwEngineCore.Limits(perMin = 600) { now }
        assertTrue(L.list("1234567890.0")); assertFalse("second list open in one unlock", L.list("1234567890.0"))
        assertTrue(L.list("1234567890.1"))
        repeat(600) { assertTrue(L.take("1234567890.0", 1)) }
        assertFalse("601st open within a minute", L.take("1234567890.0", 1))
        assertTrue("other address has its own cap", L.take("1234567890.1", 1))
        now += 30_000; assertFalse(L.take("1234567890.0", 1))
        now += 30_001; assertTrue(L.take("1234567890.0", 1))
        L.reset(); assertTrue("a new unlock allows one list again", L.list("1234567890.0"))
    }

    @Test fun clipboardAutoClearOnlyClearsItsOwnCopy() {
        assertTrue(KwClipRule.isMine("Kwallet", "secret", "Kwallet", "secret"))
        assertFalse(KwClipRule.isMine("Kwallet", "something else", "Kwallet", "secret"))
        assertFalse(KwClipRule.isMine("Notes", "secret", "Kwallet", "secret"))
        assertFalse(KwClipRule.isMine(null, null, "Kwallet", "secret"))
    }
}
