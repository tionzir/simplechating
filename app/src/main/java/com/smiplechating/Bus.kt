package com.smiplechating

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面日志与消息总线的简单实现。
 *
 * 关键改进：日志会【同时落盘】到 filesDir/agent.log，
 * 这样即使 App 被系统杀死（无障碍/悬浮服务被 unbind），
 * 重启后仍能把断线前后的证据读回来。
 */
object Bus {

    private val _log = MutableLiveData<String>()
    val log: LiveData<String> get() = _log

    private val buffer = StringBuilder()

    /** 落盘文件（在 attach 时确定），以及单文件上限 */
    @Volatile private var logFile: File? = null
    private const val FILE_MAX = 256 * 1024   // 256KB 后自动截断，避免无限增长
    private const val BUF_MAX = 20000

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 在 Application / Activity 启动时调用一次：
     *  - 绑定日志文件
     *  - 把上次遗留的日志读回内存 buffer（能看到上次被杀前发生了什么）
     */
    @Synchronized
    fun attach(ctx: Context) {
        if (logFile != null) return
        val f = File(ctx.filesDir, "agent.log")
        logFile = f
        val old = runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")
        if (old.isNotBlank()) {
            buffer.append(old)
            if (buffer.length > BUF_MAX) buffer.delete(0, buffer.length - BUF_MAX)
        }
        _log.postValue(buffer.toString())
        log("---- 日志恢复（上次运行遗留 ${old.length} 字符）----")
    }

    /** 日志文件绝对路径（供设置页「导出/查看日志」用） */
    fun logPath(): String? = logFile?.absolutePath

    @Synchronized
    fun log(line: String) {
        val ts = timeFmt.format(Date())
        val entry = "[$ts] [pid${android.os.Process.myPid()}] $line\n"
        buffer.append(entry)
        if (buffer.length > BUF_MAX) {
            buffer.delete(0, buffer.length - BUF_MAX)
        }
        _log.postValue(buffer.toString())
        appendFile(entry)
    }

    private fun appendFile(entry: String) {
        val f = logFile ?: return
        runCatching {
            if (f.exists() && f.length() > FILE_MAX) {
                // 保留后半段，避免无限膨胀
                val keep = f.readText().takeLast(FILE_MAX / 2)
                f.writeText(keep)
            }
            f.appendText(entry)
        }
    }

    @Synchronized
    fun clear() {
        buffer.setLength(0)
        _log.postValue("")
        runCatching { logFile?.writeText("") }
    }

    @Synchronized
    fun dump(): String = buffer.toString()

    /** 最近采集到的聊天上下文（供悬浮面板展示/请求服务） */
    @Volatile
    var lastContext: String = ""
}
