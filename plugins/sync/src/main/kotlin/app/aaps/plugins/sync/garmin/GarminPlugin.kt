package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventTreatmentChange
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Thresholds and constants for GarminPlugin's V2 dynamic auto-discovery and connection watchdog.
 */
private val WATCHDOG_MIN_REBUILD_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2)
private val APP_ID_REGEX = Regex("^[0-9A-Fa-f]{32}$")
private const val PUSH_ACTIVE_WINDOW_MS = 30 * 60 * 1000L      // 30 min active push window
private const val TTL_EVICTION_MS = 7 * 24 * 60 * 60 * 1000L   // 7 days full cleanup
private const val MAX_REGISTERED_APPS = 5
private const val REGISTRY_SAVE_DEBOUNCE_MS = 5_000L
private const val PREF_GARMIN_DYNAMIC_V2_APPS = "garmin_dynamic_v2_apps"
private const val MIN_PUSH_INTERVAL_MS = 3_000L                // 3 sec floor between pushes
// V3.7: the keep-alive push (every 15 min to any app seen in the last 24 h) has been
// removed. It was meant to be the only way back in for a watch face that had fallen out
// of PUSH_ACTIVE_WINDOW_MS, but the watch face already has three faster ways back on its
// own: the view redraws once a minute and calls BackgroundScheduler.schedule2(), the
// temporal event polls every 5 min, and init2() polls ~2 s after any foreground start.
// Measured 2026-09-17: switching back to the watch face produced a /get in the same
// second (06:11:54), not five minutes later. The keep-alive therefore only ever pushed
// at app IDs whose app was not running - 05:30:15, 05:45:18 and 06:00:37 that night all
// went to a watch face that did not exist - with no way to notice, since Garmin Connect
// Mobile answers SUCCESS for a dead app (30 of 31 for the demo face, which ran once in
// three hours).

/** Support communication with Garmin devices.
 *
 * This plugin supports sending glucose values to Garmin devices and receiving
 * carbs, heart rate and pump disconnect events from the device. It communicates
 * via HTTP on localhost or Garmin's native CIQ library.
 */
@Singleton
class GarminPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    resourceHelper: ResourceHelper,
    preferences: Preferences,
    private val sp: SP,
    private val context: Context,
    private val loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_watch)
        .pluginName(R.string.garmin)
        .shortName(R.string.garmin)
        .description(R.string.garmin_description)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN),
    ownPreferences = listOf(GarminStringKey::class.java, GarminBooleanKey::class.java, GarminIntKey::class.java),
    aapsLogger, resourceHelper, preferences
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    companion object {
        // Constants for step synchronization (adapted from MTR and Swissalpine)
        private const val PREF_GARMIN_LAST_STEPS = "garmin_http_last_steps"
        private const val PREF_GARMIN_LAST_TS = "garmin_http_last_steps_ts"
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
            garminMessengerField?.let { disposable.remove(it) }
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

    private val appRegistryLock = Any()
    private var appRegistryCache: MutableMap<String, Long>? = null
    private var lastRegistrySaveMs = 0L

    // V3.7: the per-app failure counter, the exclusion set and the rebuild backoff
    // ladder that used to live here have been removed. They were driven by the
    // status Garmin Connect Mobile returns for a send, and that status says only
    // "GCM accepted the bytes" - measured 2026-09-17, an app that was not running
    // at all was answered SUCCESS 30 times out of 31. Across ~13 hours of logs
    // there was 1 failure in 366 sends, it healed itself, and the watchdog never
    // fired once. See watchdogCheck() below for what is left.

    private fun loadRegisteredV2Apps(): Map<String, Long> {
        val raw = sp.getString(PREF_GARMIN_DYNAMIC_V2_APPS, "")
        if (raw.isBlank()) return emptyMap()
        return try {
            val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
            arr.associate { el ->
                val obj = el.asJsonObject
                obj.get("id").asString to obj.get("lastSeen").asLong
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.GARMIN, "Failed to parse dynamic V2 app registry, resetting", e)
            emptyMap()
        }
    }

    private fun saveRegisteredV2Apps(apps: Map<String, Long>) {
        val arr = JsonArray()
        apps.forEach { (id, lastSeen) ->
            arr.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("lastSeen", lastSeen)
            })
        }
        sp.putString(PREF_GARMIN_DYNAMIC_V2_APPS, arr.toString())
    }

    private fun registerOrTouchDynamicApp(appId: String) {
        val previousLastSeen: Long
        synchronized(appRegistryLock) {
            val registry = appRegistryCache
                ?: loadRegisteredV2Apps().toMutableMap().also { appRegistryCache = it }

            previousLastSeen = registry[appId] ?: 0L
            val now = clock.millis()
            registry[appId] = now

            // TTL eviction (7 days)
            val cutoff = now - TTL_EVICTION_MS
            val evicted = mutableListOf<String>()
            registry.entries.removeAll { entry ->
                (entry.value < cutoff).also { if (it) evicted.add(entry.key) }
            }

            // Quota eviction (max 5)
            while (registry.size > MAX_REGISTERED_APPS) {
                registry.minByOrNull { it.value }?.key?.let {
                    registry.remove(it)
                    evicted.add(it)
                }
            }

            if (previousLastSeen == 0L || now - lastRegistrySaveMs >= REGISTRY_SAVE_DEBOUNCE_MS) {
                saveRegisteredV2Apps(registry)
                lastRegistrySaveMs = now
            }
        }
    }

    private fun flushDynamicAppRegistry() {
        synchronized(appRegistryLock) {
            appRegistryCache?.let { saveRegisteredV2Apps(it) }
        }
    }

    private fun getActiveV2AppIds(): Set<String> {
        val now = clock.millis()
        return synchronized(appRegistryLock) {
            (appRegistryCache ?: loadRegisteredV2Apps().toMutableMap().also { appRegistryCache = it })
                .filter { (_, lastSeen) -> now - lastSeen < PUSH_ACTIVE_WINDOW_MS }
                .keys.toSet()
        }
    }

    @VisibleForTesting
    val isConnected = AtomicBoolean(false)

    @VisibleForTesting
    val lastMessengerRebuildAt = AtomicLong(0)

    private val lastV2PushAt = AtomicLong(0)

    private fun onConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.GARMIN, "Garmin messenger connection state: $connected")
        isConnected.set(connected)
        if (connected) {
            disposable.add(Schedulers.io().scheduleDirect { sendPhoneAppMessageV2() })
        }
    }

    private fun onSendResult(appId: String, success: Boolean, errorMessage: String?) {
        // Logged only. The status is not a health signal: Garmin Connect Mobile
        // answers SUCCESS for an app that is not running, so counting failures here
        // cannot detect a dead push path. It is still worth logging - the one real
        // failure we have ever seen (FAILURE_UNKNOWN, 2026-09-17 06:35:17) was a
        // transient Bluetooth hiccup that the retry logic in GarminDeviceClient
        // handled by itself.
        if (success) {
            aapsLogger.debug(LTag.GARMIN, "Send OK to $appId")
        } else {
            aapsLogger.warn(LTag.GARMIN, "Send failed to $appId: $errorMessage")
        }
    }

    /**
     * Rebuilds the messenger when Garmin Connect Mobile has told us the connection
     * dropped. That is the one failure this can both see and repair: GCM is alive,
     * but our binding to it is stale, so a fresh GarminMessenger re-binds and
     * re-registers the receivers.
     *
     * V3.7: this used to have a second trigger - "every active app has 3+ failed
     * sends while the watch is still polling us over HTTP". The idea was right (use
     * the HTTP channel as an independent witness that the watch is alive), but it
     * was wired to the send status, which is SUCCESS even when nothing receives the
     * message, so it could never fire. If that detection is wanted back, it has to
     * count pushes that produced no pull - not failed sends.
     *
     * Returns true when a rebuild happened; the caller then skips this push and the
     * next one goes out through the new messenger.
     */
    private fun watchdogCheck(activeIds: Set<String>): Boolean {
        if (activeIds.isEmpty()) return false
        if (isConnected.get()) return false

        val now = clock.millis()
        val prev = lastMessengerRebuildAt.get()
        if (now - prev < WATCHDOG_MIN_REBUILD_INTERVAL_MS) return false
        if (!lastMessengerRebuildAt.compareAndSet(prev, now)) return false

        aapsLogger.warn(
            LTag.GARMIN,
            "Garmin messenger watchdog: rebuilding (disconnected, activeIds=${activeIds.size})"
        )
        val oldMessenger: GarminMessenger?
        synchronized(this) {
            isConnected.set(false)
            oldMessenger = garminMessengerField
            garminMessengerField = createGarminMessenger()
        }
        oldMessenger?.let { disposable.remove(it) }
        return true
    }

    @VisibleForTesting
    private val disposable = CompositeDisposable()

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    private val valueLock = ReentrantLock()

    @VisibleForTesting
    var newValue: Condition = valueLock.newCondition()
    private var lastGlucoseValueTimestamp: Long? = null
    private val glucoseUnitStr get() = if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmoll"
    private val garminAapsKey get() = preferences.get(GarminStringKey.RequestKey)

    private fun onPreferenceChange(event: EventPreferenceChange) {
        when (event.changedKey) {
            GarminBooleanKey.LocalHttpServer.key, GarminIntKey.LocalHttpPort.key -> setupHttpServer()
            GarminStringKey.RequestKey.key                                       -> {
                sendPhoneAppMessage()
                sendPhoneAppMessageV2()
            }
        }
    }

    private fun setupGarminMessenger() {
        val old: GarminMessenger?
        synchronized(this) {
            old = garminMessengerField
            garminMessengerField = createGarminMessenger()
        }
        old?.let { disposable.remove(it) }
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false // sp.getBoolean("communication_ciq_debug_mode", false)
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, { _, _ -> }, true, enableDebug,
            connectionStateCallback = ::onConnectionStateChanged,
            sendResultCallback = { _, appId, success, errorMessage -> onSendResult(appId, success, errorMessage) }
        ).also {
            disposable.add(it)
        }
    }

    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        lastMessengerRebuildAt.set(clock.millis())
        disposable.add(
            rxBus
                .toObservable(EventPreferenceChange::class.java)
                .observeOn(Schedulers.io())
                .subscribe(::onPreferenceChange)
        )
        disposable.add(
            rxBus
                .toObservable(EventNewBG::class.java)
                .observeOn(Schedulers.io())
                .subscribe(::onNewBloodGlucose)
        )
        // Precise, narrowly-scoped triggers instead of the broad EventLoopUpdateGui
        // (which fires on any overview UI refresh, not specifically on new data):
        // - EventNewBG's push happens inside onNewBloodGlucose() itself (below),
        //   reusing the same dedup check it already does - kept separate since it
        //   also needs to update lastGlucoseValueTimestamp/signal the HTTP long-poll,
        //   not just trigger a send.
        // - EventTreatmentChange: entered insulin/carbs/bolus wizard results.
        // - EventTempTargetChange: a temporary target was set/cancelled.
        // - EventTempBasalChange: the temp basal rate changed.
        // - EventRunningModeChange: the loop's running mode changed (e.g. the user
        //   toggled the loop on/off, or it was suspended/resumed) - without this,
        //   the watch's loopEnabled/connected fields would only refresh on the next
        //   unrelated trigger or the 5-min poll.
        // These are merged and debounced: a single loop cycle can easily
        // fire more than one of them within milliseconds of each other (e.g. an
        // SMB both logs a treatment and adjusts the temp basal), which would
        // otherwise trigger several near-identical sendPhoneAppMessageV2() calls
        // in a row for no benefit.
        disposable.add(
            Observable.merge(
                listOf(
                    rxBus.toObservable(EventTreatmentChange::class.java),
                    rxBus.toObservable(EventTempTargetChange::class.java),
                    rxBus.toObservable(EventTempBasalChange::class.java),
                    rxBus.toObservable(EventRunningModeChange::class.java)
                )
            )
                .debounce(2, TimeUnit.SECONDS)
                .observeOn(Schedulers.io())
                .subscribe { sendPhoneAppMessageV2() }
        )
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

    public override fun onStop() {
        flushDynamicAppRegistry()
        disposable.clear()
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
    fun onNewBloodGlucose(event: EventNewBG) {
        val timestamp = event.glucoseValueTimestamp ?: return
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
        val prev = lastV2PushAt.get()
        if (!force && now - prev < MIN_PUSH_INTERVAL_MS) return

        // V3.7: only apps that have polled within PUSH_ACTIVE_WINDOW_MS are pushed to.
        // An app that has fallen out of that window is not running, so a push cannot
        // reach it anyway - it re-registers itself the moment it polls again.
        val activeIds = getActiveV2AppIds()
        if (watchdogCheck(activeIds)) return
        if (activeIds.isEmpty()) return

        if (!lastV2PushAt.compareAndSet(prev, now)) return
        garminMessenger.sendMessage(getGlucoseMessageV2(), activeIds)
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
            "encodedGlucose" to encodedGlucose(getGlucoseValues()),
            "remainingInsulin" to loopHub.insulinOnboard,
            "remainingBasalInsulin" to loopHub.insulinBasalOnboard,
            "glucoseUnit" to glucoseUnitStr,
            "temporaryBasalRate" to
                (loopHub.temporaryBasal.takeIf { it.isFinite() } ?: 1.0),
            "connected" to loopHub.isConnected,
            "loopEnabled" to loopHub.isLoopEnabled,
            "timestamp" to clock.instant().epochSecond,
            // Re-added for backward compatibility: old watch faces and data fields
            // that receive V1 CIQ pushes may read this field.
            // Only the first letter of the profile name is sent (matches original AAPS).
            "profile" to (loopHub.currentProfileName.firstOrNull()?.toString() ?: "")
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
            // Sensitive actions (carbs and pump disconnection) strictly require a valid key
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
            // Read endpoints (/get, /sgv.json): reject if configured key does not match
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
     * Also, gets the heart rate readings from the device.
     */
    @VisibleForTesting
    fun onGetBloodGlucose(uri: URI): CharSequence {
        receiveHeartRate(uri)
        receiveSteps(uri)

        val rawAppId = getQueryParameter(uri, "appId")
        if (!rawAppId.isNullOrEmpty()) {
            val appId = rawAppId.uppercase()
            if (APP_ID_REGEX.matches(appId)) {
                registerOrTouchDynamicApp(appId)
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
        jo.addProperty("connected", loopHub.isConnected)
        jo.addProperty("loopEnabled", loopHub.isLoopEnabled)
        jo.addProperty("timestamp", clock.instant().epochSecond)
        // Re-added for backward compatibility: old watch faces using HTTP pull may read this field.
        // Only the first letter of the profile name is sent (matches original AAPS).
        jo.addProperty("profile", loopHub.currentProfileName.firstOrNull()?.toString() ?: "")
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
    // Adapted from MTR (AIMI) and Swissalpine Garmin integration.
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

            if (isNewDay) {
                // Midnight rollover — the watch step counter was reset for the new day
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] midnight rollover detected (lastDate=$lastDate today=$today totalSteps=$totalSteps rawDevice=$rawDevice)"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                val gapMs = now - lastTs
                if (totalSteps > 0 && gapMs in 1..(15 * 60 * 1000L)) {
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

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "garmin_settings"
            title = rh.gs(R.string.garmin)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = GarminBooleanKey.LocalHttpServer, title = R.string.garmin_local_http_server))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = GarminIntKey.LocalHttpPort, title = R.string.garmin_local_http_server_port))
            addPreference(
                AdaptiveStringPreference(
                    ctx = context,
                    stringKey = GarminStringKey.RequestKey,
                    title = R.string.garmin_request_key,
                    summary = R.string.garmin_request_key_summary,
                    validatorParams = DefaultEditTextValidator.Parameters(emptyAllowed = true)
                )
            )
        }
    }
}
