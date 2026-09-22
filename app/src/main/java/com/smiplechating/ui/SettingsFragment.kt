package com.smiplechating.ui

import android.app.AlertDialog
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.smiplechating.Bus
import com.smiplechating.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_prefs, rootKey)

        findPreference<Preference>("log_view")?.setOnPreferenceClickListener {
            val text = Bus.dump().takeLast(4000).ifBlank { "（暂无日志）" }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_log_view)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
    }
}
