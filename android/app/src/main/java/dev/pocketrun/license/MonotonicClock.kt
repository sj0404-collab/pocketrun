package dev.pocketrun.license

import android.content.Context
import android.os.SystemClock

/**
 * A clock that cannot be moved back.
 *
 * A license is only as good as the time it is checked against: `nbf` and `exp`
 * are signed, but both are compared to the device clock, and that clock belongs
 * to whoever holds the device. Moving it back one year re-activates an expired
 * key, for free, forever.
 *
 * The fix is to remember the highest time the app has ever seen and to keep
 * moving that floor forward with a source the user cannot touch:
 *
 * * the wall clock, when it is at or above the floor;
 * * `SystemClock.elapsedRealtime()` — milliseconds since boot, which only ever
 *   counts up and is not settable — added to the floor, so a reboot cannot
 *   rewind it either.
 *
 * What remains possible is wiping the app's data, which also erases the key.
 * That is the honest limit of an offline scheme with no server.
 */
class MonotonicClock(
    private val wallClock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val uptimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val store: Store,
) {

    interface Store {
        /** The remembered floor and the uptime at the moment it was written. */
        fun read(): Saved?

        fun write(saved: Saved)
    }

    data class Saved(val epochSeconds: Long, val uptimeMillis: Long)

    /**
     * The time to verify a license against. Never below anything this app has
     * already observed, and never below the floor advanced by the boot clock.
     */
    fun now(): Long {
        val wall = wallClock()
        val uptime = uptimeMillis()
        val saved = store.read()
        val floor = when {
            saved == null -> wall
            // A reboot drops elapsedRealtime to zero; the wall clock carries it
            // across, and the saved floor is still the hard minimum.
            uptime >= saved.uptimeMillis ->
                maxOf(saved.epochSeconds, saved.epochSeconds + (uptime - saved.uptimeMillis) / 1000)

            else -> saved.epochSeconds
        }
        val result = maxOf(wall, floor)
        if (saved == null || result > saved.epochSeconds) {
            store.write(Saved(result, uptime))
        }
        return result
    }

    companion object {
        private const val PREFS = "license-clock"
        private const val KEY_EPOCH = "floor_epoch"
        private const val KEY_UPTIME = "floor_uptime"

        /** The clock backed by SharedPreferences in [context]. */
        fun forContext(context: Context): MonotonicClock = MonotonicClock(
            store = context.applicationContext.preferences(),
        )

        /** A clock that remembers nothing — for tests and for a fresh install. */
        fun inMemory(): MonotonicClock = MonotonicClock(store = MemoryStore(null))

        private fun Context.preferences() = object : Store {
            private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            override fun read(): Saved? {
                val epoch = prefs.getLong(KEY_EPOCH, -1)
                if (epoch < 0) return null
                return Saved(epoch, prefs.getLong(KEY_UPTIME, 0))
            }

            override fun write(saved: Saved) {
                prefs.edit()
                    .putLong(KEY_EPOCH, saved.epochSeconds)
                    .putLong(KEY_UPTIME, saved.uptimeMillis)
                    .apply()
            }
        }

        private class MemoryStore(var saved: Saved?) : Store {
            override fun read(): Saved? = saved
            override fun write(saved: Saved) {
                this.saved = saved
            }
        }
    }
}
