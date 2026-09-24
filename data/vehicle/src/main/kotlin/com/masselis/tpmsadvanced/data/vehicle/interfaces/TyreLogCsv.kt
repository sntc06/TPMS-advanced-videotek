package com.masselis.tpmsadvanced.data.vehicle.interfaces

private const val CSV_HEADER = "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt"

/**
 * Renders these log entries as CSV text, one row per entry, oldest first (matching
 * [TyreLogDatabase.selectAll] order). [TyreLogDatabase.Entry.vehicleName] is quoted and any
 * embedded double quote is escaped, since it's free-form user text and may contain a comma.
 */
public fun List<TyreLogDatabase.Entry>.toCsv(): String = buildString {
    append(CSV_HEADER)
    append('\n')
    this@toCsv.forEach { entry ->
        append(entry.timestamp)
        append(',')
        append(entry.sensorId)
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
