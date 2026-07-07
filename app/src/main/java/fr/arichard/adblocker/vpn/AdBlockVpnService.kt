package fr.arichard.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.arichard.adblocker.MainActivity
import fr.arichard.adblocker.R
import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.core.Prefs
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var processor: PacketProcessor? = null

    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us (START_STICKY) or always-on VPN
        // kicked in: treat both as a start request.
        if (intent?.action == ACTION_STOP) {
            Prefs(this).vpnDesired = false
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        // Already running or still starting up: don't spawn a second loop.
        if (vpnThread?.isAlive == true) return START_STICKY

        stopping = false
        queriesTotal.set(0)
        queriesBlocked.set(0)

        vpnThread = thread(name = "AdBlockVpn") { runVpn() }
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun runVpn() {
        val prefs = Prefs(this)
        BlocklistManager.ensureLoaded(this)
        maybeAutoRefresh(prefs)

        var attempts = 0
        while (!stopping) {
            try {
                val pfd = establish() ?: throw IllegalStateException("VPN permission was revoked")
                vpnInterface = pfd
                isRunning = true
                attempts = 0
                Log.i(TAG, "VPN established")

                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                val proc = PacketProcessor(this, output, prefs)
                processor = proc

                val buffer = ByteArray(MTU)
                while (!stopping) {
                    val length = input.read(buffer)
                    if (length > 0) proc.handlePacket(buffer, length)
                    else if (length < 0) break
                }
            } catch (e: Exception) {
                if (!stopping) Log.w(TAG, "VPN loop error: ${e.message}")
            } finally {
                processor?.close()
                processor = null
                closeInterface()
            }

            if (!stopping) {
                // Transient failure (e.g. device sleep glitch): retry, but give up eventually.
                isRunning = false
                if (++attempts > 5) {
                    Log.e(TAG, "Too many consecutive VPN failures, stopping")
                    break
                }
                try {
                    Thread.sleep(2000L * attempts)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        isRunning = false
        if (!stopping) stopSelf()
    }

    private fun establish(): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(VPN_ADDRESS, 24)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            .setBlocking(true)
            .setConfigureIntent(configureIntent)
        try {
            // Our own traffic (blocklist downloads) never needs to go through the tunnel.
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not exclude own package: ${e.message}")
        }
        return builder.establish()
    }

    private fun maybeAutoRefresh(prefs: Prefs) {
        if (!prefs.autoRefresh) return
        val age = System.currentTimeMillis() - prefs.lastRefreshMillis
        if (age < REFRESH_INTERVAL_MS) return
        thread(name = "BlocklistAutoRefresh") {
            val error = BlocklistManager.refresh(this, prefs.blocklistUrl)
            if (error != null) Log.w(TAG, "Auto refresh failed: $error")
        }
    }

    private fun closeInterface() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // ignore
        }
        vpnInterface = null
    }

    private fun shutdown() {
        stopping = true
        isRunning = false
        closeInterface() // unblocks the read loop
        vpnThread?.interrupt()
        vpnThread = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked consent in system settings.
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdBlockVpnService"

        const val ACTION_START = "fr.arichard.adblocker.action.START"
        const val ACTION_STOP = "fr.arichard.adblocker.action.STOP"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        private const val MTU = 32767
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // weekly

        val queriesTotal = AtomicLong()
        val queriesBlocked = AtomicLong()

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
