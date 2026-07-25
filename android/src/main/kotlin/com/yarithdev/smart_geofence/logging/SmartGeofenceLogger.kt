package com.yarithdev.smart_geofence.logging

import android.content.Context
import android.util.Log
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeBoolean
import com.yarithdev.smart_geofence.core.safeInt
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmartGeofenceLogger {
    private const val TAG = "SmartGeofenceLogger"
    private const val TRIM_TARGET_DIVISOR = 3
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedFileLoggingEnabled: Boolean? = null

    @Volatile
    private var cachedFileLoggingVerbose: Boolean? = null

    @Volatile
    private var cachedMaxBytes: Int? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun configure(context: Context, enabled: Boolean, maxBytes: Int, verbose: Boolean) {
        initialize(context)
        val normalizedMaxBytes = normalizeMaxBytes(maxBytes)
        context.applicationContext
            .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.CONFIG_LOG_FILE_ENABLED, enabled)
            .putBoolean(Constants.CONFIG_LOG_FILE_VERBOSE, verbose)
            .putInt(Constants.CONFIG_MAX_LOG_FILE_BYTES, normalizedMaxBytes)
            .apply()
        cachedFileLoggingEnabled = enabled
        cachedFileLoggingVerbose = verbose
        cachedMaxBytes = normalizedMaxBytes
    }

    fun d(tag: String, message: String) {
        if (!isVerboseFileLoggingEnabled()) return
        Log.d(tag, message)
        append(null, "debug", tag, message)
    }

    fun d(context: Context, tag: String, message: String) {
        if (!isFileLoggingEnabled(context) || !isVerboseFileLoggingEnabled(context)) return
        Log.d(tag, message)
        append(context, "debug", tag, message)
    }

    fun i(tag: String, message: String) {
        if (!tagOnlyFileLoggingEnabled()) return
        Log.i(tag, message)
        append(null, "info", tag, message)
    }

    fun i(context: Context, tag: String, message: String) {
        if (!isFileLoggingEnabled(context)) return
        Log.i(tag, message)
        append(context, "info", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        append(null, "warning", tag, message, throwable)
    }

    fun w(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        append(context, "warning", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        append(null, "error", tag, message, throwable)
    }

    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        append(context, "error", tag, message, throwable)
    }

    fun fgs(
        context: Context,
        service: String,
        stage: String,
        token: Long,
        attempt: Int? = null,
        detail: String? = null,
    ) {
        d(context, "SmartGeofenceFgs", fgsMessage(service, stage, token, attempt, detail))
    }

    fun fgsWarning(
        context: Context,
        service: String,
        stage: String,
        token: Long,
        attempt: Int? = null,
        detail: String? = null,
        throwable: Throwable? = null,
    ) {
        w(context, "SmartGeofenceFgs", fgsMessage(service, stage, token, attempt, detail), throwable)
    }

    fun readAsync(context: Context, callback: (Result<String>) -> Unit) {
        initialize(context)
        val ctx = context.applicationContext
        SmartGeofenceIo.execute {
            try {
                callback(Result.success(readLogFile(logFile(ctx))))
            } catch (e: Throwable) {
                Log.e(TAG, "Could not read smart_geofence log file.", e)
                callback(Result.failure(e))
            }
        }
    }

    fun clearAsync(context: Context, callback: (Result<Unit>) -> Unit) {
        initialize(context)
        val ctx = context.applicationContext
        SmartGeofenceIo.execute {
            try {
                val file = logFile(ctx)
                if (file.exists()) {
                    file.outputStream().use { }
                }
                callback(Result.success(Unit))
            } catch (e: Throwable) {
                Log.e(TAG, "Could not clear smart_geofence log file.", e)
                callback(Result.failure(e))
            }
        }
    }

    private fun append(
        context: Context?,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val ctx = context?.applicationContext ?: appContext ?: return
        if (!isFileLoggingEnabled(ctx)) return
        val line = formatLine(level, tag, message, throwable)
        SmartGeofenceIo.execute {
            if (!isFileLoggingEnabled(ctx)) return@execute
            try {
                val file = logFile(ctx)
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.append(line)
                }
                trimToMaxBytes(file, maxBytes(ctx))
            } catch (e: Throwable) {
                Log.e(TAG, "Could not append to smart_geofence log file.", e)
            }
        }
    }

    private fun formatLine(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ): String {
        val timestamp = synchronized(timestampFormat) {
            timestampFormat.format(Date(System.currentTimeMillis()))
        }
        return buildString {
            append(timestamp)
            append(" [")
            append(level)
            append("] ")
            append(tag)
            append(": ")
            append(message)
            append('\n')
            if (throwable != null) {
                append(Log.getStackTraceString(throwable))
                if (isEmpty() || this[length - 1] != '\n') append('\n')
            }
        }
    }

    private fun fgsMessage(
        service: String,
        stage: String,
        token: Long,
        attempt: Int?,
        detail: String?,
    ): String {
        val attemptPart = attempt?.let { " attempt=$it" } ?: ""
        val detailPart = detail?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return "FGS[$service] $stage token=$token$attemptPart$detailPart"
    }

    fun isFileLoggingEnabled(context: Context): Boolean {
        cachedFileLoggingEnabled?.let { return it }
        val enabled = prefs(context).safeBoolean(Constants.CONFIG_LOG_FILE_ENABLED, false)
        cachedFileLoggingEnabled = enabled
        return enabled
    }

    fun isVerboseFileLoggingEnabled(context: Context): Boolean {
        if (!isFileLoggingEnabled(context)) return false
        cachedFileLoggingVerbose?.let { return it }
        val verbose = prefs(context).safeBoolean(
            Constants.CONFIG_LOG_FILE_VERBOSE,
            Constants.DEFAULT_LOG_FILE_VERBOSE
        )
        cachedFileLoggingVerbose = verbose
        return verbose
    }

    private fun tagOnlyFileLoggingEnabled(): Boolean {
        cachedFileLoggingEnabled?.let { return it }
        val ctx = appContext ?: return false
        return isFileLoggingEnabled(ctx)
    }

    private fun isVerboseFileLoggingEnabled(): Boolean {
        val ctx = appContext ?: return false
        return isVerboseFileLoggingEnabled(ctx)
    }

    private fun maxBytes(context: Context): Int {
        cachedMaxBytes?.let { return it }
        val value = normalizeMaxBytes(
            prefs(context).safeInt(
                Constants.CONFIG_MAX_LOG_FILE_BYTES,
                Constants.DEFAULT_LOG_FILE_MAX_BYTES
            )
        )
        cachedMaxBytes = value
        return value
    }

    private fun normalizeMaxBytes(maxBytes: Int): Int =
        maxBytes.coerceIn(Constants.MIN_LOG_FILE_MAX_BYTES, Constants.MAX_LOG_FILE_MAX_BYTES)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun logFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, Constants.LOG_FILE_NAME)

    private fun readLogFile(file: File): String {
        if (!file.exists()) return ""
        val content = StringBuilder()
        file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                content.append(buffer, 0, read)
            }
        }
        return content.toString()
    }

    private fun trimToMaxBytes(file: File, maxBytes: Int) {
        if (!file.exists() || file.length() <= maxBytes) return
        val length = file.length()
        val keep = trimTargetBytes(maxBytes, length)
        val buffer = ByteArray(keep)
        RandomAccessFile(file, "r").use { input ->
            input.seek(length - keep)
            input.readFully(buffer)
        }
        var start = 0
        val newline = buffer.indexOf('\n'.code.toByte())
        if (newline >= 0 && newline < buffer.lastIndex) {
            start = newline + 1
        }
        file.outputStream().use { output ->
            output.write(buffer, start, buffer.size - start)
        }
    }

    internal fun trimTargetBytes(maxBytes: Int, currentLength: Long): Int {
        if (currentLength <= maxBytes) return currentLength.toInt()
        val target = (maxBytes / TRIM_TARGET_DIVISOR).coerceAtLeast(1)
        return target.toLong().coerceAtMost(currentLength).toInt()
    }
}
