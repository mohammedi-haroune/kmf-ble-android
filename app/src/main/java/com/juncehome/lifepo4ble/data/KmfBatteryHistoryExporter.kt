package com.juncehome.lifepo4ble.data

import com.juncehome.lifepo4ble.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KmfBatteryHistoryExport(
    val file: File,
    val rowCount: Int,
)

/** The finished CSV currently on disk (from this or an earlier session). */
data class KmfBatteryHistoryExportInfo(
    val fileName: String,
    val modifiedMs: Long,
    val sizeBytes: Long,
)

interface KmfBatteryHistoryExportStore {
    /** Writes the CSV; [onProgress] receives the rows written so far after each page. */
    suspend fun export(onProgress: (rowsWritten: Int) -> Unit = {}): KmfBatteryHistoryExport

    suspend fun clear()

    /** Last finished export file, or null when none exists. */
    suspend fun lastExport(): KmfBatteryHistoryExportInfo? = null
}

class KmfBatteryHistoryExporter(
    private val batterySampleDao: KmfBatterySampleDao,
    private val exportsDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KmfBatteryHistoryExportStore {
    override suspend fun export(onProgress: (rowsWritten: Int) -> Unit): KmfBatteryHistoryExport =
        withContext(ioDispatcher) {
            exportsDirectory.mkdirs()
            val target = exportsDirectory.resolve(FILE_NAME)
            val temporary = exportsDirectory.resolve("$FILE_NAME.tmp")
            var rowCount = 0
            var afterId = 0L
            AppLog.d("History export: writing ${temporary.name} in pages of $EXPORT_PAGE_SIZE", TAG)

            temporary.bufferedWriter().use { writer ->
                KmfBatteryCsv.appendHeaderTo(writer)
                while (true) {
                    val samples = batterySampleDao.readAfterId(afterId, EXPORT_PAGE_SIZE)
                    if (samples.isEmpty()) {
                        break
                    }
                    samples.forEach { sample ->
                        KmfBatteryCsv.appendRowTo(writer, sample)
                        rowCount += 1
                    }
                    afterId = samples.last().id
                    onProgress(rowCount)
                }
            }
            check(temporary.renameTo(target)) { "Unable to replace ${target.name}" }
            AppLog.d("History export: replaced ${target.name} rows=$rowCount bytes=${target.length()}", TAG)
            KmfBatteryHistoryExport(file = target, rowCount = rowCount)
        }

    override suspend fun clear() {
        batterySampleDao.deleteAll()
    }

    override suspend fun lastExport(): KmfBatteryHistoryExportInfo? = withContext(ioDispatcher) {
        val target = exportsDirectory.resolve(FILE_NAME)
        if (target.isFile) {
            KmfBatteryHistoryExportInfo(fileName = target.name, modifiedMs = target.lastModified(), sizeBytes = target.length())
        } else {
            null
        }
    }

    companion object {
        const val FILE_NAME = "battery-history.csv"
        private const val EXPORT_PAGE_SIZE = 500
        private const val TAG = "KMF-BLE"

        /** Documented Mac command (AGENTS.md) that pulls the finished export over ADB. */
        const val ADB_PULL_COMMAND =
            "adb exec-out run-as com.juncehome.lifepo4ble cat files/exports/$FILE_NAME > $FILE_NAME"
    }
}
