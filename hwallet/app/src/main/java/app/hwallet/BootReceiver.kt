package app.hwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** v1.2: restarts the always-on listener after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED || a == "android.intent.action.QUICKBOOT_POWERON" || a == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            HwLiveService.start(context)
        }
    }
}
