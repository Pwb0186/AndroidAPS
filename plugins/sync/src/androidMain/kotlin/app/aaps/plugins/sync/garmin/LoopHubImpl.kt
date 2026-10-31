package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToPercent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant

/**
 * Interface to the functionality of the looping algorithm and storage systems.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class LoopHubImpl(
    private val aapsLogger: AAPSLogger,
    private val activePlugin: ActivePlugin,
    private val config: Config,
    private val commandQueue: CommandQueue,
    private val constraintChecker: ConstraintsChecker,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val masterActionExecutor: MasterActionExecutor,
    @ApplicationScope private val appScope: CoroutineScope
) : LoopHub {

    val disposable = CompositeDisposable()

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    /** Returns the active insulin profile. */
    override val currentProfile: Profile? get() = runBlocking { profileFunction.getProfile() }

    /** Returns the name of the active insulin profile. */
    override val currentProfileName: String
        get() = runBlocking { profileFunction.getProfileName() }

    /** Returns the glucose unit (mg/dl or mmol/l) as selected by the user. */
    override val glucoseUnit: GlucoseUnit
        get() = GlucoseUnit.fromText(preferences.get(StringKey.GeneralUnits))

    /** Returns the remaining bolus insulin on board. */
    override val insulinOnboard: Double
        get() = runBlocking { iobCobCalculator.calculateIobFromBolus() }.iob

    /** Returns the remaining bolus and basal insulin on board. */
    override val insulinBasalOnboard: Double
        get() = runBlocking { iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended() }.basaliob

    /** Returns the remaining carbs on board. */
    override val carbsOnboard: Double?
        get() = runBlocking { iobCobCalculator.getCobInfo("LoopHubImpl") }.displayCob

    /** Returns true if the pump is connected. */
    override val isConnected: Boolean get() = runBlocking { loop.runningMode() } != RM.Mode.DISCONNECTED_PUMP

    /** Returns true if the loop is enabled and actually running. */
    override val isLoopEnabled: Boolean
        get() = loop.isEnabled() && runBlocking { loop.runningMode() }.isLoopRunning()

    /** Returns true if the current profile is set of a limited amount of time. */
    override val isTemporaryProfile: Boolean
        get() {
            val ps = runBlocking { persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis()) }
            return ps != null && ps.originalDuration > 0
        }

    /** Returns the factor by which the basal rate is currently raised (> 1) or lowered (< 1). */
    override val temporaryBasal: Double
        get() {
            return currentProfile?.let {
                val tb = runBlocking { processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis()) }
                tb?.convertedToPercent(clock.millis(), it)?.div(100.0)
            } ?: Double.NaN
        }

    override val lowGlucoseMark
        get() = profileUtil.convertToMgdl(
            preferences.get(UnitDoubleKey.OverviewLowMark), glucoseUnit
        )

    override val highGlucoseMark
        get() = profileUtil.convertToMgdl(
            preferences.get(UnitDoubleKey.OverviewHighMark), glucoseUnit
        )

    override val temporaryTarget
        get() = runBlocking { persistenceLayer.getTemporaryTargetActiveAt(clock.millis()) }

    override val pumpStatus: PumpStatus
        get() {
            val reservoir = reservoirU100()
            val reservoirLevel = when {
                reservoir == null                                          -> 0
                reservoir <= preferences.get(IntKey.OverviewResCritical) -> 2
                reservoir <= preferences.get(IntKey.OverviewResWarning)  -> 1
                else                                                       -> 0
            }
            val cannulaChange = runBlocking { persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) }
            return PumpStatus(
                reservoir = reservoir,
                reservoirLevel = reservoirLevel,
                cannulaChange = cannulaChange?.let { Instant.ofEpochMilli(it.timestamp) },
                cannulaWarnHours = preferences.get(IntKey.OverviewCageWarning),
                cannulaCritHours = preferences.get(IntKey.OverviewCageCritical),
            )
        }

    // Same rules as the AAPS overview and the Wear status, so the watch never warns where
    // AAPS does not.
    private fun reservoirU100(): Double? {
        // On AAPSClient the active pump is a virtual pump with a made-up reservoir.
        if (config.AAPSCLIENT) return null
        // Without a profile there is no concentration to convert with.
        val concentration = currentProfile?.iCfg?.concentration ?: return null
        val pump = activePlugin.activePump
        val reservoir = pump.reservoirLevel.value.iU(concentration)
        val description = pump.pumpDescription
        return when {
            // 0 is what pumps report before the first reading; AAPS shows it as unknown too.
            reservoir <= 0.0                                                            -> null
            // Otherwise an Omnipod at "50+" would always be below the 80 U default warning.
            description.isPatchPump && reservoir >= description.maxReservoirReading -> null
            else                                                                         -> reservoir
        }
    }

    /** Tells the loop algorithm that the pump is physically connected. */
    override fun connectPump() {
        appScope.launch {
            persistenceLayer.cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)
            commandQueue.cancelTempBasal(enforceNew = true)
        }
    }

    /** Tells the loop algorithm that the pump will be physically disconnected
     *  for the given number of minutes. */
    override fun disconnectPump(minutes: Int) {
        currentProfile?.let { p ->
            appScope.launch {
                loop.handleRunningModeChange(
                    durationInMinutes = minutes,
                    profile = p,
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    action = Action.DISCONNECT,
                    source = Sources.Garmin,
                    listValues = listOf(ValueWithUnit.Minute(minutes))
                )
            }
        }
    }

    /** Retrieves the glucose values starting at from. */
    override fun getGlucoseValues(from: Instant, ascending: Boolean): List<GV> = runBlocking {
        persistenceLayer.getBgReadingsDataFromTime(from.toEpochMilli(), ascending)
    }

    /** Notifies the system that carbs were eaten and stores the value. */
    override fun postCarbs(carbohydrates: Int) {
        aapsLogger.info(LTag.GARMIN, "post $carbohydrates g carbohydrates")
        val carbsAfterConstraints =
            carbohydrates.coerceAtMost(constraintChecker.getMaxCarbsAllowed().value())
        // Instant carbs now ride the shared executor (one audited path); it logs the user entry + delivers.
        appScope.launch {
            masterActionExecutor.deliverCarbs(
                carbs = carbsAfterConstraints,
                note = null,
                source = Sources.Garmin,
                onError = { aapsLogger.error(LTag.GARMIN, "carbs delivery failed: ${it.comment}") }
            )
        }
    }

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    override fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?
    ) {
        val hr = HR(
            timestamp = samplingEnd.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = avgHeartRate.toDouble(),
            device = device ?: "Garmin",
        )
        appScope.launch {
            persistenceLayer.insertOrUpdateHeartRates(listOf(hr))
        }
    }

    override fun storeStepsCount(
        timestamp: Instant,
        stepsPerWindow: Map<Int, Int>,
        device: String?
    ) {
        val records = stepsCountRecords(timestamp, stepsPerWindow, device)
        if (records.isEmpty()) return
        appScope.launch {
            try {
                val result = persistenceLayer.insertOrUpdateStepsCounts(records)
                aapsLogger.debug(LTag.GARMIN, "Steps stored in DB: ${result.inserted.size} records, $stepsPerWindow at $timestamp")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.GARMIN, "Failed to store steps: ${e.message}")
            }
        }
    }

    /** One record per window, all with the same counts; see [LoopHub.storeStepsCount].
     *  A window that the history does not cover yet has no record of its own, and its
     *  field is 0 in the other records. Read a window from the record with that duration,
     *  as the Automation trigger does. */
    private fun stepsCountRecords(timestamp: Instant, stepsPerWindow: Map<Int, Int>, device: String?): List<SC> {
        fun steps(window: Int) = stepsPerWindow[window] ?: 0
        return stepsPerWindow.keys.sorted().map { window ->
            SC(
                duration = window * 60_000L,
                timestamp = timestamp.toEpochMilli(),
                steps5min = steps(5),
                steps10min = steps(10),
                steps15min = steps(15),
                steps30min = steps(30),
                steps60min = steps(60),
                steps180min = steps(180),
                device = device ?: "Garmin",
                dateCreated = clock.millis(),
            )
        }
    }
}
