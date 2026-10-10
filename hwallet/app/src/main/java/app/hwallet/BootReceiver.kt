package app.hwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** v1.2: restarts the always-on listener after a reboot or an app update. v1.3: never throws (a receiver crash kills the app). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val a = intent.action ?: return
            if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED || a == "android.intent.action.QUICKBOOT_POWERON" || a == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                HwLiveService.start(context, false)
            }
        } catch (e: Throwable) { HwApp.write(context, "note: boot restart failed", e.toString()) }
    }
}
