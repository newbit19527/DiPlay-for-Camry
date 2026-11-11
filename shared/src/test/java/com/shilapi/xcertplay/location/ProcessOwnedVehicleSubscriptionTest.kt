package com.shilapi.xcertplay.location

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ProcessOwnedVehicleSubscriptionTest {
    @Test fun repeatedConnectionsAndConcurrentPollersOnlyRequestOnceForTheSingleton() {
        val owner = ProcessOwnedVehicleSubscription()
        val vehicle = Any()
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = List(8) { Thread {
            start.await()
            repeat(20) { owner.ensure(vehicle) { calls.incrementAndGet() } }
        }.apply { start() } }
        start.countDown(); threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, calls.get())
        assertFalse(owner.ensure(vehicle) { fail("must retain process subscription across STOP/START") })
    }

    @Test fun failedRequestCanRetryAndReplacementSingletonGetsItsOwnSubscription() {
        val owner = ProcessOwnedVehicleSubscription()
        val vehicle = Any()
        try { owner.ensure(vehicle) { throw IllegalStateException("unavailable") }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(owner.ensure(vehicle) { })
        assertTrue(owner.ensure(Any()) { })
    }
}
