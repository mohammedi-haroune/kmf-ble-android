package com.juncehome.lifepo4ble

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingLogFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli()

    private fun log(dir: File, total: Long = 1_000, perFile: Long = 100) =
        RotatingLogFile(File(dir, "kmf-ble.log"), total, perFile, { now }, ZoneOffset.UTC)

    private fun names(dir: File) = dir.list().orEmpty().sorted()

    @Test
    fun rollsOnDayChange() {
        val dir = temporaryFolder.newFolder()
        val log = log(dir)
        log.append("a\n")
        now += 86_400_000L
        log.append("b\n")
        assertEquals(listOf("kmf-ble-2026-10-18.log", "kmf-ble.log"), names(dir))
        assertEquals("a\n", File(dir, "kmf-ble-2026-10-18.log").readText())
        assertEquals("b\n", File(dir, "kmf-ble.log").readText())
    }

    @Test
    fun rollsOnPerFileCapWithSuffix() {
        val dir = temporaryFolder.newFolder()
        val log = log(dir, perFile = 10)
        repeat(3) { log.append("0123456789\n") }
        assertEquals(
            listOf("kmf-ble-2026-10-18-2.log", "kmf-ble-2026-10-18.log", "kmf-ble.log"),
            names(dir),
        )
    }

    @Test
    fun prunesOldestArchivesToTotalBudget() {
        val dir = temporaryFolder.newFolder()
        val log = log(dir, total = 35, perFile = 10)
        repeat(6) {
            log.append("0123456789\n")
            now += 1_000
        }
        val total = dir.listFiles()!!.sumOf { it.length() }
        // the active file may overshoot its cap by one line
        assertTrue("total=$total", total <= 35 + 1)
        assertTrue(File(dir, "kmf-ble.log").exists())
        assertFalse(File(dir, "kmf-ble-2026-10-18.log").exists())
    }

    @Test
    fun oversizedLegacyFileIsArchivedAndPrunedOnStart() {
        val dir = temporaryFolder.newFolder()
        val legacy = File(dir, "kmf-ble.log")
        legacy.writeText("x".repeat(500))
        legacy.setLastModified(now)
        val log = log(dir, total = 200, perFile = 100)
        log.append("new\n")
        assertEquals(listOf("kmf-ble.log"), names(dir))
        assertEquals("new\n", File(dir, "kmf-ble.log").readText())
    }
}
