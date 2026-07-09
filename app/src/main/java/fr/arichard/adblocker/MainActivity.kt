package fr.arichard.adblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.core.Prefs
import fr.arichard.adblocker.databinding.ActivityMainBinding
import fr.arichard.adblocker.vpn.AdBlockVpnService
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private companion object {
        const val TAG = "AdBlockerMain"
        const val NO_CONSENT_HANDLER = -999
    }

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.i(TAG, "VPN consent result: code=${result.resultCode} (OK=${Activity.RESULT_OK})")
            if (result.resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                showVpnConsentDialog(result.resultCode)
            }
        }

    // Whatever the user decides about notifications, continue to the VPN consent step:
    // the two system dialogs must never be shown at the same time, or the VPN one
    // gets auto-cancelled.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpnConsent()
        }

    private val updateUi = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.toggleButton.setOnClickListener {
            if (AdBlockVpnService.isRunning) {
                prefs.vpnDesired = false
                AdBlockVpnService.stop(this)
            } else {
                beginStartFlow()
            }
        }

        binding.refreshButton.setOnClickListener { refreshBlocklist() }
        binding.blockedLogButton.setOnClickListener { showRecentlyBlocked() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Load the blocklist early so the domain counter is meaningful right away,
        // and take the opportunity to run the daily (self-throttled) update check.
        thread {
            BlocklistManager.ensureLoaded(applicationContext)
            fr.arichard.adblocker.core.UpdateManager.maybeDailyCheck(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.post(updateUi)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateUi)
    }

    /** Step 1: notification permission (Android 13+), then the VPN consent dialog. */
    private fun beginStartFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnConsent()
        }
    }

    /** Step 2: ask Android for the VPN permission, or start right away if already granted. */
    private fun requestVpnConsent() {
        val consentIntent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            // Another app holds an always-on VPN lock, or a similar system restriction.
            Log.e(TAG, "VpnService.prepare() threw", e)
            Toast.makeText(this, getString(R.string.vpn_prepare_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "prepare() -> ${consentIntent?.let { "consent intent $it" } ?: "already granted"}")

        if (consentIntent == null) {
            startVpn()
            return
        }

        try {
            vpnConsentLauncher.launch(consentIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No activity handles the VPN consent intent", e)
            showVpnConsentDialog(NO_CONSENT_HANDLER)
        } catch (e: Exception) {
            Log.e(TAG, "Launching VPN consent failed", e)
            Toast.makeText(this, getString(R.string.vpn_prepare_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Name of another app currently providing a VPN, or null if none is active. */
    private fun activeOtherVpnPackage(): String? {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return null
            cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    !AdBlockVpnService.isRunning
            }?.let { "another VPN app" }
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect active networks", e)
            null
        }
    }

    private fun showVpnConsentDialog(resultCode: Int) {
        val otherVpn = activeOtherVpnPackage()
        val message = when {
            resultCode == NO_CONSENT_HANDLER ->
                getString(R.string.vpn_no_handler)
            otherVpn != null ->
                getString(R.string.vpn_blocked_by_other, otherVpn)
            else ->
                getString(R.string.vpn_consent_message)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_consent_title)
            .setMessage(message)
            .setPositiveButton(R.string.try_again) { _, _ -> requestVpnConsent() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startVpn() {
        prefs.vpnDesired = true
        AdBlockVpnService.start(this)
    }

    /**
     * Shows the most recently blocked domains; tapping one offers to allowlist it.
     * This is the self-service diagnostic for "site X stopped working": open it right
     * after the failure, the culprit is near the top.
     */
    private fun showRecentlyBlocked() {
        val events = BlocklistManager.recentlyBlocked()
        if (events.isEmpty()) {
            Toast.makeText(this, R.string.recently_blocked_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val items = events.map { event ->
            "${event.domain}   ×${event.count} · ${
                DateUtils.getRelativeTimeSpanString(
                    event.lastSeen, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
                )
            }"
        }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recently_blocked)
            .setItems(items) { _, which -> confirmAllowDomain(events[which].domain) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmAllowDomain(domain: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.allow_domain_title, domain))
            .setMessage(R.string.allow_domain_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                prefs.appendCustomAllowed(domain)
                thread { BlocklistManager.load(applicationContext) }
                Toast.makeText(
                    this, getString(R.string.domain_allowed_toast, domain), Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshBlocklist() {
        if (refreshing) return
        refreshing = true
        binding.refreshButton.isEnabled = false
        binding.refreshButton.setText(R.string.refreshing)
        val url = prefs.blocklistUrl
        thread {
            val error = BlocklistManager.refresh(applicationContext, url)
            runOnUiThread {
                refreshing = false
                binding.refreshButton.isEnabled = true
                binding.refreshButton.setText(R.string.refresh_blocklist)
                val message = if (error == null) {
                    getString(R.string.refresh_done, BlocklistManager.domainCount)
                } else {
                    getString(R.string.refresh_failed, error)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        val running = AdBlockVpnService.isRunning
        binding.statusText.setText(if (running) R.string.status_on else R.string.status_off)
        binding.statusText.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.status_on else R.color.status_off)
        )
        binding.shieldIcon.setColorFilter(
            ContextCompat.getColor(this, if (running) R.color.status_on else R.color.status_off)
        )
        binding.toggleButton.setText(
            if (running) R.string.stop_blocking else R.string.start_blocking
        )
        binding.totalValue.text = AdBlockVpnService.queriesTotal.get().toString()
        binding.blockedValue.text = AdBlockVpnService.queriesBlocked.get().toString()

        val count = BlocklistManager.domainCount
        val last = prefs.lastRefreshMillis
        val updated = if (last > 0) {
            DateUtils.getRelativeTimeSpanString(last).toString()
        } else {
            getString(R.string.blocklist_bundled)
        }
        binding.blocklistInfo.text =
            if (count > 0) getString(R.string.blocklist_info, count, updated)
            else getString(R.string.blocklist_loading)
    }
}
