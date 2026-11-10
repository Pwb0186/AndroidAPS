package app.aaps.plugins.sync.garmin

import app.aaps.plugins.sync.garmin.GarminKeyGuard.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GarminKeyGuardTest {

    private var nowMs = 1_000_000L
    private val guard = GarminKeyGuard { nowMs }
    private val key = "12345678"

    @Test
    fun onlyEightToSixteenDigits_isAPin() {
        assertEquals(Result.NO_KEY, guard.check("", ""))
        assertEquals(Result.NO_KEY, guard.check("1234567", "1234567"))
        assertEquals(Result.NO_KEY, guard.check("qwertyui", "qwertyui"))
        assertEquals(Result.NO_KEY, guard.check("12345678901234567", "12345678901234567"))
        assertEquals(Result.OK, guard.check("12345678", "12345678"))
        assertEquals(Result.OK, guard.check("1234567890123456", "1234567890123456"))
    }

    @Test
    fun wrongKeys_lockAfterMax_rightKeyDoesNotResetCount() {
        repeat(GarminKeyGuard.MAX_FAILURES - 1) { assertEquals(Result.WRONG, guard.check(key, "x")) }
        assertEquals(Result.OK, guard.check(key, key))
        // The right key in between does not give a guessing app new tries.
        assertEquals(Result.LOCKED_NOW, guard.check(key, null))
        assertEquals(Result.LOCKED, guard.check(key, key))
        assertEquals(Result.LOCKED, guard.check(key, "x"))
    }

    @Test
    fun wrongKeys_olderThanLockout_areForgotten() {
        repeat(GarminKeyGuard.MAX_FAILURES - 1) { guard.check(key, "x") }
        nowMs += GarminKeyGuard.LOCKOUT_MS
        assertEquals(Result.WRONG, guard.check(key, "x"))
    }

    @Test
    fun locked_onlyRightKeyWithAllowWhileLocked_passes_untilLockoutEnds() {
        repeat(GarminKeyGuard.MAX_FAILURES) { guard.check(key, "x") }
        assertEquals(Result.OK, guard.check(key, key, allowWhileLocked = true))
        nowMs += GarminKeyGuard.LOCKED_CHECK_MS
        assertEquals(Result.LOCKED, guard.check(key, "x", allowWhileLocked = true))
        nowMs += GarminKeyGuard.LOCKOUT_MS
        assertEquals(Result.OK, guard.check(key, key))
    }

    @Test
    fun locked_pinComparedAtMostOncePerInterval() {
        repeat(GarminKeyGuard.MAX_FAILURES) { guard.check(key, "x") }
        // A wrong guess uses the one comparison; the right PIN right after it is not even compared.
        assertEquals(Result.LOCKED, guard.check(key, "x", allowWhileLocked = true))
        assertEquals(Result.LOCKED, guard.check(key, key, allowWhileLocked = true))
        nowMs += GarminKeyGuard.LOCKED_CHECK_MS - 1
        assertEquals(Result.LOCKED, guard.check(key, key, allowWhileLocked = true))
        nowMs += 1
        assertEquals(Result.OK, guard.check(key, key, allowWhileLocked = true))
    }
}
