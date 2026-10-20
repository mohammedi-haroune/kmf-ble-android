package com.juncehome.lifepo4ble.ui.trends

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsWindowsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-18T12:32:00Z").toEpochMilli() // Sun 14:32 local
    private val minus = "−"

    @Test
    fun presetsHaveSensiblePointCounts() {
        TrendsRange.entries.filter { it != TrendsRange.Custom }.forEach { range ->
            val points = range.windowMs / range.bucketMs
            assertTrue("${range.label} has $points points", points in 60..300)
        }
        assertEquals(
            listOf("5 m", "15 m", "1 h", "2 h", "6 h", "12 h", "24 h", "2 d", "7 d", "30 d", "Custom"),
            TrendsRange.entries.map { it.label },
        )
    }

    @Test
    fun resolveShiftsBackByWholeWindows() {
        val now0 = TrendsWindows.resolve(TrendsRange.Hour6, 0, null, now)
        assertEquals(now, now0.endMs)
        assertEquals(now - 6 * 3_600_000L, now0.startMs)
        assertTrue(now0.atNow)
        val back = TrendsWindows.resolve(TrendsRange.Hour6, 2, null, now)
        assertEquals(now - 12 * 3_600_000L, back.endMs)
        assertEquals(now - 18 * 3_600_000L, back.startMs)
        assertFalse(back.atNow)
        val minutes = TrendsWindows.resolve(TrendsRange.Min5, 3, null, now)
        assertEquals(now - 15 * 60_000L, minutes.endMs)
        assertEquals(now - 20 * 60_000L, minutes.startMs)
        assertEquals(3, minutes.offset)
    }

    @Test
    fun customWindowIsClampedToNowAndPicksBucketFromSpan() {
        val custom = TrendsCustomRange(now - 3_600_000L, now + 10_000L)
        val window = TrendsWindows.resolve(TrendsRange.Custom, 5, custom, now)
        assertEquals(now, window.endMs)
        assertEquals(0, window.offset)
        assertFalse(window.atNow)
        assertEquals(30_000L, window.bucketMs)
        assertEquals(TrendsCustomRanges.default(now).fromMs, TrendsWindows.resolve(TrendsRange.Custom, 0, null, now).startMs)
        assertEquals(60_000L, TrendsWindows.bucketForSpan(90 * 60_000L))
        assertEquals(86_400_000L, TrendsWindows.bucketForSpan(10_000L * 86_400_000L))
        assertEquals(5_000L, TrendsWindows.bucketForSpan(60_000L))
    }

    @Test
    fun windowLabels() {
        fun label(range: TrendsRange, offset: Int = 0) =
            TrendsWindows.label(TrendsWindows.resolve(range, offset, null, now), now, zone)
        assertEquals("Today 14:27 - now", label(TrendsRange.Min5))
        assertEquals("Today 14:02 - 14:17", label(TrendsRange.Min15, offset = 1))
        assertEquals("Today 12:32 - now", label(TrendsRange.Hour2))
        assertEquals("Today 08:32 - now", label(TrendsRange.Hour6))
        assertEquals("Sat 14:32 - now", label(TrendsRange.Day))
        assertEquals("Fri 14:32 - Sat 14:32", label(TrendsRange.Day, offset = 1))
        assertEquals("Fri 14:32 - now", label(TrendsRange.Day2))
        assertEquals("11 Oct - now", label(TrendsRange.Week))
        assertEquals("4 Oct - 11 Oct", label(TrendsRange.Week, offset = 1))
        assertEquals("18 Sep - now", label(TrendsRange.Month))
        assertEquals("Yesterday 20:32 - 22:32", label(TrendsRange.Hour2, offset = 8))
    }

    @Test
    fun axisTicksSitOnRoundTimes() {
        fun axis(range: TrendsRange, offset: Int = 0) =
            TrendsWindows.axisTicks(TrendsWindows.resolve(range, offset, null, now), zone)
        fun ticksAt(range: TrendsRange, atMs: Long) = TrendsWindows.axisTicks(TrendsWindows.resolve(range, 0, null, atMs), zone)
        // Same count for 5 m and 15 m whatever the seconds of now.
        listOf(0L, 7_000L, 31_000L, 59_000L).forEach { s ->
            assertEquals(5, ticksAt(TrendsRange.Min5, now + s).size)
            assertEquals(5, ticksAt(TrendsRange.Min15, now + s).size)
        }
        fun ticks(range: TrendsRange, offset: Int = 0) = axis(range, offset).map { it.label }
        assertEquals(listOf("14:28", "14:29", "14:30", "14:31", "now"), ticks(TrendsRange.Min5))
        assertEquals(listOf("14:03", "14:06", "14:09", "14:12", "14:15"), ticks(TrendsRange.Min15, offset = 1))
        assertEquals(listOf("13:45", "14:00", "14:15", "now"), ticks(TrendsRange.Hour1))
        assertEquals(listOf("18:00", "00:00", "06:00", "now"), ticks(TrendsRange.Day))
        assertEquals(listOf("Sat", "12:00", "Sun", "now"), ticks(TrendsRange.Day2))
        assertEquals(listOf("Mon", "Wed", "Fri", "now"), ticks(TrendsRange.Week))
        assertEquals(listOf("21 Sep", "28 Sep", "5 Oct", "12 Oct", "now"), ticks(TrendsRange.Month))
        assertEquals("13:30", ticks(TrendsRange.Hour1, offset = 1).last())
        // Positions are real: 14:30 is 58 of the 60 minutes into the 1 h window ending 14:32.
        assertEquals(43f / 60f, axis(TrendsRange.Hour1)[2].fraction, 1e-4f)
        assertEquals(58f / 60f, axis(TrendsRange.Hour1, offset = 1).last().fraction, 1e-4f)
        assertTrue(axis(TrendsRange.Hour1).last().isEnd)
        assertTrue(axis(TrendsRange.Hour1, offset = 1).none { it.isEnd })
        TrendsRange.entries.filter { it != TrendsRange.Custom }.forEach { range ->
            val labels = ticks(range)
            assertTrue("${range.label} $labels", labels.size in 3..5 && labels.none { it.count { c -> c == ':' } > 1 })
        }
    }

    @Test
    fun customValidation() {
        assertNull(TrendsCustomRanges.validate(now - 1_000L, now, now))
        assertEquals(TrendsCustomError.FromNotBeforeTo, TrendsCustomRanges.validate(now, now, now))
        assertEquals(TrendsCustomError.FromNotBeforeTo, TrendsCustomRanges.validate(now, now - 1, now))
        assertEquals(TrendsCustomError.ToInFuture, TrendsCustomRanges.validate(now - 1_000L, now + 1, now))
    }

    @Test
    fun customFormattingAndHint() {
        val from = Instant.parse("2026-10-12T06:00:00Z").toEpochMilli()
        assertEquals("12 Oct 08:00", TrendsCustomRanges.format(from, zone))
        assertEquals("2 d", TrendsCustomRanges.spanText(2 * 86_400_000L))
        assertEquals("5 h 30 min", TrendsCustomRanges.spanText(330 * 60_000L))
        assertEquals("1 d 2 h", TrendsCustomRanges.spanText(26 * 3_600_000L))
        assertEquals("45 min", TrendsCustomRanges.spanText(45 * 60_000L))
        assertEquals(
            "History from 3 Oct - 2 d selected",
            TrendsCustomRanges.hint(Instant.parse("2026-10-03T10:00:00Z").toEpochMilli(), now - 2 * 86_400_000L, now, zone),
        )
        assertEquals("History is empty - 1 h selected", TrendsCustomRanges.hint(null, now - 3_600_000L, now, zone))
    }

    @Test
    fun pickerDateAndTimeCombineInLocalZone() {
        val pickerUtcMidnight = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-10-12T06:30:00Z").toEpochMilli(),
            TrendsCustomRanges.combine(pickerUtcMidnight, 8, 30, zone),
        )
    }

    @Test
    fun energyLayoutPerRange() {
        fun layout(range: TrendsRange, offset: Int = 0) =
            TrendsEnergySlots.layout(TrendsWindows.resolve(range, offset, null, now), zone)
        assertEquals(12, layout(TrendsRange.Hour1).starts.size)
        assertEquals(5 * 60_000L, layout(TrendsRange.Hour1).fineMs)
        assertEquals(12, layout(TrendsRange.Hour2).starts.size)
        assertEquals(5 * 60_000L, layout(TrendsRange.Hour2).fineMs)
        assertEquals(12, layout(TrendsRange.Hour6).starts.size)
        assertEquals(12, layout(TrendsRange.Hour12).starts.size)
        assertEquals(24, layout(TrendsRange.Day).starts.size)
        assertEquals(12, layout(TrendsRange.Day2).starts.size)
        assertEquals(7, layout(TrendsRange.Week).starts.size)
        assertEquals(30, layout(TrendsRange.Month).starts.size)
        assertEquals(900_000L, layout(TrendsRange.Month).fineMs)
        val hour1 = layout(TrendsRange.Hour1)
        // 14:32 local: last 5 min slot starts 14:30 local = 12:30Z.
        assertEquals(Instant.parse("2026-10-18T12:30:00Z").toEpochMilli(), hour1.starts.last())
        val day2 = layout(TrendsRange.Day2)
        assertEquals(Instant.parse("2026-10-18T10:00:00Z").toEpochMilli(), day2.starts.last()) // 12:00 local
        assertEquals(4 * 3_600_000L, day2.starts[1] - day2.starts[0])
    }

    @Test
    fun customEnergyLayoutBoundsBarCount() {
        val short = TrendsWindows.resolve(TrendsRange.Custom, 0, TrendsCustomRange(now - 3 * 3_600_000L, now), now)
        assertEquals(12, TrendsEnergySlots.layout(short, zone).starts.size)
        val long = TrendsWindows.resolve(TrendsRange.Custom, 0, TrendsCustomRange(now - 400L * 86_400_000L, now), now)
        val layout = TrendsEnergySlots.layout(long, zone)
        assertEquals(7 * 86_400_000L, layout.slotMs)
        assertTrue(layout.starts.size <= 120)
        assertEquals("15 min", TrendsEnergySlots.slotLabel(15 * 60_000L))
        assertEquals("hour", TrendsEnergySlots.slotLabel(3_600_000L))
        assertEquals("4 h", TrendsEnergySlots.slotLabel(4 * 3_600_000L))
        assertEquals("day", TrendsEnergySlots.slotLabel(86_400_000L))
    }
}
