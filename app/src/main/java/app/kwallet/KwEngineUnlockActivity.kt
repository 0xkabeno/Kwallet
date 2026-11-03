package app.kwallet

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

/**
 * v3.99.50: TextAuth's unlock opens Kwallet's engine session. TextAuth starts this sheet (startActivityForResult) when the
 * engine answers "locked"; Kwallet asks for the user's biometric / screen lock itself, then decrypts the paired addresses'
 * keys into the session (KwEngine.unlockAll). Only the pinned TextAuth may open it (callingActivity is set by the system).
 * Answer: extra "res" = {"status":"ok","n":<addresses open>} | {"status":"rejected"} | {"status":"nopair"} | {"status":"refused"}.
 * Nothing on it can be captured or drawn over (KwSecure).
 */
class KwEngineUnlockActivity : FragmentActivity() {
    private var done = false

    private fun callerOk(): Boolean {
        val c = callingActivity?.packageName ?: return false
        return c == SignActivity.TEXTAUTH && SignActivity.textAuthPinned(packageManager)
    }

    private fun answer(o: JSONObject) {
        if (done) return; done = true
        setResult(if (o.optString("status") == "ok") RESULT_OK else RESULT_CANCELED, Intent().putExtra("res", o.toString()))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KwSecure.activity(this)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (!callerOk()) { answer(JSONObject().put("status", "refused")); return }
        if (KwEngine.slots(this).isEmpty()) { answer(JSONObject().put("status", "nopair")); return }
        if (KwEngine.isOpen()) { answer(JSONObject().put("status", "ok").put("n", KwEngine.slots(this).size)); return }
        val why = intent.getStringExtra("why")?.take(80)?.takeIf { it.isNotBlank() } ?: "Unlock your TextAuth vault"
        if (!KwEngine.needsAuth(this)) {
            /* no screen lock on this phone: the keys are wrapped by a Keystore key without user auth; ask for a tap */
            val d = AlertDialog.Builder(this).setTitle("Kwallet").setMessage("$why?\n\nSet a screen lock to protect it with your fingerprint or PIN.")
                .setPositiveButton("Unlock") { _, _ -> finishUnlock() }
                .setNegativeButton("Cancel") { _, _ -> answer(JSONObject().put("status", "rejected")) }
                .setOnCancelListener { answer(JSONObject().put("status", "rejected")) }
                .create()
            KwSecure.dialog(d); d.show(); return
        }
        val auth = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Kwallet").setSubtitle(why).setAllowedAuthenticators(auth).setConfirmationRequired(false).build()
        val p = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finishUnlock() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { answer(JSONObject().put("status", "rejected").put("error", errString.toString().take(120))) }
        })
        try { p.authenticate(info) } catch (e: Exception) { answer(JSONObject().put("status", "rejected").put("error", "auth")) }
    }

    private fun finishUnlock() {
        val n = KwEngine.unlockAll(this)
        answer(if (n > 0) JSONObject().put("status", "ok").put("n", n) else JSONObject().put("status", "repair").put("error", "Kwallet could not open TextAuth's keys. Pair TextAuth again"))
    }

    override fun onDestroy() { if (!done) setResult(RESULT_CANCELED, Intent().putExtra("res", "{\"status\":\"rejected\"}")); super.onDestroy() }
}
