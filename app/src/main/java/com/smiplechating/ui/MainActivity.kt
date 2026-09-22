package com.smiplechating.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.smiplechating.Bus
import com.smiplechating.Prefs
import com.smiplechating.R
import com.smiplechating.net.Api
import com.smiplechating.service.FloatingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggleFloat: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvLog = findViewById(R.id.tvLog)
        btnToggleFloat = findViewById(R.id.btnToggleFloat)

        Bus.log.observe(this) { text ->
            tvLog.text = text
        }
        if (tvLog.text.isNullOrEmpty()) tvLog.text = Bus.dump()

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlay() }
        btnToggleFloat.setOnClickListener { toggleFloatService() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testServer() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { Bus.clear() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun refreshStatus() {
        val overlayOk = canDrawOverlay()
        val notiOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        tvStatus.text = "本地服务: ${Prefs.serverBase(this)}"
        tvStatusDetail.text = buildString {
            append("悬浮窗权限: ").append(if (overlayOk) "已授予" else "未授予").append('\n')
            append("通知权限: ").append(if (notiOk) "已允许" else "未允许").append('\n')
            append("监听 QQ: ").append(if (Prefs.watchQq(this@MainActivity)) "开" else "关")
            append("    监听微信: ").append(if (Prefs.watchWechat(this@MainActivity)) "开" else "关").append('\n')
            append("自动分析: ").append(if (Prefs.autoTrigger(this@MainActivity)) "开" else "关")
        }
        btnToggleFloat.text = getString(
            if (isFloatRunning()) R.string.main_btn_stop_float else R.string.main_btn_start_float
        )
    }

    private fun isFloatRunning(): Boolean {
        // 简单判断：偏好开启且悬浮窗权限已授予即认为在跑
        return Prefs.floatBallEnabled(this) && canDrawOverlay()
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlay() {
        if (canDrawOverlay()) {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun toggleFloatService() {
        if (isFloatRunning()) {
            Prefs.sp(this).edit().putBoolean(Prefs.KEY_FLOAT_BALL, false).apply()
            stopService(Intent(this, FloatingService::class.java))
            Bus.log("已请求停止悬浮球")
        } else {
            if (!canDrawOverlay()) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                requestOverlay()
                return
            }
            Prefs.sp(this).edit().putBoolean(Prefs.KEY_FLOAT_BALL, true).apply()
            val i = Intent(this, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            Bus.log("已请求启动悬浮球")
        }
        tvStatus.postDelayed({ refreshStatus() }, 400)
    }

    private fun testServer() {
        Bus.log("测试服务连接…")
        scope.launch {
            val h = Api.health(this@MainActivity)
            if (h != null && h.ok) {
                Toast.makeText(this@MainActivity, R.string.toast_server_ok, Toast.LENGTH_SHORT).show()
                Bus.log("连接正常  mode=${h.mode}  model_dir=${h.modelDir}")
            } else {
                Toast.makeText(this@MainActivity, R.string.toast_server_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
