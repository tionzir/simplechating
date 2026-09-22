package com.smiplechating

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** 主界面日志与消息总线的简单实现 */
object Bus {
    private val _log = MutableLiveData<String>()
    val log: LiveData<String> get() = _log

    private val buffer = StringBuilder()

    @Synchronized
    fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        buffer.append("[").append(ts).append("] ").append(line).append("\n")
        if (buffer.length > 20000) {
            buffer.delete(0, buffer.length - 20000)
        }
        _log.postValue(buffer.toString())
    }

    @Synchronized
    fun clear() {
        buffer.setLength(0)
        _log.postValue("")
    }

    @Synchronized
    fun dump(): String = buffer.toString()

    /** 最近采集到的聊天上下文（供悬浮面板展示/请求服务） */
    @Volatile
    var lastContext: String = ""
}
