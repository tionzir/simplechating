package com.jev.chat.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.jev.chat.Prefs
import com.jev.chat.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_prefs, rootKey)

            findPreference<EditTextPreference>(Prefs.KEY_SERVER_URL)?.apply {
                setOnBindEditTextListener { it.setText(Prefs.serverUrl(requireContext())) }
            }
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_AUTO)
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_TARGET_QQ)
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_TARGET_WX)
        }
    }
}
