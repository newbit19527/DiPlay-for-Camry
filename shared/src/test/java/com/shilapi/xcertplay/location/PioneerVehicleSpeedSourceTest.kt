package com.shilapi.xcertplay.location

import android.os.Looper
import com.shilapi.xcertplay.transport.PascdEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic work queues exercise subscription order without loading a native CEAM library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PioneerVehicleSpeedSourceTest {
    @Test fun convertsFirmwareKilometersPerHourAndDropsLateCallbacksAfterStop() {
        val f = Fixture()
        f.boot()
        val sdk = f.clients.single()
        sdk.emit(36.0, 10)
        assertEquals(10.0, f.source.drain()!!.samples.single().metersPerSecond, 0.0)
        f.source.stop()
        sdk.emit(20.0, 11)
        assertNull(f.source.drain())
        f.workers[0].ready()
        f.workers[1].ready()
        assertEquals(1, sdk.closed)
        assertTrue(f.workers.all { it.closed })
    }

    @Test fun startDuringAsynchronousStopWaitsForBothWorkersThenRestarts() {
        val f = Fixture()
        f.boot()
        f.clients.single().emit(10.0, 100)
        f.source.stop()
        f.source.start()
        assertEquals(2, f.workers.size)
        f.workers[0].ready()
        assertEquals(2, f.workers.size) // Selector lane has not finished yet.
        f.workers[1].ready()
        assertEquals(4, f.workers.size)
        f.workers[2].ready()
        f.workers[3].ready()
        f.clients.last().emit(39.6, 1) // New run may have a new native tick epoch.
        assertEquals(11.0, f.source.drain()!!.samples.single().metersPerSecond, 0.0)
        assertEquals(1, f.clients.first().closed)
    }

    @Test fun anotherStopRevokesAPendingRestart() {
        val f = Fixture()
        f.boot()
        f.source.stop()
        f.source.start()
        f.source.stop()
        f.workers[0].ready()
        f.workers[1].ready()
        assertEquals(2, f.workers.size)
        assertEquals(1, f.clients.size)
        assertNull(f.source.drain())
    }

    @Test fun slowSelectorReadDoesNotRefreshOldGearOrDelaySensorCallbacks() {
        val f = Fixture()
        f.boot()
        val sdk = f.clients.single()
        sdk.emit(10.0, 1)
        f.now = 1100
        sdk.read = {
            f.now = 2600 // Model a slow selector call while the independent sensor lane remains responsive.
            sdk.emit(20.0, 2)
            assertNull(f.source.drain())
            PioneerShift(1, 2)
        }
        f.workers[1].tick()
        sdk.emit(1.0, 3)
        assertNull(f.source.drain()) // The result belongs to the old request, not completion time.
        f.now = 2700
        sdk.read = { PioneerShift(1, 2) }
        f.workers[1].tick()
        sdk.emit(1.0, 4)
        assertEquals('R', f.source.drain()!!.gear.letter)
    }

    @Test fun cancellationDuringRegistrationStillUnregistersThatListener() {
        val f = Fixture()
        f.configure = { sdk -> sdk.subscribing = { f.source.stop() } }
        f.source.start()
        f.workers[0].ready()
        f.workers[1].ready()
        assertEquals(1, f.clients.single().closed)
        assertEquals(0, f.clients.single().reads)
        assertNull(f.source.drain())
    }

    @Test fun failedRegistrationIsCleanedUpWithoutAutomaticRetry() {
        val f = Fixture()
        f.configure = { sdk -> sdk.subscribing = { throw IllegalStateException("registration failed after acceptance") } }
        f.source.start()
        f.workers[0].ready()
        f.workers[1].ready()
        assertEquals(1, f.clients.single().closed)
        assertEquals(2, f.workers.size)
        assertNull(f.source.drain())
    }

    @Test fun missingNativeDependencyDoesNotEscapeOrLeaveTheSourceRunning() {
        val f = Fixture()
        f.configure = { sdk -> sdk.read = { throw UnsatisfiedLinkError("missing native SDK") } }
        f.source.start()
        f.workers[0].ready()
        f.workers[1].ready()
        f.workers[0].ready()
        assertEquals(1, f.clients.single().closed)
        assertTrue(f.workers.all { it.closed })
        assertNull(f.source.drain())
    }

    @Test fun sessionCleanupPreservesTheProcessOwnedSdkDispatcherForTheNextConnection() {
        val dispatcher = ManualWorker()
        val processOwned = ProcessOwnedSpeedWorker(dispatcher)
        val gears = mutableListOf<ManualWorker>()
        val clients = mutableListOf<FakeBackend>()
        val source = PioneerVehicleSpeedSource({}, { FakeBackend().also { clients += it } }, { name ->
            if (name == "diplay-pioneer-speed") processOwned else ManualWorker().also { gears += it }
        }, { true }, { 1000 })
        repeat(2) { index ->
            source.start()
            dispatcher.ready()
            gears.last().ready()
            clients.last().emit(10.0, 1)
            assertNotNull(source.drain())
            source.stop()
            dispatcher.ready()
            gears.last().ready()
            assertEquals(index + 1, clients.size)
            assertEquals(1, clients.last().closed)
            assertFalse(dispatcher.closed)
            assertTrue(gears.last().closed)
        }
        dispatcher.close()
    }

    @Test fun unsupportedDeviceDoesNotConstructWorkersOrVendorClient() {
        val f = Fixture()
        f.available = false
        f.source.start()
        assertTrue(f.workers.isEmpty())
        assertTrue(f.clients.isEmpty())
        assertNull(f.source.drain())
    }

    @Test fun normalizesOnceBeforeEncodingMeasuredPascdSamples() {
        val f = Fixture()
        f.boot()
        val sdk = f.clients.single()
        sdk.emit(90.0, 1)
        f.now = 1100
        sdk.emit(36.0, 2)
        val reading = requireNotNull(f.source.drain())
        assertEquals(listOf(25.0, 10.0), reading.samples.map { it.metersPerSecond })
        assertEquals("\$PASCD,1.000,C,D,0,2,0.00,25.000,0.10,10.000*44\r\n", PascdEncoder.encode(reading))
    }

    @Test fun usesPhysicalMetersPerSecondForTheUnchangedParkThreshold() {
        val f = Fixture()
        f.configure = { it.read = { PioneerShift(1, 1) } }
        f.boot()
        val sdk = f.clients.single()
        sdk.emit(1.8, 1)
        assertEquals(0.5, f.source.drain()!!.samples.single().metersPerSecond, 0.0)
        f.now++
        sdk.emit(1.80036, 2)
        assertNull(f.source.drain())
        f.now++
        sdk.emit(0.0, 3)
        assertEquals(0.0, f.source.drain()!!.samples.single().metersPerSecond, 0.0)
    }

    @Test fun rejectsNegativeNonfiniteAndOutOfRangeValuesWithoutTakingAbsoluteValue() {
        for (raw in listOf(-4.150605201721191, Double.NaN, Double.POSITIVE_INFINITY, 360.00036)) {
            val f = Fixture()
            f.boot()
            val sdk = f.clients.single()
            sdk.emit(36.0, 1)
            f.now++
            sdk.emit(raw, 2)
            assertNull("raw value $raw must discard the pending batch", f.source.drain())
        }
    }

    @Test fun conversionDoesNotReviveDuplicateTicksOrExpiredGear() {
        val f = Fixture()
        f.boot()
        val sdk = f.clients.single()
        sdk.emit(90.0, 1)
        f.now++
        sdk.emit(90.0, 1)
        assertNull(f.source.drain())
        f.now = 2001
        sdk.emit(90.0, 2)
        assertNull(f.source.drain())
    }

    private class Fixture {
        var now = 1000L
        var available = true
        val workers = mutableListOf<ManualWorker>()
        val clients = mutableListOf<FakeBackend>()
        var configure: (FakeBackend) -> Unit = {}
        val source = PioneerVehicleSpeedSource({}, {
            FakeBackend().also { configure(it); clients += it }
        }, {
            ManualWorker().also { workers += it }
        }, { available }, { now })

        fun boot() { source.start(); workers[0].ready(); workers[1].ready() }
    }

    private class ManualWorker : PioneerSpeedWorker {
        private val queued = ArrayDeque<() -> Unit>()
        private val delayed = ArrayDeque<() -> Unit>()
        var closed = false
        override val looper: Looper get() = Looper.getMainLooper()
        override fun post(action: () -> Unit) { check(!closed); queued.addLast(action) }
        override fun later(delayMillis: Long, action: () -> Unit) { check(!closed); delayed.addLast(action) }
        override fun close() { closed = true; queued.clear(); delayed.clear() }
        fun ready() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun tick() { queued.addAll(delayed); delayed.clear(); ready() }
    }

    private class FakeBackend : PioneerSpeedBackend {
        lateinit var changed: (String) -> Unit
        var subscribing: () -> Unit = {}
        var read: () -> PioneerShift = { PioneerShift(1, 4) }
        var closed = 0
        var reads = 0
        override fun subscribe(looper: Looper, changed: (String) -> Unit) {
            this.changed = changed
            subscribing()
        }
        override fun shift(): PioneerShift { reads++; return read() }
        override fun close() { closed++ }
        fun emit(speed: Double, tick: Long) { changed("{\"value\":$speed,\"interval\":250,\"tickTime\":$tick}") }
    }
}
