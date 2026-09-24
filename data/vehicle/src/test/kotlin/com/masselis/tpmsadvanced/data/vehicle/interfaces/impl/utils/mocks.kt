package com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import io.mockk.every
import io.mockk.mockk

internal fun mockBluetoothDevice(
    mockAddress: String = "00:00:00:00:00"
): BluetoothDevice = mockk {
    every { address } returns mockAddress
}

internal fun mockScanRecord(
    mockDeviceName: String = "MOCK",
    containsServiceUuids: Boolean = true,
    mockAdvertiseFlags: Int = 0x06,
    mockManufacturerData: ByteArray = byteArrayOf(),
    mockManufacturerDataKey: Int = 0,
    mockBytes: ByteArray = byteArrayOf(),
): ScanRecord = mockk {
    every { deviceName } returns mockDeviceName
    every { serviceUuids } returns mockk {
        every { contains(any()) } returns containsServiceUuids
    }
    every { advertiseFlags } returns mockAdvertiseFlags
    // Mocked company-id-agnostic, matching ScanRecord.manufacturerSpecificData: real sensors can
    // advertise the same payload under different company IDs, so decoders should read
    // valueAt(0) rather than requiring a specific key. mockManufacturerDataKey lets a decoder
    // that does check the company id (e.g. RawVSafe) be tested against a specific key too.
    every { manufacturerSpecificData } returns mockk {
        every { size() } returns 1
        every { keyAt(0) } returns mockManufacturerDataKey
        every { valueAt(0) } returns mockManufacturerData
    }
    every { bytes } returns mockBytes
}

internal fun mockScanResult(
    mockScanRecord: ScanRecord = mockScanRecord(),
    mockRssi: Int = -60,
): ScanResult = mockk {
    every { rssi } returns mockRssi
    every { scanRecord } returns mockScanRecord
    every { device } returns mockBluetoothDevice()
}
