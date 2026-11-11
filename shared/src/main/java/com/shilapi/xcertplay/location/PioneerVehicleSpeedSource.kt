package com.shilapi.xcertplay.location

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.pioneer.CamryAdaptation
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import org.json.JSONObject
import java.lang.ref.WeakReference

internal data class PioneerShift(val status: Int, val value: Int)

/** Subscribe the shared SDK singleton once without replacing its UI listener. The SDK restores
 * its subscription on service reconnect; CarPlay STOP must not globally unregister it. */
internal class ProcessOwnedVehicleSubscription {
    private var requestedFor: Any? = null
    @Synchronized fun ensure(vehicle: Any, subscribe: () -> Unit): Boolean {
        if (requestedFor === vehicle) return false
        subscribe() // A failed request can be retried only by a later explicit source START.
        requestedFor = vehicle
        return true
    }
}
private val processVehicleSubscription = ProcessOwnedVehicleSubscription()

internal interface PioneerSpeedBackend {
    fun subscribe(looper: Looper, changed: (String) -> Unit)
    fun shift(): PioneerShift
    fun close()
}

internal interface PioneerSpeedWorker {
    val looper: Looper
    fun post(action: () -> Unit)
    fun later(delayMillis: Long, action: () -> Unit)
    fun close()
}

private class HandlerSpeedWorker(name: String) : PioneerSpeedWorker {
    private val thread = HandlerThread(name).apply { start() }
    override val looper: Looper get() = thread.looper
    private val handler = Handler(looper)
    override fun post(action: () -> Unit) { handler.post { action() } }
    override fun later(delayMillis: Long, action: () -> Unit) { handler.postDelayed({ action() }, delayMillis) }
    override fun close() { thread.quitSafely() }
}

/** CEAM binds its singleton dispatcher to its first caller's Looper. Keep that dispatcher alive
 * across sessions; each session removes only its own listener. */
internal class ProcessOwnedSpeedWorker(private val delegate: PioneerSpeedWorker) : PioneerSpeedWorker by delegate {
    override fun close() = Unit
}
private val sensorDispatcher by lazy { ProcessOwnedSpeedWorker(HandlerSpeedWorker("diplay-pioneer-sensors")) }
private fun speedWorker(name: String): PioneerSpeedWorker =
    if (name == "diplay-pioneer-speed") sensorDispatcher else HandlerSpeedWorker(name)

private class CeamSpeedBackend : PioneerSpeedBackend {
    private var controller: Any? = null
    private var listener: Any? = null // CEAM retains only a WeakReference.
    private var reference: WeakReference<Any>? = null
    private var vehicle: Any? = null

    override fun subscribe(looper: Looper, changed: (String) -> Unit) {
        val type = Class.forName(PioneerVehicleSpeedSource.SENSOR)
        controller = type.getMethod("getInstance").invoke(null)
        val next = Class.forName("com.shilapi.xcertplay.pioneer.PioneerSpeedListener")
            .getConstructor(Looper::class.java, Function1::class.java).newInstance(looper, changed)
        listener = next
        val ref = WeakReference(next)
        reference = ref // Retain for cleanup even if registration takes effect and then throws.
        type.getMethod("registerListener", WeakReference::class.java).invoke(controller, ref)
    }

    override fun shift(): PioneerShift {
        val type = Class.forName(PioneerVehicleSpeedSource.VEHICLE)
        val current = vehicle ?: type.getMethod("getInstance").invoke(null).also { vehicle = it }
        processVehicleSubscription.ensure(current) {
            val listenerType = Class.forName("jp.pioneer.ceam.VehicleInfo.IVehicleInfoListener")
            type.getMethod("registerListener", listenerType).invoke(current, null)
        }
        val shift = type.getMethod("getShiftStatus").invoke(current)
        val shiftType = Class.forName(PioneerVehicleSpeedSource.SHIFT)
        return PioneerShift(shiftType.getField("status").getInt(shift), shiftType.getField("value").getInt(shift))
    }

    override fun close() {
        try {
            reference?.let { ref ->
                controller?.javaClass?.getMethod("removeListener", WeakReference::class.java)?.invoke(controller, ref)
            }
        } finally { reference = null; listener = null; controller = null }
    }
}

/** Read-only CEAM SDK client. Vendor calls stay off the UI and iAP2 threads. */
class PioneerVehicleSpeedSource internal constructor(
    private val log: (String) -> Unit,
    private val backend: () -> PioneerSpeedBackend,
    private val worker: (String) -> PioneerSpeedWorker,
    private val available: () -> Boolean,
    private val now: () -> Long,
) : VehicleSpeedSource {
    constructor(log: (String) -> Unit = {}) : this(log, ::CeamSpeedBackend, ::speedWorker,
        Companion::supported, SystemClock::elapsedRealtime)

    private val buffer = PioneerSpeedBuffer()
    private var active: Run? = null
    private var restartRequested = false

    private class Run(val sensor: PioneerSpeedWorker, val gear: PioneerSpeedWorker) {
        @Volatile var cancelled = false
        var backend: PioneerSpeedBackend? = null
        var remainingWorkers = 2 // Guarded by the source lock.
    }

    @Synchronized
    override fun start() {
        active?.let {
            if (it.cancelled) restartRequested = true
            return
        }
        if (!available()) return
        buffer.clear()
        val sensor = worker("diplay-pioneer-speed")
        val gear = try { worker("diplay-pioneer-gear") } catch (error: Throwable) { sensor.close(); throw error }
        val run = Run(sensor, gear)
        active = run
        run.sensor.post {
            if (!run.cancelled) attempt(run) {
                val client = backend()
                run.backend = client
                if (!run.cancelled) {
                    client.subscribe(run.sensor.looper) { json -> if (!run.cancelled) accept(json) }
                    if (!run.cancelled) {
                        report("Pioneer speed: sensor subscription started; awaiting fresh speed and valid gear")
                        run.gear.post { poll(run, client) }
                    }
                }
            }
        }
    }

    private fun poll(run: Run, client: PioneerSpeedBackend) {
        if (run.cancelled) return
        attempt(run) {
            val requestedAt = now()
            val shift = client.shift()
            // A slow query must not refresh an old selector using its completion time.
            if (!run.cancelled) {
                buffer.shift(shift.status, shift.value, requestedAt)
                run.gear.later(250) { poll(run, client) }
            }
        }
    }

    private fun accept(json: String) {
        try {
            check(json.length <= 1024)
            val sample = JSONObject(json)
            // CEAM JSON multiplies signed GC_Speed.speed by 3.6f. Normalize exactly once.
            // Preserve negative values so the buffer rejects them rather than inventing direction.
            buffer.speed(sample.getDouble("value") / 3.6, sample.getLong("tickTime"),
                sample.getInt("interval"), now())
        } catch (_: Exception) { buffer.discard() }
    }

    private inline fun attempt(run: Run, action: () -> Unit) {
        try { action() }
        catch (error: Exception) { fail(run, error) }
        catch (error: LinkageError) { fail(run, error) }
    }
    private fun report(message: String) { runCatching { log(message) } }
    private fun fail(run: Run, error: Throwable) {
        report("Pioneer speed: unavailable (${(error.cause ?: error).javaClass.simpleName}); no speed sent")
        cancel(run)
    }

    @Synchronized
    override fun stop() {
        restartRequested = false
        active?.let(::cancel)
        buffer.clear()
    }

    @Synchronized
    private fun cancel(run: Run) {
        if (run.cancelled) return
        run.cancelled = true
        buffer.clear()
        run.sensor.post {
            try { run.backend?.close() }
            catch (error: Exception) { report("Pioneer speed: listener release failed (${error.javaClass.simpleName})") }
            catch (error: LinkageError) { report("Pioneer speed: listener release failed (${error.javaClass.simpleName})") }
            finally { finished(run, run.sensor) }
        }
        // A blocked selector cannot delay sensor unregistration or spawn a replacement getter.
        run.gear.post { finished(run, run.gear) }
    }

    @Synchronized
    private fun finished(run: Run, lane: PioneerSpeedWorker) {
        lane.close()
        run.remainingWorkers--
        if (active === run && run.remainingWorkers == 0) {
            active = null
            if (restartRequested) { restartRequested = false; start() }
        }
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? = if (active?.cancelled == false) buffer.drain(now()) else null

    companion object {
        internal const val SENSOR = "jp.pioneer.ceam.handler.GpsChipsController"
        internal const val VEHICLE = "jp.pioneer.ceam.VehicleInfo.VehicleInfo"
        internal const val SHIFT = "jp.pioneer.ceam.VehicleInfo.IVehicleInfo\$IShiftStatus"

        /** Metadata only: do not initialize the vendor controller or native library here. */
        fun supported(): Boolean = CamryAdaptation.enabled() && runCatching {
            val loader = PioneerVehicleSpeedSource::class.java.classLoader
            val sensor = Class.forName(SENSOR, false, loader)
            sensor.getMethod("registerListener", WeakReference::class.java)
            sensor.getMethod("removeListener", WeakReference::class.java)
            val vehicle = Class.forName(VEHICLE, false, loader)
            vehicle.getMethod("getShiftStatus")
            vehicle.getMethod("registerListener", Class.forName("jp.pioneer.ceam.VehicleInfo.IVehicleInfoListener", false, loader))
            val shift = Class.forName(SHIFT, false, loader)
            shift.getField("status"); shift.getField("value")
            true
        }.getOrDefault(false)
    }
}
