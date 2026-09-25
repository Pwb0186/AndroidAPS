package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TT
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginGarmin
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Support communication with Garmin devices.
 *
 * This plugin supports sending glucose values to Garmin devices and receiving
 * carbs, heart rate and pump disconnect events from the device. It communicates
 * via HTTP on localhost or Garmin's native CIQ library.
 *
 * The V2 push-to-pull machinery (dynamic app discovery, push throttling)
 * lives in GarminV2Push - see that file for
 * why it was split out and how it and this class divide the work.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(370)
@SingleIn(AppScope::class)
@Inject
class GarminPlugin(
    aapsLogger: AAPSLogger,
    resourceHelper: ResourceHelper,
    preferences: Preferences,
    private val sp: SP,
    private val context: Context,
    private val loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(IcPluginGarmin)
        .pluginName(SyncStrings.garmin)
        .shortName(SyncStrings.garmin)
        .description(SyncStrings.garmin_description),
    ownPreferences = GarminStringKey.entries + GarminBooleanKey.entries + GarminIntKey.entries,
    aapsLogger, resourceHelper, preferences, notificationManager
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    /** Dynamic app discovery and push throttling for the V2
     *  push-to-pull path. See GarminV2Push.kt. */
    @VisibleForTesting
    val garminV2Push = GarminV2Push(aapsLogger, sp) { clock.millis() }

    companion object {
        // Constants for step synchronization (adapted from MTR and Swissalpine)
        private const val PREF_GARMIN_LAST_STEPS = "garmin_http_last_steps"
        private const val PREF_GARMIN_LAST_TS = "garmin_http_last_steps_ts"

        // Longest gap between two step readings whose delta is still stored.
        // The delta is always stored as a single 5-minute record (steps5min), so a
        // delta that actually accumulated over a much longer time (phone out of
        // range, watch face not running, AAPS restarted) would show up as a false
        // activity spike. Beyond this gap only the baseline is moved. Same limit
        // as the midnight-rollover branch already used.
        private const val MAX_STEPS_GAP_MS = 12 * 60 * 1000L

        // Database types whose changes trigger a V2 push (debounced). This replaces the
        // RxBus events used by the old (master) AAPS (EventTreatmentChange, EventTempTargetChange,
        // EventTempBasalChange, EventRunningModeChange), which no longer exist.
        // GV is not here: a new glucose value pushes at once in onNewBloodGlucose().
        private val PUSH_TRIGGER_TYPES: Set<KClass<*>> =
            setOf(BS::class, CA::class, TT::class, TB::class, EB::class, RM::class)

        // Longer than GarminV2Push.MIN_PUSH_INTERVAL_MS (3 s), so a loop result that arrives
        // right after a new BG is not throttled away by the BG push.
        private const val PUSH_TRIGGER_DEBOUNCE_MS = 3_500L

        // Longest a loop push waits for AAPS to finish recalculating IOB/COB
        // (see onLoopDataChanged). The calculation normally takes a few seconds.
        @VisibleForTesting
        const val LOOP_PUSH_MAX_WAIT_MS = 15_000L
    }

    // Lock for thread-safe step ingestion against race conditions from HttpServer thread pool
    private val stepsIngestLock = Any()

    @VisibleForTesting
    var garminMessengerField: GarminMessenger? = null
    val garminMessenger: GarminMessenger
        get() {
            return synchronized(this) {
                garminMessengerField ?: createGarminMessenger().also { garminMessengerField = it }
            }
        }

    private fun resetGarminMessenger() {
        synchronized(this) {
            garminMessengerField?.dispose()
            garminMessengerField = null
        }
    }

    /** Garmin ConnectIQ application id for native communication. Phone pushes values. */
    private val glucoseAppIds = mapOf(
        "C9E90EE7E6924829A8B45E7DAFFF5CB4" to "GlucoseWatch_Dev",
        "1107CA6C2D5644B998D4BCB3793F2B7C" to "GlucoseDataField_Dev",
        "928FE19A4D3A4259B50CB6F9DDAF0F4A" to "GlucoseWidget_Dev",
        "662DFCF7F5A147DE8BD37F09574ADB11" to "GlucoseWatch",
        "815C7328C21248C493AD9AC4682FE6B3" to "GlucoseDataField",
        "4BDDCC1740084A1FAB83A3B2E2FCF55B" to "GlucoseWidget",
    )

    private fun onConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.GARMIN, "Garmin messenger connection state: $connected")
        if (connected) {
            scope.launch { sendPhoneAppMessageV2() }
        }
    }

    @VisibleForTesting
    var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    private val valueLock = ReentrantLock()

    @VisibleForTesting
    var newValue: Condition = valueLock.newCondition()
    private var lastGlucoseValueTimestamp: Long? = null
    private val glucoseUnitStr get() = if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmoll"
    private val garminAapsKey get() = preferences.get(GarminStringKey.RequestKey)

    private fun setupGarminMessenger() {
        val old: GarminMessenger?
        synchronized(this) {
            old = garminMessengerField
            garminMessengerField = createGarminMessenger()
        }
        old?.dispose()
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false // sp.getBoolean("communication_ciq_debug_mode", false)
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, { _, _ -> }, true, enableDebug,
            connectionStateCallback = ::onConnectionStateChanged
        )
    }

    @OptIn(FlowPreview::class)
    override suspend fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        preferences.observe(GarminBooleanKey.LocalHttpServer)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminIntKey.LocalHttpPort)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminStringKey.RequestKey)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) {
                sendPhoneAppMessage()
                sendPhoneAppMessageV2()
            }
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN, block = ::onNewBloodGlucose)
        // Treatments, carbs, temp targets, temp basals and running mode changes push to the
        // watch. They are debounced: one loop cycle can change several of them within
        // milliseconds (an SMB both stores a bolus and changes the temp basal), and one push
        // is enough.
        persistenceLayer.observeAnyChange()
            .filter { changed -> changed.any { it in PUSH_TRIGGER_TYPES } }
            .debounce(PUSH_TRIGGER_DEBOUNCE_MS)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { onLoopDataChanged() }
        // End of an IOB/COB calculation - releases a loop push that is waiting for COB
        // (onLoopDataChanged). Sent after every calculation, whether or not the Autosens
        // feature is used; the name refers to AAPS's AutosensData table. Subscribed for
        // the plugin's whole life, so it is already listening when a push starts to wait.
        rxBus.toFlow(EventAutosensCalculationFinished::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { onCalculationFinished() }
        setupHttpServer()
        setupGarminMessenger()
    }

    private fun setupHttpServer() {
        setupHttpServer(Duration.ZERO)
    }

    @VisibleForTesting
    fun setupHttpServer(wait: Duration) {
        if (preferences.get(GarminBooleanKey.LocalHttpServer)) {
            val port = preferences.get(GarminIntKey.LocalHttpPort)
            if (server != null && server?.port == port) return
            aapsLogger.info(LTag.GARMIN, "starting HTTP server on $port")
            server?.close()
            server = HttpServer(aapsLogger, port).apply {
                registerEndpoint("/get", requestHandler(::onGetBloodGlucose))
                registerEndpoint("/carbs", requestHandler(::onPostCarbs))
                registerEndpoint("/connect", requestHandler(::onConnectPump))
                registerEndpoint("/sgv.json", requestHandler(::onSgv))
                awaitReady(wait)
            }
        } else if (server != null) {
            aapsLogger.info(LTag.GARMIN, "stopping HTTP server")
            server?.close()
            server = null
        }
    }

    override suspend fun onStop() {
        scope.cancel()
        loopPushPending.set(false)
        loopPushSendOnTimeout.set(true)
        loopPushTimeout.set(null)
        aapsLogger.info(LTag.GARMIN, "Stop")
        resetGarminMessenger()
        server?.close()
        server = null
        super.onStop()
    }

    /** Receive new blood glucose events.
     *
     * Stores new blood glucose values in lastGlucoseValue to make sure we return
     * these values immediately when values are requested by Garmin device.
     * Sends a message to the Garmin devices via the ciqMessenger. */
    @VisibleForTesting
    fun onNewBloodGlucose(glucoseValues: List<GV>) {
        val timestamp = glucoseValues.maxOfOrNull { it.timestamp } ?: return
        var isNew = false
        valueLock.withLock {
            if ((lastGlucoseValueTimestamp ?: 0) >= timestamp) return
            lastGlucoseValueTimestamp = timestamp
            isNew = true
            newValue.signalAll()
        }
        // Push outside the lock - sendPhoneAppMessageV2() talks to the Connect IQ
        // SDK, which shouldn't happen while holding valueLock (used elsewhere for
        // the HTTP long-poll wait).
        if (isNew) {
            aapsLogger.info(LTag.GARMIN, "onNewBloodGlucose ${Date(timestamp)}")
            sendPhoneAppMessageV2(force = true)
        }
    }

    /** A loop push is waiting for the IOB/COB calculation (onLoopDataChanged). */
    private val loopPushPending = AtomicBoolean(false)
    /** When the last IOB/COB calculation finished (EventAutosensCalculationFinished). */
    private val lastCalcFinishedAt = AtomicLong(0)
    /** When the current wait started, for the log. */
    private val loopPushWaitStart = AtomicLong(0)
    /** False while only a /get without COB is waiting (see waitForCob). */
    private val loopPushSendOnTimeout = AtomicBoolean(true)
    private val loopPushTimeout = AtomicReference<Job?>(null)

    /** Treatment, carbs, temp target, temp basal or running mode changed (debounced).
     *
     * What the watch wants per 5 min cycle is two pushes: the new BG right away
     * (onNewBloodGlucose), and - if the loop changed something - the result once it
     * is complete. After new BG, AAPS recalculates IOB/COB, the loop runs on that,
     * sets TBR/SMB on the pump (~20 s after BG), and the TBR/SMB makes AAPS
     * recalculate IOB/COB once more; only after that are TBR, IOB and COB all fresh.
     * While it recalculates, COB is null.
     *
     * So a loop push waits for the end of that last calculation
     * (EventAutosensCalculationFinished), at most LOOP_PUSH_MAX_WAIT_MS. Pushing as soon as COB
     * happened to be there was not enough: the recalculation often started just after,
     * and the watch fetched 1-2 s later without COB (log 2026-09-24/25: 58 of 143).
     *
     * Exception: if a calculation already finished inside the debounce window, the
     * recalculation after this change is done and COB is there - push now.
     */
    @VisibleForTesting
    fun onLoopDataChanged() {
        val sinceCalc = clock.millis() - lastCalcFinishedAt.get()
        if (loopHub.carbsOnboard != null && sinceCalc < PUSH_TRIGGER_DEBOUNCE_MS) {
            aapsLogger.info(LTag.GARMIN, "loop push now (calculation finished ${sinceCalc} ms ago)")
            sendPhoneAppMessageV2(force = true)
            return
        }
        waitForCob("loop push", sendOnTimeout = true)
    }

    /** Arms a push that goes out when the IOB/COB calculation has finished and COB
     *  is there again (onCalculationFinished), or after LOOP_PUSH_MAX_WAIT_MS.
     *  With sendOnTimeout = false the timeout only sends if COB has come back -
     *  used after a /get without COB, so a COB that stays missing can't make it
     *  push every 15 s. */
    private fun waitForCob(what: String, sendOnTimeout: Boolean) {
        if (sendOnTimeout) loopPushSendOnTimeout.set(true)
        if (!loopPushPending.compareAndSet(false, true)) return  // already waiting
        if (!sendOnTimeout) loopPushSendOnTimeout.set(false)
        loopPushWaitStart.set(clock.millis())
        aapsLogger.info(LTag.GARMIN, "$what waits for IOB/COB calculation")
        val timeout = scope.launch {
            delay(LOOP_PUSH_MAX_WAIT_MS)
            releaseLoopPush("timeout")
        }
        loopPushTimeout.getAndSet(timeout)?.cancel()
    }

    /** IOB/COB calculation finished: send a waiting loop push once COB is there.
     *  If it is still missing (another recalculation already started), keep
     *  waiting - for the next finished calculation or the timeout. */
    @VisibleForTesting
    fun onCalculationFinished() {
        lastCalcFinishedAt.set(clock.millis())
        if (loopPushPending.get() && loopHub.carbsOnboard != null) {
            releaseLoopPush("calculation finished")
        }
    }

    private fun releaseLoopPush(reason: String) {
        if (!loopPushPending.compareAndSet(true, false)) return
        loopPushTimeout.getAndSet(null)?.cancel()
        if (loopHub.carbsOnboard == null && !loopPushSendOnTimeout.get()) {
            aapsLogger.info(LTag.GARMIN, "cob re-push dropped after $reason (cob still missing)")
            return
        }
        aapsLogger.info(
            LTag.GARMIN,
            "loop push after $reason, waited ${clock.millis() - loopPushWaitStart.get()} ms " +
                "(cob ${if (loopHub.carbsOnboard != null) "ok" else "missing"})"
        )
        // force: a re-push can come < MIN_PUSH_INTERVAL_MS after the push it corrects
        // and must not be throttled away. At most one per wait, so no flood.
        sendPhoneAppMessageV2(force = true)
    }

    @VisibleForTesting
    fun onConnectDevice(device: GarminDevice) {
        aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
        sendPhoneAppMessage(device)
        sendPhoneAppMessageV2()
    }

    private fun sendPhoneAppMessage(device: GarminDevice) {
        garminMessenger.sendMessage(device, getGlucoseMessage())
    }

    private fun sendPhoneAppMessage() {
        garminMessenger.sendMessage(getGlucoseMessage())
    }

    private fun sendPhoneAppMessageV2(force: Boolean = false) {
        val now = clock.millis()
        val prev = garminV2Push.lastV2PushAt.get()
        if (!force && now - prev < GarminV2Push.MIN_PUSH_INTERVAL_MS) return

        // Only apps that have polled within PUSH_ACTIVE_WINDOW_MS are pushed to.
        // An app that has fallen out of that window is not running, so a push cannot
        // reach it anyway - it re-registers itself the moment it polls again.
        val activeIds = garminV2Push.getActiveV2AppIds()
        if (activeIds.isEmpty()) return

        if (!garminV2Push.lastV2PushAt.compareAndSet(prev, now)) return
        garminMessenger.sendMessage(garminV2Push.getGlucoseMessageV2(garminAapsKey), activeIds)
    }

    @VisibleForTesting
    fun getGlucoseMessage() = mapOf<String, Any>(
        "key" to garminAapsKey,
        "command" to "glucose",
        "profile" to loopHub.currentProfileName.first().toString(),
        "encodedGlucose" to encodedGlucose(getGlucoseValues()),
        "remainingInsulin" to loopHub.insulinOnboard,
        "remainingBasalInsulin" to loopHub.insulinBasalOnboard,
        "glucoseUnit" to glucoseUnitStr,
        "temporaryBasalRate" to
            (loopHub.temporaryBasal.takeIf(java.lang.Double::isFinite) ?: 1.0),
        "connected" to loopHub.isConnected,
        "timestamp" to clock.instant().epochSecond
    )

    /** Gets the last 2+ hours of glucose values. */
    @VisibleForTesting
    fun getGlucoseValues(): List<GV> {
        val from = clock.instant().minus(Duration.ofHours(2).plusMinutes(9))
        return loopHub.getGlucoseValues(from, true)
    }

    /** Get the last 2+ hours of glucose values and waits in case a new value should arrive soon. */
    private fun getGlucoseValues(maxWait: Duration): List<GV> {
        val glucoseFrequency = Duration.ofMinutes(5)
        return valueLock.withLock {
            val glucoseValues = getGlucoseValues()
            val last = glucoseValues.lastOrNull() ?: return@withLock emptyList()
            val delay = Duration.ofMillis(clock.millis() - last.timestamp)
            if (!maxWait.isZero
                && delay > glucoseFrequency
                && delay < glucoseFrequency.plusMinutes(1)
            ) {
                aapsLogger.debug(LTag.GARMIN, "waiting for new glucose (delay=$delay)")
                newValue.awaitNanos(maxWait.toNanos())
                getGlucoseValues()
            } else {
                glucoseValues
            }
        }
    }

    private fun encodedGlucose(glucoseValues: List<GV>): String {
        val encodedGlucose = DeltaVarEncodedList(glucoseValues.size * 16, 2)
        for (glucose: GV in glucoseValues) {
            val timeSec: Int = (glucose.timestamp / 1000).toInt()
            val glucoseMgDl: Int = glucose.value.roundToInt()
            encodedGlucose.add(timeSec, glucoseMgDl)
        }
        return encodedGlucose.encodedBase64()
    }

    @VisibleForTesting
    fun requestHandler(action: (URI) -> CharSequence) = { caller: SocketAddress, uri: URI, _: String? ->
        // Same rule as upstream (master) AAPS, for backward compatibility with older watch
        // faces and data fields: with no key set, every endpoint is open (including /carbs
        // and /connect); with a key set, every endpoint needs it. On a wrong key the key is
        // pushed to the watch apps (V1 message), so an old watch face can pick it up.
        val key = garminAapsKey
        val deviceKey = getQueryParameter(uri, "key")
        if (key.isNotEmpty() && key != deviceKey) {
            aapsLogger.warn(LTag.GARMIN, "Invalid AAPS Key from $caller for ${uri.path}")
            sendPhoneAppMessage()
            Thread.sleep(1000L)
            HttpURLConnection.HTTP_UNAUTHORIZED to "{}"
        } else {
            aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
            HttpURLConnection.HTTP_OK to action(uri).also {
                aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
            }
        }
    }

    /** Responses to get glucose value request by the device.
     *
     * Also, gets the heart rate readings from the device.
     */
    @VisibleForTesting
    fun onGetBloodGlucose(uri: URI): CharSequence {
        receiveHeartRate(uri)
        receiveSteps(uri)

        val rawAppId = getQueryParameter(uri, "appId")
        var isV2App = false
        if (!rawAppId.isNullOrEmpty()) {
            val appId = rawAppId.uppercase()
            if (garminV2Push.matchesAppIdFormat(appId)) {
                garminV2Push.registerOrTouchDynamicApp(appId)
                isV2App = true
            } else {
                aapsLogger.debug(LTag.GARMIN, "Ignoring malformed appId: $rawAppId")
            }
        }

        val waitSec = getQueryParameter(uri, "wait", 0L)
        val glucoseValues = getGlucoseValues(Duration.ofSeconds(waitSec))
        val jo = JsonObject()
        jo.addProperty("encodedGlucose", encodedGlucose(glucoseValues))
        jo.addProperty("remainingInsulin", loopHub.insulinOnboard)
        jo.addProperty("remainingBasalInsulin", loopHub.insulinBasalOnboard)
        // Left out while AAPS has no COB (displayCob is null while it recalculates right
        // after a treatment change - which is exactly when a push arrives). Sending 0.0
        // then made the watch show 0 g for one update; without the field the watch
        // keeps the last value.
        //
        // A push can still race the recalculation: COB is there when the loop push
        // goes out, the recalculation starts right after, and the watch's /get 1-2 s
        // later finds none (log 2026-09-24/25: 58 of 143 direct loop pushes). So when
        // a push app is served without COB, push again once COB is back.
        val cob = loopHub.carbsOnboard
        if (cob != null) {
            jo.addProperty("carbsOnBoard", cob)
        } else if (isV2App) {
            waitForCob("cob re-push", sendOnTimeout = false)
        }
        loopHub.lowGlucoseMark.takeIf { it > 0.0 }?.let {
            jo.addProperty("lowGlucoseMark", it.roundToInt())
        }
        loopHub.highGlucoseMark.takeIf { it > 0.0 }?.let {
            jo.addProperty("highGlucoseMark", it.roundToInt())
        }
        jo.addProperty("glucoseUnit", glucoseUnitStr)
        loopHub.temporaryBasal.also {
            if (!it.isNaN()) jo.addProperty("temporaryBasalRate", it)
        }
        loopHub.temporaryTarget?.let {
            jo.addProperty("temporaryTargetActive", true)
            jo.addProperty("temporaryTargetLow", it.lowTarget.roundToInt())
            jo.addProperty("temporaryTargetHigh", it.highTarget.roundToInt())
            jo.addProperty("temporaryTargetReason", it.reason.text)
            jo.addProperty("temporaryTargetEndSec", it.end / 1000)
            jo.addProperty("temporaryTargetDurationMin", it.duration / 60000)
        } ?: jo.addProperty("temporaryTargetActive", false)
        jo.addProperty("connected", loopHub.isConnected)
        jo.addProperty("loopEnabled", loopHub.isLoopEnabled)
        jo.addProperty("timestamp", clock.instant().epochSecond)
        jo.addProperty("profile", loopHub.currentProfileName.first().toString())
        return jo.toString()
    }

    private fun getQueryParameter(uri: URI, name: String): String? {
        val raw = (uri.query ?: "")
            .split("&")
            .map { kv -> kv.split("=", limit = 2) }
            .firstOrNull { kv -> kv.size == 2 && kv[0] == name }?.get(1)
            ?: return null
        return try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            raw
        }
    }

    private fun getQueryParameter(
        uri: URI,
        @Suppress("SameParameterValue") name: String,
        @Suppress("SameParameterValue") defaultValue: Boolean
    ): Boolean {
        return when (getQueryParameter(uri, name)?.lowercase()) {
            "true"  -> true
            "false" -> false
            else    -> defaultValue
        }
    }

    private fun getQueryParameter(
        uri: URI, name: String,
        @Suppress("SameParameterValue") defaultValue: Long
    ): Long {
        val value = getQueryParameter(uri, name)
        return try {
            if (value.isNullOrEmpty()) defaultValue else value.toLong()
        } catch (_: NumberFormatException) {
            aapsLogger.error(LTag.GARMIN, "invalid $name value '$value'")
            defaultValue
        }
    }

    private fun toLong(v: Any?) = (v as? Number?)?.toLong() ?: 0L

    @VisibleForTesting
    fun receiveHeartRate(msg: Map<String, Any>, test: Boolean) {
        val avg: Int = msg.getOrDefault("hr", 0) as Int
        val samplingStartSec: Long = toLong(msg["hrStart"])
        val samplingEndSec: Long = toLong(msg["hrEnd"])
        val device: String? = msg["device"] as String?
        receiveHeartRate(
            Instant.ofEpochSecond(samplingStartSec), Instant.ofEpochSecond(samplingEndSec),
            avg, device, test
        )
    }

    @VisibleForTesting
    fun receiveHeartRate(uri: URI) {
        val avg: Int = getQueryParameter(uri, "hr", 0L).toInt()
        val samplingStartSec: Long = getQueryParameter(uri, "hrStart", 0L)
        val samplingEndSec: Long = getQueryParameter(uri, "hrEnd", 0L)
        val device: String? = getQueryParameter(uri, "device")
        receiveHeartRate(
            Instant.ofEpochSecond(samplingStartSec), Instant.ofEpochSecond(samplingEndSec),
            avg, device, getQueryParameter(uri, "test", false)
        )
    }

    private fun receiveHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avg: Int, device: String?, test: Boolean
    ) {
        aapsLogger.info(LTag.GARMIN, "average heart rate $avg BPM $samplingStart to $samplingEnd")
        if (test) return
        if (avg > 10 && samplingStart > Instant.ofEpochMilli(0L) && samplingEnd > samplingStart) {
            loopHub.storeHeartRate(samplingStart, samplingEnd, avg, device)
        } else if (avg > 0) {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid HR $avg $samplingStart..$samplingEnd")
        }
    }

    // =========================================================================
    // Garmin Steps Integration
    // Adapted from MTR (AIMI) and Swissalpine Garmin integration.
    // =========================================================================

    @VisibleForTesting
    fun receiveSteps(uri: URI) {
        aapsLogger.debug(LTag.GARMIN, "receiveSteps(HTTP) - Query: ${uri.query ?: "<empty>"}")

        var samplingStart: Long? = getQueryParameter(uri, "stepsStart")?.toLongOrNull()
        var samplingEnd: Long? = getQueryParameter(uri, "stepsEnd")?.toLongOrNull()

        if (samplingStart == null || samplingEnd == null) {
            if ((uri.query ?: "").contains("steps", ignoreCase = true)) {
                val now = clock.instant().epochSecond
                aapsLogger.debug(LTag.GARMIN, "HTTP steps without timestamps. Using fallback: now-5min to now")
                samplingStart = now - 300
                samplingEnd = now
            } else {
                return
            }
        }

        val steps5 = getQueryParameter(uri, "steps5")?.toIntOrNull() ?: 0
        val steps10 = getQueryParameter(uri, "steps10")?.toIntOrNull() ?: 0
        val steps15 = getQueryParameter(uri, "steps15")?.toIntOrNull() ?: 0
        val steps30 = getQueryParameter(uri, "steps30")?.toIntOrNull() ?: 0
        val steps60 = getQueryParameter(uri, "steps60")?.toIntOrNull() ?: 0
        val steps180 = getQueryParameter(uri, "steps180")?.toIntOrNull() ?: 0
        val device = getQueryParameter(uri, "device")
        val test = getQueryParameter(uri, "test", false)

        val hasData = steps5 > 0 || steps10 > 0 || steps15 > 0 || steps30 > 0 || steps60 > 0 || steps180 > 0
        if (!hasData) {
            val totalSteps = getQueryParameter(uri, "steps")?.toIntOrNull() ?: -1
            aapsLogger.debug(LTag.GARMIN, "Garmin sent steps: $totalSteps")
            if (totalSteps >= 0) {
                ingestHttpTotalSteps(uri, totalSteps, samplingStart, samplingEnd, test)
                return
            }

            aapsLogger.debug(LTag.GARMIN, "HTTP Steps: All buckets are 0. Skipping.")
            return
        }

        aapsLogger.info(LTag.GARMIN, "HTTP Steps: 5=$steps5, 10=$steps10, 15=$steps15, 30=$steps30, 60=$steps60, 180=$steps180")

        receiveSteps(
            Instant.ofEpochSecond(samplingStart),
            Instant.ofEpochSecond(samplingEnd),
            steps5,
            steps10,
            steps15,
            steps30,
            steps60,
            steps180,
            device,
            test,
        )
    }

    private fun ingestHttpTotalSteps(
        uri: URI,
        totalSteps: Int,
        samplingStart: Long,
        samplingEnd: Long,
        test: Boolean
    ) {
        if (test) {
            aapsLogger.info(LTag.GARMIN, "[GarminHTTP] test mode active, skipping steps ingest (total=$totalSteps)")
            return
        }

        synchronized(stepsIngestLock) {
            // Note: AAPS treats all Garmin step inputs as a single unified stream ("Garmin").
            // Per-device tracking is not supported, matching the global PREF_GARMIN_LAST_STEPS.
            val canonicalDevice = "Garmin"
            val rawDevice = getQueryParameter(uri, "device") ?: "unknown"
            val none = 0

            val now = clock.millis()
            val lastTotal = sp.getInt(PREF_GARMIN_LAST_STEPS, -1)
            val lastTs = sp.getLong(PREF_GARMIN_LAST_TS, 0L)

            // First measurement ever → record baseline value only, no delta
            if (lastTotal < 0) {
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                aapsLogger.info(LTag.GARMIN, "[GarminHTTP] baseline steps=$totalSteps (rawDevice=$rawDevice)")
                return
            }

            val today = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()
            val lastDate = if (lastTs > 0L) Instant.ofEpochMilli(lastTs).atZone(ZoneId.systemDefault()).toLocalDate() else today
            val isNewDay = today.isAfter(lastDate)
            val delta = totalSteps - lastTotal
            val gapMs = now - lastTs

            if (isNewDay) {
                // Midnight rollover — the watch step counter was reset for the new day
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] midnight rollover detected (lastDate=$lastDate today=$today totalSteps=$totalSteps rawDevice=$rawDevice)"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                if (totalSteps > 0 && gapMs in 1..MAX_STEPS_GAP_MS) {
                    loopHub.storeStepsCount(
                        Instant.ofEpochSecond(samplingStart),
                        Instant.ofEpochSecond(samplingEnd),
                        totalSteps,
                        none,
                        none,
                        none,
                        none,
                        none,
                        canonicalDevice
                    )
                } else if (totalSteps > 0) {
                    aapsLogger.info(
                        LTag.GARMIN,
                        "[GarminHTTP] midnight rollover after long gap ($gapMs ms); adjusting baseline without storing spike"
                    )
                }
                return
            }

            if (delta < 0) {
                // Sensor glitch, watch reboot, or watch/watchface change on the same day.
                // Adjust baseline only without recording totalSteps as a 5-minute activity spike!
                aapsLogger.warn(
                    LTag.GARMIN,
                    "[GarminHTTP] step counter dropped from $lastTotal to $totalSteps on same day (rawDevice=$rawDevice); adjusting baseline without storing spike"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                return
            }

            // delta == 0: No movement or duplicate call
            if (delta == 0) {
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                aapsLogger.debug(LTag.GARMIN, "[GarminHTTP] delta=0, skipping (Total: $totalSteps, rawDevice=$rawDevice)")
                return
            }

            // delta > 0 after a long gap (phone out of range, watch face not running,
            // AAPS restarted): the steps accumulated over the whole gap, but would be
            // stored as a single 5-minute record - a false activity spike. Move the
            // baseline only, the same way the midnight-rollover branch above does.
            if (gapMs !in 1..MAX_STEPS_GAP_MS) {
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] long gap ($gapMs ms, delta=$delta, Total: $totalSteps, rawDevice=$rawDevice); adjusting baseline without storing spike"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                return
            }

            // delta > 0: Normal activity
            aapsLogger.info(
                LTag.GARMIN,
                "[GarminHTTP] steps delta=$delta (${Instant.ofEpochSecond(samplingStart)} → ${Instant.ofEpochSecond(samplingEnd)}) Total: $totalSteps"
            )

            sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
            sp.putLong(PREF_GARMIN_LAST_TS, now)
            loopHub.storeStepsCount(
                Instant.ofEpochSecond(samplingStart),
                Instant.ofEpochSecond(samplingEnd),
                delta,
                none,
                none,
                none,
                none,
                none,
                canonicalDevice
            )
        }
    }

    private fun receiveSteps(
        samplingStart: Instant,
        samplingEnd: Instant,
        steps5: Int,
        steps10: Int,
        steps15: Int,
        steps30: Int,
        steps60: Int,
        steps180: Int,
        device: String?,
        test: Boolean,
    ) {
        aapsLogger.info(
            LTag.GARMIN,
            "Steps aggregated: 5=$steps5, 10=$steps10, 15=$steps15, 30=$steps30, 60=$steps60, 180=$steps180 ($samplingStart to $samplingEnd)"
        )
        if (test) return
        if (samplingStart > Instant.ofEpochMilli(0L) && samplingEnd > samplingStart) {
            loopHub.storeStepsCount(
                samplingStart,
                samplingEnd,
                steps5,
                steps10,
                steps15,
                steps30,
                steps60,
                steps180,
                device
            )
        } else {
            aapsLogger.warn(LTag.GARMIN, "Skip saving invalid Steps timestamps $samplingStart..$samplingEnd")
        }
    }

    /** Handles carb notification from the device. */
    @VisibleForTesting
    fun onPostCarbs(uri: URI): CharSequence {
        postCarbs(getQueryParameter(uri, "carbs", 0L).toInt())
        return ""
    }

    private fun postCarbs(carbs: Int) {
        if (carbs > 0) {
            loopHub.postCarbs(carbs)
        }
    }

    /** Handles pump connected notification that the user entered on the Garmin device. */
    @VisibleForTesting
    fun onConnectPump(uri: URI): CharSequence {
        val minutes = getQueryParameter(uri, "disconnectMinutes", 0L).toInt()
        if (minutes > 0) {
            loopHub.disconnectPump(minutes)
        } else {
            loopHub.connectPump()
        }

        val jo = JsonObject()
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
    }

    private fun glucoseSlopeMgDlPerMilli(glucose1: GV, glucose2: GV): Double {
        val dt = glucose2.timestamp - glucose1.timestamp
        if (dt <= 0L) return 0.0
        return (glucose2.value - glucose1.value) / dt
    }

    /** Returns glucose values in Nightscout/Xdrip format. */
    @VisibleForTesting
    fun onSgv(uri: URI): CharSequence {
        receiveHeartRate(uri)
        val count = getQueryParameter(uri, "count", 24L)
            .toInt().coerceAtMost(1000).coerceAtLeast(1)
        val briefMode = getQueryParameter(uri, "brief_mode", false)

        // Guess a start time to get [count+1] readings. This is a heuristic that only works if we get readings
        // every 5 minutes and we're not missing readings. We truncate in case we get more readings but we'll
        // get less, e.g., in case we're missing readings for the last half hour. We get one extra reading,
        // to compute the glucose delta.
        val from = clock.instant().minus(Duration.ofMinutes(5L * (count + 1)))
        val glucoseValues = loopHub.getGlucoseValues(from, false)
        val joa = JsonArray()
        for (i in 0 until count.coerceAtMost(glucoseValues.size)) {
            val jo = JsonObject()
            val glucose = glucoseValues[i]
            if (!briefMode) {
                jo.addProperty("_id", glucose.id.toString())
                jo.addProperty("device", glucose.sourceSensor.toString())
                val timestamp = Instant.ofEpochMilli(glucose.timestamp)
                jo.addProperty("deviceString", timestamp.toString())
                jo.addProperty("sysTime", timestamp.toString())
                glucose.raw?.let { raw -> jo.addProperty("unfiltered", raw) }
            }
            jo.addProperty("date", glucose.timestamp)
            jo.addProperty("sgv", glucose.value.roundToInt())
            if (i + 1 < glucoseValues.size) {
                // Compute the 5 minute delta.
                val delta = 300_000.0 * glucoseSlopeMgDlPerMilli(glucoseValues[i + 1], glucose)
                jo.addProperty("delta", BigDecimal(delta, MathContext(3, RoundingMode.HALF_UP)))
            }
            jo.addProperty("direction", glucose.trendArrow.text)
            glucose.noise?.let { n -> jo.addProperty("noise", n) }
            if (i == 0) {
                when (loopHub.glucoseUnit) {
                    GlucoseUnit.MGDL -> jo.addProperty("units_hint", "mgdl")
                    GlucoseUnit.MMOL -> jo.addProperty("units_hint", "mmol")
                }
                jo.addProperty("iob", loopHub.insulinOnboard + loopHub.insulinBasalOnboard)
                loopHub.temporaryBasal.also {
                    if (!it.isNaN()) {
                        val temporaryBasalRateInPercent = (it * 100.0).toInt()
                        jo.addProperty("tbr", temporaryBasalRateInPercent)
                    }
                }
                jo.addProperty("cob", loopHub.carbsOnboard ?: 0.0)
            }
            joa.add(jo)
        }
        return joa.toString()
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "garmin_settings",
        title = SyncStrings.garmin,
        items = listOf(
            GarminBooleanKey.LocalHttpServer,
            GarminIntKey.LocalHttpPort,
            GarminStringKey.RequestKey
        ),
        icon = pluginDescription.icon
    )
}
