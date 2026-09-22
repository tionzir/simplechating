package com.jev.chat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.chat.Bus
import com.jev.chat.Prefs
import com.jev.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jev.chat.net.Api

/**
 * 悬浮窗前台服务：
 * - 一个可拖动的悬浮球
 * - 点击 → 显示候选面板（调本地服务拿候选）
 * - 点候选 → 通过无障碍服务填词（不发送）
 * - 自动模式：收到 Bus.onChatContext 时自动弹出候选
 */
class FloatingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var ballView: View? = null
    private var panelView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var lastContext = ""

    companion object {
        var running = false
            private set
        private const val CH_ID = "jevchat_float"
        private const val NOTI_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTI_ID, buildNotification())
        showBall()

        // 自动模式：抓到上下文 → 自动分析
        Bus.onChatContext = { ctx ->
            lastContext = ctx
            if (Prefs.auto(this)) {
                Bus.log("Float", "自动触发分析")
                analyzeAndShow(ctx)
            }
        }
        // 手动：主界面点了「分析当前聊天」
        Bus.onRequestAnalyze = {
            if (lastContext.isEmpty()) lastContext = grabCurrentContext()
            analyzeAndShow(lastContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        Bus.onChatContext = null
        Bus.onRequestAnalyze = null
        ballView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "JevChat 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("JevChat 运行中")
            .setContentText("点击悬浮球分析聊天")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
    }

    // ---------- 悬浮球 ----------
    private fun showBall() {
        val v = LayoutInflater.from(this).inflate(R.layout.float_ball, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = ev.rawX; touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) moved = true
                    p.x = startX + dx; p.y = startY + dy
                    wm.updateViewLayout(v, p)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 点击 → 分析并显示候选
                        if (lastContext.isEmpty()) lastContext = grabCurrentContext()
                        analyzeAndShow(lastContext)
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(v, p)
        ballView = v
        ballParams = p
    }

    private fun grabCurrentContext(): String {
        // 简单占位：真正上下文由无障碍服务推送；这里返回最近一次
        return lastContext
    }

    // ---------- 候选面板 ----------
    private fun analyzeAndShow(ctx: String) {
        scope.launch {
            val url = Prefs.serverUrl(this@FloatingService)
            val count = Prefs.candidates(this@FloatingService)
            Bus.log("Float", "请求 $url （${ctx.length} 字）")
            val list = withContext(Dispatchers.IO) {
                Api.reply(url, ctx.ifEmpty { "(空)" }, count)
            }
            if (list.isEmpty()) {
                Bus.log("Float", "未取到候选（检查服务是否启动）")
                showPanel(listOf("(未取到候选，请检查本地服务)"))
            } else {
                Bus.log("Float", "取到 ${list.size} 条候选")
                showPanel(list)
            }
        }
    }

    private fun showPanel(candidates: List<String>) {
        removePanel()
        val v = LayoutInflater.from(this).inflate(R.layout.float_panel, null)
        val container = v.findViewById<LinearLayout>(R.id.containerCandidates)

        for (c in candidates) {
            val btn = Button(this).apply {
                text = c
                isAllCaps = false
                setOnClickListener {
                    val ok = Bus.onFillText?.invoke(c) ?: false
                    if (ok) {
                        removePanel()
                    } else {
                        Bus.log("Float", "填入失败，请确认无障碍已开且处于聊天界面")
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(btn, lp)
        }

        v.findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            analyzeAndShow(lastContext)
        }
        v.findViewById<Button>(R.id.btnClose).setOnClickListener { removePanel() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        wm.addView(v, p)
        panelView = v
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }
}
