package fr.arichard.adblocker.core

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Thin wrapper around the default SharedPreferences with typed accessors and defaults. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val upstreamDns: String
        get() = sp.getString(KEY_UPSTREAM_DNS, DEFAULT_UPSTREAM)?.trim().orEmpty()
            .ifEmpty { DEFAULT_UPSTREAM }

    val upstreamDns2: String
        get() = sp.getString(KEY_UPSTREAM_DNS2, DEFAULT_UPSTREAM2)?.trim().orEmpty()
            .ifEmpty { DEFAULT_UPSTREAM2 }

    val blocklistUrl: String
        get() = sp.getString(KEY_BLOCKLIST_URL, DEFAULT_BLOCKLIST_URL)?.trim().orEmpty()
            .ifEmpty { DEFAULT_BLOCKLIST_URL }

    val autoStartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, true)

    val autoRefresh: Boolean
        get() = sp.getBoolean(KEY_AUTO_REFRESH, true)

    val customBlocked: List<String>
        get() = parseDomainList(sp.getString(KEY_CUSTOM_BLOCKED, "") ?: "")

    val customAllowed: List<String>
        get() = parseDomainList(sp.getString(KEY_CUSTOM_ALLOWED, "") ?: "")

    var lastRefreshMillis: Long
        get() = sp.getLong(KEY_LAST_REFRESH, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_REFRESH, value).apply()

    val autoUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    var notifiedUpdateVersion: String?
        get() = sp.getString(KEY_NOTIFIED_UPDATE_VERSION, null)
        set(value) = sp.edit().putString(KEY_NOTIFIED_UPDATE_VERSION, value).apply()

    /** Set to true whenever the user manually starts the VPN, false when they stop it. */
    var vpnDesired: Boolean
        get() = sp.getBoolean(KEY_VPN_DESIRED, false)
        set(value) = sp.edit().putBoolean(KEY_VPN_DESIRED, value).apply()

    private fun parseDomainList(raw: String): List<String> =
        raw.split('\n', ',', ' ', ';')
            .map { it.trim().trimEnd('.').lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('.') }

    companion object {
        const val KEY_UPSTREAM_DNS = "upstream_dns"
        const val KEY_UPSTREAM_DNS2 = "upstream_dns2"
        const val KEY_BLOCKLIST_URL = "blocklist_url"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_AUTO_REFRESH = "auto_refresh"
        const val KEY_CUSTOM_BLOCKED = "custom_blocked"
        const val KEY_CUSTOM_ALLOWED = "custom_allowed"
        const val KEY_LAST_REFRESH = "last_refresh"
        const val KEY_VPN_DESIRED = "vpn_desired"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_NOTIFIED_UPDATE_VERSION = "notified_update_version"

        const val DEFAULT_UPSTREAM = "1.1.1.1"
        const val DEFAULT_UPSTREAM2 = "8.8.8.8"
        const val DEFAULT_BLOCKLIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    }
}
