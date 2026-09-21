package com.steamcontroller.android.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.steamcontroller.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small release-safe event history for support without requiring adb or raw HID capture. */
object Diagnostics {
    private const val MAX_LINES = 256
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun record(tag: String, message: String) {
        if (lines.size == MAX_LINES) lines.removeFirst()
        lines.addLast(
            "${timestamp.format(Date())} +${SystemClock.elapsedRealtime()}ms $tag $message"
        )
    }

    @Synchronized
    fun export(context: Context): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        return File(directory, "steam-controller-diagnostics.txt").apply {
            writeText(
                buildString {
                    appendLine(
                        "steam controller ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    )
                    appendLine("android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT}")
                    appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine()
                    lines.forEach(::appendLine)
                }
            )
        }
    }
}
