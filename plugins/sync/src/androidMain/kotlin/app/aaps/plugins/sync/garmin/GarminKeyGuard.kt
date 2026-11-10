package app.aaps.plugins.sync.garmin

import java.security.MessageDigest

/**
 * Checks the remote control PIN for the endpoints that change treatment.
 *
 * Any app on the phone can call localhost, so the PIN is the only thing between another app and a
 * bolus. A short pause per wrong PIN does not stop an app that sends many requests at once, so wrong
 * PINs are counted for all requests together: after [MAX_FAILURES] wrong PINs, commands are refused
 * for [LOCKOUT_MS], also with the right PIN. Only stop bolus still works with the right PIN then,
 * so another app cannot lock the user out of stopping a bolus. Wrong PINs are not counted while
 * locked, so the PIN is compared at most once per [LOCKED_CHECK_MS] then: otherwise another app
 * could start the lockout itself and then guess freely, because the answer tells a right PIN apart.
 * Stop has its own such slot, so the stop screen's /status polling cannot make a stop wait.
 */
class GarminKeyGuard(private val now: () -> Long) {

    enum class Result { OK, NO_KEY, WRONG, LOCKED, LOCKED_NOW }

    companion object {

        /** Only 8-16 digits count as a PIN: that is all the watch app can type in, and with the
         *  lockout 8 digits cannot be guessed. The AAPS dialog for a PIN cannot check this itself. */
        private val PIN = Regex("[0-9]{8,16}")

        fun isPinSet(pin: String): Boolean = PIN.matches(pin)
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 15 * 60 * 1000L
        const val LOCKED_CHECK_MS = 2000L
    }

    private val lock = Any()
    /** Times of wrong PINs in the last [LOCKOUT_MS]. Not cleared by a right PIN, or a guessing app could
     *  get [MAX_FAILURES] - 1 tries between each request of the watch. */
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L
    /** When the PIN was last compared while locked; see [LOCKED_CHECK_MS]. Stop has its own. */
    private var lastLockedCheck = 0L
    private var lastLockedStopCheck = 0L

    fun check(expected: String, given: String?, allowWhileLocked: Boolean = false, stopSlot: Boolean = false): Result = synchronized(lock) {
        if (!isPinSet(expected)) return Result.NO_KEY
        val locked = now() < lockedUntil
        if (locked && !allowWhileLocked) return Result.LOCKED
        if (locked) {
            // For all requests together: 15 min then allows about 450 guesses, far too few for 8 digits.
            val last = if (stopSlot) lastLockedStopCheck else lastLockedCheck
            if (now() - last < LOCKED_CHECK_MS) return Result.LOCKED
            if (stopSlot) lastLockedStopCheck = now() else lastLockedCheck = now()
        }
        // Constant time, so the time of the answer tells nothing about how much of the PIN was right.
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
