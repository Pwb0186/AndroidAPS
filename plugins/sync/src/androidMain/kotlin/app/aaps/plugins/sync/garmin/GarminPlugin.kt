package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.collectResilient
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/** Support communication with Garmin devices.
 *
 * This plugin supports sending glucose values to Garmin devices and receiving
 * carbs, heart rate, pump disconnect events, temp targets, boluses and scenes from the device. It communicates
 * via HTTP on localhost or Garmin's native CIQ library.
 *
 * On a client it only shows values: carbs and pump connect or disconnect from the
 * watch are refused there, see [refuseOnClient].
 * Temp targets from the watch are in GarminTempTarget, boluses in GarminBolus, scenes in GarminScene.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(370)
@SingleIn(AppScope::class)
@Inject
class GarminPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    private val context: Context,
    private val loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    private val config: Config,
    notificationManager: NotificationManager,
    batchExecutor: BatchExecutor,
    /** Bolus wizard, Quick Wizard and stop bolus from the watch. See GarminBolus.kt. */
    private val garminBolus: GarminBolus,
    /** Scenes from the watch. See GarminScene.kt. */
    private val garminScene: GarminScene
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(IcPluginGarmin)
        .pluginName(SyncStrings.garmin)
        .description(SyncStrings.garmin_description),
    ownPreferences = GarminStringKey.entries + GarminBooleanKey.entries + GarminIntKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    /** Temp target from the watch (/tt/prepare, /tt/commit). See GarminTempTarget.kt. */
    @VisibleForTesting
    val garminTempTarget = GarminTempTarget(aapsLogger, preferences, rh, batchExecutor, { loopHub.temporaryTarget }, { loopHub.glucoseUnit }) { clock.millis() }

    companion object {
        // The key in a logged request (?key=... / &key=...): the remote control PIN or the AAPS key.
        private val KEY_IN_QUERY = Regex("(?<=[?&])key=[^&]+")

        /** Replaces a non-empty key in [s] with "***", so logs can be shared. */
        @VisibleForTesting
        fun maskKey(s: String) = KEY_IN_QUERY.replace(s, "key=***")

        /** Commands blocked after wrong PINs (not in HttpURLConnection). */
        const val HTTP_TOO_MANY_REQUESTS = 429
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

    /**
     * The remote control PIN for commands. Unlike the AAPS key it is never sent to a watch: the AAPS key is sent
     * to the watch apps when a request has the wrong key.
     */
    private val garminCommandKey get() = preferences.get(GarminStringKey.CommandKey)

    /** Wrong remote control PINs, counted over all requests. See GarminKeyGuard.kt. */
    @VisibleForTesting
    val keyGuard = GarminKeyGuard { clock.millis() }

    private fun setupGarminMessenger() {
        resetGarminMessenger()
        createGarminMessenger()
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, { _, _ -> }, true, enableDebug
        )
    }

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
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { sendPhoneAppMessage() }
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN, block = ::onNewBloodGlucose)
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
                registerEndpoint("/carbs", commandHandler(::onPostCarbs))
                registerEndpoint("/connect", commandHandler(::onConnectPump))
                registerEndpoint("/sgv.json", requestHandler(::onSgv))
                registerEndpoint("/tt/prepare", commandHandler(garminTempTarget::prepare))
                registerEndpoint("/tt/commit", commandHandler(garminTempTarget::commit))
                registerEndpoint("/wizard/prepare", commandHandler(garminBolus::prepareWizard))
                registerEndpoint("/qw/list", commandHandler(garminBolus::quickWizardList))
                registerEndpoint("/qw/prepare", commandHandler(garminBolus::prepareQuickWizard))
                registerEndpoint("/treatment/prepare", commandHandler(garminBolus::prepareTreatment))
                registerEndpoint("/bolus/commit", commandHandler(garminBolus::commit))
                registerEndpoint("/bolus/discard", commandHandler(garminBolus::discard))
                // Stop still works with the right PIN while wrong PINs block the other commands.
                registerEndpoint("/bolus/stop", commandHandler(garminBolus::stop, allowWhileLocked = true))
                registerEndpoint("/scene/list", commandHandler(garminScene::list))
                registerEndpoint("/scene/prepare", commandHandler(garminScene::prepare))
                registerEndpoint("/scene/commit", commandHandler(garminScene::commit))
                registerEndpoint("/scene/stop", commandHandler(garminScene::stop))
                registerEndpoint("/status", commandHandler({
                    garminBolus.status(loopHub.temporaryTarget != null).also {
                        garminScene.addStatus(it)
                        // The unit the watch shows and sends a manual temp target in.
                        it.addProperty("units", if (loopHub.glucoseUnit == GlucoseUnit.MGDL) "mgdl" else "mmol")
                    }.toString()
                // Only reads: the stop screen keeps its progress while wrong PINs block the commands.
                }, allowWhileLocked = true))
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
        garminMessengerField?.dispose()
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
        valueLock.withLock {
            if ((lastGlucoseValueTimestamp ?: 0) >= timestamp) return
            lastGlucoseValueTimestamp = timestamp
            newValue.signalAll()
        }
    }

    @VisibleForTesting
    fun onConnectDevice(device: GarminDevice) {
        if (garminAapsKey.isNotEmpty()) {
            aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
            sendPhoneAppMessage(device)
        }
    }

    private fun sendPhoneAppMessage(device: GarminDevice) {
        garminMessenger.sendMessage(device, getGlucoseMessage())
    }

    private fun sendPhoneAppMessage() {
        garminMessenger.sendMessage(getGlucoseMessage())
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
        val glucoseValues = getGlucoseValues()
        val last = glucoseValues.lastOrNull() ?: return emptyList()
        val delay = Duration.ofMillis(clock.millis() - last.timestamp)
        return if (!maxWait.isZero
            && delay > glucoseFrequency
            && delay < glucoseFrequency.plusMinutes(1)
        ) {
            valueLock.withLock {
                aapsLogger.debug(LTag.GARMIN, "waiting for new glucose (delay=$delay)")
                newValue.awaitNanos(maxWait.toNanos())
            }
            getGlucoseValues()
        } else {
            glucoseValues
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
        if (key.isNotEmpty() && key != deviceKey) {
            aapsLogger.warn(LTag.GARMIN, "Invalid AAPS Key from $caller, got '$deviceKey' want '$key' $uri")
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

    /** Handler for commands from the watch: they need the remote control PIN, not the AAPS key.
     *
     * Reading (/get, /sgv.json) stays with [requestHandler]. Commands are refused while no PIN is
     * set, because any app on the phone can call localhost.
     */
    @VisibleForTesting
    fun commandHandler(action: (URI) -> CharSequence, allowWhileLocked: Boolean = false) = { caller: SocketAddress, uri: URI, _: String? ->
        when (keyGuard.check(garminCommandKey, getQueryParameter(uri, "key"), allowWhileLocked)) {
            GarminKeyGuard.Result.NO_KEY     -> {
                aapsLogger.warn(LTag.GARMIN, "No remote control PIN set, refused ${uri.path} from $caller")
                HttpURLConnection.HTTP_FORBIDDEN to "{}"
            }

            GarminKeyGuard.Result.WRONG      -> {
                aapsLogger.warn(LTag.GARMIN, "Invalid remote control PIN from $caller for ${uri.path}")
                HttpURLConnection.HTTP_UNAUTHORIZED to "{}"
            }

            GarminKeyGuard.Result.LOCKED_NOW -> {
                // Another app on the phone may be guessing the PIN; the watch shows "locked" from now on.
                aapsLogger.warn(LTag.GARMIN, "${GarminKeyGuard.MAX_FAILURES} wrong remote control PINs, commands blocked for 15 min")
                HTTP_TOO_MANY_REQUESTS to "{}"
            }

            GarminKeyGuard.Result.LOCKED     -> {
                aapsLogger.warn(LTag.GARMIN, "Commands blocked after wrong PINs, refused ${uri.path} from $caller")
                HTTP_TOO_MANY_REQUESTS to "{}"
            }

            GarminKeyGuard.Result.OK         -> {
                aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: ${maskKey(uri.toString())}")
                HttpURLConnection.HTTP_OK to action(uri).also {
                    aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: ${maskKey(uri.toString())}, result: $it")
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
        val profileName = loopHub.currentProfileName
        val waitSec = getQueryParameter(uri, "wait", 0L)
        val glucoseValues = getGlucoseValues(Duration.ofSeconds(waitSec))
        val jo = JsonObject()
        jo.addProperty("encodedGlucose", encodedGlucose(glucoseValues))
        jo.addProperty("remainingInsulin", loopHub.insulinOnboard)
        jo.addProperty("remainingBasalInsulin", loopHub.insulinBasalOnboard)
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
        jo.addProperty("profile", profileName.first().toString())
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
    }

    // Decodes each value exactly once (see URI.queryParameter in GarminQuery.kt).
    // POST form bodies stay encoded, as in the original Garmin plugin - HttpServer builds
    // their URI with extra quoting.
    private fun getQueryParameter(uri: URI, name: String): String? = uri.queryParameter(name)

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
        // Carbs make the loop give insulin, so they need the same switch as a bolus from the watch.
        if (!preferences.get(GarminBooleanKey.BolusFromWatch)) {
            aapsLogger.warn(LTag.GARMIN, "carbs from the watch refused: not allowed in the Garmin settings")
            return ""
        }
        postCarbs(getQueryParameter(uri, "carbs", 0L).toInt())
        return ""
    }

    private fun postCarbs(carbs: Int) {
        if (carbs > 0) {
            if (config.AAPSCLIENT)
                refuseOnClient(rh.gs(SyncStrings.garmin_carbs_not_on_client, rh.gs(InterfacesStrings.format_carbs, carbs)))
            else
                loopHub.postCarbs(carbs)
        }
    }

    /** Handles pump connected notification that the user entered on the Garmin device. */
    @VisibleForTesting
    fun onConnectPump(uri: URI): CharSequence {
        val minutes = getQueryParameter(uri, "disconnectMinutes", 0L).toInt()
        if (config.AAPSCLIENT) {
            refuseOnClient(rh.gs(SyncStrings.garmin_pump_not_on_client))
        } else if (minutes > 0) {
            loopHub.disconnectPump(minutes)
        } else {
            loopHub.connectPump()
        }

        val jo = JsonObject()
        jo.addProperty("connected", loopHub.isConnected)
        return jo.toString()
    }

    /**
     * A client only shows values on the watch. Carbs or a pump disconnect stored here would stay on the client and
     * never reach the master, so they are not used. The watch cannot show why, so the phone says it.
     */
    private fun refuseOnClient(text: String) {
        aapsLogger.warn(LTag.GARMIN, "not used on a client: $text")
        notificationManager.post(id = NotificationId.GARMIN_NOT_ON_CLIENT, text = text)
    }

    private fun glucoseSlopeMgDlPerMilli(glucose1: GV, glucose2: GV): Double {
        return (glucose2.value - glucose1.value) / (glucose2.timestamp - glucose1.timestamp)
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
                jo.addProperty("cob", loopHub.carbsOnboard)
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
            GarminStringKey.RequestKey,
            GarminStringKey.CommandKey,
            GarminBooleanKey.BolusFromWatch
        ),
        icon = pluginDescription.icon
    )

}
