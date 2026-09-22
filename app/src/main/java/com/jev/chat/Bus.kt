package com.jev.chat

/**
 * 全局轻量事件总线：无障碍服务 → 悬浮窗服务，传递「已抓到的聊天上下文」。
 * 不用第三方库，纯回调，避免额外依赖。
 */
object Bus {
    /** 抓到一段聊天上下文时触发（由无障碍服务调用） */
    var onChatContext: ((String) -> Unit)? = null

    /** 请求悬浮窗立刻分析一次（手动触发） */
    var onRequestAnalyze: (() -> Unit)? = null

    /** 把候选填入输入框（无障碍服务注册实现） */
    var onFillText: ((String) -> Boolean)? = null

    /** 日志回调给主界面 */
    var onLog: ((String) -> Unit)? = null

    fun log(tag: String, msg: String) {
        onLog?.invoke("[$tag] $msg")
    }
}
