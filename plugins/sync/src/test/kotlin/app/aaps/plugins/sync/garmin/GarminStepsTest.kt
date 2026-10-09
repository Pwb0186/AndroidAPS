package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier

class GarminStepsTest : TestBase() {

    /** Backing store for [sp]: the last total and its time live in SharedPreferences. */
    private val store = mutableMapOf<String, Any>()
    private val sp = mock<SP>()
    private val loopHub = mock<LoopHub>()

    private val t0 = Instant.parse("2026-10-01T12:00:00Z")
    private var now: Instant = t0
    private val zone: ZoneId = ZoneOffset.UTC

    private fun newSteps(zone: ZoneId = this.zone) =
        GarminSteps(aapsLogger, sp, loopHub, { Clock.fixed(now, zone) }, { zone })

    private fun uri(query: String) = URI("http://127.0.0.1:28891/get?$query")

    private val lastTotal get() = store[GarminSteps.PREF_GARMIN_LAST_STEPS]
    private val lastTs get() = store[GarminSteps.PREF_GARMIN_LAST_TS]

    /** Steps stored at [now], by window in minutes - exactly these windows. */
    private fun verifyStored(vararg windows: Pair<Int, Int>) {
        verify(loopHub).storeStepsCount(eq(now), eq(mapOf(*windows)), eq("Garmin"))
    }

    @BeforeEach
    fun setup() {
        whenever(sp.getInt(any<String>(), any<Int>())).thenAnswer { i ->
            store[i.getArgument(0)] as? Int ?: i.getArgument<Int>(1)
        }
        whenever(sp.getLong(any<String>(), any<Long>())).thenAnswer { i ->
            store[i.getArgument(0)] as? Long ?: i.getArgument<Long>(1)
        }
        doAnswer { i -> store[i.getArgument(0)] = i.getArgument<Int>(1); null }
            .whenever(sp).putInt(any<String>(), any<Int>())
        doAnswer { i -> store[i.getArgument(0)] = i.getArgument<Long>(1); null }
            .whenever(sp).putLong(any<String>(), any<Long>())
    }

    @Test
    fun firstMeasurement_BaselineOnly() {
        newSteps().receive(uri("steps=1000"))
        assertEquals(1000, lastTotal)
        assertEquals(t0.toEpochMilli(), lastTs)
        verifyNoInteractions(loopHub)
    }

    @Test
    fun normalDelta_StoredForFiveMinutes() {
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1250"))
        verifyStored(5 to 250)
        assertEquals(1250, lastTotal)
        verifyNoMoreInteractions(loopHub)
    }

    @Test
    fun deltaZero_StoredAsZero() {
        // Standing still is stored too, so a rule like "fewer than 100 steps" can see it.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1000"))
        verifyStored(5 to 0)
        assertEquals(now.toEpochMilli(), lastTs)
    }

    @Test
    fun deltaZero_KeepsShortGap() {
        // Standing still for a while: delta=0 moves the time, so the next real delta
        // is not treated as a long gap.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        for (i in 1..4) {
            now = t0.plusSeconds(300L * i)
            steps.receive(uri("steps=1000"))
        }
        now = t0.plusSeconds(1500)  // 25 min after the first reading, 5 min after the last
        steps.receive(uri("steps=1080"))
        verifyStored(5 to 80, 10 to 80, 15 to 80)
    }

    @Test
    fun windows_AfterThreeHours() {
        // 100 steps every 5 minutes; old readings drop out of the 180-minute window.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        for (i in 1..36) {
            now = t0.plusSeconds(300L * i)
            steps.receive(uri("steps=${1000 + 100 * i}"))
        }
        verifyStored(5 to 100, 10 to 200, 15 to 300, 30 to 600, 60 to 1200, 180 to 3600)
        now = t0.plusSeconds(300L * 37)
        steps.receive(uri("steps=${1000 + 100 * 37}"))
        verifyStored(5 to 100, 10 to 200, 15 to 300, 30 to 600, 60 to 1200, 180 to 3600)
    }

    @Test
    fun onlyCoveredWindows_AfterBaseline() {
        // 10 minutes of history: the 15-minute window and longer are left out, not
        // stored too low.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1100"))
        now = t0.plusSeconds(600)
        steps.receive(uri("steps=1250"))
        verifyStored(5 to 150, 10 to 250)
    }

    @Test
    fun shortFirstInterval_NothingStored() {
        // 4 minutes of history do not cover the 5-minute window yet.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(240)
        steps.receive(uri("steps=1100"))
        verifyNoInteractions(loopHub)
        assertEquals(1100, lastTotal)
    }

    @Test
    fun stepsSpreadOverInterval() {
        // 500 steps in 5 min, then 700 steps in 7 min (100 a minute): the last 5 minutes
        // get 5/7 of the 700, the last 10 minutes all 700 and 3/5 of the 500.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1500"))
        now = t0.plusSeconds(720)
        steps.receive(uri("steps=2200"))
        verifyStored(5 to 500, 10 to 1000)
    }

    @Test
    fun longGap_BaselineMovedWithoutSpike() {
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusMillis(GarminSteps.MAX_STEPS_GAP_MS + 1)
        steps.receive(uri("steps=4000"))
        verifyNoInteractions(loopHub)
        assertEquals(4000, lastTotal)
        assertEquals(now.toEpochMilli(), lastTs)
    }

    @Test
    fun gapAtLimit_StillStored() {
        // 500 steps over the whole 12 minutes: 5/12 of them in the last 5 minutes.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusMillis(GarminSteps.MAX_STEPS_GAP_MS)
        steps.receive(uri("steps=1500"))
        verifyStored(5 to 208, 10 to 417)
    }

    @Test
    fun counterDropsSameDay_BaselineMovedWithoutSpike() {
        val steps = newSteps()
        steps.receive(uri("steps=5000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=200"))  // watch reboot or another watch face
        verifyNoInteractions(loopHub)
        assertEquals(200, lastTotal)
    }

    @Test
    fun counterDrop_WindowsStartAgain() {
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        for (i in 1..3) {
            now = t0.plusSeconds(300L * i)
            steps.receive(uri("steps=${1000 + 100 * i}"))
        }
        now = t0.plusSeconds(1200)
        steps.receive(uri("steps=50"))  // another counter
        now = t0.plusSeconds(1500)
        steps.receive(uri("steps=120"))
        verifyStored(5 to 70)
    }

    @Test
    fun restart_WindowsStartAgain() {
        // The history is only in memory: after an AAPS restart the windows start again
        // from the last reading, which is kept in the preferences.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        for (i in 1..3) {
            now = t0.plusSeconds(300L * i)
            steps.receive(uri("steps=${1000 + 100 * i}"))
        }
        now = t0.plusSeconds(1200)
        newSteps().receive(uri("steps=1400"))
        verifyStored(5 to 100)
    }

    @Test
    fun midnightShortGap_NewDayTotalStored() {
        now = Instant.parse("2026-10-01T23:58:00Z")
        val steps = newSteps()
        steps.receive(uri("steps=9000"))
        now = now.plusSeconds(300)  // 00:03, counter reset at midnight
        steps.receive(uri("steps=40"))
        verifyStored(5 to 40)
        assertEquals(40, lastTotal)
    }

    @Test
    fun midnightLongGap_BaselineOnly() {
        now = Instant.parse("2026-10-01T23:30:00Z")
        val steps = newSteps()
        steps.receive(uri("steps=9000"))
        now = now.plusSeconds(3600)  // 00:30 next day
        steps.receive(uri("steps=400"))
        verifyNoInteractions(loopHub)
        assertEquals(400, lastTotal)
    }

    @Test
    fun midnightWithoutDrop_OrdinaryDelta() {
        // The watch read yesterday's total just before its midnight, AAPS got it just
        // after the phone's: not a reset, so only the difference is stored.
        now = Instant.parse("2026-10-01T23:57:00Z")
        val steps = newSteps()
        steps.receive(uri("steps=9000"))
        now = now.plusSeconds(300)
        steps.receive(uri("steps=9050"))
        verifyStored(5 to 50)
    }

    @Test
    fun localMidnight_NotUtcMidnight() {
        // The watch resets its counter at local midnight. In Copenhagen (UTC+2 in
        // summer) that is 22:00 UTC, where the UTC date does not change.
        now = Instant.parse("2026-09-30T21:58:00Z")  // 23:58 local
        val steps = newSteps(ZoneId.of("Europe/Copenhagen"))
        steps.receive(uri("steps=9000"))
        now = now.plusSeconds(300)  // 00:03 local, next day
        steps.receive(uri("steps=40"))
        verifyStored(5 to 40)
        assertEquals(40, lastTotal)
    }

    @Test
    fun summerTimeEnds_SameDay_OrdinaryDelta() {
        // On the last Sunday of October 03:00 summer time becomes 02:00 winter time.
        // The local clock goes back an hour, but it is the same day: not a reset.
        now = Instant.parse("2026-10-25T00:58:00Z")  // 02:58 summer time
        val steps = newSteps(ZoneId.of("Europe/Copenhagen"))
        steps.receive(uri("steps=300"))
        now = now.plusSeconds(300)  // 02:03 winter time
        steps.receive(uri("steps=320"))
        verifyStored(5 to 20)
    }

    @Test
    fun sameTotalFromTwoThreads_StoredOnce() {
        // The HTTP server answers on a thread pool, so two /get with the same total
        // can arrive at the same time. Only one of them may count the delta - without
        // ingestLock both read the same last total and both count it.
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        val rounds = 200
        repeat(rounds) { i ->
            now = t0.plusSeconds(60L * (i + 1))
            val total = 1000 + 10 * (i + 1)
            val barrier = CyclicBarrier(2)
            List(2) {
                Thread {
                    barrier.await()
                    steps.receive(uri("steps=$total"))
                }.apply { start() }
            }.forEach { it.join() }
        }
        // 10 steps a minute: 50 in every 5 minutes. The first 4 rounds do not cover
        // 5 minutes yet.
        verify(loopHub, times(rounds - 4)).storeStepsCount(
            any(), argThat { this[5] == 50 }, eq("Garmin")
        )
        verifyNoMoreInteractions(loopHub)
        assertEquals(1000 + 10 * rounds, lastTotal)
    }

    @Test
    fun noStepsParameter_Ignored() {
        newSteps().receive(uri("appId=CBE3D42A21C748B4A1FDF2511322F0B6&hr=60&trig=push"))
        verifyNoInteractions(loopHub)
        assertTrue(store.isEmpty())
    }
}
