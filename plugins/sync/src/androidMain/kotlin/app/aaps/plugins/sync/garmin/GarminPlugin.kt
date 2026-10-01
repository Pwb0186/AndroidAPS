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
import java.util.Date
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

    /** Step counts from the watch (/get?steps=...). See GarminSteps.kt. */
    @VisibleForTesting
    val garminSteps = GarminSteps(aapsLogger, sp, loopHub, { clock })

    companion object {
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

    /** Runs [block] and logs an exception instead of letting it escape.
     *
     * Used around everything that ends in a call to Garmin Connect (the Connect IQ
     * service in another app). Such a call can throw - e.g. while Garmin Connect is
     * updated or restarted - and from a coroutine started with scope.launch the
     * exception went to the thread's uncaught-exception handler, i.e. crashed AAPS.
     * The watch fetches on its own when a push is lost, so logging is enough. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            aapsLogger.error(LTag.GARMIN, "$what failed", e)
        }
    }

    private fun onConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.GARMIN, "Garmin messenger connection state: $connected")
        if (connected) {
            scope.launch { safely("push after reconnect") { sendPhoneAppMessageV2() } }
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
        running = true
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
        // As in master, the Connect IQ messenger (the binding to Garmin Connect) only
        // runs when something uses it: an AAPS key is set - master sends it to the watch
        // apps - or a push-to-pull watch face has polled within the last 15 min.
        // Otherwise AAPS stays out of Garmin Connect, as before, for users of HTTP-only
        // watch faces, data fields or xDrip. A push watch face that polls later starts
        // it (ensureGarminMessenger in onGetBloodGlucose).
        if (garminAapsKey.isNotEmpty() || garminV2Push.getActiveV2AppIds().isNotEmpty()) {
            setupGarminMessenger()
        }
    }

    /** False after onStop. See ensureGarminMessenger. */
    @Volatile private var running = false

    /** Starts the messenger if it is not running yet. Called when a push-to-pull
     *  watch face polls, so the next push can reach it: binding to Garmin Connect
     *  takes a moment, and the next push (new BG) comes up to 5 min later. */
    private fun ensureGarminMessenger() {
        // running: a /get still in progress while the plugin stops must not start
        // a new messenger after onStop has disposed the old one.
        if (!running || garminMessengerField != null) return
        synchronized(this) {
            if (garminMessengerField == null) {
                aapsLogger.info(LTag.GARMIN, "push watch face registered, starting IQ messenger")
                garminMessengerField = createGarminMessenger()
            }
        }
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
        running = false
        scope.cancel()
        synchronized(loopPushLock) {
            loopPushPending = false
            loopPushSendOnTimeout = true
            loopPushTimeout = null
        }
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

    /** Guards the four loopPush* fields below. They change together - a /get without
     *  COB (HTTP thread) and a loop push (another thread) arming the wait at the same
     *  time must not leave loopPushSendOnTimeout false when the loop push wanted true. */
    private val loopPushLock = Any()
    /** A loop push is waiting for the IOB/COB calculation (onLoopDataChanged). */
    @Volatile private var loopPushPending = false
    /** False while only a /get without COB is waiting (see waitForCob). */
    private var loopPushSendOnTimeout = true
    /** When the current wait started, for the log. */
    private var loopPushWaitStart = 0L
    private var loopPushTimeout: Job? = null

    /** Treatment, carbs, temp target, temp basal or running mode changed (debounced).
     *
     * Each of these makes AAPS recalculate IOB/COB, and while it does, COB is not
     * available (displayCob == null) - so a push sent now makes the watch fetch
     * data without COB, and the watch keeps its old COB until the next BG push, up
     * to 5 min later. This hits about half of all loop pushes but hardly ever a BG
     * push, and is most noticeable right after carbs are entered.
     *
     * So the push waits for EventAutosensCalculationFinished when COB is missing,
     * at most LOOP_PUSH_MAX_WAIT_MS (awake time, see waitForCob). Same number of pushes as
     * before, only a few seconds later when needed, and with the fresh COB.
     */
    // Not done: always waiting for the calculation (max 2 pushes per BG). About a
    // third of the loop pushes then reached the watch 25 s - 3.6 min late -
    // with the screen off the phone sleeps, and both AAPS's recalculation and the
    // LOOP_PUSH_MAX_WAIT_MS timer wait for it to wake up. So the loop push goes out at once when
    // COB is there (TBR on the watch within seconds), and the /get without COB
    // that can follow is fixed by the re-push in onGetBloodGlucose (waitForCob).
    @VisibleForTesting
    fun onLoopDataChanged() {
        if (loopHub.carbsOnboard != null) {
            aapsLogger.info(LTag.GARMIN, "loop push now (cob ok)")
            sendPhoneAppMessageV2()
            return
        }
        waitForCob("loop push", sendOnTimeout = true)
    }

    /** Arms a push that goes out when the IOB/COB calculation has finished and COB
     *  is there again (onCalculationFinished), or after LOOP_PUSH_MAX_WAIT_MS.
     *  With sendOnTimeout = false the timeout only sends if COB has come back -
     *  used after a /get without COB, so a COB that stays missing can't make it
     *  push every 15 s.
     *
     *  The wait counts awake time only: with the screen off the phone sleeps, and
     *  both the timer and the calculation wait for it to wake up. So the real wait
     *  can be minutes, mostly at night.
     */
    private fun waitForCob(what: String, sendOnTimeout: Boolean) {
        synchronized(loopPushLock) {
            if (loopPushPending) {
                // Already waiting: a loop push upgrades a COB re-push to "send also on
                // timeout"; a COB re-push never downgrades a waiting loop push.
                if (sendOnTimeout) loopPushSendOnTimeout = true
                return
            }
            loopPushPending = true
            loopPushSendOnTimeout = sendOnTimeout
            loopPushWaitStart = clock.millis()
            loopPushTimeout?.cancel()
            loopPushTimeout = scope.launch {
                delay(LOOP_PUSH_MAX_WAIT_MS)
                safely("loop push timeout") { onLoopPushTimeout() }
            }
        }
        aapsLogger.info(LTag.GARMIN, "$what waits for IOB/COB calculation")
    }

    @VisibleForTesting
    fun onLoopPushTimeout() = releaseLoopPush("timeout")

    /** IOB/COB calculation finished: send a waiting loop push once COB is there.
     *  If it is still missing (another recalculation already started), keep
     *  waiting - for the next finished calculation or the timeout. */
    @VisibleForTesting
    fun onCalculationFinished() {
        if (loopPushPending && loopHub.carbsOnboard != null) {
            releaseLoopPush("calculation finished")
        }
    }

    private fun releaseLoopPush(reason: String) {
        val sendOnTimeout: Boolean
        val waitedMs: Long
        synchronized(loopPushLock) {
            if (!loopPushPending) return
            loopPushPending = false
            loopPushTimeout?.cancel()
            loopPushTimeout = null
            sendOnTimeout = loopPushSendOnTimeout
            waitedMs = clock.millis() - loopPushWaitStart
        }
        val cob = loopHub.carbsOnboard  // read once (it is recalculated in the background)
        if (cob == null && !sendOnTimeout) {
            aapsLogger.info(LTag.GARMIN, "cob re-push dropped after $reason, waited $waitedMs ms (cob still missing)")
            return
        }
        aapsLogger.info(LTag.GARMIN, "loop push after $reason, waited $waitedMs ms (cob ${if (cob != null) "ok" else "missing"})")
        // force: a re-push can come < MIN_PUSH_INTERVAL_MS after the push it corrects
        // and must not be throttled away. At most one per wait, so no flood.
        sendPhoneAppMessageV2(force = true)
    }

    @VisibleForTesting
    fun onConnectDevice(device: GarminDevice) {
        // As master: the V1 message carries the AAPS key, so only when one is set.
        if (garminAapsKey.isNotEmpty()) {
            aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
            sendPhoneAppMessage(device)
        }
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
        garminSteps.receive(uri)

        val rawAppId = getQueryParameter(uri, "appId")
        var isV2App = false
        if (!rawAppId.isNullOrEmpty()) {
            val appId = rawAppId.uppercase()
            if (garminV2Push.matchesAppIdFormat(appId)) {
                garminV2Push.registerOrTouchDynamicApp(appId)
                ensureGarminMessenger()
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
        // later finds none (about 4 in 10 direct loop pushes). So when
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

    // Reads the raw (still encoded) query and decodes each value exactly once.
    // uri.query is already decoded, so decoding that again turned "+" into a space
    // and broke "%xx" (e.g. a key with "+" never matched). POST form bodies stay
    // encoded, as in master - HttpServer builds their URI with extra quoting.
    private fun getQueryParameter(uri: URI, name: String): String? {
        val raw = (uri.rawQuery ?: "")
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
