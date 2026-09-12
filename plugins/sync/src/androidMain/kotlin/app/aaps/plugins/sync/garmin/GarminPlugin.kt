package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TT
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginGarmin
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminLongKey
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val WATCHDOG_STALE_MS = TimeUnit.MINUTES.toMillis(20)
private val WATCHDOG_MIN_REBUILD_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2)
private const val WATCHDOG_MAX_CONSECUTIVE_FAILURES = 5

/** Support communication with Garmin devices.
 *
 * This plugin supports sending glucose values to Garmin devices and receiving
 * carbs, heart rate and pump disconnect events from the device. It communicates
 * via HTTP on localhost or Garmin's native CIQ library.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(370)
@SingleIn(AppScope::class)
@Inject
class GarminPlugin(
    aapsLogger: AAPSLogger,
    resourceHelper: ResourceHelper,
    preferences: Preferences,
    private val context: Context,
    private val loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(IcPluginGarmin)
        .pluginName(SyncStrings.garmin)
        .shortName(SyncStrings.garmin)
        .description(SyncStrings.garmin_description),
    ownPreferences = GarminStringKey.entries + GarminBooleanKey.entries + GarminIntKey.entries + GarminLongKey.entries,
    aapsLogger, resourceHelper, preferences
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    // Lock til trådsikker skridt-ingest mod race conditions fra HttpServer trådpulje
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

    private val v2GlucoseAppIds = mapOf(
        "A0B1C2D3E4F54A6B8C7D9E0F1A2B3C4D" to "GlucoseWatch_V2"
    )

    @VisibleForTesting
    var garminMessengerV2Field: GarminMessenger? = null
    val garminMessengerV2: GarminMessenger
        get() {
            return synchronized(this) {
                garminMessengerV2Field ?: createGarminMessengerV2().also { garminMessengerV2Field = it }
            }
        }

    private fun resetGarminMessengerV2() {
        synchronized(this) {
            garminMessengerV2Field?.dispose()
            garminMessengerV2Field = null
        }
    }

    /**
     * Real-time V2 connection health, fed by [GarminMessenger]'s connection/send
     * callbacks (see [createGarminMessengerV2]) instead of guessed on a timer.
     * The actual fix for the observed failure (a silently-swallowed failed
     * bind with zero retry) now lives in GarminDeviceClient.bindService()
     * itself - this is a second, independent layer: if GarminDeviceClient's
     * own retry logic somehow still leaves things unhealthy for too long
     * (an unanticipated failure mode), this rebuilds the whole messenger as a
     * last resort, and logs enough to tell the two apart afterwards.
     *
     * All four fields are Atomic: written from ConnectIQ callback threads,
     * read/reset from the Coroutine / IO scheduler thread. Long is not guaranteed
     * atomic on all JVMs; AtomicLong/AtomicInteger/AtomicBoolean close that gap.
     */
    @VisibleForTesting
    val isConnectedV2 = AtomicBoolean(false)

    @VisibleForTesting
    val lastSendConfirmedAtV2 = AtomicLong(0)

    @VisibleForTesting
    val consecutiveSendFailuresV2 = AtomicInteger(0)

    @VisibleForTesting
    val lastMessengerRebuildAtV2 = AtomicLong(0)

    private fun onV2ConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.GARMIN, "V2 messenger connection state: $connected")
        isConnectedV2.set(connected)
        if (connected) {
            consecutiveSendFailuresV2.set(0)
            sendPhoneAppMessageV2()
        }
    }

    private fun onV2SendResult(success: Boolean, errorMessage: String?) {
        if (success) {
            lastSendConfirmedAtV2.set(clock.millis())
            consecutiveSendFailuresV2.set(0)
        } else {
            val failures = consecutiveSendFailuresV2.incrementAndGet()
            aapsLogger.warn(LTag.GARMIN, "V2 send failed ($failures in a row): $errorMessage")
        }
    }

    /**
     * Last-resort safety net - see class comment above. Rebuilds at most once
     * per [WATCHDOG_MIN_REBUILD_INTERVAL_MS] even if every check keeps
     * failing, so a persistently broken state doesn't turn into a tight
     * rebuild loop.
     *
     * Returns true if a rebuild was triggered. Callers that would send
     * immediately after must skip the send: the new messenger isn't connected
     * yet, and [onV2ConnectionStateChanged] will fire the first send once it is.
     */
    private fun watchdogCheckV2(): Boolean {
        val now = clock.millis()
        if (now - lastMessengerRebuildAtV2.get() < WATCHDOG_MIN_REBUILD_INTERVAL_MS) return false

        val neverConfirmed = lastSendConfirmedAtV2.get() == 0L && now - lastMessengerRebuildAtV2.get() > WATCHDOG_STALE_MS
        val staleConfirmation = lastSendConfirmedAtV2.get() > 0 && now - lastSendConfirmedAtV2.get() > WATCHDOG_STALE_MS
        val tooManyFailures = consecutiveSendFailuresV2.get() >= WATCHDOG_MAX_CONSECUTIVE_FAILURES

        if (!isConnectedV2.get() || staleConfirmation || tooManyFailures || neverConfirmed) {
            aapsLogger.warn(
                LTag.GARMIN,
                "V2 messenger watchdog: rebuilding (connected=${isConnectedV2.get()}, " +
                    "failures=${consecutiveSendFailuresV2.get()}, " +
                    "staleMin=${if (lastSendConfirmedAtV2.get() > 0) (now - lastSendConfirmedAtV2.get()) / 60_000 else -1})"
            )
            resetGarminMessengerV2()
            garminMessengerV2Field = createGarminMessengerV2()
            consecutiveSendFailuresV2.set(0)
            lastMessengerRebuildAtV2.set(now)
            return true
        }
        return false
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
        resetGarminMessenger()
        garminMessengerField = createGarminMessenger()
        resetGarminMessengerV2()
        garminMessengerV2Field = createGarminMessengerV2()
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, { _, _ -> }, true, enableDebug
        )
    }

    private fun createGarminMessengerV2(): GarminMessenger {
        val enableDebug = false
        aapsLogger.info(LTag.GARMIN, "initialize V2 IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, v2GlucoseAppIds, { _, _ -> }, true, enableDebug,
            connectionStateCallback = ::onV2ConnectionStateChanged,
            sendResultCallback = { _, _, success, errorMessage -> onV2SendResult(success, errorMessage) }
        )
    }

    @OptIn(FlowPreview::class)
    override suspend fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        lastMessengerRebuildAtV2.set(clock.millis())
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
        persistenceLayer.observeAnyChange()
            .map { changed -> changed.any { it in setOf(BS::class, CA::class, EB::class, TB::class, TT::class) } }
            .filter { it }
            .debounce(2000L)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { sendPhoneAppMessageV2() }
        setupHttpServer()
        if (garminAapsKey.isNotEmpty())
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
        resetGarminMessenger()
        resetGarminMessengerV2()
        aapsLogger.info(LTag.GARMIN, "Stop")
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
        aapsLogger.info(LTag.GARMIN, "onNewBloodGlucose ${Date(timestamp)}")
        var isNew = false
        valueLock.withLock {
            if ((lastGlucoseValueTimestamp ?: 0) >= timestamp) return
            lastGlucoseValueTimestamp = timestamp
            isNew = true
            newValue.signalAll()
        }
        if (isNew) sendPhoneAppMessageV2()
    }

    @VisibleForTesting
    fun onConnectDevice(device: GarminDevice) {
        if (garminAapsKey.isNotEmpty()) {
            aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
            sendPhoneAppMessage(device)
            sendPhoneAppMessageV2(device)
        }
    }

    private fun sendPhoneAppMessage(device: GarminDevice) {
        garminMessenger.sendMessage(device, getGlucoseMessage())
    }

    private fun sendPhoneAppMessage() {
        garminMessenger.sendMessage(getGlucoseMessage())
    }

    private fun sendPhoneAppMessageV2(device: GarminDevice) {
        if (watchdogCheckV2()) return
        garminMessengerV2.sendMessage(device, getGlucoseMessageV2())
    }

    private fun sendPhoneAppMessageV2() {
        if (watchdogCheckV2()) return
        garminMessengerV2.sendMessage(getGlucoseMessageV2())
    }

    /**
     * V2 wake-up signal: intentionally minimal - just the key and "updateWatch" command.
     * The watch ignores the payload and immediately does an HTTP GET (/get) to fetch
     * the full glucose dataset itself, eliminating the 30-second long-poll connection
     * used by the old V1 pull model. Full data is never sent via CIQ in V2.
     */
    private fun getGlucoseMessageV2(): Map<String, Any> {
        return mapOf(
            "key" to garminAapsKey,
            "command" to "updateWatch"
        )
    }

    private fun addTemporaryTarget(values: MutableMap<String, Any>) {
        val temporaryTarget = loopHub.temporaryTarget
        values["temporaryTargetActive"] = temporaryTarget != null
        temporaryTarget?.let {
            values["temporaryTargetLow"] = it.lowTarget.roundToInt()
            values["temporaryTargetHigh"] = it.highTarget.roundToInt()
            values["temporaryTargetReason"] = it.reason.text
            values["temporaryTargetEndSec"] = it.end / 1000
            values["temporaryTargetDurationMin"] = it.duration / 60000
        }
    }

    @VisibleForTesting
    fun getGlucoseMessage(): Map<String, Any> {
        val values = mutableMapOf<String, Any>(
            "key" to garminAapsKey,
            "command" to "glucose",
            "profile" to (loopHub.currentProfileName.firstOrNull()?.toString() ?: ""),
            "encodedGlucose" to encodedGlucose(getGlucoseValues()),
            "remainingInsulin" to loopHub.insulinOnboard,
            "remainingBasalInsulin" to loopHub.insulinBasalOnboard,
            "glucoseUnit" to glucoseUnitStr,
            "temporaryBasalRate" to
                (loopHub.temporaryBasal.takeIf { it.isFinite() } ?: 1.0),
            "connected" to loopHub.isConnected,
            "timestamp" to clock.instant().epochSecond
        )
        addTemporaryTarget(values)
        return values
    }

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
        val key = garminAapsKey
        val deviceKey = getQueryParameter(uri, "key")
        val isSensitiveEndpoint = uri.path == "/carbs" || uri.path == "/connect"

        if (isSensitiveEndpoint) {
            // Følsomme handlinger (kulhydrater og pumpeafbrydelse) kræver altid gyldig nøgle
            if (key.isEmpty() || key != deviceKey) {
                aapsLogger.warn(LTag.GARMIN, "Unauthorized HTTP access attempt to sensitive endpoint ${uri.path} from $caller")
                HttpURLConnection.HTTP_UNAUTHORIZED to "{}"
            } else {
                aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
                HttpURLConnection.HTTP_OK to action(uri).also {
                    aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
                }
            }
        } else {
            // Læse-endpoints (/get, /sgv.json): afvis hvis konfigureret nøgle ikke matcher
            if (key.isNotEmpty() && key != deviceKey) {
                aapsLogger.warn(LTag.GARMIN, "Unauthorized HTTP access attempt to ${uri.path} from $caller")
                HttpURLConnection.HTTP_UNAUTHORIZED to "{}"
            } else {
                aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
                HttpURLConnection.HTTP_OK to action(uri).also {
                    aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
                }
            }
        }
    }

    /** Responses to get glucose value request by the device.
     *
     * Also, gets the heart rate readings and steps from the device.
     */
    @VisibleForTesting
    fun onGetBloodGlucose(uri: URI): CharSequence {
        receiveHeartRate(uri)
        receiveSteps(uri)
        val profileName = loopHub.currentProfileName
        val waitSec = getQueryParameter(uri, "wait", 0L)
        val glucoseValues = getGlucoseValues(Duration.ofSeconds(waitSec))
        val jo = JsonObject()
        jo.addProperty("encodedGlucose", encodedGlucose(glucoseValues))
        jo.addProperty("remainingInsulin", loopHub.insulinOnboard)
        jo.addProperty("remainingBasalInsulin", loopHub.insulinBasalOnboard)
        jo.addProperty("carbsOnBoard", loopHub.carbsOnboard ?: 0.0)
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
        jo.addProperty("profile", profileName.firstOrNull()?.toString() ?: "")
        jo.addProperty("connected", loopHub.isConnected)
        jo.addProperty("timestamp", clock.instant().epochSecond)
        return jo.toString()
    }

    private fun getQueryParameter(uri: URI, name: String) = (uri.query ?: "")
        .split("&")
        .map { kv -> kv.split("=") }
        .firstOrNull { kv -> kv.size == 2 && kv[0] == name }?.get(1)

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

    private fun toLong(v: Any?): Long {
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun toInt(v: Any?) = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt()
        else -> null
    }

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
        receiveSteps(msg, test)
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
    // Kode og logik lånt og tilpasset fra MTR (AIMI) og Swissalpine Garmin-integrationen.
    // =========================================================================

    @VisibleForTesting
    fun receiveSteps(msg: Map<String, Any>, test: Boolean) {
        aapsLogger.debug(LTag.GARMIN, "receiveSteps() - Keys received: ${msg.keys.joinToString(", ")}")

        var samplingStartSec = toLong(msg["stepsStart"])
        var samplingEndSec = toLong(msg["stepsEnd"])

        if (samplingStartSec == 0L) samplingStartSec = toLong(msg["stepsstart"])
        if (samplingEndSec == 0L) samplingEndSec = toLong(msg["stepsend"])

        if (samplingStartSec == 0L || samplingEndSec == 0L) {
            if (msg.keys.any { it.contains("steps", ignoreCase = true) && it !in listOf("stepsStart", "stepsEnd", "stepsstart", "stepsend") }) {
                val now = clock.instant().epochSecond
                aapsLogger.warn(LTag.GARMIN, "Steps data without timestamps. Using fallback: now-5min to now. Keys: ${msg.keys.joinToString(",")}")
                samplingStartSec = now - 300
                samplingEndSec = now
            } else {
                return
            }
        }

        val steps5 = toInt(msg["steps5"]) ?: 0
        val steps10 = toInt(msg["steps10"]) ?: 0
        val steps15 = toInt(msg["steps15"]) ?: 0
        val steps30 = toInt(msg["steps30"]) ?: 0
        val steps60 = toInt(msg["steps60"]) ?: 0
        val steps180 = toInt(msg["steps180"]) ?: 0
        val device: String? = msg["device"] as String?

        val hasData = steps5 > 0 || steps10 > 0 || steps15 > 0 || steps30 > 0 || steps60 > 0 || steps180 > 0
        if (!hasData) {
            aapsLogger.debug(LTag.GARMIN, "Steps: All buckets are 0. Skipping.")
            return
        }

        aapsLogger.info(LTag.GARMIN, "Steps: 5=$steps5, 10=$steps10, 15=$steps15, 30=$steps30, 60=$steps60, 180=$steps180")

        receiveSteps(
            Instant.ofEpochSecond(samplingStartSec),
            Instant.ofEpochSecond(samplingEndSec),
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

    @VisibleForTesting
    fun receiveSteps(uri: URI) {
        aapsLogger.debug(LTag.GARMIN, "receiveSteps(HTTP) - Query: ${uri.query ?: "<empty>"}")

        var samplingStart: Long? = getQueryParameter(uri, "stepsStart")?.toLongOrNull()
        var samplingEnd: Long? = getQueryParameter(uri, "stepsEnd")?.toLongOrNull()

        if (samplingStart == null || samplingEnd == null) {
            if ((uri.query ?: "").contains("steps", ignoreCase = true)) {
                val now = clock.instant().epochSecond
                aapsLogger.warn(LTag.GARMIN, "HTTP steps without timestamps. Using fallback: now-5min to now")
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
            // Håndterer uret der sender samlet dagstotal "steps=xxx" (MTR / Swissalpine logik)
            val totalSteps = getQueryParameter(uri, "steps")?.toIntOrNull() ?: -1
            aapsLogger.debug(LTag.GARMIN, "Garmin Swissalpine workaround. Received steps $totalSteps")
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
            val canonicalDevice = getQueryParameter(uri, "device") ?: "Garmin"
            val none = 0

            val now = System.currentTimeMillis()
            val lastTotal = preferences.get(GarminIntKey.LastStepsTotal)
            val lastTs = preferences.get(GarminLongKey.LastStepsTotalTs)

            // Første måling nogensinde → gem kun basisværdi uden delta
            if (lastTs == 0L) {
                preferences.put(GarminIntKey.LastStepsTotal, totalSteps)
                preferences.put(GarminLongKey.LastStepsTotalTs, now)
                aapsLogger.info(LTag.GARMIN, "[GarminHTTP] baseline steps=$totalSteps")
                return
            }

            val today = LocalDate.now(ZoneId.systemDefault())
            val lastDate = if (lastTs > 0L) Instant.ofEpochMilli(lastTs).atZone(ZoneId.systemDefault()).toLocalDate() else today
            val isNewDay = today.isAfter(lastDate)
            val delta = totalSteps - lastTotal

            if (isNewDay) {
                // Midnatsskift — uret er nulstillet og tæller forfra for den nye dag
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] midnight rollover detected (lastDate=$lastDate today=$today totalSteps=$totalSteps)"
                )
                preferences.put(GarminIntKey.LastStepsTotal, totalSteps)
                preferences.put(GarminLongKey.LastStepsTotalTs, now)
                if (totalSteps > 0) {
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
                }
                return
            }

            if (delta < 0) {
                // Sensor-glitch, ur-genstart eller tidsjustering på samme dag.
                // Juster kun baseline uden at gemme totalSteps som 5-minutters aktivitet!
                aapsLogger.warn(
                    LTag.GARMIN,
                    "[GarminHTTP] step counter dropped from $lastTotal to $totalSteps on same day; adjusting baseline without storing spike"
                )
                preferences.put(GarminIntKey.LastStepsTotal, totalSteps)
                preferences.put(GarminLongKey.LastStepsTotalTs, now)
                return
            }

            if (delta == 0) {
                preferences.put(GarminLongKey.LastStepsTotalTs, now)
                if (totalSteps > 0) {
                    val midnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val todayCount = runBlocking {
                        persistenceLayer.getStepsCountFromTimeToTime(midnight, now)
                    }.count { it.device == canonicalDevice }
                    if (todayCount == 0) {
                        aapsLogger.info(LTag.GARMIN, "[GarminHTTP] no records today, storing initial total=$totalSteps")
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
                    } else {
                        aapsLogger.info(LTag.GARMIN, "[GarminHTTP] delta=0 but $todayCount records already today, skipping")
                    }
                }
                return
            }

            // delta > 0: Normal aktivitet
            aapsLogger.info(
                LTag.GARMIN,
                "[GarminHTTP] steps delta=$delta (${Instant.ofEpochSecond(samplingStart)} → ${Instant.ofEpochSecond(samplingEnd)}) Total: $totalSteps"
            )

            preferences.put(GarminIntKey.LastStepsTotal, totalSteps)
            preferences.put(GarminLongKey.LastStepsTotalTs, now)
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
