package com.smiplechating.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.smiplechating.Bus
import com.smiplechating.Prefs
import com.smiplechating.R
import com.smiplechating.net.Api
import com.smiplechating.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮球 + 候选面板服务。
 *
 * 前台服务，保证在后台存活。
 * 关键：通知里带「停止」按钮，用户可随时关闭而不用卸载。
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private var ballView: View? = null
    private var panelView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        alive = true                       // ← 新增：真实存活标志
        startForegroundSafe()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Prefs.floatBallEnabled(this)) showBall()
        Bus.log("悬浮球服务已启动 (pid=${android.os.Process.myPid()})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Bus.log("收到通知栏「停止」指令，服务即将退出")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 被系统重启后可能悬浮球丢了，重新确保一次
        if (ballView == null && Prefs.floatBallEnabled(this)) {
            runCatching { showBall() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        alive = false                      // ← 新增：真实存活标志
        removeBall()
        removePanel()
        scope.cancel()
        Bus.log("悬浮球服务已停止")
    }

    // ---------- 前台通知 ----------

    private fun startForegroundSafe() {
        val chId = "smiplechating_float"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "悬浮球", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知栏「停止」按钮
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("SimpleChating")
            .setContentText("聊天助手运行中（点通知可打开）")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, "停止", stopPi)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTI_ID, n)
            }
        } catch (e: Exception) {
            // 前台启动失败（如权限被撤）不能让服务崩溃，降级为普通服务
            Bus.log("startForeground 失败: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // ---------- 悬浮球 ----------

    private fun showBall() {
        if (ballView != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.float_ball, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }
        v.alpha = (Prefs.floatAlpha(this) / 100f).coerceIn(0.2f, 1f)

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        ballView = v
        ballParams = params
        runCatching { wm.addView(v, params) }
    }

    private fun removeBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------- 候选面板 ----------

    private fun togglePanel() {
        if (panelView != null) {
            removePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        val v = LayoutInflater.from(this).inflate(R.layout.float_panel, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val tvContext = v.findViewById<TextView>(R.id.tvContext)
        val list = v.findViewById<LinearLayout>(R.id.candidateList)
        val empty = v.findViewById<TextView>(R.id.tvEmpty)

        val ctxText = Bus.lastContext
        tvContext.text = if (ctxText.isBlank()) "（暂无采集到的消息）"
        else "最近消息：\n" + ctxText.take(120)

        v.findViewById<TextView>(R.id.btnClose).setOnClickListener { removePanel() }

        v.findViewById<View>(R.id.btnAnalyze).setOnClickListener {
            list.removeAllViews()
            empty.visibility = View.GONE
            val state = Bus.lastContext.ifBlank { tvContext.text.toString() }
            Bus.log("请求候选回复…")
            scope.launch {
                Api.judge(this@FloatingService, state)
                val candidates = Api.reply(this@FloatingService, state)
                renderCandidates(list, empty, candidates)
            }
        }

        panelView = v
        runCatching { wm.addView(v, params) }
    }

    private fun renderCandidates(list: LinearLayout, empty: TextView, candidates: List<String>) {
        list.removeAllViews()
        if (candidates.isEmpty()) {
            empty.text = "服务未返回候选（检查设置里的服务地址）"
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        for (c in candidates) {
            val tv = TextView(this).apply {
                text = c
                textSize = 13f
                setPadding(20, 20, 20, 20)
                setTextColor(0xFF1B1F27.toInt())
                setBackgroundColor(0x14000000)
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("candidate", c))
                    Toast.makeText(this@FloatingService, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    Bus.log("已复制候选: ${c.take(30)}")
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 12
            list.addView(tv, lp)
        }
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    companion object {
        const val NOTI_ID = 1001
        const val ACTION_STOP = "com.smiplechating.action.STOP_FLOAT"

        /** 服务是否真的活着（供主界面按钮显示，别再用偏好猜） */
        @Volatile var alive = false
    }
}
