package com.masselis.tpmsadvanced.feature.unlocated.interfaces.ui

/**
 * Parses a full Bluetooth MAC address (e.g. "AA:BB:CC:C3:5A:6E", colons optional, case
 * insensitive) into the sensor id used to bind a sensor manually.
 *
 * Only the last 3 bytes are used, combined the same way as
 * [com.masselis.tpmsadvanced.data.vehicle.interfaces.impl.RawVSafe.id]: the first 3 bytes (the
 * OUI, i.e. the manufacturer prefix) are intentionally ignored, so the user doesn't need to know
 * or type an accurate OUI for this to match a sensor actually seen over the air.
 *
 * Returns `null` if [this] isn't a valid 6-byte MAC address (wrong length or non-hex characters).
 */
internal fun String.toSensorIdOrNull(): Int? {
    val hex = replace(":", "").replace("-", "")
    if (hex.length != MAC_ADDRESS_HEX_LENGTH) return null
    val bytes = hex
        .chunked(2)
        .map { it.toIntOrNull(radix = 16) ?: return null }
    val (b0, b1, b2) = bytes.takeLast(3)
    return (b0 shl (2 * BYTE_BITS)) or (b1 shl BYTE_BITS) or b2
}

private const val MAC_ADDRESS_HEX_LENGTH = 12
private const val BYTE_BITS = 8
