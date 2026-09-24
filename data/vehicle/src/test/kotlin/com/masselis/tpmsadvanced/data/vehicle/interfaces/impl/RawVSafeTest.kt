package com.masselis.tpmsadvanced.data.vehicle.interfaces.impl

import com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.utils.mockScanRecord
import com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.utils.mockScanResult
import com.masselis.tpmsadvanced.data.vehicle.model.Tyre
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalStdlibApi::class)
internal class RawVSafeTest {

    // Full raw advertisement captured live with nRF Connect from a physical V-SAFE BT1 sensor
    // (front wheel, MAC tail C3:5A:6E):
    // 02 01 06 03 03 B0FB 12 FF AC00A24655031252471F0A6E5AC3ECB303 0C 09 54504D535F433335413645
    // (Flags + 16-bit Service UUID 0xFBB0 + Manufacturer Specific Data + device name "TPMS_C35A6E")
    //
    // ScanRecord.manufacturerSpecificData.valueAt(0) strips the 2-byte company ID (AC 00 -> key
    // 0x00AC), so the samples below are the 15 bytes that remain.
    private val frontWheelSample = "A24655031252471F0A6E5AC3ECB303"
    private val rearWheelSample = "A5515600125E471F0A265BC3ECB303"

    @Test
    fun `front wheel real sample is parsed with the values measured on the physical sensor`() {
        val raw = RawVSafe(
            mockScanResult(
                mockScanRecord = mockScanRecord(
                    containsServiceUuids = true,
                    mockManufacturerData = frontWheelSample.hexToByteArray(),
                    mockManufacturerDataKey = EXPECTED_COMPANY_ID,
                )
            )
        )

        assertNotNull(raw)
        assertEquals(0xC35A6E, raw.id())
        assertEquals(31.92f, raw.pressure().asPsi(), PSI_TOLERANCE)
        assertEquals(30f, raw.temperature().celsius)
        assertEquals(2.84f, raw.voltage(), VOLTAGE_TOLERANCE)

        val tyre = raw.asTyre()
        assertIs<Tyre.Unlocated>(tyre)
        assertFalse(tyre.isAlarm)
    }

    @Test
    fun `rear wheel real sample is parsed with the values measured on the physical sensor`() {
        val raw = RawVSafe(
            mockScanResult(
                mockScanRecord = mockScanRecord(
                    containsServiceUuids = true,
                    mockManufacturerData = rearWheelSample.hexToByteArray(),
                    mockManufacturerDataKey = EXPECTED_COMPANY_ID,
                )
            )
        )

        assertNotNull(raw)
        assertEquals(0xC35B26, raw.id())
        assertEquals(36.94f, raw.pressure().asPsi(), PSI_TOLERANCE)
        assertEquals(31f, raw.temperature().celsius)
        assertEquals(2.87f, raw.voltage(), VOLTAGE_TOLERANCE)

        val tyre = raw.asTyre()
        assertIs<Tyre.Unlocated>(tyre)
        assertFalse(tyre.isAlarm)
    }

    @Test
    fun `returns null when the service uuid is missing`() {
        val raw = RawVSafe(
            mockScanResult(
                mockScanRecord = mockScanRecord(
                    containsServiceUuids = false,
                    mockManufacturerData = frontWheelSample.hexToByteArray(),
                    mockManufacturerDataKey = EXPECTED_COMPANY_ID,
                )
            )
        )

        assertNull(raw)
    }

    @Test
    fun `returns null when the company id doesn't match the unencrypted sub-type`() {
        val raw = RawVSafe(
            mockScanResult(
                mockScanRecord = mockScanRecord(
                    containsServiceUuids = true,
                    mockManufacturerData = frontWheelSample.hexToByteArray(),
                    // byte[1] != 0x00 over the air, i.e. any company id other than 0x00AC
                    mockManufacturerDataKey = 0x15AC,
                )
            )
        )

        assertNull(raw)
    }

    @Test
    fun `returns null when the checksum doesn't match`() {
        val corrupted = frontWheelSample.hexToByteArray().also { it[1] = it[1].inc() }
        val raw = RawVSafe(
            mockScanResult(
                mockScanRecord = mockScanRecord(
                    containsServiceUuids = true,
                    mockManufacturerData = corrupted,
                    mockManufacturerDataKey = EXPECTED_COMPANY_ID,
                )
            )
        )

        assertNull(raw)
    }

    private companion object {
        const val EXPECTED_COMPANY_ID = 0x00AC
        const val PSI_TOLERANCE = 0.1f
        const val VOLTAGE_TOLERANCE = 0.01f
    }
}
