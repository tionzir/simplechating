package com.smiplechating.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.smiplechating.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_prefs, rootKey)
    }
}
