package online.bmarian.bus.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

// Port of the special days in frontend/src/utils/time.ts.

private val SCHEDULE_DAYS = setOf("weekday", "saturday", "sunday")

@Serializable
data class DayNote(val reason: String = "", val holiday: String? = null)

data class SpecialDay(val schedule: String, val note: DayNote?)

/** The dates whose timetable differs from the weekday rule, keyed by Cluj date. */
class ScheduleCalendar(days: Map<String, String> = emptyMap(), private val notes: Map<String, DayNote> = emptyMap()) {
    private val days = days.filterValues { it in SCHEDULE_DAYS }

    fun scheduleDay(date: LocalDate): String = days[date.toString()] ?: plainScheduleDay(date)

    fun specialDay(date: LocalDate): SpecialDay? = days[date.toString()]?.let { SpecialDay(it, notes[date.toString()]) }
}

object ScheduleDays {
    private val mutable = MutableStateFlow(ScheduleCalendar())
    val state: StateFlow<ScheduleCalendar> = mutable
    val current: ScheduleCalendar get() = mutable.value

    fun use(calendar: ScheduleCalendar) {
        mutable.value = calendar
    }
}

fun plainScheduleDay(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.SATURDAY -> "saturday"
    DayOfWeek.SUNDAY -> "sunday"
    else -> "weekday"
}

/** The timetable that runs on a Cluj date. */
fun scheduleDayOn(date: LocalDate, calendar: ScheduleCalendar = ScheduleDays.current): String = calendar.scheduleDay(date)

/** The timetable that runs at an instant, on its Cluj date whatever the device's time zone. */
fun scheduleDayAt(ms: Long, calendar: ScheduleCalendar = ScheduleDays.current): String = scheduleDayOn(clujDate(ms), calendar)

/** The date's timetable and why, or null when the weekday rule holds. */
fun specialDayOn(date: LocalDate, calendar: ScheduleCalendar = ScheduleDays.current): SpecialDay? = calendar.specialDay(date)
