package com.masselis.tpmsadvanced.data.vehicle.interfaces

import com.masselis.tpmsadvanced.data.vehicle.model.Pressure.CREATOR.bar
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature.CREATOR.celsius
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals

internal class TyreLogCsvTest {

    // Pinned so the rendered timestamps don't depend on where the tests run
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test
    fun `empty list only has the header`() {
        assertEquals(
            "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt\n",
            emptyList<TyreLogDatabase.Entry>().toCsv(taipei)
        )
    }

    @Test
    fun `single entry is rendered as one row`() {
        val entry = TyreLogDatabase.Entry(
            // 2026-09-24T08:00:00+08:00
            timestamp = 1_790_208_000.0,
            sensorId = 0xC35A6E,
            vehicleName = "My car",
            pressure = 2f.bar,
            temperature = 30f.celsius,
            battery = 28u,
        )
        assertEquals(
            "timestamp,sensorId,vehicleName,pressureKpa,temperatureCelsius,batteryVolt\n" +
                "2026-09-24T08:00:00+08:00,C35A6E,\"My car\",200.0,30.0,2.8\n",
            listOf(entry).toCsv(taipei)
        )
    }

    @Test
    fun `timestamp is rendered in the given zone`() {
        val entry = entry(timestamp = 1_790_208_000.0)
        assertEquals(
            "2026-09-24T00:00:00Z",
            entry.rowIn(ZoneId.of("UTC")).substringBefore(',')
        )
        assertEquals(
            "2026-09-24T02:00:00+02:00",
            entry.rowIn(ZoneId.of("Europe/Paris")).substringBefore(',')
        )
    }

    /**
     * A sensor is advertised and bound by hand as `C35A6E`, so that's how it has to show up here,
     * rather than as the 12802670 the id happens to be as a number.
     */
    @Test
    fun `sensor id is hexadecimal and padded to six digits`() {
        assertEquals("C35A6E", entry(sensorId = 0xC35A6E).rowIn(taipei).split(',')[1])
        assertEquals("000001", entry(sensorId = 1).rowIn(taipei).split(',')[1])
    }

    /**
     * Sensors like [com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.RawPecham] build their
     * id out of a hash code, which can be negative. It has to survive as eight digits rather than
     * being truncated to six.
     */
    @Test
    fun `negative sensor id keeps all of its digits`() {
        assertEquals("EBA61180", entry(sensorId = -341_438_080).rowIn(taipei).split(',')[1])
    }

    @Test
    fun `vehicle name with a comma and a quote is escaped`() {
        assertEquals(
            "2026-09-24T08:00:00+08:00,C35A6E,\"Bob's \"\"fast\"\", red car\",0.0,0.0,0.0",
            entry(vehicleName = "Bob's \"fast\", red car").rowIn(taipei)
        )
    }

    @Test
    fun `multiple entries are rendered in order, one per row`() {
        val entries = listOf(
            TyreLogDatabase.Entry(1.0, 1, "Car A", 1f.bar, 1f.celsius, 10u),
            TyreLogDatabase.Entry(2.0, 2, "Car B", 2f.bar, 2f.celsius, 20u),
        )
        assertEquals(3, entries.toCsv(taipei).lines().count { it.isNotEmpty() })
    }

    private fun entry(
        timestamp: Double = 1_790_208_000.0,
        sensorId: Int = 0xC35A6E,
        vehicleName: String = "My car",
    ) = TyreLogDatabase.Entry(
        timestamp = timestamp,
        sensorId = sensorId,
        vehicleName = vehicleName,
        pressure = 0f.bar,
        temperature = 0f.celsius,
        battery = 0u,
    )

    private fun TyreLogDatabase.Entry.rowIn(zone: ZoneId) =
        listOf(this).toCsv(zone).lines()[1]
}
