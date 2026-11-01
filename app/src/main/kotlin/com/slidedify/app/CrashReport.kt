package com.slidedify.app

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the stack trace of the last crash so the next launch can show it and the user can
 * copy it into a bug report. The default handler still runs, so Android behaves as usual.
 */
object CrashReport {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
                file.writeText(
                    buildString {
                        appendLine("Compass $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Thread: ${thread.name}")
                        append(error.stackTraceToString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
