package com.masselis.tpmsadvanced.feature.unlocated.interfaces.ui

/**
 * Parses the 3-byte id of a V-SAFE BT1 sensor into the sensor id used to bind it manually.
 *
 * The sensor advertises itself as `TPMS_C35A6E`, where `C35A6E` is both the tail of its MAC
 * address and, read as a big-endian 24 bit number, the very id
 * [com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.RawVSafe.id] builds out of the
 * advertisement payload. So `"C35A6E"` is what the user is expected to type.
 *
 * A full 6-byte MAC address is accepted too and only its last 3 bytes are read, since the OUI (the
 * manufacturer prefix) never reaches the advertisement payload and therefore can't take part in
 * the id. Colons and dashes are optional, case doesn't matter.
 *
 * Returns `null` unless [this] is either 3 or 6 bytes of hexadecimal.
 */
internal fun String.toSensorIdOrNull(): Int? {
    val hex = replace(":", "").replace("-", "")
    if (hex.length != SENSOR_ID_HEX_LENGTH && hex.length != MAC_ADDRESS_HEX_LENGTH) return null
    val bytes = hex
        .chunked(2)
        .map { it.toIntOrNull(radix = 16) ?: return null }
    val (b0, b1, b2) = bytes.takeLast(3)
    return (b0 shl (2 * BYTE_BITS)) or (b1 shl BYTE_BITS) or b2
}

private const val SENSOR_ID_HEX_LENGTH = 6
private const val MAC_ADDRESS_HEX_LENGTH = 12
private const val BYTE_BITS = 8
