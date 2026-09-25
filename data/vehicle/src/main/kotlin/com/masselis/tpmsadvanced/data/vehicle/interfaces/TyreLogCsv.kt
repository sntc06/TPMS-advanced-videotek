package com.masselis.tpmsadvanced.data.vehicle.interfaces

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

private const val CSV_HEADER = "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt"

// ISO 8601 with a fixed width, unlike DateTimeFormatter.ISO_OFFSET_DATE_TIME which drops the
// seconds when they're zero
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

private const val MILLIS_PER_SECOND = 1_000

/**
 * Renders these log entries as CSV text, one row per entry, oldest first (matching
 * [TyreLogDatabase.selectAll] order).
 *
 * Timestamps are ISO 8601 in [zone] so a spreadsheet can read them and the offset says which
 * wall clock they belong to. Sensor ids are hexadecimal, the way a sensor advertises itself
 * (`TPMS_C35A6E`) and the way one is typed in to be bound by hand.
 *
 * [TyreLogDatabase.Entry.vehicleName] is quoted and any embedded double quote is escaped, since
 * it's free-form user text and may contain a comma.
 */
public fun List<TyreLogDatabase.Entry>.toCsv(
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    append(CSV_HEADER)
    append('\n')
    this@toCsv.forEach { entry ->
        append(
            Instant
                .ofEpochMilli((entry.timestamp * MILLIS_PER_SECOND).roundToLong())
                .atZone(zone)
                .format(TIMESTAMP_FORMAT)
        )
        append(',')
        append("%06X".format(entry.sensorId))
        append(',')
        append('"')
        append(entry.vehicleName.replace("\"", "\"\""))
        append('"')
        append(',')
        append(entry.pressure.kpa)
        append(',')
        append(entry.temperature.celsius)
        append(',')
        append(entry.battery.toInt() / 10f)
        append('\n')
    }
}
