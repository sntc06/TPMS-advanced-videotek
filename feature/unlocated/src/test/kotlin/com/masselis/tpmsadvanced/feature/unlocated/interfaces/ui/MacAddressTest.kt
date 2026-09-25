package com.masselis.tpmsadvanced.feature.unlocated.interfaces.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class MacAddressTest {

    /**
     * The whole point of typing a MAC address by hand is to bind a sensor that a scan will later
     * report with the very same id, so this has to agree with
     * [com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.RawVSafe.id]. `RawVSafeTest` asserts
     * a real advertisement from the sensor named `TPMS_C35A6E` parses to `0xC35A6E`.
     */
    @Test
    fun sensorIdMatchesTheOneRawVSafeBuildsFromAnAdvertisement() {
        assertEquals(0xC35A6E, "AA:BB:CC:C3:5A:6E".toSensorIdOrNull())
        assertEquals(0xC35B26, "AA:BB:CC:C3:5B:26".toSensorIdOrNull())
    }

    @Test
    fun ouiDoesntTakePartInTheId() {
        assertEquals(0xC35A6E, "00:00:00:C3:5A:6E".toSensorIdOrNull())
        assertEquals(0xC35A6E, "FF:FF:FF:C3:5A:6E".toSensorIdOrNull())
    }

    @Test
    fun separatorsAndCaseAreIgnored() {
        assertEquals(0xC35A6E, "aabbccc35a6e".toSensorIdOrNull())
        assertEquals(0xC35A6E, "AA-BB-CC-C3-5A-6E".toSensorIdOrNull())
    }

    @Test
    fun highestByteDoesntTurnTheIdNegative() {
        assertEquals(0xFFFFFF, "AA:BB:CC:FF:FF:FF".toSensorIdOrNull())
    }

    @Test
    fun onlySixBytesAreAccepted() {
        assertNull("".toSensorIdOrNull())
        assertNull("C35A6E".toSensorIdOrNull())
        assertNull("AA:BB:CC:C3:5A".toSensorIdOrNull())
        assertNull("AA:BB:CC:DD:C3:5A:6E".toSensorIdOrNull())
    }

    @Test
    fun nonHexadecimalIsRejected() {
        assertNull("AA:BB:CC:C3:5A:6Z".toSensorIdOrNull())
        assertNull("ZZ:ZZ:ZZ:ZZ:ZZ:ZZ".toSensorIdOrNull())
    }
}
