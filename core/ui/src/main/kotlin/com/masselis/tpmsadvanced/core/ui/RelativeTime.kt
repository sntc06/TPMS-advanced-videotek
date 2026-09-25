package com.masselis.tpmsadvanced.core.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.masselis.tpmsadvanced.core.common.now
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

private const val REFRESH_PERIOD_MILLIS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Formats [epochSeconds] as a localized, human-readable relative time span (e.g. "5 minutes
 * ago"), refreshing itself every 30 seconds so the displayed text doesn't go stale while the
 * screen stays open.
 *
 * Anything under a minute reads as "now". [DateUtils] would spell it out as "0 minutes ago", and
 * as "in 0 minutes" whenever [epochSeconds] happens to sit past the last refresh, which a sensor
 * reading that arrives between two refreshes routinely does. The two then alternate as readings
 * come in, and the exact number of seconds means nothing to the reader anyway.
 */
@Composable
public fun relativeTimeSpanString(epochSeconds: Double): String {
    val nowMillis by produceState(initialValue = now().times(MILLIS_PER_SECOND).toLong()) {
        while (true) {
            value = now().times(MILLIS_PER_SECOND).toLong()
            delay(REFRESH_PERIOD_MILLIS)
        }
    }
    val millis = epochSeconds.times(MILLIS_PER_SECOND).toLong()
    return if ((nowMillis - millis).absoluteValue < DateUtils.MINUTE_IN_MILLIS)
        stringResource(R.string.relative_time_now)
    else
        DateUtils.getRelativeTimeSpanString(
            millis,
            nowMillis,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
}
