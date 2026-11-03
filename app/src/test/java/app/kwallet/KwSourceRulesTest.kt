package app.kwallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.99.50: lint-style source rules, read from the repository (unit tests run with the module dir as working dir).
 *  - every Activity sets FLAG_SECURE (through KwSecure / KwActivity) and every dialog goes through KwSecure.dialog;
 *  - the TextAuth pairing answer carries no key, and an old TextAuth's key-export request is refused;
 *  - pasting into Kwallet is untouched (no code reads, blocks or clears the clipboard on paste).
 */
class KwSourceRulesTest {
    private fun root(): File { var d = File("").absoluteFile; while (!File(d, "settings.gradle.kts").exists()) d = d.parentFile; return d }
    private fun src(p: String) = File(root(), p).readText()
    private val kt by lazy { File(root(), "app/src/main/java/app/kwallet").listFiles()!!.filter { it.name.endsWith(".kt") } }

    @Test fun everyActivityAndDialogIsSecure() {
        val acts = kt.filter { Regex("class \\w+\\s*(\\([^)]*\\))?\\s*:\\s*(\\w+\\.)*(FragmentActivity|ComponentActivity|AppCompatActivity|Activity|KwActivity)\\(").containsMatchIn(it.readText()) }
        assertTrue("activities found", acts.size >= 3)
        for (f in acts) {
            val t = f.readText()
            assertTrue("${f.name}: FLAG_SECURE via KwSecure.activity or KwActivity", t.contains("KwSecure.activity(this)") || Regex(":\\s*KwActivity\\(").containsMatchIn(t))
        }
        assertTrue(src("app/src/main/java/app/kwallet/KwActivity.kt").contains("KwSecure.activity(this)"))
        val sec = src("app/src/main/java/app/kwallet/KwSecure.kt")
        for (w in listOf("FLAG_SECURE", "setRecentsScreenshotEnabled(false)", "setHideOverlayWindows(true)", "filterTouchesWhenObscured = true", "ACCESSIBILITY_DATA_SENSITIVE_YES", "addScreenRecordingCallback", "registerScreenCaptureCallback", "IS_SENSITIVE")) assertTrue("KwSecure: $w", sec.contains(w))
        for (f in kt) {
            val t = f.readText()
            val made = Regex("AlertDialog\\.Builder|Dialog\\(|PopupWindow\\(").findAll(t).count()
            if (made > 0) assertTrue("${f.name}: dialogs go through KwSecure.dialog", Regex("KwSecure\\.dialog\\(").findAll(t).count() >= Regex("\\.create\\(\\)|Dialog\\(|PopupWindow\\(").findAll(t).count())
        }
        assertTrue("incognito keyboard", src("app/src/main/java/app/kwallet/KwWebView.kt").contains("IME_FLAG_NO_PERSONALIZED_LEARNING"))
    }

    @Test fun pairingAnswerHasNoKeysAndOldKeyExportIsRefused() {
        val w = src("web/index.html")
        val pv = w.substring(w.indexOf("async function pkVault(r)"), w.indexOf("/* v3.98.4 (#B): no seed paired yet"))
        for (k in listOf("out.vaultMaster", "out.vaultIdSk", "out.nostrSk", "Object.assign({ status: 'ok', method: 'pk_pair' }, vk), 'Paired")) assertFalse("pkVault answer: $k", pv.contains(k))
        assertTrue(pv.contains("KwSign.engineKeep("))
        assertFalse(Regex("out\\s*=\\s*KwPasskey\\.vault").containsMatchIn(pv))
        val pp = w.substring(w.indexOf("function pkPairPage()"), w.indexOf("function pairPage()"))
        assertTrue("old TextAuth gets update error", pp.contains("+pr.eng >= 1") && pp.contains("status: 'error'") && pp.contains("PK_UPDATE"))
        val lk = src("app/src/main/java/app/kwallet/KwTaLinkService.kt")
        assertTrue(lk.contains("PROTO = 3") && lk.contains("KwEngine.handle"))
    }

    @Test fun pasteIsUnaffected() {
        for (f in kt) {
            val t = f.readText()
            assertFalse("${f.name}: no paste interception", t.contains("onTextContextMenuItem") || t.contains("OnReceiveContentListener") || t.contains("android.R.id.paste"))
        }
        val sec = src("app/src/main/java/app/kwallet/KwSecure.kt")
        assertTrue("clears only its own copy", sec.contains("KwClipRule.isMine("))
        assertFalse("never reads clipboard except to compare", sec.contains("addPrimaryClipChangedListener"))
    }
}
