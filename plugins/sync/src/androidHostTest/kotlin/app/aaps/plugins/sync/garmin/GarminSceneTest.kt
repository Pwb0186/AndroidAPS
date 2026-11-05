package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.Scene
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.scenes.ActiveSceneSync
import app.aaps.core.interfaces.scenes.SceneActions
import app.aaps.core.interfaces.scenes.SceneAutomationApi
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.URI

class GarminSceneTest : TestBaseWithProfile() {

    @Mock private lateinit var sceneApi: SceneAutomationApi
    @Mock private lateinit var sceneActions: SceneActions
    @Mock private lateinit var activeSceneSync: ActiveSceneSync

    private lateinit var garminScene: GarminScene
    private var nowMs = 1_000_000L
    private val preparedId = 1_792_500_000_789L
    private val run = Scene(id = "run", name = "Run")
    private val off = Scene(id = "off", name = "Off", isEnabled = false)

    @BeforeEach
    fun setup() {
        garminScene = GarminScene(aapsLogger, rh, sceneApi, sceneActions, activeSceneSync)
        garminScene.now = { nowMs }
        whenever(sceneApi.getScenes()).thenReturn(listOf(run, off))
        runBlocking {
            whenever(sceneActions.prepareStart(any(), anyOrNull()))
                .thenReturn(ActionProgress.Prepared(preparedId, listOf(ConfirmationLine(ConfirmationRole.SCENE, "Run"))))
            whenever(sceneActions.commitStart(any())).thenReturn(ActionProgress.Applied)
            whenever(sceneActions.stop(any())).thenReturn(ActionProgress.Applied)
        }
    }

    private fun json(s: CharSequence): JsonObject = JsonParser.parseString(s.toString()).asJsonObject
    private fun uri(q: String) = URI("http://foo/x?$q")

    @Test
    fun list_onlyEnabledScenes() {
        val items = json(garminScene.list(uri("")))["items"].asJsonArray
        assertEquals(1, items.size())
        assertEquals("run", items[0].asJsonObject["id"].asString)
        assertEquals("Run", items[0].asJsonObject["name"].asString)
    }

    @Test
    fun prepareAndCommit_once() {
        val jo = json(garminScene.prepare(uri("id=run")))
        assertEquals(preparedId.toString(), jo["id"].asString)
        assertEquals("Run", jo["lines"].asJsonArray[0].asString)
        assertTrue(json(garminScene.commit(uri("id=$preparedId")))["ok"].asBoolean)
        assertFalse(json(garminScene.commit(uri("id=$preparedId")))["ok"].asBoolean)
        runBlocking { verify(sceneActions, times(1)).commitStart(preparedId) }
    }

    @Test
    fun commit_exceptionOrUnconfirmed_isUnknown() {
        runBlocking { whenever(sceneActions.commitStart(any())).thenThrow(IllegalStateException("db")) }
        garminScene.prepare(uri("id=run"))
        assertTrue(json(garminScene.commit(uri("id=$preparedId")))["unknown"].asBoolean)
        // doReturn: whenever(commitStart()) would call the stub above, which throws.
        runBlocking { doReturn(ActionProgress.Unconfirmed(FailureReason.NoReply)).whenever(sceneActions).commitStart(any()) }
        garminScene.prepare(uri("id=run"))
        assertTrue(json(garminScene.commit(uri("id=$preparedId")))["unknown"].asBoolean)
    }

    @Test
    fun commit_afterTimeLimit_refused() {
        garminScene.prepare(uri("id=run"))
        nowMs += GarminScene.PREPARED_TTL_MS + 1
        assertFalse(json(garminScene.commit(uri("id=$preparedId")))["ok"].asBoolean)
        runBlocking { verify(sceneActions, never()).commitStart(any()) }
    }

    @Test
    fun prepareRejected_errorWithDetail() {
        runBlocking { whenever(sceneActions.prepareStart(any(), anyOrNull())).thenReturn(ActionProgress.Rejected(FailureReason.SceneDisabled, "Scene disabled")) }
        assertEquals("Scene disabled", json(garminScene.prepare(uri("id=run")))["error"].asString)
    }

    @Test
    fun stop_endsWithoutFollowUp_orSaysNoneRunning() {
        whenever(sceneApi.hasSceneToStop()).thenReturn(false)
        assertEquals("No scene running", json(garminScene.stop(uri("")))["error"].asString)
        whenever(sceneApi.hasSceneToStop()).thenReturn(true)
        assertEquals("Scene ended", json(garminScene.stop(uri("")))["text"].asString)
        runBlocking { verify(sceneActions, times(1)).stop(false) }
    }

    @Test
    fun status_activeSceneAndCount() {
        whenever(sceneApi.hasSceneToStop()).thenReturn(true)
        val state = mock<ActiveSceneState>()
        whenever(state.scene).thenReturn(run)
        whenever(activeSceneSync.getActiveState()).thenReturn(state)
        val jo = JsonObject().also { garminScene.addStatus(it) }
        assertTrue(jo["sceneActive"].asBoolean)
        assertEquals("Run", jo["sceneName"].asString)
        assertEquals(1, jo["sceneCount"].asInt)
    }

    @Test
    fun status_noScene_noName() {
        whenever(sceneApi.hasSceneToStop()).thenReturn(false)
        val jo = JsonObject().also { garminScene.addStatus(it) }
        assertFalse(jo["sceneActive"].asBoolean)
        assertFalse(jo.has("sceneName"))
    }
}
