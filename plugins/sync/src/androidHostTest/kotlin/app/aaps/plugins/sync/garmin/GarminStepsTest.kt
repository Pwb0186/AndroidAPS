package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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

    /** The single record the total-steps path stores: delta in steps5min, 5 min up to now. */
    private fun verifyStored(steps: Int, end: Instant = now) {
        verify(loopHub).storeStepsCount(
            eq(end.minusSeconds(300)), eq(end), eq(steps), eq("Garmin")
        )
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
    fun normalDelta_StoredAsFiveMinuteRecord() {
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1250"))
        verifyStored(250)
        assertEquals(1250, lastTotal)
        verifyNoMoreInteractions(loopHub)
    }

    @Test
    fun deltaZero_NothingStored_TimeMoved() {
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1000"))
        verifyNoInteractions(loopHub)
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
        verifyStored(80)
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
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusMillis(GarminSteps.MAX_STEPS_GAP_MS)
        steps.receive(uri("steps=1500"))
        verifyStored(500)
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
    fun midnightShortGap_NewDayTotalStored() {
        now = Instant.parse("2026-10-01T23:58:00Z")
        val steps = newSteps()
        steps.receive(uri("steps=9000"))
        now = now.plusSeconds(300)  // 00:03, counter reset at midnight
        steps.receive(uri("steps=40"))
        verifyStored(40)
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
        now = Instant.parse("2026-10-01T23:58:00Z")
        val steps = newSteps()
        steps.receive(uri("steps=9000"))
        now = now.plusSeconds(240)
        steps.receive(uri("steps=9050"))
        verifyStored(50)
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
        verifyStored(40)
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
        verifyStored(20)
    }

    @Test
    fun sameTotalFromTwoThreads_StoredOnce() {
        // The HTTP server answers on a thread pool, so two /get with the same total
        // can arrive at the same time. Only one of them may store the delta - without
        // ingestLock both read the same last total and both store it.
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
        verify(loopHub, times(rounds)).storeStepsCount(
            any(), any(), eq(10), eq("Garmin")
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
