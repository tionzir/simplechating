package com.jev.chat.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.chat.Bus
import com.jev.chat.Prefs
import com.jev.chat.R
import com.jev.chat.service.FloatingService
import com.jev.chat.service.ChatAccessibilityService
import com.jev.chat.net.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvLog = findViewById(R.id.tvLog)

        Bus.onLog = { line ->
            runOnUiThread {
                tvLog.append(line + "\n")
            }
        }

        findViewById<Button>(R.id.btnGotoAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnGotoOverlay).setOnClickListener {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        }
        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            startFloating()
        }
        findViewById<Button>(R.id.btnToggleAuto).setOnClickListener {
            Prefs.get(this).edit().putBoolean(Prefs.KEY_AUTO, !Prefs.auto(this)).apply()
            refreshStatus()
        }
        findViewById<Button>(R.id.btnAnalyze).setOnClickListener {
            Bus.onRequestAnalyze?.invoke()
                ?: Bus.log("Main", "请先启动悬浮窗服务")
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { tvLog.text = "" }

        // 探活本地服务
        scope.launch {
            val url = Prefs.serverUrl(this@MainActivity)
            val h = withContext(Dispatchers.IO) { Api.health(url) }
            val tv = findViewById<TextView>(R.id.tvServiceStatus)
            if (h == null) {
                tv.text = "服务: 未连接（$url）"
            } else {
                tv.text = "服务: 在线 mode=${h.mode}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Bus.onLog = null
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val a11yOn = ChatAccessibilityService.instance != null
        val overlayOn = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.tvAccessibility).text =
            getString(if (a11yOn) R.string.status_on else R.string.status_off)
        findViewById<TextView>(R.id.tvOverlay).text =
            getString(if (overlayOn) R.string.status_on else R.string.status_off)
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Bus.log("Main", "请先授予悬浮窗权限")
            return
        }
        startForegroundService(Intent(this, FloatingService::class.java))
        Bus.log("Main", "悬浮窗服务已启动")
    }
}
