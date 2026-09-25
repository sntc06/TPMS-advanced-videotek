package com.masselis.tpmsadvanced.feature.unlocated.interfaces.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class SensorIdTest {

    /**
     * The whole point of typing an id by hand is to bind a sensor that a scan will later report
     * with that very same id, so this has to agree with
     * [com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.RawVSafe.id]. `RawVSafeTest` asserts
     * a real advertisement from the sensor named `TPMS_C35A6E` parses to `0xC35A6E`.
     */
    @Test
    fun sensorIdMatchesTheOneRawVSafeBuildsFromAnAdvertisement() {
        assertEquals(0xC35A6E, "C35A6E".toSensorIdOrNull())
        assertEquals(0xC35B26, "C35B26".toSensorIdOrNull())
    }

    @Test
    fun fullMacAddressKeepsOnlyItsLastThreeBytes() {
        assertEquals(0xC35A6E, "AA:BB:CC:C3:5A:6E".toSensorIdOrNull())
        // The OUI never reaches the advertisement payload, so it can't change the id
        assertEquals(0xC35A6E, "00:00:00:C3:5A:6E".toSensorIdOrNull())
    }

    @Test
    fun separatorsAndCaseAreIgnored() {
        assertEquals(0xC35A6E, "c3:5a:6e".toSensorIdOrNull())
        assertEquals(0xC35A6E, "c3-5a-6e".toSensorIdOrNull())
        assertEquals(0xC35A6E, "C3-5A:6e".toSensorIdOrNull())
    }

    @Test
    fun zeroBytesAreKept() {
        assertEquals(0x000000, "000000".toSensorIdOrNull())
        assertEquals(0x00FF00, "00FF00".toSensorIdOrNull())
    }

    @Test
    fun highestByteDoesntTurnTheIdNegative() {
        assertEquals(0xFFFFFF, "FFFFFF".toSensorIdOrNull())
    }

    @Test
    fun onlyThreeOrSixBytesAreAccepted() {
        assertNull("".toSensorIdOrNull())
        assertNull("C35A".toSensorIdOrNull())
        assertNull("C35A6".toSensorIdOrNull())
        assertNull("C35A6E1".toSensorIdOrNull())
        assertNull("AA:BB:CC:C3:5A".toSensorIdOrNull())
        assertNull("AA:BB:CC:DD:C3:5A:6E".toSensorIdOrNull())
    }

    @Test
    fun nonHexadecimalIsRejected() {
        assertNull("C35A6Z".toSensorIdOrNull())
        assertNull("ZZZZZZ".toSensorIdOrNull())
        assertNull("C3 5A 6E".toSensorIdOrNull())
    }
}
