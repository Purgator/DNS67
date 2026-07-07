package fr.arichard.adblocker.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import fr.arichard.adblocker.vpn.AdBlockVpnService

/** Restarts the ad-blocking VPN after boot and after app updates. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = Prefs(context)
        if (!prefs.autoStartOnBoot || !prefs.vpnDesired) return

        // prepare() returns null when the user's VPN consent is still valid;
        // without consent we cannot start silently from the background.
        if (VpnService.prepare(context) != null) {
            Log.w("BootReceiver", "VPN consent missing, cannot auto-start")
            return
        }
        AdBlockVpnService.start(context)
    }
}
