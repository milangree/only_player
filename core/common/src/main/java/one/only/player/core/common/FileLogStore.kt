package one.only.player.core.common

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogStore(
    context: Context,
    private val maxSizeBytes: Long = MAX_LOG_SIZE_BYTES,
) {
    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val exportLogFile = File(context.cacheDir, EXPORT_LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun append(
        level: String,
        tag: String,
        message: String,
        throwable: String? = null,
    ) = synchronized(lock) {
        val line = buildString {
            append(dateFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            throwable?.let {
                appendLine()
                append(it)
            }
            appendLine()
        }

        logFile.parentFile?.mkdirs()
        logFile.appendText(line)
        trimToMaxSize()
    }

    fun read(): String = synchronized(lock) {
        if (!logFile.exists()) return@synchronized ""
        trimToMaxSize()
        logFile.readText()
    }

    fun readTail(maxBytes: Long): String = synchronized(lock) {
        if (!logFile.exists()) return@synchronized ""
        trimToMaxSize()
        readTailText(maxBytes.coerceAtLeast(0L))
    }

    fun clear(): Boolean = synchronized(lock) {
        val isCleared = runCatching {
            if (logFile.exists()) logFile.writeText("")
        }.isSuccess
        if (isCleared) runCatching { exportLogFile.delete() }
        isCleared
    }

    fun exportFile(content: String): File = synchronized(lock) {
        exportLogFile.parentFile?.mkdirs()
        exportLogFile.writeText(content)
        exportLogFile
    }

    private fun trimToMaxSize() {
        if (logFile.length() <= maxSizeBytes) return

        logFile.writeText(readTailText(maxSizeBytes))
    }

    private fun readTailText(maxBytes: Long): String {
        if (maxBytes <= 0L) return ""

        val length = logFile.length()
        val start = (length - maxBytes).coerceAtLeast(0L)
        val size = (length - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(size)
        RandomAccessFile(logFile, "r").use { input ->
            input.seek(start)
            input.readFully(bytes)
        }
        val tail = bytes.toString(StandardCharsets.UTF_8)
        val firstLineBreakIndex = tail.indexOf('\n')
        return if (start > 0L && firstLineBreakIndex >= 0) tail.drop(firstLineBreakIndex + 1) else tail
    }

    companion object {
        private const val LOG_FILE_NAME = "only_player.log"
        private const val EXPORT_LOG_FILE_NAME = "only_player_sanitized.log"
        const val MAX_LOG_SIZE_BYTES = 1L * 1024L * 1024L
    }
}
