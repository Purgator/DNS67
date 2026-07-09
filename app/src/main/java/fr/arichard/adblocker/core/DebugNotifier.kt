package fr.arichard.adblocker.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import fr.arichard.adblocker.R
import fr.arichard.adblocker.RecentlyBlockedActivity

/**
 * "Active debug mode": while enabled, every blocked query updates a single silent
 * notification showing the last few blocked domains in real time. One notification
 * ID, low-importance channel — no sound, no vibration, no notification pile-up.
 */
object DebugNotifier {

    private const val CHANNEL_ID = "debug_blocked"
    private const val NOTIFICATION_ID = 3
    private const val MAX_LINES = 6

    private val recentLines = ArrayDeque<String>()

    fun notifyBlocked(context: Context, domain: String, uid: Int) {
        val label = AppNames.label(context, uid)
        val line = if (label != null) "$domain — $label" else domain
        val lines: List<String>
        synchronized(recentLines) {
            recentLines.addFirst(line)
            while (recentLines.size > MAX_LINES) recentLines.removeLast()
            lines = recentLines.toList()
        }

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.debug_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val openLog = PendingIntent.getActivity(
            context, 4,
            Intent(context, RecentlyBlockedActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.debug_blocked_title, domain))
                .setContentText(line)
                .setStyle(style)
                .setContentIntent(openLog)
                .setSilent(true)
                .build()
        )
    }

    /** Removes the notification and resets its history (used when the mode is turned off). */
    fun clear(context: Context) {
        synchronized(recentLines) { recentLines.clear() }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)
    }
}
