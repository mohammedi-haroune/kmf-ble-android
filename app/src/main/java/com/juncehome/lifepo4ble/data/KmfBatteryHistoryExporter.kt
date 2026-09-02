package com.juncehome.lifepo4ble.data

import java.io.File

data class KmfBatteryHistoryExport(
    val file: File,
    val rowCount: Int,
)

interface KmfBatteryHistoryExportStore {
    suspend fun export(): KmfBatteryHistoryExport

    suspend fun clear()
}

class KmfBatteryHistoryExporter(
    private val batterySampleDao: KmfBatterySampleDao,
    private val exportsDirectory: File,
) : KmfBatteryHistoryExportStore {
    override suspend fun export(): KmfBatteryHistoryExport {
        exportsDirectory.mkdirs()
        val target = exportsDirectory.resolve(FILE_NAME)
        val temporary = exportsDirectory.resolve("$FILE_NAME.tmp")
        var rowCount = 0
        var afterId = 0L

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
            }
        }
        check(temporary.renameTo(target)) { "Unable to replace ${target.name}" }
        return KmfBatteryHistoryExport(file = target, rowCount = rowCount)
    }

    override suspend fun clear() {
        batterySampleDao.deleteAll()
    }

    companion object {
        const val FILE_NAME = "battery-history.csv"
        private const val EXPORT_PAGE_SIZE = 500
    }
}
