package com.masselis.tpmsadvanced.data.vehicle.interfaces

import com.masselis.tpmsadvanced.data.vehicle.model.Pressure.CREATOR.bar
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature.CREATOR.celsius
import org.junit.Test
import kotlin.test.assertEquals

internal class TyreLogCsvTest {

    @Test
    fun `empty list only has the header`() {
        assertEquals(
            "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt\n",
            emptyList<TyreLogDatabase.Entry>().toCsv()
        )
    }

    @Test
    fun `single entry is rendered as one row`() {
        val entry = TyreLogDatabase.Entry(
            timestamp = 100.0,
            sensorId = 12_814_446,
            vehicleName = "My car",
            pressure = 2f.bar,
            temperature = 30f.celsius,
            battery = 28u,
        )
        assertEquals(
            "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt\n" +
                "100.0,12814446,\"My car\",200.0,30.0,2.8\n",
            listOf(entry).toCsv()
        )
    }

    @Test
    fun `vehicle name with a comma and a quote is escaped`() {
        val entry = TyreLogDatabase.Entry(
            timestamp = 0.0,
            sensorId = 1,
            vehicleName = "Bob's \"fast\", red car",
            pressure = 0f.bar,
            temperature = 0f.celsius,
            battery = 0u,
        )
        val csv = listOf(entry).toCsv()
        assertEquals(
            "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt\n" +
                "0.0,1,\"Bob's \"\"fast\"\", red car\",0.0,0.0,0.0\n",
            csv
        )
    }

    @Test
    fun `multiple entries are rendered in order, one per row`() {
        val entries = listOf(
            TyreLogDatabase.Entry(1.0, 1, "Car A", 1f.bar, 1f.celsius, 10u),
            TyreLogDatabase.Entry(2.0, 2, "Car B", 2f.bar, 2f.celsius, 20u),
        )
        assertEquals(3, entries.toCsv().lines().count { it.isNotEmpty() })
    }
}
