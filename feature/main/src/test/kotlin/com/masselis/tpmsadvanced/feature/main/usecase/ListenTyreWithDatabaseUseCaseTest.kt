package com.masselis.tpmsadvanced.feature.main.usecase

import app.cash.turbine.test
import com.masselis.tpmsadvanced.core.common.now
import com.masselis.tpmsadvanced.core.test.mockkQueryOneOrNull
import com.masselis.tpmsadvanced.data.vehicle.interfaces.LogPreferences
import com.masselis.tpmsadvanced.data.vehicle.interfaces.TyreDatabase
import com.masselis.tpmsadvanced.data.vehicle.interfaces.TyreLogDatabase
import com.masselis.tpmsadvanced.data.vehicle.model.Pressure.CREATOR.bar
import com.masselis.tpmsadvanced.data.vehicle.model.SensorLocation.FRONT_LEFT
import com.masselis.tpmsadvanced.data.vehicle.model.Temperature.CREATOR.celsius
import com.masselis.tpmsadvanced.data.vehicle.model.Tyre
import com.masselis.tpmsadvanced.data.vehicle.model.Vehicle
import com.masselis.tpmsadvanced.data.vehicle.model.Vehicle.Kind.Location
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals

internal class ListenTyreWithDatabaseUseCaseTest {

    private lateinit var vehicle: Vehicle
    private lateinit var location: Location
    private lateinit var tyreDatabase: TyreDatabase
    private lateinit var tyreLogDatabase: TyreLogDatabase
    private lateinit var logPreferences: LogPreferences
    private lateinit var listenTyreUseCase: ListenTyreUseCase

    private fun CoroutineScope.test() = ListenTyreWithDatabaseUseCase.Impl(
        vehicle,
        location,
        tyreDatabase,
        tyreLogDatabase,
        logPreferences,
        listenTyreUseCase,
        this
    )

    @Before
    fun setup() {
        vehicle = mockk {
            val uuid = UUID.randomUUID()
            every { this@mockk.uuid } returns uuid
            every { name } returns "My car"
        }
        location = Location.Wheel(FRONT_LEFT)
        tyreDatabase = mockk {
            coEvery { insert(any(), any()) } returns Unit
            every { latestByTyreLocationByVehicle(any<Location.Wheel>(), any()) } returns
                    mockkQueryOneOrNull(null as Tyre.Located?)
        }
        tyreLogDatabase = mockk {
            coEvery { insert(any(), any(), any(), any(), any(), any()) } returns Unit
        }
        logPreferences = mockk {
            every { enabled } returns MutableStateFlow(false)
        }
        listenTyreUseCase = mockk {
            every { listen() } returns MutableSharedFlow()
        }
    }

    @Test
    fun `2 tyres emit with same id at front left`() = runTest {
        val tyresToEmit = listOf(
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT)),
            Tyre.Located(now(), -30, 1, 2f.bar, 2f.celsius, 25u, false, Location.Wheel(FRONT_LEFT))
        )
        every { listenTyreUseCase.listen() } returns tyresToEmit
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            assertEquals(tyresToEmit[0], awaitItem())
            assertEquals(tyresToEmit[1], awaitItem())
        }
        coVerify(exactly = 2) { tyreDatabase.insert(any(), any()) }
        coroutineContext.cancelChildren()
    }

    @Test
    fun `No tyre emit but a cache exists`() = runTest {
        val savedTyre =
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 1u, false, Location.Wheel(FRONT_LEFT))
        every { tyreDatabase.latestByTyreLocationByVehicle(location, any()) } returns
                mockkQueryOneOrNull(savedTyre)
        test().listen().test {
            assertEquals(savedTyre, awaitItem())
        }
        coVerify(exactly = 0) { tyreDatabase.insert(any(), any()) }
        coroutineContext.cancelChildren()
    }

    @Test
    fun `tyre log is not written when logging is disabled`() = runTest {
        every { logPreferences.enabled } returns MutableStateFlow(false)
        val tyreToEmit =
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        every { listenTyreUseCase.listen() } returns listOf(tyreToEmit)
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            assertEquals(tyreToEmit, awaitItem())
        }
        coVerify(exactly = 0) { tyreLogDatabase.insert(any(), any(), any(), any(), any(), any()) }
        coroutineContext.cancelChildren()
    }

    @Test
    fun `tyre log is written when logging is enabled`() = runTest {
        every { logPreferences.enabled } returns MutableStateFlow(true)
        val tyreToEmit =
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        every { listenTyreUseCase.listen() } returns listOf(tyreToEmit)
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            assertEquals(tyreToEmit, awaitItem())
        }
        coVerify(exactly = 1) {
            tyreLogDatabase.insert(
                tyreToEmit.timestamp,
                tyreToEmit.sensorId,
                "My car",
                tyreToEmit.pressure,
                tyreToEmit.temperature,
                tyreToEmit.battery,
            )
        }
        coroutineContext.cancelChildren()
    }

    /**
     * A sensor broadcasts the same reading up to 10 times in a row, and the scanner lets all of
     * them through because its own `distinctUntilChanged` compares the RSSI too. Only the first one
     * is worth writing, to either table.
     */
    @Test
    fun `a reading repeated by the sensor is written once`() = runTest {
        every { logPreferences.enabled } returns MutableStateFlow(true)
        val reading =
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        // Same reading, as the scanner reports it: a burst about 100ms apart, with a moving RSSI
        val repeats = List(10) { index ->
            reading.copy(timestamp = reading.timestamp + index.times(0.1), rssi = -20 - index)
        }
        every { listenTyreUseCase.listen() } returns repeats
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            // Every repeat still reaches the UI and the background monitoring, so the displayed
            // update time keeps moving and an alert isn't held back
            repeats.forEach { assertEquals(it, awaitItem()) }
        }
        coVerify(exactly = 1) { tyreDatabase.insert(any(), any()) }
        coVerify(exactly = 1) {
            tyreLogDatabase.insert(any(), any(), any(), any(), any(), any())
        }
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a reading is logged again once any of its values changes`() = runTest {
        every { logPreferences.enabled } returns MutableStateFlow(true)
        val first =
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        val emissions = listOf(
            first,
            first.copy(pressure = 2f.bar),
            first.copy(pressure = 2f.bar, temperature = 2f.celsius),
            first.copy(pressure = 2f.bar, temperature = 2f.celsius, battery = 49u),
            // Back to a value already seen, but not the one logged last
            first,
        )
        every { listenTyreUseCase.listen() } returns emissions
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            emissions.forEach { assertEquals(it, awaitItem()) }
        }
        coVerify(exactly = 5) {
            tyreLogDatabase.insert(any(), any(), any(), any(), any(), any())
        }
        coroutineContext.cancelChildren()
    }

    /**
     * Otherwise a tyre holding its pressure would stay out of the log entirely, which reads the
     * same as a sensor that stopped reporting.
     */
    @Test
    fun `an unchanged reading is logged again once the interval has passed`() = runTest {
        every { logPreferences.enabled } returns MutableStateFlow(true)
        val start = now()
        val reading =
            Tyre.Located(start, -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        val emissions = listOf(0.0, 30.0, 59.0, 60.0, 90.0, 120.0)
            .map { reading.copy(timestamp = start + it) }
        every { listenTyreUseCase.listen() } returns emissions
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            emissions.forEach { assertEquals(it, awaitItem()) }
        }
        // The interval runs from the reading that was logged, not from the first one seen, so the
        // one at 90 is held back while the one at 120 goes through
        coVerify(exactly = 3) {
            tyreLogDatabase.insert(any(), any(), any(), any(), any(), any())
        }
        listOf(start, start + 60.0, start + 120.0).forEach { timestamp ->
            coVerify(exactly = 1) {
                tyreLogDatabase.insert(timestamp, any(), any(), any(), any(), any())
            }
        }
        coroutineContext.cancelChildren()
    }

    /**
     * The cache only feeds the first value a collector sees, so it's written often enough to read
     * as "now" when the app is opened again and no more than that. The log is kept lighter.
     */
    @Test
    fun `an unchanged reading reaches the cache twice as often as the log`() = runTest {
        every { logPreferences.enabled } returns MutableStateFlow(true)
        val start = now()
        val reading =
            Tyre.Located(start, -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        val emissions = listOf(0.0, 30.0, 60.0, 90.0)
            .map { reading.copy(timestamp = start + it) }
        every { listenTyreUseCase.listen() } returns emissions
            .asFlow()
            .onCompletion { awaitCancellation() }
        test().listen().test {
            emissions.forEach { assertEquals(it, awaitItem()) }
        }
        // Every 30 seconds for the cache, so all four
        coVerify(exactly = 4) { tyreDatabase.insert(any(), any()) }
        // Every minute for the log, so the ones at 0 and 60
        coVerify(exactly = 2) {
            tyreLogDatabase.insert(any(), any(), any(), any(), any(), any())
        }
        coroutineContext.cancelChildren()
    }

    /**
     * Otherwise nothing would be logged after switching logging back on until a value happened to
     * change, which reads as the feature being broken.
     */
    @Test
    fun `switching logging off and on logs the next reading even when unchanged`() = runTest {
        val enabled = MutableStateFlow(true)
        every { logPreferences.enabled } returns enabled
        val reading =
            Tyre.Located(now(), -20, 1, 1f.bar, 1f.celsius, 50u, false, Location.Wheel(FRONT_LEFT))
        val emissions = MutableSharedFlow<Tyre.Located>(replay = 0)
        every { listenTyreUseCase.listen() } returns emissions
        test().listen().test {
            // shareIn only attaches upstream once something collects, and a replayless
            // MutableSharedFlow drops whatever is emitted before that
            emissions.subscriptionCount.first { it > 0 }

            emissions.emit(reading)
            awaitItem()

            enabled.value = false
            emissions.emit(reading)
            awaitItem()

            enabled.value = true
            emissions.emit(reading)
            awaitItem()
        }
        // Once before switching off, once after switching back on
        coVerify(exactly = 2) {
            tyreLogDatabase.insert(any(), any(), any(), any(), any(), any())
        }
        coroutineContext.cancelChildren()
    }
}
