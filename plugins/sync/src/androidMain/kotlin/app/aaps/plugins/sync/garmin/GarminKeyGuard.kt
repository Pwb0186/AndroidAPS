package app.aaps.plugins.sync.garmin

import java.security.MessageDigest

/**
 * Checks the command key for the endpoints that change treatment.
 *
 * Any app on the phone can call localhost, so the key is the only thing between another app and a
 * bolus. A short pause per wrong key does not stop an app that sends many requests at once, so wrong
 * keys are counted for all requests together: after [MAX_FAILURES] wrong keys, commands are refused
 * for [LOCKOUT_MS], also with the right key. Only stop bolus still works with the right key then,
 * so another app cannot lock the user out of stopping a bolus.
 */
class GarminKeyGuard(private val now: () -> Long) {

    enum class Result { OK, NO_KEY, WRONG, LOCKED, LOCKED_NOW }

    companion object {

        /** Shorter keys are treated as not set: with the lockout 8 characters cannot be guessed. */
        const val MIN_KEY_LENGTH = 8
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 15 * 60 * 1000L
    }

    private val lock = Any()
    /** Times of wrong keys in the last [LOCKOUT_MS]. Not cleared by a right key, or a guessing app could
     *  get [MAX_FAILURES] - 1 tries between each request of the watch. */
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    fun check(expected: String, given: String?, allowWhileLocked: Boolean = false): Result = synchronized(lock) {
        if (expected.length < MIN_KEY_LENGTH) return Result.NO_KEY
        val locked = now() < lockedUntil
        if (locked && !allowWhileLocked) return Result.LOCKED
        // Constant time, so the time of the answer tells nothing about how much of the key was right.
        val right = given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
        if (right) return Result.OK
        if (locked) return Result.LOCKED
        val t = now()
        while (failures.isNotEmpty() && t - failures.first() >= LOCKOUT_MS) failures.removeFirst()
        failures.addLast(t)
        if (failures.size >= MAX_FAILURES) {
            failures.clear()
            lockedUntil = t + LOCKOUT_MS
            return Result.LOCKED_NOW
        }
        return Result.WRONG
    }
}
