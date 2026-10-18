package com.juncehome.lifepo4ble

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Append-only log with daily rotation and a total size budget.
 *
 * The active file keeps its name (`kmf-ble.log`). When the local day changes, or it reaches
 * [maxFileBytes], it is renamed to `kmf-ble-YYYY-MM-DD[-N].log`. Oldest archives are deleted
 * so archives plus a full active file fit within [maxTotalBytes].
 */
class RotatingLogFile(
    private val file: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val stem = file.nameWithoutExtension
    private val archivePattern = Regex("""${Regex.escape(stem)}-(\d{4}-\d{2}-\d{2})(?:-(\d+))?\.log""")
    private var currentBytes = 0L
    private var currentDay: LocalDate

    init {
        file.parentFile?.mkdirs()
        currentBytes = if (file.exists()) file.length() else 0L
        currentDay = if (currentBytes > 0L) dayOf(file.lastModified()) else dayOf(clock())
        synchronized(file) {
            rollIfNeeded()
            prune()
        }
    }

    fun append(line: String) {
        synchronized(file) {
            rollIfNeeded()
            file.appendText(line)
            currentBytes += line.toByteArray().size
        }
    }

    private fun dayOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun rollIfNeeded() {
        val today = dayOf(clock())
        if (currentBytes > 0L && (today != currentDay || currentBytes >= maxFileBytes)) {
            archive()
        }
        if (currentBytes == 0L) currentDay = today
    }

    private fun archive() {
        val dir = file.parentFile ?: return
        var target = File(dir, "$stem-$currentDay.log")
        var n = 2
        while (target.exists()) target = File(dir, "$stem-$currentDay-${n++}.log")
        if (file.renameTo(target)) {
            currentBytes = 0L
            prune()
        }
    }

    /** Oldest first: by day, then by part number (the unsuffixed file is part 1). */
    private fun archiveKey(f: File): Pair<String, Int> {
        val m = archivePattern.matchEntire(f.name)!!
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 1)
    }

    private fun prune() {
        val archives = file.parentFile?.listFiles { f -> archivePattern.matches(f.name) }
            ?.sortedWith(compareBy<File> { archiveKey(it).first }.thenBy { archiveKey(it).second })
            ?.toMutableList() ?: return
        // Reserve room for the active file to grow to maxFileBytes so the total stays within budget.
        val archiveBudget = (maxTotalBytes - maxFileBytes).coerceAtLeast(0L)
        var total = archives.sumOf { it.length() }
        while (total > archiveBudget && archives.isNotEmpty()) {
            val oldest = archives.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 500L * 1024 * 1024
        const val DEFAULT_MAX_FILE_BYTES = 100L * 1024 * 1024
    }
}
