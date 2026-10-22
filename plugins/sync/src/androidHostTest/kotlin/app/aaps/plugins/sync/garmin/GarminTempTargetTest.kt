package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.keys.StringNonKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.URI

class GarminTempTargetTest : TestBaseWithProfile() {

    @Mock private lateinit var batchExecutor: BatchExecutor

    private var nowMs = 1_000_000L
    private var activeTt: TT? = null
    private lateinit var tt: GarminTempTarget

    private val preparedId = 1_792_500_000_123L

    @BeforeEach
    fun setup() {
        tt = GarminTempTarget(aapsLogger, preferences, rh, batchExecutor, { activeTt }) { nowMs }
        whenever(preferences.get(GarminBooleanKey.TempTargetFromWatch)).thenReturn(true)
        // No presets saved: AAPS' default preset values are used.
        whenever(preferences.get(StringNonKey.TempTargetPresets)).thenReturn("")
        // doReturn: calling rh.gs() inside whenever() would hit the test base's strict text answer.
        doReturn("Temporary target").whenever(rh).gs(CoreUiStrings.temporary_target)
        runBlocking {
            whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(
                ActionProgress.Prepared(preparedId, listOf(ConfirmationLine(ConfirmationRole.TEMP_TARGET, "Activity 8.0 mmol/l 60 min")))
            )
            whenever(batchExecutor.commit(any(), any(), any(), any())).thenReturn(ActionProgress.Applied)
        }
    }

    private fun get(path: String): JsonObject = JsonParser.parseString(
        (if (path.startsWith("/tt/prepare")) tt.prepare(URI("http://foo$path")) else tt.commit(URI("http://foo$path"))).toString()
    ).asJsonObject

    private fun prepareActivity(): String {
        val jo = get("/tt/prepare?preset=activity")
        assertTrue(jo["ok"].asBoolean)
        return jo["id"].asString
    }

    @Test
    fun prepare_returnsIdAsTextAndAapsLines() {
        val jo = get("/tt/prepare?preset=activity")
        assertTrue(jo["ok"].asBoolean)
        assertEquals(preparedId.toString(), jo["id"].asString)
        assertEquals("Activity 8.0 mmol/l 60 min", jo["lines"].asJsonArray[0].asString)
        val captor = argumentCaptor<List<BatchAction>>()
        runBlocking { verify(batchExecutor).prepare(captor.capture(), eq(Sources.Garmin), eq("Temporary target")) }
        val action = captor.firstValue.single() as BatchAction.TempTarget
        assertEquals(TT.Reason.ACTIVITY.text, action.reason)
        assertTrue(action.durationMinutes > 0)
        assertEquals(action.lowMgdl, action.highMgdl)
    }

    @Test
    fun prepare_cancel_isTempTargetWithDurationZero() {
        activeTt = mock()
        get("/tt/prepare?preset=cancel")
        val captor = argumentCaptor<List<BatchAction>>()
        runBlocking { verify(batchExecutor).prepare(captor.capture(), any(), any()) }
        assertEquals(0, (captor.firstValue.single() as BatchAction.TempTarget).durationMinutes)
    }

    @Test
    fun prepare_cancel_noRunningTempTarget_refused() {
        val jo = get("/tt/prepare?preset=cancel")
        assertFalse(jo["ok"].asBoolean)
        assertEquals("No temp target running", jo["error"].asString)
        runBlocking { verify(batchExecutor, never()).prepare(any(), any(), any()) }
    }

    @Test
    fun prepare_unknownPreset_refused() {
        val jo = get("/tt/prepare?preset=foo")
        assertFalse(jo["ok"].asBoolean)
        runBlocking { verify(batchExecutor, never()).prepare(any(), any(), any()) }
    }

    @Test
    fun settingOff_refused() {
        whenever(preferences.get(GarminBooleanKey.TempTargetFromWatch)).thenReturn(false)
        assertFalse(get("/tt/prepare?preset=activity").get("ok").asBoolean)
        assertFalse(get("/tt/commit?id=$preparedId").get("ok").asBoolean)
        runBlocking {
            verify(batchExecutor, never()).prepare(any(), any(), any())
            verify(batchExecutor, never()).commit(any(), any(), any(), any())
        }
    }

    @Test
    fun commit_appliesOnce() {
        val id = prepareActivity()
        assertTrue(get("/tt/commit?id=$id")["ok"].asBoolean)
        assertFalse(get("/tt/commit?id=$id")["ok"].asBoolean)
        runBlocking { verify(batchExecutor, times(1)).commit(eq(preparedId), eq(Sources.Garmin), any(), any()) }
    }

    @Test
    fun commit_wrongOrMissingId_refused() {
        prepareActivity()
        assertFalse(get("/tt/commit?id=123")["ok"].asBoolean)
        assertFalse(get("/tt/commit?key=secret")["ok"].asBoolean)
        runBlocking { verify(batchExecutor, never()).commit(any(), any(), any(), any()) }
    }

    @Test
    fun commit_withoutPrepare_refused() {
        assertFalse(get("/tt/commit?id=$preparedId")["ok"].asBoolean)
        runBlocking { verify(batchExecutor, never()).commit(any(), any(), any(), any()) }
    }

    @Test
    fun commit_afterTimeLimit_refused() {
        val id = prepareActivity()
        nowMs += GarminTempTarget.PREPARED_TTL_MS + 1
        assertFalse(get("/tt/commit?id=$id")["ok"].asBoolean)
        runBlocking { verify(batchExecutor, never()).commit(any(), any(), any(), any()) }
    }

    @Test
    fun commit_atTimeLimit_applied() {
        val id = prepareActivity()
        nowMs += GarminTempTarget.PREPARED_TTL_MS
        assertTrue(get("/tt/commit?id=$id")["ok"].asBoolean)
    }

    @Test
    fun commit_olderIdAfterNewPrepare_refused() {
        val first = prepareActivity()
        runBlocking {
            whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Prepared(preparedId + 1))
        }
        get("/tt/prepare?preset=hypo")
        assertFalse(get("/tt/commit?id=$first")["ok"].asBoolean)
        runBlocking { verify(batchExecutor, never()).commit(any(), any(), any(), any()) }
    }

    @Test
    fun prepareRejected_errorWithDetail() {
        runBlocking {
            whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(ActionProgress.Rejected(FailureReason.ExecutionFailed, "Target out of range"))
        }
        val jo = get("/tt/prepare?preset=activity")
        assertFalse(jo["ok"].asBoolean)
        assertEquals("Target out of range", jo["error"].asString)
    }

    @Test
    fun commitUnconfirmed_saysCheckAaps() {
        runBlocking {
            whenever(batchExecutor.commit(any(), any(), any(), any())).thenReturn(ActionProgress.Unconfirmed(FailureReason.NoReply))
        }
        val id = prepareActivity()
        val jo = get("/tt/commit?id=$id")
        assertFalse(jo["ok"].asBoolean)
        assertEquals("Unknown - check AAPS", jo["error"].asString)
        assertTrue(jo["unknown"].asBoolean)
    }

    @Test
    fun commitException_isUnknown() {
        runBlocking { whenever(batchExecutor.commit(any(), any(), any(), any())).thenThrow(IllegalStateException("db")) }
        val id = prepareActivity()
        val jo = get("/tt/commit?id=$id")
        assertFalse(jo["ok"].asBoolean)
        assertTrue(jo["unknown"].asBoolean)
    }

    @Test
    fun commitRejected_isPlainRefusal() {
        runBlocking { whenever(batchExecutor.commit(any(), any(), any(), any())).thenReturn(ActionProgress.Rejected(FailureReason.ExecutionFailed, "Not allowed")) }
        val id = prepareActivity()
        val jo = get("/tt/commit?id=$id")
        assertEquals("Not allowed", jo["error"].asString)
        assertFalse(jo.has("unknown"))
    }
}
