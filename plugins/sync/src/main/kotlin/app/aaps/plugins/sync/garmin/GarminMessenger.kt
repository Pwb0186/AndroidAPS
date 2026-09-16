package app.aaps.plugins.sync.garmin

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.CopyOnWriteArrayList

class GarminMessenger(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    applicationIdNames: Map<String, String>,
    private val messageCallback: (app: GarminApplication, msg: Any) -> Unit,
    enableConnectIq: Boolean,
    enableSimulator: Boolean,
    /**
     * Called whenever this messenger's overall connection state to the phone's
     * ConnectIQ service changes - true on onConnect, false on onDisconnect.
     * Defaults to a no-op so existing call sites (e.g. the V1 messenger) don't
     * need to change. Added so callers can know connection health in real
     * time instead of guessing - see GarminPlugin's watchdog for why that
     * matters.
     */
    private val connectionStateCallback: (connected: Boolean) -> Unit = {},
    /**
     * Called with the result of every sendMessage() attempt - true/null error
     * on success, false/message on failure. Defaults to a no-op. Previously
     * this result was only ever logged inside this class and never surfaced.
     */
    private val sendResultCallback: (deviceId: Long, appId: String, success: Boolean, errorMessage: String?) -> Unit =
        { _, _, _, _ -> }
): Disposable, GarminReceiver {

    @Volatile private var disposed: Boolean = false
    private var activeDeviceClient: GarminDeviceClient? = null
    /** All devices that where connected since this instance was created. */
    private val devices = mutableMapOf<Long, GarminDevice>()
    // CopyOnWriteArrayList ensures thread safety: onConnect/onDisconnect write
    // from the ConnectIQ callback thread while sendMessage() iterates from the
    // RxJava IO thread. mutableListOf() was not safe for concurrent access.
    private val clients = CopyOnWriteArrayList<GarminClient>()
    private val appIdNames = mutableMapOf<String, String>()
    init {
        aapsLogger.info(LTag.GARMIN, "init CIQ debug=$enableSimulator")
        appIdNames.putAll(applicationIdNames)
        if (enableConnectIq) startDeviceClient()
        if (enableSimulator) {
            appIdNames["SimApp"] = "SimulatorApp"
            GarminSimulatorClient(aapsLogger, this)
        }
    }

    private fun getDevice(client: GarminClient, deviceId: Long): GarminDevice {
        synchronized (devices) {
            return devices.getOrPut(deviceId) {
                client.connectedDevices.firstOrNull { d -> d.id == deviceId } ?:
                GarminDevice(client, deviceId, "unknown") }
        }
    }

    private fun getApplication(client: GarminClient, deviceId: Long, appId: String): GarminApplication {
        return GarminApplication(getDevice(client, deviceId), appId, appIdNames[appId])
    }

    private fun startDeviceClient() {
        synchronized(this) {
            if (disposed) return
            activeDeviceClient?.dispose()
            activeDeviceClient = GarminDeviceClient(aapsLogger, context, this)
        }
    }

    override fun onConnect(client: GarminClient) {
        aapsLogger.info(LTag.GARMIN, "onConnect $client")
        synchronized(this) {
            if (disposed) {
                client.dispose()
                if (client == activeDeviceClient) {
                    activeDeviceClient = null
                }
                return
            }
        }
        // Guard against duplicate entries: onServiceConnected can fire more than
        // once if the getter's reconnect branch triggers a re-bind.
        val isNew = clients.addIfAbsent(client)
        if (isNew) {
            if (clients.size == 1) {
                connectionStateCallback(true)
            }
        }
    }

    private var restartCount = 0
    private var lastRestartMs = 0L

    override fun onDisconnect(client: GarminClient) {
        if (disposed) return
        aapsLogger.info(LTag.GARMIN, "onDisconnect ${client.name}")
        val shouldRestart: Boolean
        val currentRestartCount: Int
        synchronized(this) {
            if (client == activeDeviceClient) {
                activeDeviceClient = null
            }
            shouldRestart = !disposed && activeDeviceClient == null
            if (shouldRestart && client is GarminDeviceClient) {
                val now = System.currentTimeMillis()
                if (now - lastRestartMs > 60000) {
                    restartCount = 0
                }
                restartCount++
                lastRestartMs = now
            }
            currentRestartCount = restartCount
        }
        clients.remove(client)
        synchronized (devices) {
            val deviceIds = devices.filter { (_, d) -> d.client == client }.map { (id, _) -> id }
            deviceIds.forEach { id -> devices.remove(id) }
        }
        if (clients.isEmpty()) {
            connectionStateCallback(false)
        }
        client.dispose()
        when (client) {
            is GarminDeviceClient -> {
                if (!shouldRestart) return
                if (currentRestartCount > 10) {
                    // Too many rapid restarts within 60s - cool down for 5 minutes
                    // then reset the counter and try again. Permanent stop is avoided
                    // because V2 push is the primary data path and should recover
                    // without requiring a full plugin restart.
                    aapsLogger.warn(LTag.GARMIN, "Max rapid restart count reached. Cooling down for 5 minutes before retrying.")
                    io.reactivex.rxjava3.schedulers.Schedulers.io().scheduleDirect({
                        if (!disposed) {
                            synchronized(this) { restartCount = 0 }
                            startDeviceClient()
                        }
                    }, 5L, java.util.concurrent.TimeUnit.MINUTES)
                } else {
                    io.reactivex.rxjava3.schedulers.Schedulers.io().scheduleDirect({
                        if (!disposed) startDeviceClient()
                    }, currentRestartCount * 5L, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            is GarminSimulatorClient -> {
                if (!disposed) GarminSimulatorClient(aapsLogger, this)
            }
            else -> aapsLogger.warn(LTag.GARMIN, "onDisconnect unknown client $client")
        }
    }

    override fun onReceiveMessage(client: GarminClient, deviceId: Long, appId: String, data: ByteArray) {
        val app = getApplication(client, deviceId, appId)
        val msg = GarminSerializer.deserialize(data)
        if (msg == null) {
            aapsLogger.warn(LTag.GARMIN, "receive NULL msg")
        } else {
            aapsLogger.info(LTag.GARMIN, "receive ${data.size} bytes")
            messageCallback(app, msg)
        }
    }

    /** Receives status notifications for a sent message. */
    override fun onSendMessage(client: GarminClient, deviceId: Long, appId: String, errorMessage: String?) {
        val app = getApplication(client, deviceId, appId)
        aapsLogger.info(LTag.GARMIN, "onSendMessage $app ${errorMessage ?: "OK"}")
        sendResultCallback(deviceId, appId, errorMessage == null, errorMessage)
    }

    fun sendMessage(device: GarminDevice, msg: Any) {
        appIdNames.forEach { (appId, _) ->
            sendMessage(getApplication(device.client, device.id, appId), msg)
        }
    }

    /** Sends a message to all applications on all devices (V1 legacy). */
    fun sendMessage(msg: Any) {
        clients.forEach { cl -> cl.connectedDevices.forEach { d -> sendMessage(d, msg) }}
    }

    /** Sends a message to a specific set of target applications on all devices (V2 dynamic). */
    fun sendMessage(msg: Any, targetAppIds: Collection<String>) {
        val snapshot = targetAppIds.toSet()
        if (snapshot.isEmpty()) return
        clients.forEach { cl ->
            cl.connectedDevices.forEach { d ->
                snapshot.forEach { appId -> sendMessage(getApplication(cl, d.id, appId), msg) }
            }
        }
    }

    private fun sendMessage(app: GarminApplication, msg: Any) {
        // Convert msg to string for logging, excluding encodedGlucose to save log volume.
        val s = when (msg) {
            is Map<*,*> ->
                msg.filterKeys { it != "encodedGlucose" }.entries.joinToString(", ", "(", ")") { (k, v) -> "$k=$v" }
            is List<*> ->
                "(List of ${msg.size} items)"
            else ->
                msg.toString()
        }
        val data = GarminSerializer.serialize(msg)
        aapsLogger.info(LTag.GARMIN, "sendMessage $app ${data.size} bytes $s")
        try {
            app.client.sendMessage(app, data)
        } catch (e: Exception) {
            aapsLogger.error(LTag.GARMIN, "${app.client} not connected or send failed", e)
            sendResultCallback(app.device.id, app.id, false, e.message ?: "send exception")
        }
    }

    override fun dispose() {
        synchronized(this) {
            if (!disposed) {
                disposed = true
                activeDeviceClient?.dispose()
                activeDeviceClient = null
                clients.forEach { c -> c.dispose() }
                clients.clear()
            }
        }
    }

    override fun isDisposed() = disposed
}