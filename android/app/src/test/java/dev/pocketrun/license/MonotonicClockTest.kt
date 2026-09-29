package dev.pocketrun.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicClockTest {

    private class FakeStore(var saved: MonotonicClock.Saved? = null) : MonotonicClock.Store {
        override fun read(): MonotonicClock.Saved? = saved
        override fun write(saved: MonotonicClock.Saved) {
            this.saved = saved
        }
    }

    /** A device whose wall clock and uptime the test moves by hand. */
    private class Device(epoch: Long, uptime: Long) {
        var wall = epoch
        var uptime = uptime
    }

    private fun clockOf(device: Device, store: MonotonicClock.Store = FakeStore()) = MonotonicClock(
        wallClock = { device.wall },
        uptimeMillis = { device.uptime },
        store = store,
    )

    @Test
    fun `a fresh install trusts the wall clock`() {
        val device = Device(1_000_000, 500)
        val clock = clockOf(device)
        assertEquals(1_000_000, clock.now())
    }

    @Test
    fun `the floor is remembered between runs`() {
        val store = FakeStore()
        clockOf(Device(1_000_000, 0), store).now()
        // A new process, the same storage: the clock went nowhere.
        assertEquals(1_000_000, clockOf(Device(1_000_000, 0), store).now())
    }

    @Test
    fun `setting the clock back does not rewind time`() {
        val store = FakeStore()
        clockOf(Device(1_000_000, 0), store).now()
        val rolledBack = Device(900_000, 0)
        assertEquals(1_000_000, clockOf(rolledBack, store).now())
    }

    @Test
    fun `a rollback across a reboot does not rewind time either`() {
        val store = FakeStore()
        clockOf(Device(1_000_000, 60_000), store).now()
        // Reboot: uptime restarts at zero, the wall clock is rolled back.
        val afterBoot = Device(900_000, 0)
        assertEquals(1_000_000, clockOf(afterBoot, store).now())
    }

    @Test
    fun `uptime keeps the floor moving while the wall clock stands still`() {
        val store = FakeStore()
        clockOf(Device(1_000_000, 0), store).now()
        // The user freezes the wall clock; the device keeps running.
        val frozen = Device(1_000_000, 600_000)
        assertEquals(1_000_600, clockOf(frozen, store).now())
    }

    @Test
    fun `the wall clock wins when it is ahead`() {
        val store = FakeStore()
        clockOf(Device(1_000_000, 0), store).now()
        assertEquals(1_000_900, clockOf(Device(1_000_900, 1_000), store).now())
    }

    @Test
    fun `the floor only ever grows`() {
        val store = FakeStore()
        val device = Device(1_000_000, 0)
        val clock = clockOf(device, store)
        var previous = clock.now()
        repeat(5) {
            device.wall += 100
            device.uptime += 100_000
            val now = clock.now()
            assertTrue("time went backwards: $now < $previous", now >= previous)
            previous = now
        }
        assertEquals(1_000_500, previous)
    }

    @Test
    fun `a zero uptime does not drag the floor back`() {
        val store = FakeStore()
        clockOf(Device(1_000_000, 500_000), store).now()
        // Some devices report 0 while the floor was written at a real uptime.
        assertEquals(1_000_000, clockOf(Device(1_000_000, 0), store).now())
    }
}
