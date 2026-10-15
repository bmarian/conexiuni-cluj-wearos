package online.bmarian.bus

import online.bmarian.bus.data.Api
import online.bmarian.bus.data.DaySchedule
import online.bmarian.bus.data.NetworkBundle
import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.Timetable
import online.bmarian.bus.data.TimetableEntry
import online.bmarian.bus.data.clujClock
import online.bmarian.bus.logic.DayNote
import online.bmarian.bus.logic.ScheduleCalendar
import online.bmarian.bus.logic.ScheduleDays
import online.bmarian.bus.logic.SpecialDay
import online.bmarian.bus.logic.daySchedule
import online.bmarian.bus.logic.scheduleDayAt
import online.bmarian.bus.logic.specialDayOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TimeZone

class SpecialDaysTest {
    private val calendar = ScheduleCalendar(
        mapOf("2026-12-01" to "sunday"),
        mapOf("2026-12-01" to DayNote("holiday", "national_day")),
    )

    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    private val timetable = Timetable(
        weekdays = DaySchedule(entries = listOf("08:00").map { TimetableEntry(departureIn = it) }),
        sunday = DaySchedule(entries = listOf("10:00").map { TimetableEntry(departureIn = it) }),
    )

    @Test
    fun aHolidayRunsTheTimetableTheBundleNames() {
        assertEquals("sunday", scheduleDayAt(utc("2026-12-01T08:00:00Z"), calendar))
        assertEquals(SpecialDay("sunday", DayNote("holiday", "national_day")), specialDayOn(LocalDate.parse("2026-12-01"), calendar))
        ScheduleDays.use(calendar)
        try {
            assertEquals(timetable.sunday, daySchedule(timetable, LocalDateTime.parse("2026-12-01T10:00")))
            assertEquals("sunday" to 10, clujClock(utc("2026-12-01T08:00:00Z")))
        } finally {
            ScheduleDays.use(ScheduleCalendar())
        }
    }

    @Test
    fun anOrdinaryDayFollowsTheWeekday() {
        assertEquals("weekday", scheduleDayAt(utc("2026-12-02T08:00:00Z"), calendar))
        assertEquals("saturday", scheduleDayAt(utc("2026-12-05T08:00:00Z"), calendar))
        assertNull(specialDayOn(LocalDate.parse("2026-12-02"), calendar))
        assertEquals(timetable.weekdays, daySchedule(timetable, LocalDateTime.parse("2026-12-01T10:00")))
    }

    @Test
    fun aBundleWithoutTheFieldsKeepsTheWeekdayRule() {
        val old = NetworkIndex(Api.json.decodeFromString<NetworkBundle>("""{"version":"v1","routes":[],"stops":[]}"""))
        assertEquals("weekday", scheduleDayAt(utc("2026-12-01T08:00:00Z"), old.calendar))
        assertNull(specialDayOn(LocalDate.parse("2026-12-01"), old.calendar))

        val fresh = NetworkIndex(
            Api.json.decodeFromString<NetworkBundle>(
                """{"schedule_days":{"2026-12-01":"sunday"},"day_notes":{"2026-12-01":{"reason":"holiday","holiday":"national_day"}}}""",
            ),
        )
        assertEquals(SpecialDay("sunday", DayNote("holiday", "national_day")), specialDayOn(LocalDate.parse("2026-12-01"), fresh.calendar))
    }

    @Test
    fun justAfterMidnightCountsTheClujDateWhateverTheDeviceZone() {
        val device = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            // 00:30 on 1 Dec in Cluj, still 30 Nov in New York.
            assertEquals("sunday" to 0, clujClock(utc("2026-11-30T22:30:00Z"), calendar))
            // 00:30 on 2 Dec in Cluj, still the 1 Dec holiday in New York.
            assertEquals("weekday", scheduleDayAt(utc("2026-12-01T22:30:00Z"), calendar))
        } finally {
            TimeZone.setDefault(device)
        }
    }
}
