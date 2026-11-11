package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample

/** One direction per batch; no extrapolation, cached-speed polling, or invented selector state. */
internal class PioneerSpeedBuffer(private val dropped: ((String, Int) -> Unit)? = null) {
    private var gear: VehicleGear? = null
    private var gearAt = -1L
    private var lastTick: Long? = null
    private val samples = ArrayDeque<VehicleSpeedSample>()

    @Synchronized
    fun shift(status: Int, value: Int, now: Long) {
        // Original TPS VehicleBasicInfoControl.getGear: valid status=1; 1=P, 2=R, 3=N, 4=D.
        val next = if (status == 1) when (value) {
            1 -> VehicleGear.PARK
            2 -> VehicleGear.REVERSE
            3 -> VehicleGear.NEUTRAL
            4 -> VehicleGear.DRIVE
            else -> null
        } else null
        if (next != gear) discard("selector_changed")
        gear = next
        gearAt = now
    }

    @Synchronized
    fun speed(metersPerSecond: Double, nativeTick: Long, interval: Int, now: Long): String {
        val previousTick = lastTick
        if (!metersPerSecond.isFinite() || metersPerSecond !in 0.0..100.0 ||
            nativeTick < 0 || interval <= 0 || (previousTick != null && nativeTick <= previousTick)) {
            val reason = when {
                !metersPerSecond.isFinite() || metersPerSecond !in 0.0..100.0 -> "invalid_speed"
                nativeTick < 0 -> "invalid_tick"
                interval <= 0 -> "invalid_interval"
                else -> "nonadvancing_tick"
            }
            discard(reason)
            return reason
        }
        lastTick = nativeTick
        if (gear == null || now - gearAt !in 0..GEAR_MAX_AGE_MS ||
            (gear == VehicleGear.PARK && metersPerSecond > 0.5)) {
            val reason = when {
                gear == null -> "invalid_gear"
                now - gearAt !in 0..GEAR_MAX_AGE_MS -> "stale_gear"
                else -> "moving_in_park"
            }
            discard(reason)
            return reason
        }
        if (samples.lastOrNull()?.elapsedMillis?.let { now <= it } == true) return "nonadvancing_receipt"
        // Native tick units/epoch are not assumed to equal Android elapsedRealtime.
        // Preserve native ordering, and stamp each newly received measurement on Android's clock.
        samples.addLast(VehicleSpeedSample(now, metersPerSecond))
        while (samples.size > MAX_SAMPLES) { samples.removeFirst(); dropped?.invoke("capacity", 1) }
        return "accepted"
    }

    @Synchronized
    fun drain(now: Long): VehicleSpeedReading? {
        if (now - gearAt !in 0..GEAR_MAX_AGE_MS) { discard("stale_gear_at_drain"); return null }
        var expired = 0
        while (samples.isNotEmpty() && now - samples.first().elapsedMillis !in 0..SPEED_MAX_AGE_MS) { samples.removeFirst(); expired++ }
        if (expired > 0) dropped?.invoke("stale_speed_at_drain", expired)
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    @Synchronized
    fun discard(reason: String = "malformed_json") {
        if (samples.isNotEmpty()) dropped?.invoke(reason, samples.size)
        samples.clear()
    }

    @Synchronized
    fun clear() { discard("reset"); gear = null; gearAt = -1; lastTick = null }

    internal companion object {
        const val GEAR_MAX_AGE_MS = 1_000L
        const val SPEED_MAX_AGE_MS = 2_000L
        const val MAX_SAMPLES = 32
    }
}
