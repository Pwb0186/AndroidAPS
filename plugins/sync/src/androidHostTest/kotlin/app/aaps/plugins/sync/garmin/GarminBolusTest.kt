package app.aaps.plugins.sync.garmin

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.objects.wizard.QuickWizardMode
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.URI

class GarminBolusTest : TestBaseWithProfile() {

    @Mock private lateinit var wizardExecutor: WizardExecutor
    @Mock private lateinit var batchExecutor: BatchExecutor
    @Mock private lateinit var quickWizard: QuickWizard
    @Mock private lateinit var ads: AutosensDataStore
    @Mock private lateinit var garminProfileFunction: ProfileFunction
    @Mock private lateinit var garminPersistenceLayer: PersistenceLayer
    @Mock private lateinit var garminCommandQueue: CommandQueue
    @Mock private lateinit var garminActivePlugin: ActivePlugin
    @Mock private lateinit var pump: PumpWithConcentration

    private lateinit var bolus: GarminBolus
    private var nowMs = 1_000_000L
    private val preparedId = 1_792_500_000_456L

    @BeforeEach
    fun setup() {
        bolus = GarminBolus(
            aapsLogger, preferences, rh, wizardExecutor, batchExecutor, quickWizard, iobCobCalculator,
            garminProfileFunction, garminPersistenceLayer, garminCommandQueue, garminActivePlugin
        )
        bolus.now = { nowMs }
        bolus.secondsFromMidnight = { 12 * 3600 }
        whenever(preferences.get(GarminBooleanKey.BolusFromWatch)).thenReturn(true)
        whenever(preferences.get(IntKey.OverviewBolusPercentage)).thenReturn(100)
        whenever(preferences.get(BooleanKey.WearWizardBg)).thenReturn(true)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(timestamp = nowMs, value = 120.0))
        whenever(garminProfileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(garminActivePlugin.activePump).thenReturn(pump)
        // doReturn: calling rh.gs() inside whenever() would hit the test base's strict text answer.
        doReturn("Bolus wizard").whenever(rh).gs(CoreUiStrings.boluswizard)
        doReturn("Treatment").whenever(rh).gs(CoreUiStrings.overview_treatment_label)
        val prepared = ActionProgress.Prepared(preparedId, listOf(ConfirmationLine(ConfirmationRole.BOLUS, "Insulin: 2.00 U")))
        runBlocking {
            whenever(wizardExecutor.prepare(any(), any())).thenReturn(prepared)
            whenever(batchExecutor.prepare(any(), any(), any())).thenReturn(prepared)
            whenever(wizardExecutor.commit(any(), any(), any(), any(), any())).thenReturn(ActionProgress.Applied)
            whenever(batchExecutor.commit(any(), any(), any(), any())).thenReturn(ActionProgress.Applied)
            whenever(garminPersistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)).thenReturn(null)
        }
    }

    private fun json(s: CharSequence): JsonObject = JsonParser.parseString(s.toString()).asJsonObject
    private fun uri(q: String) = URI("http://foo/x?$q")

    private fun qwEntry(guid: String, mode: QuickWizardMode, device: Int = QuickWizardEntry.DEVICE_WATCH, from: Int = 0, to: Int = 86399): QuickWizardEntry =
        mock<QuickWizardEntry>().also {
            whenever(it.guid()).thenReturn(guid)
            whenever(it.buttonText()).thenReturn("Btn $guid")
            whenever(it.mode()).thenReturn(mode)
            whenever(it.forDevice(any())).thenAnswer { i -> i.getArgument<Int>(0) == device || device == QuickWizardEntry.DEVICE_ALL }
            whenever(it.validFrom()).thenReturn(from)
            whenever(it.validTo()).thenReturn(to)
            whenever(it.insulin()).thenReturn(1.5)
            whenever(it.carbs()).thenReturn(30)
            whenever(quickWizard.get(guid)).thenReturn(it)
        }

    @Test
    fun settingOff_everythingButStopRefused() {
        whenever(preferences.get(GarminBooleanKey.BolusFromWatch)).thenReturn(false)
        assertFalse(json(bolus.prepareWizard(uri("carbs=20")))["ok"].asBoolean)
        assertFalse(json(bolus.quickWizardList(uri("")))["ok"].asBoolean)
        assertFalse(json(bolus.prepareQuickWizard(uri("guid=a")))["ok"].asBoolean)
        assertFalse(json(bolus.commit(uri("id=$preparedId")))["ok"].asBoolean)
        assertTrue(json(bolus.stop(uri("")))["ok"].asBoolean)
        runBlocking { verify(wizardExecutor, never()).prepare(any(), any()) }
    }

    @Test
    fun wizard_aapsComputesWithItsOwnBg_watchSendsOnlyCarbs() {
        val jo = json(bolus.prepareWizard(uri("carbs=40")))
        assertTrue(jo["ok"].asBoolean)
        assertEquals(preparedId.toString(), jo["id"].asString)
        assertEquals("Insulin: 2.00 U", jo["lines"].asJsonArray[0].asString)
        val captor = argumentCaptor<WizardExecutor.WizardSource>()
        runBlocking { verify(wizardExecutor).prepare(captor.capture(), any()) }
        val inputs = (captor.firstValue as WizardExecutor.WizardSource.Manual).inputs
        assertEquals(40, inputs.carbs)
        assertEquals(120.0, inputs.bg)
        assertEquals(100, inputs.percentage)
        assertEquals(Sources.Garmin, inputs.source)
    }

    @Test
    fun wizard_noRecentBg_refused() {
        whenever(ads.actualBg()).thenReturn(null)
        doReturn("No recent BG").whenever(rh).gs(CoreUiStrings.wizard_no_actual_bg)
        val jo = json(bolus.prepareWizard(uri("carbs=40")))
        assertFalse(jo["ok"].asBoolean)
        runBlocking { verify(wizardExecutor, never()).prepare(any(), any()) }
    }

    @Test
    fun wizard_missingOrNegativeCarbs_refused() {
        assertFalse(json(bolus.prepareWizard(uri("")))["ok"].asBoolean)
        assertFalse(json(bolus.prepareWizard(uri("carbs=-5")))["ok"].asBoolean)
    }

    @Test
    fun commit_wizardDoseGivenOnce() {
        bolus.prepareWizard(uri("carbs=40"))
        assertTrue(json(bolus.commit(uri("id=$preparedId")))["ok"].asBoolean)
        assertFalse(json(bolus.commit(uri("id=$preparedId")))["ok"].asBoolean)
        runBlocking { verify(wizardExecutor, times(1)).commit(eq(preparedId), eq(false), eq(Sources.Garmin), any(), any()) }
    }

    @Test
    fun commit_afterTimeLimit_refused() {
        bolus.prepareWizard(uri("carbs=40"))
        nowMs += GarminBolus.PREPARED_TTL_MS + 1
        assertFalse(json(bolus.commit(uri("id=$preparedId")))["ok"].asBoolean)
        runBlocking { verify(wizardExecutor, never()).commit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun commit_otherBolusSincePrepare_refused() {
        bolus.prepareWizard(uri("carbs=40"))
        val other = mock<BS>()
        whenever(other.timestamp).thenReturn(nowMs + 1)
        runBlocking { whenever(garminPersistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)).thenReturn(other) }
        val jo = json(bolus.commit(uri("id=$preparedId")))
        assertFalse(jo["ok"].asBoolean)
        assertEquals("Another bolus was given, choose again", jo["error"].asString)
        runBlocking { verify(wizardExecutor, never()).commit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun commit_wrongOrMissingId_refused() {
        bolus.prepareWizard(uri("carbs=40"))
        assertFalse(json(bolus.commit(uri("id=1")))["ok"].asBoolean)
        assertFalse(json(bolus.commit(uri("")))["ok"].asBoolean)
        runBlocking { verify(wizardExecutor, never()).commit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun commit_unconfirmed_saysDoNotRepeat() {
        runBlocking {
            whenever(wizardExecutor.commit(any(), any(), any(), any(), any())).thenReturn(ActionProgress.Unconfirmed(FailureReason.NoReply))
        }
        bolus.prepareWizard(uri("carbs=40"))
        assertEquals("Unknown - do NOT repeat, check AAPS", json(bolus.commit(uri("id=$preparedId")))["error"].asString)
    }

    @Test
    fun prepareRejected_errorWithDetail() {
        runBlocking { whenever(wizardExecutor.prepare(any(), any())).thenReturn(ActionProgress.Rejected(FailureReason.BolusComputeFailed, "No insulin required")) }
        val jo = json(bolus.prepareWizard(uri("carbs=0")))
        assertFalse(jo["ok"].asBoolean)
        assertEquals("No insulin required", jo["error"].asString)
    }

    @Test
    fun quickWizardList_onlyWatchButtonsInTheirTimeWindow() {
        val now = qwEntry("now", QuickWizardMode.WIZARD)
        val phoneOnly = qwEntry("phone", QuickWizardMode.WIZARD, device = QuickWizardEntry.DEVICE_PHONE)
        val later = qwEntry("later", QuickWizardMode.WIZARD, from = 18 * 3600, to = 20 * 3600)
        val overMidnight = qwEntry("night", QuickWizardMode.WIZARD, from = 22 * 3600, to = 13 * 3600)
        whenever(quickWizard.list()).thenReturn(arrayListOf(now, phoneOnly, later, overMidnight))
        val items = json(bolus.quickWizardList(uri("")))["items"].asJsonArray.map { it.asJsonObject["guid"].asString }
        assertEquals(listOf("now", "night"), items)
    }

    @Test
    fun quickWizard_wizardButton_recomputedByAaps() {
        qwEntry("w", QuickWizardMode.WIZARD)
        assertTrue(json(bolus.prepareQuickWizard(uri("guid=w")))["ok"].asBoolean)
        runBlocking { verify(wizardExecutor).prepare(eq(WizardExecutor.WizardSource.QuickWizard("w")), any()) }
        bolus.commit(uri("id=$preparedId"))
        runBlocking { verify(wizardExecutor).commit(eq(preparedId), eq(false), eq(Sources.Garmin), any(), any()) }
    }

    @Test
    fun quickWizard_insulinButton_givenAsIt_Is() {
        qwEntry("i", QuickWizardMode.INSULIN)
        bolus.prepareQuickWizard(uri("guid=i"))
        val captor = argumentCaptor<List<BatchAction>>()
        runBlocking { verify(batchExecutor).prepare(captor.capture(), eq(Sources.Garmin), any()) }
        val b = captor.firstValue.single() as BatchAction.Bolus
        assertEquals(1.5, b.insulin)
        assertEquals(0, b.carbs)
        assertEquals("i", b.quickWizardGuid)
        bolus.commit(uri("id=$preparedId"))
        runBlocking { verify(batchExecutor).commit(eq(preparedId), eq(Sources.Garmin), any(), any()) }
    }

    @Test
    fun quickWizard_carbsButton_noInsulin() {
        qwEntry("c", QuickWizardMode.CARBS)
        bolus.prepareQuickWizard(uri("guid=c"))
        val captor = argumentCaptor<List<BatchAction>>()
        runBlocking { verify(batchExecutor).prepare(captor.capture(), any(), any()) }
        val b = captor.firstValue.single() as BatchAction.Bolus
        assertEquals(0.0, b.insulin)
        assertEquals(30, b.carbs)
    }

    @Test
    fun quickWizard_notForWatchOrOutsideWindowOrUnknown_refused() {
        qwEntry("p", QuickWizardMode.WIZARD, device = QuickWizardEntry.DEVICE_PHONE)
        qwEntry("l", QuickWizardMode.WIZARD, from = 18 * 3600, to = 20 * 3600)
        assertFalse(json(bolus.prepareQuickWizard(uri("guid=p")))["ok"].asBoolean)
        assertFalse(json(bolus.prepareQuickWizard(uri("guid=l")))["ok"].asBoolean)
        assertFalse(json(bolus.prepareQuickWizard(uri("guid=unknown")))["ok"].asBoolean)
        runBlocking { verify(wizardExecutor, never()).prepare(any(), any()) }
    }

    @Test
    fun stop_cancelsQueuedAndRunningBolus() {
        whenever(garminCommandQueue.bolusInQueue()).thenReturn(true)
        assertEquals("Bolus stopped", json(bolus.stop(uri("")))["text"].asString)
        verify(garminCommandQueue).cancelAllBoluses(anyOrNull())
        verify(pump).stopBolusDelivering()
    }

    @Test
    fun stop_noBolus_saysSo() {
        whenever(garminCommandQueue.bolusInQueue()).thenReturn(false)
        whenever(garminCommandQueue.isRunning(Command.CommandType.BOLUS)).thenReturn(false)
        assertEquals("No bolus running", json(bolus.stop(uri("")))["text"].asString)
        verify(pump).stopBolusDelivering()
    }

    @Test
    fun status_bolusRunningTtAndWatchButtons_evenWithSettingOff() {
        whenever(preferences.get(GarminBooleanKey.BolusFromWatch)).thenReturn(false)
        val now = qwEntry("now", QuickWizardMode.WIZARD)
        val phoneOnly = qwEntry("phone", QuickWizardMode.WIZARD, device = QuickWizardEntry.DEVICE_PHONE)
        whenever(quickWizard.list()).thenReturn(arrayListOf(now, phoneOnly))
        whenever(garminCommandQueue.bolusInQueue()).thenReturn(false)
        whenever(garminCommandQueue.isRunning(Command.CommandType.BOLUS)).thenReturn(true)
        val jo = json(bolus.status(ttActive = true))
        assertTrue(jo["ok"].asBoolean)
        assertTrue(jo["bolusRunning"].asBoolean)
        assertTrue(jo["ttActive"].asBoolean)
        assertEquals(1, jo["qwCount"].asInt)
    }

    @Test
    fun status_nothingRunning() {
        whenever(quickWizard.list()).thenReturn(arrayListOf())
        val jo = json(bolus.status(ttActive = false))
        assertFalse(jo["bolusRunning"].asBoolean)
        assertFalse(jo["ttActive"].asBoolean)
        assertEquals(0, jo["qwCount"].asInt)
    }
}
