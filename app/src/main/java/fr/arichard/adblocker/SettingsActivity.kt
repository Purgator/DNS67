package fr.arichard.adblocker

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.core.DebugNotifier
import fr.arichard.adblocker.core.Prefs
import fr.arichard.adblocker.core.UpdateManager
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        // Apply custom block/allow rules immediately, even while the VPN is running.
        thread { BlocklistManager.load(applicationContext) }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private data class Preset(val nameRes: Int, val url: String)

        private val presets = listOf(
            Preset(R.string.preset_standard, Prefs.DEFAULT_BLOCKLIST_URL),
            Preset(
                R.string.preset_family,
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-porn/hosts"
            ),
            Preset(
                R.string.preset_comprehensive,
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/multi.txt"
            ),
        )

        private lateinit var appPrefs: Prefs

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            appPrefs = Prefs(requireContext())

            listOf(Prefs.KEY_UPSTREAM_DNS, Prefs.KEY_UPSTREAM_DNS2).forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT
                    it.setSingleLine()
                }
            }

            findPreference<Preference>(Prefs.KEY_BLOCKLIST_URL)?.setOnPreferenceClickListener {
                showBlocklistPicker()
                true
            }
            findPreference<Preference>(Prefs.KEY_CUSTOM_BLOCKED)?.setOnPreferenceClickListener {
                showDomainListEditor(Prefs.KEY_CUSTOM_BLOCKED, R.string.pref_custom_blocked)
                true
            }
            findPreference<Preference>(Prefs.KEY_CUSTOM_ALLOWED)?.setOnPreferenceClickListener {
                showDomainListEditor(Prefs.KEY_CUSTOM_ALLOWED, R.string.pref_custom_allowed)
                true
            }
            findPreference<Preference>(KEY_OPEN_RECENTLY_BLOCKED)?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), RecentlyBlockedActivity::class.java))
                true
            }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_DEBUG_NOTIFICATIONS)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == false) DebugNotifier.clear(requireContext())
                    true
                }
            findPreference<Preference>(KEY_CHECK_UPDATES)?.apply {
                summary = getString(
                    R.string.pref_check_updates_summary,
                    UpdateManager.currentVersion(requireContext())
                )
                setOnPreferenceClickListener {
                    checkForUpdates()
                    true
                }
            }
            updateSummaries()
        }

        private fun updateSummaries() {
            val url = appPrefs.blocklistUrl
            findPreference<Preference>(Prefs.KEY_BLOCKLIST_URL)?.summary =
                presets.firstOrNull { it.url == url }?.let { getString(it.nameRes) } ?: url
            findPreference<Preference>(Prefs.KEY_CUSTOM_BLOCKED)?.summary =
                countSummary(appPrefs.customBlocked.size)
            findPreference<Preference>(Prefs.KEY_CUSTOM_ALLOWED)?.summary =
                countSummary(appPrefs.customAllowed.size)
        }

        private fun countSummary(count: Int): String =
            if (count == 0) getString(R.string.domain_list_empty_summary)
            else resources.getQuantityString(R.plurals.domains_count, count, count)

        // ------------------------------------------------------------ blocklist picker

        private fun showBlocklistPicker() {
            val current = appPrefs.blocklistUrl
            val names = presets.map { getString(it.nameRes) } + getString(R.string.preset_custom)
            val checked = presets.indexOfFirst { it.url == current }
                .let { if (it < 0) names.size - 1 else it }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_blocklist)
                .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                    dialog.dismiss()
                    if (which < presets.size) saveBlocklistUrl(presets[which].url)
                    else showCustomUrlDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showCustomUrlDialog() {
            val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
            val input = view.findViewById<TextInputEditText>(R.id.textInput)
            view.findViewById<TextInputLayout>(R.id.inputLayout).hint =
                getString(R.string.custom_url_hint)
            input.setText(appPrefs.blocklistUrl)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.preset_custom_title)
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    val url = input.text?.toString()?.trim().orEmpty()
                    if (url.startsWith("https://")) saveBlocklistUrl(url)
                    else toast(getString(R.string.invalid_url))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun saveBlocklistUrl(url: String) {
            if (url == appPrefs.blocklistUrl) return
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putString(Prefs.KEY_BLOCKLIST_URL, url).apply()
            updateSummaries()
            toast(getString(R.string.blocklist_downloading))
            val appContext = requireContext().applicationContext
            thread {
                val error = BlocklistManager.refresh(appContext, url)
                val message = if (error == null) {
                    appContext.getString(R.string.refresh_done, BlocklistManager.domainCount)
                } else {
                    appContext.getString(R.string.refresh_failed, error)
                }
                activity?.runOnUiThread { toast(message) }
            }
        }

        // ------------------------------------------------------------ domain list editor

        private fun showDomainListEditor(key: String, titleRes: Int) {
            val context = requireContext()
            val view = layoutInflater.inflate(R.layout.dialog_domain_list, null)
            val rows = view.findViewById<LinearLayout>(R.id.domainRows)
            val emptyHint = view.findViewById<TextView>(R.id.emptyHint)
            val input = view.findViewById<TextInputEditText>(R.id.domainInput)
            val addButton = view.findViewById<View>(R.id.addButton)

            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            val domains = (sp.getString(key, "") ?: "")
                .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

            fun rebuild() {
                rows.removeAllViews()
                emptyHint.visibility = if (domains.isEmpty()) View.VISIBLE else View.GONE
                domains.forEach { domain ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    row.addView(TextView(context).apply {
                        text = domain
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    })
                    row.addView(TextView(context).apply {
                        text = "✕"
                        textSize = 16f
                        alpha = 0.6f
                        setPadding(dp(12), dp(10), dp(4), dp(10))
                        setOnClickListener {
                            domains.remove(domain)
                            rebuild()
                        }
                    })
                    rows.addView(row)
                }
            }

            fun addFromInput() {
                val domain = input.text?.toString()?.trim()?.trimEnd('.')?.lowercase().orEmpty()
                if (!DOMAIN_PATTERN.matches(domain)) {
                    toast(getString(R.string.invalid_domain))
                    return
                }
                if (domain !in domains) {
                    domains.add(0, domain)
                    rebuild()
                }
                input.setText("")
            }

            addButton.setOnClickListener { addFromInput() }
            input.setOnEditorActionListener { _, _, _ -> addFromInput(); true }
            rebuild()

            MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    sp.edit().putString(key, domains.joinToString("\n")).apply()
                    updateSummaries()
                    thread { BlocklistManager.load(context.applicationContext) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // ------------------------------------------------------------ updates

        private fun checkForUpdates() {
            val appContext = requireContext().applicationContext
            toast(getString(R.string.update_checking))
            thread {
                val result = UpdateManager.check(appContext, allowDownload = true)
                activity?.runOnUiThread {
                    when (result.status) {
                        UpdateManager.Status.UPDATE_READY -> {
                            startActivity(UpdateManager.installIntent(appContext, result.version!!))
                        }
                        UpdateManager.Status.UP_TO_DATE ->
                            toast(getString(R.string.update_none, result.version))
                        else ->
                            toast(getString(R.string.update_error, result.detail ?: "?"))
                    }
                }
            }
        }

        // ------------------------------------------------------------ helpers

        private fun toast(message: String) {
            context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        private companion object {
            const val KEY_CHECK_UPDATES = "check_updates_now"
            const val KEY_OPEN_RECENTLY_BLOCKED = "open_recently_blocked"
            val DOMAIN_PATTERN = Regex("^([a-z0-9-]+\\.)+[a-z]{2,}$")
        }
    }
}
