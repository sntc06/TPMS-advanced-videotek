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

        /** The last reading written to [TyreLogDatabase], to avoid logging it again unchanged */
        private var lastLoggedReading: LoggedReading? = null

        /** When [lastLoggedReading] was written, as carried by the reading itself */
        private var lastLoggedAt: Double? = null

        /** [Tyre.Located] minus everything that isn't a sensor reading, notably the timestamp */
        private data class LoggedReading(
            val sensorId: Int,
            val pressure: Pressure,
            val temperature: Temperature,
            val battery: UShort,
        )

        private val flow = listenTyreUseCase
            .listen()
            .onEach { tyre -> tyreDatabase.insert(tyre, vehicle.uuid) }
            .onEach { tyre ->
                if (logPreferences.enabled.value.not()) {
                    // So that switching logging back on always records the reading that follows,
                    // even when nothing changed while it was off
                    lastLoggedReading = null
                    lastLoggedAt = null
                    return@onEach
                }
                val reading = LoggedReading(
                    tyre.sensorId,
                    tyre.pressure,
                    tyre.temperature,
                    tyre.battery,
                )
                // The clock can be set either way, so read the distance, not the direction
                val sinceLastLog = lastLoggedAt?.let { (tyre.timestamp - it).absoluteValue }
                // A sensor broadcasts the same reading up to 10 times in a row and the scanner lets
                // all of them through, since its own distinctUntilChanged compares the RSSI too and
                // that moves between packets. Logging every one of them would fill the log with
                // rows that only differ by a fraction of a second. Keeping only what changed would
                // leave a tyre that holds its pressure out of the log entirely though, which reads
                // the same as a sensor that stopped reporting, hence the interval.
                if (reading == lastLoggedReading &&
                    sinceLastLog != null &&
                    sinceLastLog < LOG_INTERVAL_SECONDS
                ) return@onEach
                lastLoggedReading = reading
                lastLoggedAt = tyre.timestamp
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
