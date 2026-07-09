package fr.arichard.adblocker

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import fr.arichard.adblocker.core.BlocklistManager
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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            listOf(Prefs.KEY_UPSTREAM_DNS, Prefs.KEY_UPSTREAM_DNS2).forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT
                    it.setSingleLine()
                }
            }
            findPreference<EditTextPreference>(Prefs.KEY_BLOCKLIST_URL)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_TEXT_VARIATION_URI
                it.setSingleLine()
            }
            listOf(Prefs.KEY_CUSTOM_BLOCKED, Prefs.KEY_CUSTOM_ALLOWED).forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    it.minLines = 3
                }
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
        }

        private fun checkForUpdates() {
            val appContext = requireContext().applicationContext
            Toast.makeText(appContext, R.string.update_checking, Toast.LENGTH_SHORT).show()
            thread {
                val result = UpdateManager.check(appContext, allowDownload = true)
                activity?.runOnUiThread {
                    when (result.status) {
                        UpdateManager.Status.UPDATE_READY -> {
                            startActivity(UpdateManager.installIntent(appContext, result.version!!))
                        }
                        UpdateManager.Status.UP_TO_DATE -> Toast.makeText(
                            appContext,
                            getString(R.string.update_none, result.version),
                            Toast.LENGTH_LONG
                        ).show()
                        else -> Toast.makeText(
                            appContext,
                            getString(R.string.update_error, result.detail ?: "?"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        private companion object {
            const val KEY_CHECK_UPDATES = "check_updates_now"
        }
    }
}
