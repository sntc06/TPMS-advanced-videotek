package com.masselis.tpmsadvanced.data.vehicle.interfaces

import com.masselis.tpmsadvanced.data.vehicle.Database
import com.masselis.tpmsadvanced.data.vehicle.model.Pressure
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

public class TyreLogDatabase internal constructor(
    database: Database
) {
    private val queries = database.tyreLogQueries

    public suspend fun insert(
        timestamp: Double,
        sensorId: Int,
        vehicleName: String,
        pressure: Pressure,
        temperature: Temperature,
        battery: UShort,
    ): Unit = withContext(IO) {
        queries.insert(
            timestamp,
            sensorId.toLong(),
            vehicleName,
            pressure.kpa.toDouble(),
            temperature.celsius.toDouble(),
            battery.toLong(),
        )
    }

    public data class Entry(
        val timestamp: Double,
        val sensorId: Int,
        val vehicleName: String,
        val pressure: Pressure,
        val temperature: Temperature,
        val battery: UShort,
    )

    public suspend fun selectAll(): List<Entry> = withContext(IO) {
        queries
            .selectAll()
            .executeAsList()
            .map { row ->
                Entry(
                    row.timestamp,
                    row.sensorId.toInt(),
                    row.vehicleName,
                    Pressure(row.pressure.toFloat()),
                    Temperature(row.temperature.toFloat()),
                    row.battery.toUInt().toUShort(),
                )
            }
    }

    public suspend fun deleteAll(): Unit = withContext(IO) {
        queries.deleteAll()
    }

    public suspend fun count(): Long = withContext(IO) {
        queries.count().executeAsOne()
    }
}
