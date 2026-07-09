package fr.arichard.adblocker.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fr.arichard.adblocker.MainActivity
import fr.arichard.adblocker.core.Prefs

/**
 * Quick Settings tile: toggle blocking from the notification shade without opening
 * the app. Falls back to opening the app when VPN consent hasn't been granted yet.
 */
class AdBlockTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        if (AdBlockVpnService.isRunning) {
            prefs.vpnDesired = false
            AdBlockVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            prefs.vpnDesired = true
            AdBlockVpnService.start(this)
        } else {
            // Consent missing: only the app can show the system dialog.
            openApp()
            return
        }
        // isRunning flips asynchronously once the tunnel is up/down.
        handler.postDelayed({ updateTile() }, 600)
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 5, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (AdBlockVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
