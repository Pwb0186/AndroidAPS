package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.net.URI

class GarminExerciseTest : TestBaseWithProfile() {

    @Mock private lateinit var persistenceLayer: PersistenceLayer

    private val nowMs = 1_800_000_000_000L
    private val startSec = nowMs / 1000 - 600
    private var client = false
    private var refused = 0
    private var stored: Boolean = true
    private lateinit var exercise: GarminExercise

    @BeforeEach
    fun setup() {
        exercise = GarminExercise(aapsLogger, persistenceLayer, { GlucoseUnit.MMOL }, { client }, { refused++ }) { nowMs }
        runBlocking {
            whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), anyOrNull(), any())).thenAnswer { i ->
                PersistenceLayer.TransactionResult<TE>().apply { if (stored) inserted.add(i.getArgument(0)) }
            }
        }
    }

    private fun get(query: String): JsonObject =
        JsonParser.parseString(exercise.log(URI("http://foo/exercise?$query")).toString()).asJsonObject

    private fun assertRefused(query: String, error: String? = null) {
        val jo = get(query)
        assertFalse(jo["ok"].asBoolean)
        if (error != null) assertEquals(error, jo["error"].asString)
        runBlocking { verify(persistenceLayer, never()).insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), anyOrNull(), any()) }
    }

    private fun insertedEvent(): TE {
        val captor = argumentCaptor<TE>()
        runBlocking { verify(persistenceLayer).insertPumpTherapyEventIfNewByTimestamp(captor.capture(), any(), any(), any(), anyOrNull(), any()) }
        return captor.firstValue
    }

    @Test
    fun valid_isInsertedAsExerciseWithMillisecondsAndSource() {
        val jo = get("start=$startSec&duration=45&note=Run")
        assertTrue(jo["ok"].asBoolean)
        assertEquals("OK", jo["text"].asString)
        val te = insertedEvent()
        assertEquals(TE.Type.EXERCISE, te.type)
        assertEquals(startSec * 1000, te.timestamp)
        assertEquals(45 * 60_000L, te.duration)
        assertEquals("Run", te.note)
        assertEquals(GlucoseUnit.MMOL, te.glucoseUnit)
        val action = argumentCaptor<Action>()
        val source = argumentCaptor<Sources>()
        val note = argumentCaptor<String>()
        val values = argumentCaptor<List<ValueWithUnit>>()
        runBlocking {
            verify(persistenceLayer).insertPumpTherapyEventIfNewByTimestamp(any(), any(), action.capture(), source.capture(), note.capture(), values.capture())
        }
        assertEquals(Action.CAREPORTAL, action.firstValue)
        assertEquals(Sources.Garmin, source.firstValue)
        assertEquals("Run", note.firstValue)
        assertEquals(listOf(ValueWithUnit.Timestamp(startSec * 1000), ValueWithUnit.TEType(TE.Type.EXERCISE), ValueWithUnit.Minute(45)), values.firstValue)
    }

    @Test
    fun alreadyLogged_answersOkAndWritesNothingNew() {
        stored = false
        val jo = get("start=$startSec&duration=45&note=Run")
        assertTrue(jo["ok"].asBoolean)
        assertEquals("Already logged", jo["text"].asString)
        // One call only, and it is the "insert if new" one: nothing is updated.
        runBlocking {
            verify(persistenceLayer).insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), anyOrNull(), any())
            verify(persistenceLayer, never()).insertOrUpdateTherapyEvent(any())
        }
    }

    @Test
    fun start_futureWithin2MinutesIsAccepted() {
        assertTrue(get("start=${nowMs / 1000 + 120}&duration=10")["ok"].asBoolean)
    }

    @Test
    fun start_futureOver2MinutesIsRefused() {
        assertRefused("start=${nowMs / 1000 + 121}&duration=10", "Start is in the future")
    }

    @Test
    fun start_exactly24HoursAgoIsAccepted() {
        assertTrue(get("start=${nowMs / 1000 - 24 * 3600}&duration=10")["ok"].asBoolean)
    }

    @Test
    fun start_olderThan24HoursIsRefused() {
        assertRefused("start=${nowMs / 1000 - 24 * 3600 - 1}&duration=10", "Start is more than 24 h ago")
    }

    @Test
    fun start_missingOrInvalidIsRefused() {
        assertRefused("duration=10", "Missing start")
        assertRefused("start=abc&duration=10", "Missing start")
        assertRefused("start=-5&duration=10", "Invalid start")
        // Would wrap around to a valid time if it were multiplied by 1000 first.
        assertRefused("start=${Long.MAX_VALUE / 1000 + 1}&duration=10", "Invalid start")
    }

    @Test
    fun duration_limitsAre1To1080() {
        assertRefused("start=$startSec&duration=0", "Duration must be 1 to 1080 min")
        assertRefused("start=$startSec&duration=1081")
        assertRefused("start=$startSec&duration=-1")
        assertRefused("start=$startSec&duration=abc")
        assertRefused("start=$startSec", "Missing duration")
    }

    @Test
    fun duration_1And1080AreAccepted() {
        assertTrue(get("start=$startSec&duration=1")["ok"].asBoolean)
        assertTrue(get("start=${startSec - 1}&duration=1080")["ok"].asBoolean)
    }

    @Test
    fun note_longerThan60IsCut() {
        val long = "a".repeat(80)
        get("start=$startSec&duration=10&note=$long")
        assertEquals("a".repeat(60), insertedEvent().note)
    }

    @Test
    fun note_isTrimmedAndDecoded() {
        get("start=$startSec&duration=10&note=%20%20Long%20run%20in%20the%20park%20%20")
        assertEquals("Long run in the park", insertedEvent().note)
    }

    @Test
    fun note_emptyOrMissingIsAccepted() {
        assertTrue(get("start=$startSec&duration=10&note=")["ok"].asBoolean)
        assertNull(insertedEvent().note)
        assertTrue(get("start=${startSec - 1}&duration=10")["ok"].asBoolean)
    }

    @Test
    fun note_cutKeepsNoHalfEmoji() {
        // The emoji is two chars; a cut after its first char would leave a broken one.
        get("start=$startSec&duration=10&note=" + java.net.URLEncoder.encode("a".repeat(59) + "🏃", "UTF-8"))
        assertEquals("a".repeat(59), insertedEvent().note)
    }

    @Test
    fun client_refusesWithNotificationAndStoresNothing() {
        client = true
        assertRefused("start=$startSec&duration=45&note=Run", "Not on AAPSClient")
        assertEquals(1, refused)
        verifyNoInteractions(persistenceLayer)
    }

    @Test
    fun master_doesNotRefuseOnClientPath() {
        assertTrue(get("start=$startSec&duration=45")["ok"].asBoolean)
        assertEquals(0, refused)
    }

    @Test
    fun databaseError_answersError() {
        runBlocking {
            whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), anyOrNull(), any())).thenThrow(IllegalStateException("db"))
        }
        val jo = get("start=$startSec&duration=45")
        assertFalse(jo["ok"].asBoolean)
        assertEquals("Error", jo["error"].asString)
    }
}
