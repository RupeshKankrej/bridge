package dev.bridge.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporting to a local file only (no telemetry): uncaught exceptions are appended to `files/crash.log`, private
 * to Bridge, then passed on so Android still shows its crash dialog. Stack traces only; never payloads.
 */
object CrashLog {
    private const val MAX_BYTES = 256 * 1024
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "crash.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { append("Crash in thread ${thread.name}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
    }

    fun append(entry: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val old = if (file.exists()) file.readText() else ""
        // Keep the newest entries; the cap keeps a crash loop from filling storage.
        file.writeText((old + "=== $stamp\n$entry\n").takeLast(MAX_BYTES))
    }

    fun read(): String = if (::file.isInitialized && file.exists()) file.readText() else ""

    fun clear() {
        if (::file.isInitialized) file.delete()
    }
}
