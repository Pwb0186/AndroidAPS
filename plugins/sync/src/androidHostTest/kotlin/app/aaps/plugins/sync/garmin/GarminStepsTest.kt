package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class GarminStepsTest : TestBase() {

    /** Backing store for [sp]: the last total and its time live in SharedPreferences. */
    private val store = mutableMapOf<String, Any>()
    private val sp = mock<SP>()
    private val loopHub = mock<LoopHub>()

    private val t0 = Instant.parse("2026-10-01T12:00:00Z")
    private var now: Instant = t0
    private val zone: ZoneId = ZoneOffset.UTC

    private fun newSteps() = GarminSteps(aapsLogger, sp, loopHub, { Clock.fixed(now, zone) }, { zone })

    private fun uri(query: String) = URI("http://127.0.0.1:28891/get?$query")

    private val lastTotal get() = store[GarminSteps.PREF_GARMIN_LAST_STEPS]
    private val lastTs get() = store[GarminSteps.PREF_GARMIN_LAST_TS]

    /** The single record the total-steps path stores: delta in steps5min, 5 min up to now. */
    private fun verifyStored(steps: Int, end: Instant = now) {
        verify(loopHub).storeStepsCount(
            eq(end.minusSeconds(300)), eq(end), eq(steps), eq(0), eq(0), eq(0), eq(0), eq(0), eq("Garmin")
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
    fun testFlag_NothingStoredOrRemembered() {
        val steps = newSteps()
        steps.receive(uri("steps=1000"))
        now = t0.plusSeconds(300)
        steps.receive(uri("steps=1250&test=true"))
        verifyNoInteractions(loopHub)
        assertEquals(1000, lastTotal)
    }

    @Test
    fun noStepsParameter_Ignored() {
        newSteps().receive(uri("appId=CBE3D42A21C748B4A1FDF2511322F0B6&hr=60&trig=push"))
        verifyNoInteractions(loopHub)
        assertTrue(store.isEmpty())
    }

    @Test
    fun buckets_StoredAsSent() {
        val end = t0.epochSecond
        newSteps().receive(uri("steps5=40&steps10=80&steps15=120&steps30=200&steps60=400&steps180=900&stepsStart=${end - 300}&stepsEnd=$end&device=Edge"))
        verify(loopHub).storeStepsCount(
            eq(Instant.ofEpochSecond(end - 300)), eq(Instant.ofEpochSecond(end)),
            eq(40), eq(80), eq(120), eq(200), eq(400), eq(900), eq("Edge")
        )
        assertTrue(store.isEmpty())  // the bucket path keeps no running total
    }

    @Test
    fun buckets_TestFlag_NotStored() {
        val end = t0.epochSecond
        newSteps().receive(uri("steps5=40&stepsStart=${end - 300}&stepsEnd=$end&test=true"))
        verifyNoInteractions(loopHub)
    }

    @Test
    fun timestampsInMilliseconds_Rejected() {
        // A device sending ms instead of epoch seconds would date the steps ~year 56,000.
        val end = t0.toEpochMilli()
        val steps = newSteps()
        steps.receive(uri("steps5=40&stepsStart=${end - 300_000}&stepsEnd=$end"))
        steps.receive(uri("steps=1000&stepsStart=${end - 300_000}&stepsEnd=$end"))
        verifyNoInteractions(loopHub)
        assertTrue(store.isEmpty())
    }

    @Test
    fun timestampsMoreThanADayOff_Rejected() {
        val start = t0.epochSecond - GarminSteps.MAX_TIMESTAMP_OFFSET_SEC - 1
        newSteps().receive(uri("steps5=40&stepsStart=$start&stepsEnd=${start + 300}"))
        verify(loopHub, never()).storeStepsCount(any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun timestampsWithinADay_Accepted() {
        val end = t0.epochSecond - 3600  // an hour old, e.g. sent after reconnecting
        newSteps().receive(uri("steps5=40&stepsStart=${end - 300}&stepsEnd=$end"))
        verify(loopHub).storeStepsCount(
            eq(Instant.ofEpochSecond(end - 300)), eq(Instant.ofEpochSecond(end)),
            eq(40), eq(0), eq(0), eq(0), eq(0), eq(0), anyOrNull()
        )
    }
}
