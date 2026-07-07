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
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

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
                requestNotificationPermissionIfNeeded()
                val consentIntent = VpnService.prepare(this)
                if (consentIntent != null) {
                    vpnConsentLauncher.launch(consentIntent)
                } else {
                    startVpn()
                }
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

    private fun startVpn() {
        prefs.vpnDesired = true
        AdBlockVpnService.start(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
