package com.masselis.tpmsadvanced.core.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.masselis.tpmsadvanced.core.common.now
import kotlinx.coroutines.delay

private const val REFRESH_PERIOD_MILLIS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Formats [epochSeconds] as a localized, human-readable relative time span (e.g. "5 minutes
 * ago"), refreshing itself every 30 seconds so the displayed text doesn't go stale while the
 * screen stays open.
 */
@Composable
public fun relativeTimeSpanString(epochSeconds: Double): String {
    val nowMillis by produceState(initialValue = now().times(MILLIS_PER_SECOND).toLong()) {
        while (true) {
            value = now().times(MILLIS_PER_SECOND).toLong()
            delay(REFRESH_PERIOD_MILLIS)
        }
    }
    return DateUtils.getRelativeTimeSpanString(
        epochSeconds.times(MILLIS_PER_SECOND).toLong(),
        nowMillis,
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
