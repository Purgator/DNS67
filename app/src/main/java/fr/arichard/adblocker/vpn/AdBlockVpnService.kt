package fr.arichard.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import fr.arichard.adblocker.core.UpdateManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var maintenanceThread: Thread? = null
    private var processor: PacketProcessor? = null
    private var unmeteredCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us (START_STICKY) or always-on VPN
        // kicked in: treat both as a start request.
        if (intent?.action == ACTION_STOP) {
            Prefs(this).vpnDesired = false
            shutdown()
            // Stopped from the notification: leave a dismissible one behind so blocking
            // can be resumed from the shade without opening the app.
            if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
                postStoppedNotification(consentNeeded = false)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        // Consent can be revoked in system settings while we're stopped; without it
        // establish() can never succeed, so tell the user instead of failing silently.
        if (prepare(this) != null) {
            Log.w(TAG, "VPN consent missing, cannot start")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            postStoppedNotification(consentNeeded = true)
            stopSelf()
            return START_NOT_STICKY
        }
        notificationManager.cancel(STOPPED_NOTIFICATION_ID)

        // Already running or still starting up: don't spawn a second loop.
        if (vpnThread?.isAlive == true) return START_STICKY

        stopping = false
        queriesTotal.set(0)
        queriesBlocked.set(0)

        vpnThread = thread(name = "AdBlockVpn") { runVpn() }
        return START_STICKY
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            Intent(this, AdBlockVpnService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_FROM_NOTIFICATION, true),
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

    /**
     * Dismissible notification shown after stopping from the shade (with a Start action),
     * or when a start attempt found the VPN consent revoked (tap opens the app).
     */
    private fun postStoppedNotification(consentNeeded: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_stopped_title))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
        if (consentNeeded) {
            builder.setContentText(getString(R.string.notification_consent_text))
        } else {
            val startIntent = PendingIntent.getForegroundService(
                this, 6,
                Intent(this, AdBlockVpnService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentText(getString(R.string.notification_stopped_text))
            builder.addAction(0, getString(R.string.action_start), startIntent)
        }
        notificationManager.notify(STOPPED_NOTIFICATION_ID, builder.build())
    }

    private fun runVpn() {
        val prefs = Prefs(this)
        BlocklistManager.ensureLoaded(this)
        startMaintenanceThread(prefs)

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
        // Track the real default network so its transport type shows through the VPN.
        builder.setUnderlyingNetworks(null)
        // Android classifies a VPN network as METERED by default regardless of what is
        // underneath it, which breaks every "Wi-Fi only" feature (Google Photos backup
        // checks for an unmetered network, not literally Wi-Fi). setMetered(false) makes
        // the VPN inherit meteredness from the underlying network instead: unmetered on
        // Wi-Fi, metered on mobile data.
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }
        try {
            // Our own traffic (blocklist downloads) never needs to go through the tunnel.
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not exclude own package: ${e.message}")
        }
        return builder.establish()
    }

    /**
     * Low-cost periodic housekeeping while the VPN runs: weekly blocklist refresh
     * and daily update check, both self-throttled by timestamps in prefs. The thread
     * spends its life asleep, so this costs nothing between iterations.
     */
    private fun startMaintenanceThread(prefs: Prefs) {
        if (maintenanceThread?.isAlive == true) return
        maintenanceThread = thread(name = "Maintenance", isDaemon = true) {
            while (!stopping) {
                try {
                    if (prefs.autoRefresh &&
                        System.currentTimeMillis() - prefs.lastRefreshMillis > REFRESH_INTERVAL_MS
                    ) {
                        val error = BlocklistManager.refresh(this, prefs.blocklistUrl)
                        if (error != null) Log.w(TAG, "Blocklist auto refresh failed: $error")
                    }
                    UpdateManager.maybeDailyCheck(this)
                } catch (e: Exception) {
                    Log.w(TAG, "Maintenance pass failed: ${e.message}")
                }
                try {
                    Thread.sleep(MAINTENANCE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        registerUnmeteredCallback()
    }

    /**
     * Runs the update check whenever an unmetered (Wi-Fi) network appears, so a pending
     * download that was deferred on mobile data completes as soon as Wi-Fi is back — no
     * waiting on the 6-hour maintenance tick. The check is self-throttled, so frequent
     * callbacks are cheap.
     */
    private fun registerUnmeteredCallback() {
        if (unmeteredCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                thread(name = "UpdateOnWifi", isDaemon = true) {
                    try {
                        UpdateManager.maybeDailyCheck(this@AdBlockVpnService)
                    } catch (e: Exception) {
                        Log.w(TAG, "Update check on network change failed: ${e.message}")
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            unmeteredCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun unregisterUnmeteredCallback() {
        val callback = unmeteredCallback ?: return
        unmeteredCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Already unregistered.
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
        unregisterUnmeteredCallback()
        closeInterface() // unblocks the read loop
        vpnThread?.interrupt()
        vpnThread = null
        maintenanceThread?.interrupt()
        maintenanceThread = null
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
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1
        private const val STOPPED_NOTIFICATION_ID = 5

        private const val MTU = 32767
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // weekly
        private const val MAINTENANCE_INTERVAL_MS = 6L * 60 * 60 * 1000  // wake every 6h

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
