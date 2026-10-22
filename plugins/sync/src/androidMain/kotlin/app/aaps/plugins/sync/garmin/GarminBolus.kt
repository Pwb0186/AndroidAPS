package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.BS
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.bolus.WizardBolusExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.objects.wizard.QuickWizardMode
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.LocalTime

/**
 * Bolus wizard, Quick Wizard and stop bolus from a Garmin watch app.
 *
 * Same two steps as the temp target and as the Wear OS watch, through the same AAPS code
 * ([WizardExecutor], [BatchExecutor]): /wizard/prepare and /qw/prepare let AAPS compute the dose
 * itself (BG, IOB, COB, limits) and park it; the watch only shows AAPS' text, and /bolus/commit?id=
 * gives the parked dose once, after the user confirmed it on the watch. The watch never sends an
 * insulin amount.
 *
 * Because this doses insulin it is stricter than the Wear watch: it needs "Bolus from Garmin watch"
 * (off by default) next to the AAPS key the request handler checks, a prepared dose can only be given
 * for [PREPARED_TTL_MS], and not when another bolus was given after it was prepared.
 *
 * /bolus/stop only needs the key: stopping a bolus is a safety action, as on the Wear watch.
 * /status only reads, so the watch can show the menu items that can be used and close its stop
 * screen when the bolus is done. /wizard/prepare&type= splits the carbs by meal type like the AAPS
 * bolus wizard.
 */
@SingleIn(AppScope::class)
@Inject
class GarminBolus(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val wizardExecutor: WizardExecutor,
    private val batchExecutor: BatchExecutor,
    private val quickWizard: QuickWizard,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction,
    private val persistenceLayer: PersistenceLayer,
    private val commandQueue: CommandQueue,
    private val activePlugin: ActivePlugin,
) {

    companion object {

        /** How long a prepared dose can be given. The watch closes its confirmation sooner. */
        @VisibleForTesting
        const val PREPARED_TTL_MS = 60_000L
    }

    /**
     * The meal types of the AAPS bolus wizard, with its numbers (they live in the ui module, which
     * this plugin does not use).
     */
    @VisibleForTesting
    enum class MealType(val param: String, val carbsPercent: Int, val eCarbsPercent: Int, val eCarbsDelayMinutes: Int, val eCarbsDurationHours: Int) {
        BREAD("bread", 100, 0, 0, 0),
        CAKE("cake", 90, 20, 60, 2),
        PIZZA("pizza", 80, 35, 60, 3)
    }

    @VisibleForTesting
    var now: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    var secondsFromMidnight: () -> Int = { LocalTime.now().toSecondOfDay() }

    /** A parked dose: commit it through the executor that prepared it. */
    private class Prepared(val id: Long, val atMs: Long, val byWizard: Boolean)

    private val lock = Any()
    private var prepared: Prepared? = null

    fun prepareWizard(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val carbs = uri.queryParameter("carbs")?.toIntOrNull()?.takeIf { it >= 0 } ?: return error("Missing carbs")
        val meal = MealType.entries.firstOrNull { it.param == (uri.queryParameter("type") ?: "bread") }
            ?: return error("Unknown meal type")
        val bg = iobCobCalculator.ads.actualBg() ?: return error(rh.gs(CoreUiStrings.wizard_no_actual_bg))
        // Split as the AAPS bolus wizard does: part of the carbs now, part as eCarbs later.
        val eCarbs = carbs * meal.eCarbsPercent / 100
        // The same choices as the Wear watch's wizard (Wear settings), so the two watches compute alike.
        val inputs = WizardBolusExecutor.WizardInputs(
            bg = bg.valueToUnits(profileFunction.getUnits()),
            carbs = carbs * meal.carbsPercent / 100,
            percentage = preferences.get(IntKey.OverviewBolusPercentage),
            directCorrection = 0.0,
            carbTime = 0,
            useBg = preferences.get(BooleanKey.WearWizardBg),
            useCob = preferences.get(BooleanKey.WearWizardCob),
            useIob = preferences.get(BooleanKey.WearWizardIob),
            useTt = preferences.get(BooleanKey.WearWizardTt),
            useTrend = preferences.get(BooleanKey.WearWizardTrend),
            alarm = false,
            notes = "",
            eCarbsGrams = eCarbs,
            eCarbsDelayMinutes = if (eCarbs > 0) meal.eCarbsDelayMinutes else 0,
            eCarbsDurationHours = if (eCarbs > 0) meal.eCarbsDurationHours else 0,
            source = Sources.Garmin
        )
        val progress = runBlocking { wizardExecutor.prepare(WizardExecutor.WizardSource.Manual(inputs), wizardLabel()) }
        return park(progress, byWizard = true, what = "wizard $carbs g ${meal.param}")
    }

    /** The Quick Wizard buttons set to show on the watch whose time window is now. */
    fun quickWizardList(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val items = JsonArray()
        watchButtons().forEach {
            items.add(JsonObject().apply {
                addProperty("guid", it.guid())
                addProperty("name", it.buttonText())
            })
        }
        return JsonObject().apply {
            addProperty("ok", true)
            add("items", items)
        }.toString()
    }

    fun prepareQuickWizard(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val guid = uri.queryParameter("guid") ?: return error("Missing guid")
        val entry = quickWizard.get(guid)
        if (entry == null || !entry.forDevice(QuickWizardEntry.DEVICE_WATCH) || !inTimeWindow(entry))
            return error("Quick Wizard not available now")
        // Like the phone and the Wear watch: a fixed insulin or carbs button is given as it is, a
        // wizard button is computed again by AAPS.
        val progress = runBlocking {
            when (entry.mode()) {
                QuickWizardMode.INSULIN -> batchExecutor.prepare(listOf(fixed(entry, insulin = entry.insulin(), carbs = 0)), Sources.Garmin, treatmentLabel())
                QuickWizardMode.CARBS   -> batchExecutor.prepare(listOf(fixed(entry, insulin = 0.0, carbs = entry.carbs())), Sources.Garmin, treatmentLabel())
                else                    -> wizardExecutor.prepare(WizardExecutor.WizardSource.QuickWizard(guid), wizardLabel())
            }
        }
        val byWizard = entry.mode() != QuickWizardMode.INSULIN && entry.mode() != QuickWizardMode.CARBS
        return park(progress, byWizard, what = "quick wizard ${entry.buttonText()}")
    }

    fun commit(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val id = uri.queryParameter("id")?.toLongOrNull() ?: return error("Missing id")
        // Taken out under the lock, so an id can only be given once.
        val p = synchronized(lock) { prepared?.takeIf { it.id == id }?.also { prepared = null } }
            ?: return error("Not prepared or already used")
        if (now() - p.atMs > PREPARED_TTL_MS) return error("Expired, choose again")
        // A bolus given after the dose was computed (from the phone or another watch) is not in it.
        val newest = runBlocking { persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL) }
        if (newest != null && newest.timestamp > p.atMs) return error("Another bolus was given, choose again")
        val progress = runBlocking {
            if (p.byWizard) wizardExecutor.commit(id, asAdvisor = false, Sources.Garmin, wizardLabel())
            else batchExecutor.commit(id, Sources.Garmin, treatmentLabel())
        }
        aapsLogger.info(LTag.GARMIN, "bolus id $id committed: $progress")
        if (progress !is ActionProgress.Applied) return error(failure(progress))
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", "OK")
        }.toString()
    }

    /** Stops a bolus that is queued or running. Only the key is needed: stopping is a safety action. */
    fun stop(uri: URI): CharSequence {
        // A bolus still in the AAPS queue has not reached the pump yet, so stopping the pump alone
        // would let it start a moment later. Both are done.
        val running = commandQueue.bolusInQueue() || commandQueue.isRunning(Command.CommandType.BOLUS)
        commandQueue.cancelAllBoluses(null)
        activePlugin.activePump.stopBolusDelivering()
        aapsLogger.info(LTag.GARMIN, "stop bolus from the watch, bolus was running: $running")
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", if (running) "Bolus stopped" else "No bolus running")
        }.toString()
    }

    /**
     * What the watch needs to build its menu and to know when a bolus is done. Not behind the bolus
     * setting: it changes nothing, and the stop screen needs it like stop itself.
     */
    fun status(ttActive: Boolean): JsonObject = JsonObject().apply {
        addProperty("ok", true)
        addProperty("bolusRunning", commandQueue.bolusInQueue() || commandQueue.isRunning(Command.CommandType.BOLUS))
        addProperty("ttActive", ttActive)
        addProperty("qwCount", watchButtons().size)
    }

    private fun watchButtons() = quickWizard.list().filter { it.forDevice(QuickWizardEntry.DEVICE_WATCH) && inTimeWindow(it) }

    private fun park(progress: ActionProgress, byWizard: Boolean, what: String): CharSequence {
        if (progress !is ActionProgress.Prepared) return error(failure(progress))
        synchronized(lock) { prepared = Prepared(progress.id, now(), byWizard) }
        aapsLogger.info(LTag.GARMIN, "$what prepared, id ${progress.id}")
        val lines = JsonArray().apply { progress.lines.forEach { add(it.text) } }
        return JsonObject().apply {
            addProperty("ok", true)
            // As text: the id does not fit in a number on the watch (32 bit).
            addProperty("id", progress.id.toString())
            add("lines", lines)
        }.toString()
    }

    private fun fixed(entry: QuickWizardEntry, insulin: Double, carbs: Int) = BatchAction.Bolus(
        insulin = insulin, carbs = carbs, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0,
        recordOnly = false, notes = entry.buttonText(), timestamp = 0L, iCfg = null,
        quickWizardGuid = entry.guid() // AAPS marks the button used when it is given
    )

    /** The button's time window, which may wrap midnight. */
    private fun inTimeWindow(entry: QuickWizardEntry): Boolean {
        val s = secondsFromMidnight()
        return if (entry.validTo() >= entry.validFrom()) s in entry.validFrom()..entry.validTo()
        else s >= entry.validFrom() || s <= entry.validTo()
    }

    private fun wizardLabel() = rh.gs(CoreUiStrings.boluswizard)
    private fun treatmentLabel() = rh.gs(CoreUiStrings.overview_treatment_label)

    private fun refusal(): String? =
        if (!preferences.get(GarminBooleanKey.BolusFromWatch)) "Bolus from Garmin is off in AAPS" else null

    private fun failure(progress: ActionProgress): String = when (progress) {
        // The dose may or may not be given: say so, so it is not given again blindly.
        is ActionProgress.Unconfirmed -> "Unknown - do NOT repeat, check AAPS"
        is ActionProgress.Rejected    -> progress.detail ?: rh.gs(progress.reason.failText())
        else                          -> "Error"
    }

    private fun error(text: String): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "bolus refused: $text")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", text)
        }.toString()
    }
}
