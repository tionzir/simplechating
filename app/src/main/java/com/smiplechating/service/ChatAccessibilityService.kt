package com.smiplechating.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smiplechating.Bus
import com.smiplechating.Prefs

/**
 * 聊天消息采集服务（QQ / 微信）
 *
 * 职责：
 *  1. 监听目标 App 窗口内容变化，抓取最新聊天气泡文本
 *  2. 维护最近 N 条消息作为「上下文 state」
 *  3. 通过 Bus 通知悬浮面板（可自动触发分析）
 *  4. 【保活】连接时主动拉起 FloatingService（前台通知），
 *     让整个进程处于前台，降低被系统回收的概率
 *
 * 注意：本服务【绝不发送消息】，也不主动操作输入框。
 */
class ChatAccessibilityService : AccessibilityService() {

    private val recent = ArrayDeque<String>()
    private var lastText: String = ""
    private var lastEventAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Bus.log("无障碍服务已连接 (pid=${android.os.Process.myPid()})")
        ensureFloatingForeground()
    }

    /**
     * 关键保活：无障碍连上后，确保 FloatingService 在跑。
     * 只要它是前台服务，整个 App 进程就有前台优先级，
     * 切到别的 App 时不容易被系统直接杀掉。
     */
    private fun ensureFloatingForeground() {
        try {
            val it = Intent(this, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
            Bus.log("已请求拉起前台悬浮服务（保活）")
        } catch (e: Exception) {
            Bus.log("拉起前台服务失败: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString() ?: return

        val allowed = when (pkg) {
            PKG_QQ -> Prefs.watchQq(this)
            PKG_WECHAT -> Prefs.watchWechat(this)
            else -> false
        }
        if (!allowed) return

        val now = System.currentTimeMillis()
        if (now - lastEventAt < 300) return   // 简单节流
        lastEventAt = now

        val root = rootInActiveWindow ?: return
        try {
            val texts = ArrayList<String>()
            collectText(root, texts, 0)
            if (texts.isEmpty()) return

            val candidate = texts.lastOrNull { it.length in 1..200 } ?: return
            if (candidate == lastText) return
            lastText = candidate

            push(candidate)

            val appName = if (pkg == PKG_QQ) "QQ" else "微信"
            Bus.log("[$appName] 捕获: ${candidate.take(40)}")
            Bus.lastContext = contextText()

            if (Prefs.autoTrigger(this)) {
                Bus.log("自动分析已触发（请在悬浮面板查看候选）")
            }
        } catch (e: Exception) {
            Bus.log("采集异常: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** 递归收集可见文本节点 */
    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.add(t)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    private fun push(text: String) {
        recent.addLast(text)
        val max = Prefs.maxMessages(this).coerceIn(1, 100)
        while (recent.size > max) recent.removeFirst()
    }

    private fun contextText(): String =
        recent.joinToString(separator = "\n")

    override fun onInterrupt() {
        Bus.log("无障碍服务被中断 (onInterrupt)")
    }

    override fun onDestroy() {
        super.onDestroy()
        Bus.log("无障碍服务已断开 (onDestroy)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Bus.log("无障碍服务 unbind （系统解绑，通常是进程被回收或服务被停用）")
        return super.onUnbind(intent)
    }

    companion object {
        const val PKG_QQ = "com.tencent.mobileqq"
        const val PKG_WECHAT = "com.tencent.mm"
    }
}
