package com.masselis.tpmsadvanced.data.vehicle.interfaces.impl

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import androidx.core.util.size
import com.masselis.tpmsadvanced.core.common.now
import com.masselis.tpmsadvanced.data.vehicle.model.Pressure.CREATOR.kpa
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature.CREATOR.celsius
import com.masselis.tpmsadvanced.data.vehicle.model.Tyre
import java.util.UUID.fromString
import kotlin.math.roundToInt

/**
 * Sensor from the "V-SAFE BT1" TPMS app (com.videotek.vsafe), reverse engineered from the app's
 * bundled `libtpms-lib.so` (package `com.pingwang.tpmslibrary`).
 *
 * The manufacturer specific data as returned by [android.bluetooth.le.ScanRecord.getManufacturerSpecificData]
 * (i.e. `valueAt(0)`) has already had its 2-byte "company ID" stripped by the platform (it's
 * exposed separately as the SparseArray key, `keyAt(0)`). For this sensor those 2 bytes
 * (`0xAC 0x00` over the air, i.e. key `0x00AC` once decoded little-endian by the platform) aren't
 * a real BLE SIG company identifier: they're this protocol's own packet-type + sub-type header.
 * Every offset below is shifted back by 2 compared to the raw over-the-air payload:
 *
 * ```
 * manufacturerData[0]     = battery raw value, voltage = value * 0.01 + 1.22
 * manufacturerData[1]     = pressure raw value, kpa = value * 3.144
 * manufacturerData[2]     = temperature raw value, celsius = value - 55
 * manufacturerData[3..4]  = used in the checksum, meaning unconfirmed
 * manufacturerData[5]     = checksum = sum(manufacturerData[0..4]) & 0xFF
 * manufacturerData[9..11] = MAC address' last 3 bytes, reversed (little-endian), used as sensorId
 * ```
 *
 * The sensor doesn't embed its own front/rear/left/right location (unlike [RawSysgration]), so it
 * surfaces as [Tyre.Unlocated] and the user must bind it to a wheel, same as [RawPecham] and
 * [RawBekubeeTpms].
 */
@ConsistentCopyVisibility
@Suppress("MagicNumber")
internal data class RawVSafe private constructor(
    private val rssi: Int,
    private val manufacturerData: ByteArray,
) : Raw {

    fun id() = (manufacturerData[9].toInt() and 0xFF) or
            ((manufacturerData[10].toInt() and 0xFF) shl 8) or
            ((manufacturerData[11].toInt() and 0xFF) shl 16)

    fun pressure() = (manufacturerData[1].toInt() and 0xFF)
        .times(PRESSURE_KPA_PER_UNIT)
        .kpa

    fun temperature() = ((manufacturerData[2].toInt() and 0xFF) - TEMPERATURE_OFFSET)
        .toFloat()
        .celsius

    // Returns 2.84 for 2.84 volts
    fun voltage() = (manufacturerData[0].toInt() and 0xFF) * 0.01f + 1.22f

    private fun isChecksumValid() = manufacturerData
        .copyOfRange(0, 5)
        .sumOf { it.toInt() and 0xFF }
        .and(0xFF) == (manufacturerData[5].toInt() and 0xFF)

    override fun asTyre(): Tyre.SensorInput = Tyre.Unlocated(
        now(),
        rssi,
        id(),
        pressure(),
        temperature(),
        voltage().times(10f).roundToInt().toUShort(),
        voltage() <= LOW_BATTERY_VOLTAGE,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawVSafe

        if (rssi != other.rssi) return false
        return manufacturerData.contentEquals(other.manufacturerData)
    }

    override fun hashCode(): Int {
        var result = rssi
        result = 31 * result + manufacturerData.contentHashCode()
        return result
    }

    companion object {
        internal val SERVICE_UUID = ParcelUuid(fromString("0000fbb0-0000-1000-8000-00805f9b34fb"))

        // Over the air: byte[0]=0xAC (packet type), byte[1]=0x00 (unencrypted sub-type). The
        // platform reads these 2 bytes as a little-endian "company ID" key, so it comes back as
        // 0x00AC. Other sub-types (byte[1] != 0x00) use a different, unconfirmed pressure formula
        // and aren't supported.
        private const val EXPECTED_COMPANY_ID = 0x00AC
        private const val PRESSURE_KPA_PER_UNIT = 3.144f
        private const val TEMPERATURE_OFFSET = 55
        // Mirrors the app's UI threshold in TyreStatusView.setBatteryVoltage()
        private const val LOW_BATTERY_VOLTAGE = 2.3f
        private const val MIN_MANUFACTURER_DATA_LENGTH = 12

        @Suppress("ReturnCount")
        operator fun invoke(scanResult: ScanResult): RawVSafe? {
            val scanRecord = scanResult.scanRecord ?: return null
            if (scanRecord.serviceUuids?.contains(SERVICE_UUID)?.not() ?: true) return null
            val manufacturerSpecificData = scanRecord.manufacturerSpecificData
                ?.takeIf { it.size > 0 }
                ?: return null
            if (manufacturerSpecificData.keyAt(0) != EXPECTED_COMPANY_ID) return null
            val manufacturerData = manufacturerSpecificData
                .valueAt(0)
                ?.takeIf { it.size >= MIN_MANUFACTURER_DATA_LENGTH }
                ?: return null
            return RawVSafe(scanResult.rssi, manufacturerData)
                .takeIf { it.isChecksumValid() }
        }
    }
}
