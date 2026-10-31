package app.aaps.plugins.sync.garmin

import app.aaps.plugins.sync.garmin.GarminKeyGuard.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GarminKeyGuardTest {

    private var nowMs = 1_000_000L
    private val guard = GarminKeyGuard { nowMs }
    private val key = "commandkey"

    @Test
    fun emptyOrShortKey_isNoKey() {
        assertEquals(Result.NO_KEY, guard.check("", ""))
        assertEquals(Result.NO_KEY, guard.check("1234567", "1234567"))
        assertEquals(Result.OK, guard.check("12345678", "12345678"))
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
        assertEquals(Result.LOCKED, guard.check(key, "x", allowWhileLocked = true))
        nowMs += GarminKeyGuard.LOCKOUT_MS
        assertEquals(Result.OK, guard.check(key, key))
    }
}
