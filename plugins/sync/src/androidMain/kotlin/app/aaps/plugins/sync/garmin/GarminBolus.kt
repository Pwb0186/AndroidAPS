package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.BS
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.BolusProgressData
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
 * Because this doses insulin it is stricter than the Wear watch: it needs "Carbs and bolus from Garmin
 * watch" (off by default) next to the control key the request handler checks, a prepared dose can only
 * be given for [PREPARED_TTL_MS], and not when another bolus was given after AAPS began computing it.
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
    private val config: Config,
    private val constraintChecker: ConstraintsChecker,
    private val activePlugin: ActivePlugin,
    private val bolusProgressData: BolusProgressData,
) {

    companion object {

        /**
         * How long a prepared dose can be given. Shorter than for a temp target or a scene (120 s),
         * because the dose was computed from BG, IOB and COB at that moment. The watch closes its
         * confirmation sooner.
         */
        @VisibleForTesting
        const val PREPARED_TTL_MS = 60_000L

        /** More than any meal; keeps the carb split below from overflowing before AAPS' own limit. */
        private const val MAX_CARBS_FROM_WATCH = 1000

        private const val UNKNOWN = "Unknown - do NOT repeat, check AAPS"
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
        // Before AAPS reads BG, IOB and boluses: a bolus stored while it computes is newer than this.
        val startMs = now()
        refusal()?.let { return error(it) }
        busy()?.let { return error(it) }
        val carbs = uri.queryParameter("carbs")?.toIntOrNull()?.takeIf { it >= 0 } ?: return error("Missing carbs")
        if (carbs > MAX_CARBS_FROM_WATCH) return error("Too many carbs")
        val meal = MealType.entries.firstOrNull { it.param == (uri.queryParameter("type") ?: "bread") }
            ?: return error("Unknown meal type")
        // Same limits as the AAPS bolus wizard.
        val carbTime = uri.queryParameter("carbTime")?.let { it.toIntOrNull()?.takeIf { m -> m in -60..60 } ?: return error("Carb time must be -60 to 60 min") } ?: 0
        val percentage = uri.queryParameter("percent")?.let { it.toIntOrNull()?.takeIf { p -> p in 10..200 } ?: return error("Percentage must be 10 to 200") }
            ?: preferences.get(IntKey.OverviewBolusPercentage)
        val bg = iobCobCalculator.ads.actualBg() ?: return error(rh.gs(CoreUiStrings.wizard_no_actual_bg))
        // Split as the AAPS bolus wizard does: part of the carbs now, part as eCarbs later.
        val eCarbs = carbs * meal.eCarbsPercent / 100
        // The same choices as the Wear watch's wizard (Wear settings), so the two watches compute alike.
        val inputs = MasterActionExecutor.WizardInputs(
            bg = bg.valueToUnits(profileFunction.getUnits()),
            carbs = carbs * meal.carbsPercent / 100,
            percentage = percentage,
            directCorrection = 0.0,
            carbTime = carbTime,
            useBg = preferences.get(BooleanKey.WearWizardBg),
            useCob = preferences.get(BooleanKey.WearWizardCob),
            useIob = preferences.get(BooleanKey.WearWizardIob),
            useTt = preferences.get(BooleanKey.WearWizardTt),
            useTrend = preferences.get(BooleanKey.WearWizardTrend),
            alarm = false,
            notes = "",
            eCarbsGrams = eCarbs,
            // The eCarbs start after the meal, so they move with the carb time, as in the AAPS bolus wizard.
            eCarbsDelayMinutes = if (eCarbs > 0) meal.eCarbsDelayMinutes + carbTime else 0,
            eCarbsDurationHours = if (eCarbs > 0) meal.eCarbsDurationHours else 0,
            source = Sources.Garmin
        )
        val progress = runBlocking { wizardExecutor.prepare(WizardExecutor.WizardSource.Manual(inputs), wizardLabel()) }
        return park(progress, startMs, byWizard = true, what = "wizard $carbs g ${meal.param} $carbTime min $percentage %", insulin = wizardInsulin(progress))
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
        // Before AAPS reads BG, IOB and boluses: a bolus stored while it computes is newer than this.
        val startMs = now()
        refusal()?.let { return error(it) }
        busy()?.let { return error(it) }
        val guid = uri.queryParameter("guid") ?: return error("Missing guid")
        val entry = quickWizard.get(guid)
        if (entry == null || !entry.forDevice(QuickWizardEntry.DEVICE_WATCH) || !inTimeWindow(entry))
            return error("Quick Wizard not available now")
        if (coolingDown(entry)) return error("Quick Wizard button used less than 30 min ago")
        // Like the phone and the Wear watch: a fixed insulin or carbs button is given as it is, a
        // wizard button is computed again by AAPS.
        val progress = runBlocking {
            when (entry.mode()) {
                QuickWizardMode.INSULIN -> batchExecutor.prepare(listOf(fixed(entry, insulin = entry.insulin(), carbs = 0)), Sources.Garmin, treatmentLabel())
                QuickWizardMode.CARBS   -> batchExecutor.prepare(listOf(fixedCarbs(entry)), Sources.Garmin, treatmentLabel())
                else                    -> wizardExecutor.prepare(WizardExecutor.WizardSource.QuickWizard(guid), wizardLabel())
            }
        }
        val byWizard = entry.mode() != QuickWizardMode.INSULIN && entry.mode() != QuickWizardMode.CARBS
        val insulin = when (entry.mode()) {
            QuickWizardMode.INSULIN -> entry.insulin()
            QuickWizardMode.CARBS   -> 0.0
            else                    -> wizardInsulin(progress)
        }
        return park(progress, startMs, byWizard, what = "quick wizard ${entry.buttonText()}", insulin = insulin)
    }

    /**
     * Insulin and/or carbs chosen on the watch, like the treatment dialog on the phone and the Wear
     * watch. AAPS rounds the insulin to the pump's step and limits both to max bolus and max carbs;
     * the confirmation shows what will be given.
     */
    fun prepareTreatment(uri: URI): CharSequence {
        // Before AAPS reads BG, IOB and boluses: a bolus stored while it computes is newer than this.
        val startMs = now()
        val insulin = uri.queryParameter("insulin")?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0
        val carbs = uri.queryParameter("carbs")?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        if (insulin == 0.0 && carbs == 0) return error("Choose insulin or carbs")
        refusal()?.let { return error(it) }
        busy()?.let { return error(it) }
        if (carbs > MAX_CARBS_FROM_WATCH) return error("Too many carbs")
        val action = BatchAction.Bolus(
            insulin = insulin, carbs = carbs, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0,
            recordOnly = false, notes = "", timestamp = 0L, iCfg = null
        )
        val progress = runBlocking { batchExecutor.prepare(listOf(action), Sources.Garmin, treatmentLabel()) }
        return park(progress, startMs, byWizard = false, what = "treatment $insulin U $carbs g", insulin = insulin)
    }

    fun commit(uri: URI): CharSequence {
        if (config.AAPSCLIENT) return error("Not on AAPSClient")
        val id = uri.queryParameter("id")?.toLongOrNull() ?: return error("Missing id")
        // Taken out under the lock, so an id can only be given once.
        val p = synchronized(lock) { prepared?.takeIf { it.id == id }?.also { prepared = null } }
            ?: return error("Not prepared or already used")
        refusal()?.let { return error(it) }
        if (now() - p.atMs > PREPARED_TTL_MS) return error("Expired, choose again")
        // A bolus still waiting or running (also an SMB) is not in the computed dose yet.
        busy()?.let { return error(it) }
        // A bolus given after the dose was computed (from the phone, another watch or an SMB) is not in it.
        val newest = runBlocking {
            listOfNotNull(persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL), persistenceLayer.getNewestBolusOfType(BS.Type.SMB))
                .maxOfOrNull { it.timestamp }
        }
        if (newest != null && newest > p.atMs) return error("Another bolus was given, choose again")
        val progress = try {
            runBlocking {
                if (p.byWizard) wizardExecutor.commit(id, asAdvisor = false, Sources.Garmin, wizardLabel())
                else batchExecutor.commit(id, Sources.Garmin, treatmentLabel())
            }
        } catch (e: Exception) {
            // The commit may have reached the pump before it failed.
            aapsLogger.error(LTag.GARMIN, "bolus id $id commit failed", e)
            return unknown()
        }
        aapsLogger.info(LTag.GARMIN, "bolus id $id committed: $progress")
        // Only a refusal proves that nothing was given; anything else may hide a given dose.
        if (progress is ActionProgress.Rejected) return error(failure(progress))
        if (progress !is ActionProgress.Applied) return unknown()
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", "OK")
        }.toString()
    }

    /** Stops a bolus that is queued or running. Only the key is needed: stopping is a safety action. */
    fun stop(uri: URI): CharSequence {
        // On AAPSClient the bolus runs on the master, which this queue cannot stop.
        if (config.AAPSCLIENT) return error("Not on AAPSClient")
        val running = bolusRunning()
        // Clears the queued boluses and stops the pump (off this thread), like the stop button in AAPS.
        commandQueue.cancelAllBoluses(null)
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
        addProperty("bolusRunning", bolusRunning())
        addProperty("ttActive", ttActive)
        addProperty("qwCount", watchButtons().size)
        // So the watch shows only what can be used and its pickers stop at the limits AAPS applies anyway.
        addProperty("bolusEnabled", preferences.get(GarminBooleanKey.BolusFromWatch))
        // One switch covers both; the watch still reads two fields.
        addProperty("carbsEnabled", preferences.get(GarminBooleanKey.BolusFromWatch))
        addProperty("maxBolus", constraintChecker.getMaxBolusAllowed().value())
        addProperty("maxCarbs", constraintChecker.getMaxCarbsAllowed().value())
        addProperty("wizardPercent", preferences.get(IntKey.OverviewBolusPercentage))
        // The step the pump gives, so the watch cannot pick an amount AAPS would round down.
        addProperty("bolusStep", activePlugin.activePump.bolusStepSize(1.0))
        // Lets the stop screen show how far the bolus has come. Only a bolus: an SMB or priming is not
        // what the screen waits for. Percent rather than the delivered amount, because some pumps only
        // report a percent; the watch works out the units from it.
        bolusProgressData.state.value?.takeIf { !it.isSMB && !it.isPriming }?.let {
            addProperty("bolusPercent", it.percent.coerceIn(0, 100))
            addProperty("bolusInsulin", it.insulin)
        }
    }

    private fun watchButtons() =
        quickWizard.list().filter { it.forDevice(QuickWizardEntry.DEVICE_WATCH) && inTimeWindow(it) && !coolingDown(it) }

    private fun bolusRunning() = commandQueue.bolusInQueue() || commandQueue.isRunning(Command.CommandType.BOLUS)

    private fun busy(): String? = if (bolusRunning()) "A bolus is running, wait" else null

    /**
     * Same rule as the phone (QuickWizardEntry.isActive): a button for a short time window (a meal) is
     * not offered again for 30 min after it was used, so the same meal is not given twice.
     */
    private fun coolingDown(entry: QuickWizardEntry): Boolean {
        val windowSec = if (entry.validTo() >= entry.validFrom()) entry.validTo() - entry.validFrom()
        else 86400 - entry.validFrom() + entry.validTo()
        return windowSec < 4 * 3600 && now() - entry.lastUsed() <= QuickWizardEntry.COOLDOWN_MILLIS
    }

    /** The dose AAPS computed, after its limits; null when it did not say. */
    private fun wizardInsulin(progress: ActionProgress): Double? = (progress as? ActionProgress.Prepared)?.wizardDetail?.totalInsulin

    private fun park(progress: ActionProgress, startMs: Long, byWizard: Boolean, what: String, insulin: Double? = null): CharSequence {
        if (progress !is ActionProgress.Prepared) return error(failure(progress))
        synchronized(lock) { prepared = Prepared(progress.id, startMs, byWizard) }
        aapsLogger.info(LTag.GARMIN, "$what prepared, id ${progress.id}")
        val lines = JsonArray().apply { progress.lines.forEach { add(it.text) } }
        return JsonObject().apply {
            addProperty("ok", true)
            // As text: the id does not fit in a number on the watch (32 bit).
            addProperty("id", progress.id.toString())
            add("lines", lines)
            // Lets the watch skip the hold and the stop screen when no insulin is given. Left out when
            // unknown: the watch then treats it as a bolus.
            insulin?.let { addProperty("insulin", it) }
        }.toString()
    }

    /** A carbs button with its extra eCarbs, as the phone and the Wear watch give it. */
    private fun fixedCarbs(entry: QuickWizardEntry): BatchAction.Bolus {
        val eCarbs = entry.useEcarbs() == QuickWizardEntry.ALWAYS
        return fixed(entry, insulin = 0.0, carbs = entry.carbs()).copy(
            eCarbsGrams = if (eCarbs) entry.carbs2() else 0,
            eCarbsDelayMinutes = if (eCarbs) entry.time() else 0,
            eCarbsDurationHours = if (eCarbs) entry.duration() else 0
        )
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

    private fun refusal(): String? = when {
        !preferences.get(GarminBooleanKey.BolusFromWatch) -> "Carbs and bolus from Garmin are off in AAPS"
        // On AAPSClient the bolus would run on the master, but stop and status only see this phone.
        config.AAPSCLIENT                                 -> "Not on AAPSClient"
        else                                              -> null
    }

    private fun failure(progress: ActionProgress): String = when (progress) {
        // The dose may or may not be given: say so, so it is not given again blindly.
        is ActionProgress.Unconfirmed -> UNKNOWN
        is ActionProgress.Rejected    -> progress.detail ?: rh.gs(progress.reason.failText())
        else                          -> "Error"
    }

    /** The watch must not offer the same dose again: it may have been given. */
    private fun unknown(): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "bolus outcome unknown")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", UNKNOWN)
            addProperty("unknown", true)
        }.toString()
    }

    private fun error(text: String): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "bolus refused: $text")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", text)
        }.toString()
    }
}
