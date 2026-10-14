package app.hwallet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * v1.8.12 (push 17, R1): keeps the native Monero sync (HwXmrN) running until it reaches the chain tip, whatever the
 * screen does: foreground service + partial wake lock + Wi-Fi lock while it catches up, a progress notification
 * (height, blocks left, speed), START_STICKY so Android brings it back after killing the process (the engine reopens the
 * wallet file and goes on from the height it saved), and BootReceiver restarts it after a reboot when a sync is still owed.
 * When the wallet is synced (or the user pauses it) the locks are released and the service ends; the app keeps the
 * wallet listening for new blocks while it is open.
 */
class HwXmrService : Service() {
    companion object {
        const val CH = "hw_xmr_sync"
        const val NID = 7121
        @Volatile var running = false

        /** start (or poke) the service; from the visible app a plain startService, from the background startForegroundService */
        fun kick(c: Context) {
            try {
                if (running) return
                val i = Intent(c, HwXmrService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(c, i) else c.startService(i)
            } catch (e: Throwable) { HwApp.write(c, "note: Monero sync service not started", e.toString()) }
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastNote = 0L
    private var doneAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        try { channel() } catch (_: Throwable) {}
        fg()
        try { HwNet.load(this) } catch (_: Throwable) {}
        try {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hwallet:xmrsync").apply { setReferenceCounted(false); acquire(6 * 3600 * 1000L) }
        } catch (_: Throwable) {}
        try {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Hwallet:xmrsync").apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
        HwXmrN.onTick = { onTick() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fg()
        /* after a process restart nothing is open yet: reopen the saved wallet and go on from its height */
        if (HwXmrN.available(this)) HwXmrN.resumeAsync(this)
        else { stopSelfNow(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        val done = HwXmrN.synced || HwXmrN.paused || !HwXmrN.wanted(this)
        if (done) {
            if (doneAt == 0L) doneAt = now
            /* a few seconds of grace so a page that opens right away does not start it again */
            if (now - doneAt > 4000) { stopSelfNow(); return }
        } else {
            doneAt = 0L
            /* a long first scan can outlast one wake-lock period: take it again while there is work left */
            try { if (wake?.isHeld != true) wake?.acquire(6 * 3600 * 1000L) } catch (_: Throwable) {}
        }
        if (now - lastNote > 2000) { lastNote = now; try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, note()) } catch (_: Throwable) {} }
    }

    private fun stopSelfNow() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Throwable) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        if (HwXmrN.onTick != null) HwXmrN.onTick = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CH, "Monero sync", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Hwallet scans Monero blocks up to the chain tip"; setShowBadge(false); enableVibration(false); setSound(null, null)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun openApp(): PendingIntent {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, HwActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun note(): Notification {
        val h = HwXmrN.h; val dh = HwXmrN.dh; val st = HwXmrN.startH
        val left = if (dh > h) dh - h else 0L
        val pct = if (dh <= st || dh <= 0) 0 else (((h - st).toDouble() / (dh - st).toDouble()) * 100).toInt().coerceIn(0, 100)
        val txt = when {
            HwXmrN.paused -> "Paused by you"
            HwXmrN.synced -> "Synced to block " + String.format("%,d", h)
            dh <= 0 -> "Connecting to the Monero node\u2026"
            HwXmrN.err.isNotEmpty() -> HwXmrN.err
            else -> "Block " + String.format("%,d", h) + " \u00b7 " + String.format("%,d", left) + " left"
        }
        val b = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.hw_stat).setContentTitle("Monero sync" + if (dh > 0 && !HwXmrN.synced) " \u00b7 $pct%" else "")
            .setContentText(txt).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setContentIntent(openApp()).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (dh > 0 && !HwXmrN.synced && !HwXmrN.paused) b.setProgress(100, pct, false)
        return b.build()
    }

    @SuppressLint("InlinedApi")
    private fun fg() {
        try {
            val n = note()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NID, n)
        } catch (e: Throwable) { HwApp.write(this, "note: Monero sync could not enter the foreground", e.toString()) }
    }
}
