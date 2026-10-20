package online.bmarian.bus.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Port of frontend/src/utils/cityClock.ts.

val CLUJ_ZONE: ZoneId = ZoneId.of("Europe/Bucharest")

fun clujDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(CLUJ_ZONE).toLocalDate()
