package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.transport.PascdEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.*
import org.junit.Test

class PioneerSpeedBufferTest {
    @Test fun usesRealSelectorAndAlreadyConvertedMetersPerSecond() {
        val buffer = PioneerSpeedBuffer()
        for ((raw, gear) in listOf(1 to VehicleGear.PARK, 2 to VehicleGear.REVERSE,
                3 to VehicleGear.NEUTRAL, 4 to VehicleGear.DRIVE)) {
            buffer.shift(1, raw, 1000)
            buffer.speed(if (raw == 1) 0.0 else 10.0, raw.toLong(), 250, 1000)
            val reading = requireNotNull(buffer.drain(1000))
            assertEquals(gear, reading.gear)
            assertEquals(if (raw == 1) 0.0 else 10.0, reading.samples.single().metersPerSecond, 0.0)
        }
    }

    @Test fun batchesOnlyFreshMeasuredSamplesAndDrainsOnce() {
        val buffer = driving()
        buffer.speed(10.0, 1, 250, 1000)
        buffer.speed(11.0, 2, 250, 1250)
        val reading = requireNotNull(buffer.drain(1500))
        assertEquals(2, reading.samples.size)
        assertTrue(PascdEncoder.encode(reading)!!.startsWith("\$PASCD,1.000,C,D,0,2,0.00,10.000,0.25,11.000*"))
        assertNull(buffer.drain(1500)) // No cached getter value is repeated as a new measurement.
    }

    @Test fun invalidSelectorNeverTurnsIntoParkOrDrive() {
        for ((status, raw) in listOf(0 to 1, 2 to 4, 1 to 0, 1 to 99)) {
            val buffer = PioneerSpeedBuffer()
            buffer.shift(status, raw, 1000)
            buffer.speed(0.0, 1, 250, 1000)
            assertNull(buffer.drain(1000))
        }
    }

    @Test fun reverseChangeDropsEarlierForwardSamples() {
        val buffer = driving()
        buffer.speed(10.0, 1, 250, 1000)
        buffer.shift(1, 2, 1100)
        buffer.speed(1.0, 2, 250, 1200)
        val reading = requireNotNull(buffer.drain(1200))
        assertEquals(VehicleGear.REVERSE, reading.gear)
        assertEquals(listOf(1.0), reading.samples.map { it.metersPerSecond })
    }

    @Test fun rejectsDuplicateOrRegressingNativeTicksAndMalformedMeasurements() {
        for ((speed, tick, interval) in listOf(Triple(10.0, 1L, 250), Triple(10.0, 0L, 250),
                Triple(Double.NaN, 2L, 250), Triple(Double.POSITIVE_INFINITY, 2L, 250),
                Triple(-1.0, 2L, 250), Triple(101.0, 2L, 250), Triple(10.0, 2L, 0))) {
            val buffer = driving()
            buffer.speed(10.0, 1, 250, 1000)
            buffer.speed(speed, tick, interval, 1100)
            assertNull(buffer.drain(1100))
        }
    }

    @Test fun expiresGearAndMeasurementsSeparately() {
        val buffer = driving()
        buffer.speed(10.0, 1, 250, 1000)
        assertNull(buffer.drain(2001)) // Selector observation is too old.
        buffer.shift(1, 4, 3000)
        buffer.speed(10.0, 2, 250, 3000)
        buffer.shift(1, 4, 5100)
        assertNull(buffer.drain(5100)) // Fresh selector cannot revive an old speed sample.
    }

    @Test fun stopClearsStateAndASecondSessionMayUseANewNativeEpoch() {
        val buffer = driving()
        buffer.speed(10.0, 100, 250, 1000)
        buffer.clear()
        assertNull(buffer.drain(1000))
        buffer.shift(1, 4, 2000)
        buffer.speed(11.0, 1, 250, 2000)
        assertEquals(11.0, buffer.drain(2000)!!.samples.single().metersPerSecond, 0.0)
    }

    @Test fun queueIsBoundedAndParkCannotClaimMovingData() {
        val buffer = driving()
        repeat(100) { buffer.speed(10.0, it.toLong(), 1, 1000L + it) }
        assertEquals(PioneerSpeedBuffer.MAX_SAMPLES, buffer.drain(1100)!!.samples.size)
        buffer.shift(1, 1, 1200)
        buffer.speed(5.0, 101, 250, 1200)
        assertNull(buffer.drain(1200))
    }

    private fun driving() = PioneerSpeedBuffer().apply { shift(1, 4, 1000) }
}
