package fr.arichard.adblocker

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.core.Prefs
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
        }
    }
}
