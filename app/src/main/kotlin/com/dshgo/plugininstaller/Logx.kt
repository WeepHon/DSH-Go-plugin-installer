package com.dshgo.plugininstaller

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 内存日志缓冲：上限 400 行，供 UI 实时展示与导出。 */
object Logx {
    private val lines = ArrayDeque<String>(400)
    @Volatile var listener: ((String) -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 400) lines.removeFirst()
        }
        Handler(Looper.getMainLooper()).post { listener?.invoke(line) }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }
}
