package com.livesort.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集器
 *
 * 1. 捕获所有未处理的 Java 异常并写入文件
 * 2. 抓取当前进程 logcat 缓存（不需要 root）
 */
object CrashReporter {
    private const val CRASH_LOG = "crash_log.txt"
    private const val LOGCAT_LOG = "logcat_log.txt"

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(context.applicationContext, thread, throwable)
                collectLogcat(context.applicationContext)
            } catch (_: Exception) {
                // 如果日志写入也崩了，不阻塞系统默认处理
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, CRASH_LOG)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        file.writeText(buildString {
            appendLine("Crash Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("Stack Trace:")
            appendLine(throwable.stackTraceToString())
        })
    }

    fun hasCrashLog(context: Context): Boolean = File(context.filesDir, CRASH_LOG).exists()

    fun getCrashLog(context: Context): String = try {
        File(context.filesDir, CRASH_LOG).readText()
    } catch (_: Exception) {
        ""
    }

    fun clearCrashLog(context: Context) {
        File(context.filesDir, CRASH_LOG).delete()
        File(context.filesDir, LOGCAT_LOG).delete()
    }

    fun collectLogcat(context: Context): String = try {
        val pid = Process.myPid().toString()
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid"))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        File(context.filesDir, LOGCAT_LOG).writeText(output)
        output
    } catch (e: Exception) {
        "logcat failed: ${e.message}"
    }

    fun getLogcat(context: Context): String = try {
        File(context.filesDir, LOGCAT_LOG).readText()
    } catch (_: Exception) {
        ""
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", text))
        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
