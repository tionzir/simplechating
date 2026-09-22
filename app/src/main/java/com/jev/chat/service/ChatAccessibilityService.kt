package com.jev.chat.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.chat.Bus
import com.jev.chat.Prefs

/**
 * 无障碍服务：
 * 1) 监听 QQ / 微信 的聊天界面，抓取最近消息文本 → Bus.onChatContext
 * 2) 提供「把候选文本填入输入框」能力 → Bus.onFillText
 *
 * 绝不自动发送：只做 ACTION_SET_TEXT，不触发发送按钮。
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        const val QQ = "com.tencent.mobileqq"
        const val WX = "com.tencent.mm"

        /** 供悬浮窗调用：当前实例（用于填词/取窗口） */
        @Volatile
        var instance: ChatAccessibilityService? = null
            private set

        /** 常见输入框 id（QQ/微信不同版本略有差异，逐个尝试） */
        private val INPUT_IDS = listOf(
            "com.tencent.mobileqq:id/input",
            "com.tencent.mobileqq:id/et_input",
            "com.tencent.mm:id/bkk",
            "com.tencent.mm:id/chatting_input",
            "com.tencent.mm:id/edit_text"
        )
    }

    private var lastContext = ""
    private var lastTime = 0L
    private var lastPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Bus.onFillText = { text -> fillInput(text) }
        Bus.log("A11y", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return

        // 只处理 QQ / 微信
        val isQQ = pkg == QQ
        val isWX = pkg == WX
        if (!isQQ && !isWX) return
        if (isQQ && !Prefs.listenQQ(this)) return
        if (isWX && !Prefs.listenWX(this)) return

        // 只在自动模式抓上下文
        if (!Prefs.auto(this)) return

        val root = rootInActiveWindow ?: return

        // 防抖：同一内容/短时间不重复
        val now = System.currentTimeMillis()
        val ctx = collectMessages(root)
        if (ctx.isEmpty()) return
        if (ctx == lastContext && now - lastTime < 1500) return
        if (now - lastTime < Prefs.debounce(this)) return

        lastContext = ctx
        lastTime = now
        lastPkg = pkg
        Bus.log("A11y", "抓到 ${ctx.length} 字上下文（${if (isQQ) "QQ" else "微信"}）")
        Bus.onChatContext?.invoke(ctx)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        Bus.onFillText = null
        super.onDestroy()
    }

    /**
     * 遍历节点树，收集可见文本，拼接成上下文。
     * 取「屏幕中下部」的文本，避免把顶部标题栏也带进去。
     */
    private fun collectMessages(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        walk(root, sb)
        // 取最后一段（最近的消息在末尾），限制长度
        val all = sb.toString().trim()
        return if (all.length > 600) all.substring(all.length - 600) else all
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        val n = node ?: return
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && n.isVisibleToUser) {
            // 跳过明显的按钮/时间戳
            if (!isNoise(text)) {
                sb.append(text).append('\n')
            }
        }
        for (i in 0 until n.childCount) {
            walk(n.getChild(i), sb)
        }
    }

    private fun isNoise(t: String): Boolean {
        if (t.length <= 0) return true
        // 纯时间 "12:30" 或 "[图片]" 之类
        if (t.matches(Regex("^\\d{1,2}:\\d{2}$"))) return true
        if (t == "[图片]" || t == "[表情]" || t == "[语音]" || t == "[视频]") return true
        return false
    }

    /** 找输入框并填入文本（不发送） */
    private fun fillInput(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Bus.log("A11y", "填词失败：无活动窗口")
            return false
        }
        // 1) 按已知 id 找
        for (id in INPUT_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                if (setText(nodes[0], text)) return true
            }
        }
        // 2) 按 Editable 类找
        val editables = root.findAccessibilityNodeInfosByViewId("")
        val found = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, found)
        if (found.isNotEmpty()) {
            if (setText(found.last(), text)) return true
        }
        Bus.log("A11y", "填词失败：未找到输入框")
        return false
    }

    private fun collectEditable(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        val n = node ?: return
        if (n.isEditable && n.isVisibleToUser) out.add(n)
        for (i in 0 until n.childCount) collectEditable(n.getChild(i), out)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            Bus.log("A11y", "已填入：$text")
        }
        return ok
    }
}
