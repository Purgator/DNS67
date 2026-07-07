package fr.arichard.adblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
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

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                showVpnConsentDialog()
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
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Load the blocklist early so the domain counter is meaningful right away.
        thread { BlocklistManager.ensureLoaded(applicationContext) }
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
            Toast.makeText(this, getString(R.string.vpn_prepare_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        if (consentIntent != null) {
            vpnConsentLauncher.launch(consentIntent)
        } else {
            startVpn()
        }
    }

    private fun showVpnConsentDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_consent_title)
            .setMessage(R.string.vpn_consent_message)
            .setPositiveButton(R.string.try_again) { _, _ -> requestVpnConsent() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startVpn() {
        prefs.vpnDesired = true
        AdBlockVpnService.start(this)
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
