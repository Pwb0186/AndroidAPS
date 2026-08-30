package app.aaps.plugins.sync.garmin

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import io.reactivex.rxjava3.disposables.Disposable

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

    private var disposed: Boolean = false
    /** All devices that where connected since this instance was created. */
    private val devices = mutableMapOf<Long, GarminDevice>()
    private val clients = mutableListOf<GarminClient>()
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
        GarminDeviceClient(aapsLogger, context, this)
    }

    override fun onConnect(client: GarminClient) {
        aapsLogger.info(LTag.GARMIN, "onConnect $client")
        clients.add(client)
        connectionStateCallback(true)
    }

    override fun onDisconnect(client: GarminClient) {
        aapsLogger.info(LTag.GARMIN, "onDisconnect ${client.name}")
        clients.remove(client)
        synchronized (devices) {
            val deviceIds = devices.filter { (_, d) -> d.client == client }.map { (id, _) -> id }
            deviceIds.forEach { id -> devices.remove(id) }
        }
        connectionStateCallback(false)
        client.dispose()
        when (client) {
            is GarminDeviceClient -> startDeviceClient()
            is GarminSimulatorClient -> GarminSimulatorClient(aapsLogger, this)
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

    /** Sends a message to all applications on all devices. */
    fun sendMessage(msg: Any) {
        clients.forEach { cl -> cl.connectedDevices.forEach { d -> sendMessage(d, msg) }}
    }

    private fun sendMessage(app: GarminApplication, msg: Any) {
        // Convert msg to string for logging.
        val s = when (msg) {
            is Map<*,*> ->
                msg.entries.joinToString(", ", "(", ")") { (k, v) -> "$k=$v" }
            is List<*> ->
                msg.joinToString(", ", "(", ")")
            else ->
                msg.toString()
        }
        val data = GarminSerializer.serialize(msg)
        aapsLogger.info(LTag.GARMIN, "sendMessage $app ${data.size} bytes $s")
        try {
            app.client.sendMessage(app, data)
        } catch (e: IllegalStateException) {
            aapsLogger.error(LTag.GARMIN, "${app.client} not connected", e)
        }
    }

    override fun dispose() {
        if (!disposed) {
            clients.forEach { c -> c.dispose() }
            disposed = true
        }
        clients.clear()
    }

    override fun isDisposed() = disposed
}