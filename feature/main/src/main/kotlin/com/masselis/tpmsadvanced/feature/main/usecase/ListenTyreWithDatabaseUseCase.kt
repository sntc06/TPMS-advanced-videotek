package com.masselis.tpmsadvanced.feature.main.usecase

import com.masselis.tpmsadvanced.core.common.dematerializeCompletion
import com.masselis.tpmsadvanced.core.common.materializeCompletion
import com.masselis.tpmsadvanced.data.vehicle.interfaces.LogPreferences
import com.masselis.tpmsadvanced.data.vehicle.interfaces.TyreDatabase
import com.masselis.tpmsadvanced.data.vehicle.interfaces.TyreLogDatabase
import com.masselis.tpmsadvanced.data.vehicle.model.Pressure
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature
import com.masselis.tpmsadvanced.data.vehicle.model.Tyre
import com.masselis.tpmsadvanced.data.vehicle.model.Vehicle
import com.masselis.tpmsadvanced.data.vehicle.model.Vehicle.Kind.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlin.math.absoluteValue

/** How long a reading holding steady stays out of the log before it's written again */
private const val LOG_INTERVAL_SECONDS = 60.0

/**
 * How long a reading holding steady stays out of the cache before it's written again. The cache
 * only feeds the first value a collector sees, so writing every reading buys nothing beyond a
 * timestamp that is worth keeping fresh enough to read as "now" when the app is opened again.
 */
private const val CACHE_INTERVAL_SECONDS = 30.0

/**
 * Lets a reading through when it reads differently from the last one it let through, or when that
 * one is older than [intervalSeconds].
 *
 * A sensor broadcasts the same reading up to 10 times in a row and the scanner lets all of them
 * through, since its own `distinctUntilChanged` compares the RSSI too and that moves between
 * packets. Holding on to what changed alone would drop a tyre that keeps its pressure entirely
 * though, which reads the same as a sensor that stopped reporting, hence the interval.
 */
private class ReadingThrottle(private val intervalSeconds: Double) {

    private var last: Values? = null
    private var lastAt: Double? = null

    /** Whether [tyre] is worth writing. Remembers it when it is */
    fun admits(tyre: Tyre.Located): Boolean {
        val values = Values(tyre.sensorId, tyre.pressure, tyre.temperature, tyre.battery)
        // The clock can be set either way, so read the distance, not the direction
        val since = lastAt?.let { (tyre.timestamp - it).absoluteValue }
        if (values == last && since != null && since < intervalSeconds) return false
        last = values
        lastAt = tyre.timestamp
        return true
    }

    /** Drops what was remembered, so that the reading that follows is admitted whatever it holds */
    fun forget() {
        last = null
        lastAt = null
    }

    /** [Tyre.Located] minus everything that isn't a sensor reading, notably the timestamp */
    private data class Values(
        val sensorId: Int,
        val pressure: Pressure,
        val temperature: Temperature,
        val battery: UShort,
    )
}

internal interface ListenTyreWithDatabaseUseCase : ListenTyreUseCase {
    class Impl(
        vehicle: Vehicle,
        location: Location,
        tyreDatabase: TyreDatabase,
        tyreLogDatabase: TyreLogDatabase,
        logPreferences: LogPreferences,
        listenTyreUseCase: ListenTyreUseCase,
        scope: CoroutineScope,
    ) : ListenTyreWithDatabaseUseCase {

        private val cacheThrottle = ReadingThrottle(CACHE_INTERVAL_SECONDS)
        private val logThrottle = ReadingThrottle(LOG_INTERVAL_SECONDS)

        private val flow = listenTyreUseCase
            .listen()
            // Both writes are side effects, so every reading carries on downstream whether it gets
            // written or not
            .onEach { tyre ->
                if (cacheThrottle.admits(tyre)) tyreDatabase.insert(tyre, vehicle.uuid)
            }
            .onEach { tyre ->
                if (logPreferences.enabled.value.not()) {
                    // So that switching logging back on always records the reading that follows,
                    // even when nothing changed while it was off
                    logThrottle.forget()
                    return@onEach
                }
                if (logThrottle.admits(tyre).not()) return@onEach
                tyreLogDatabase.insert(
                    tyre.timestamp,
                    tyre.sensorId,
                    vehicle.name,
                    tyre.pressure,
                    tyre.temperature,
                    tyre.battery,
                )
            }
            .materializeCompletion()
            .shareIn(scope, WhileSubscribed())
            .dematerializeCompletion()
            .onStart {
                tyreDatabase
                    .latestByTyreLocationByVehicle(location, vehicle.uuid)
                    .execute()
                    ?.also { emit(it) }
            }
            .flowOn(Dispatchers.IO)

        override fun listen(): Flow<Tyre.Located> = flow
    }

    class Wrapper(private val source: ListenTyreUseCase) : ListenTyreWithDatabaseUseCase {
        override fun listen(): Flow<Tyre.Located> = source.listen()
    }
}
