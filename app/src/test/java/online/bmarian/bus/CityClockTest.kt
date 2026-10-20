package online.bmarian.bus

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import online.bmarian.bus.data.Api
import online.bmarian.bus.data.NetworkBundle
import online.bmarian.bus.data.NetworkIndex
import online.bmarian.bus.data.clujClock
import online.bmarian.bus.logic.CLUJ_ZONE
import online.bmarian.bus.logic.ScheduleCalendar
import online.bmarian.bus.logic.formatMinutesFromNow
import online.bmarian.bus.logic.scheduleDayAt
import online.bmarian.bus.logic.stopDepartures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone

class CityClockTest {
    private val fixture = Api.json.parseToJsonElement(
        javaClass.classLoader!!.getResourceAsStream("network/network_fixture.json").bufferedReader().readText(),
    ).jsonObject
    private val index = NetworkIndex(Api.json.decodeFromJsonElement<NetworkBundle>(fixture["bundle"]!!))
    private val stopId = fixture["stop_id"]!!.jsonPrimitive.int

    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    private fun <T> inEveryZone(read: () -> T): T {
        val device = TimeZone.getDefault()
        try {
            val answers = listOf("Europe/Bucharest", "America/New_York", "Asia/Tokyo").map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                zone to read()
            }
            for ((zone, answer) in answers) assertEquals(zone, answers.first().second, answer)
            return answers.first().second
        } finally {
            TimeZone.setDefault(device)
        }
    }

    @Test
    fun aStopListsTheSameDeparturesWhateverTheDeviceZone() {
        // Sunday 05:00 in Cluj is Saturday 22:00 in New York; then the night the clocks go back.
        for (at in listOf("2026-10-18T02:00:00Z", "2026-10-24T20:30:00Z", "2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z", "2026-10-25T05:00:00Z").map(::utc)) {
            val departures = inEveryZone {
                val info = index.stopInfo(stopId, at)!!
                stopDepartures(info, null, LocalDateTime.ofInstant(Instant.ofEpochMilli(at), CLUJ_ZONE), at)
            }
            assertTrue(departures.isNotEmpty())
        }
    }

    @Test
    fun theTimetableDayIsTheCitysDate() {
        assertEquals("sunday" to 5, inEveryZone { clujClock(utc("2026-10-18T02:00:00Z"), ScheduleCalendar()) })
        val calendar = ScheduleCalendar(mapOf("2026-10-25" to "weekday"))
        // Already the 25th in Tokyo, still the 24th in Cluj.
        assertEquals("saturday", inEveryZone { scheduleDayAt(utc("2026-10-24T20:30:00Z"), calendar) })
        // Already the 25th in Cluj, still the 24th in New York.
        assertEquals("weekday" to 1, inEveryZone { clujClock(utc("2026-10-24T22:30:00Z"), calendar) })
        assertEquals("weekday" to 3, inEveryZone { clujClock(utc("2026-10-25T01:30:00Z"), calendar) })
    }

    @Test
    fun clockTimesCountRealMinutes() {
        // 06:30 in Cluj, Saturday 23:30 in New York.
        val morning = LocalDateTime.ofInstant(Instant.ofEpochMilli(utc("2026-10-18T03:30:00Z")), CLUJ_ZONE)
        assertEquals("08:00", inEveryZone { formatMinutesFromNow(90, morning, "now") })
        // 90 minutes after 02:30 summer time is 03:00 winter time.
        assertEquals("03:00", inEveryZone { formatMinutesFromNow(90, LocalDateTime.parse("2026-10-25T02:30"), "now") })
    }
}
