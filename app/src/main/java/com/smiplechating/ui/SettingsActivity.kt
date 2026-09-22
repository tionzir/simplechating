package com.smiplechating.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.smiplechating.R

/** 设置页：直接承载 res/xml/settings_prefs.xml */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment())
            .commit()
    }
}
