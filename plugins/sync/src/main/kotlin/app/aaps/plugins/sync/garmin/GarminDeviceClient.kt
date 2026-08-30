package app.aaps.plugins.sync.garmin

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.waitMillis
import com.garmin.android.apps.connectmobile.connectiq.IConnectIQService
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQMessage
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.jetbrains.annotations.VisibleForTesting
import java.lang.Thread.UncaughtExceptionHandler
import java.time.Instant
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** GarminClient that talks via the ConnectIQ app to a physical device. */
class GarminDeviceClient(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val receiver: GarminReceiver,
    private val retryWaitFactor: Long = 5L
) : Disposable, GarminClient {

    override val name = "Device"
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r).apply {
            name = "Garmin callback"
            isDaemon = true
            uncaughtExceptionHandler = UncaughtExceptionHandler { _, e ->
                aapsLogger.error(LTag.GARMIN, "ConnectIQ callback failed", e)
            }
        }
    }
    private var bindLock = Object()
    /** Retry/backoff state for [bindService]. Both are only touched under [bindLock]. */
    private var bindRetryAttempt = 0
    private var bindAttemptId = 0
    private var ciqService: IConnectIQService? = null
        get() {
            synchronized(bindLock) {
                if (field?.asBinder()?.isBinderAlive != true) {
                    field = null
                    if (state !in arrayOf(State.BINDING, State.RECONNECTING)) {
                        aapsLogger.info(LTag.GARMIN, "reconnecting to ConnectIQ service")
                        state = State.RECONNECTING
                        bindService()
                    }
                    bindLock.waitMillis(2_000L)
                    if (field?.asBinder()?.isBinderAlive != true) {
                        field = null
                        // The [serviceConnection] didn't have a chance to reassign ciqService,
                        // i.e. the wait timed out. Give up.
                        aapsLogger.warn(LTag.GARMIN, "no ciqservice $this")
                    }
                }
                return field
            }
        }

    private val registeredActions = mutableSetOf<String>()
    private val broadcastReceiver = mutableListOf<BroadcastReceiver>()
    private var state = State.DISCONNECTED
    private val serviceIntent
        get() = Intent(CONNECTIQ_SERVICE_ACTION).apply {
            component = CONNECTIQ_SERVICE_COMPONENT
        }

    @VisibleForTesting
    val sendMessageAction = createAction("SEND_MESSAGE")

    private enum class State {
        BINDING,
        CONNECTED,
        DISCONNECTED,
        DISPOSED,
        RECONNECTING,
    }

    private val ciqServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            var notifyReceiver: Boolean
            val ciq: IConnectIQService
            synchronized(bindLock) {
                aapsLogger.info(LTag.GARMIN, "ConnectIQ App connected")
                ciq = IConnectIQService.Stub.asInterface(service)
                notifyReceiver = state != State.RECONNECTING
                state = State.CONNECTED
                ciqService = ciq
                bindRetryAttempt = 0
                bindLock.notifyAll()
            }
            if (notifyReceiver) receiver.onConnect(this@GarminDeviceClient)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                aapsLogger.info(LTag.GARMIN, "ConnectIQ App disconnected")
                ciqService = null
                if (state != State.DISPOSED) state = State.DISCONNECTED
            }
            broadcastReceiver.forEach { br -> context.unregisterReceiver(br) }
            broadcastReceiver.clear()
            registeredActions.clear()
            receiver.onDisconnect(this@GarminDeviceClient)
        }
    }

    init {
        aapsLogger.info(LTag.GARMIN, "binding to ConnectIQ service")
        registerReceiver(sendMessageAction, ::onSendMessage)
        state = State.BINDING
        bindService()
    }

    /**
     * Binds to the ConnectIQ service, and - unlike the original implementation -
     * actually reacts if that fails, in either of the two ways it can fail
     * silently:
     *  1. `context.bindService(...)` returns `false` immediately (e.g. Garmin
     *     Connect Mobile wasn't reachable at that exact moment) - its return
     *     value was previously never checked, so this failure produced no log
     *     line and no further attempt, ever.
     *  2. `bindService(...)` returns `true` (the OS accepted the request) but
     *     [ciqServiceConnection]'s `onServiceConnected` never actually fires -
     *     e.g. Garmin Connect Mobile hangs mid-startup. Without a timeout,
     *     [state] would stay `BINDING` forever with nothing left to retry it.
     *
     * This is a confirmed real-world failure mode: an exported AAPS log showed
     * exactly case 1 - one silent, unretried bind attempt, followed by 90+
     * minutes of complete silence until AAPS was manually force-restarted.
     */
    private fun bindService() {
        val myAttemptId: Int
        synchronized(bindLock) {
            myAttemptId = ++bindAttemptId
        }
        val started = try {
            context.bindService(serviceIntent, Context.BIND_AUTO_CREATE, executor, ciqServiceConnection)
        } catch (e: Exception) {
            aapsLogger.error(LTag.GARMIN, "bindService() threw", e)
            false
        }
        if (!started) {
            aapsLogger.warn(LTag.GARMIN, "bindService() returned false - will retry")
            scheduleReconnect()
            return
        }
        Schedulers.io().scheduleDirect({
            synchronized(bindLock) {
                // Only act if this specific attempt is still the current one and
                // still hasn't connected - a newer attempt or a successful
                // connect in the meantime makes this stale timeout a no-op.
                if (bindAttemptId == myAttemptId && state != State.CONNECTED && state != State.DISPOSED) {
                    aapsLogger.warn(LTag.GARMIN, "ConnectIQ bind timed out after ${BIND_CONNECT_TIMEOUT_SEC}s - will retry")
                    try {
                        context.unbindService(ciqServiceConnection)
                    } catch (e: Exception) {
                        // Expected if it was never actually bound - not worth logging as an error.
                    }
                    scheduleReconnect()
                }
            }
        }, BIND_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    /** Schedules another [bindService] attempt with exponential backoff, capped at [BIND_RETRY_MAX_DELAY_SEC]. */
    private fun scheduleReconnect() {
        val delaySec: Long
        synchronized(bindLock) {
            if (state == State.DISPOSED) return
            state = State.RECONNECTING
            delaySec = minOf(
                BIND_RETRY_BASE_DELAY_SEC * (1L shl minOf(bindRetryAttempt, 5)),
                BIND_RETRY_MAX_DELAY_SEC
            )
            bindRetryAttempt++
        }
        aapsLogger.info(LTag.GARMIN, "retrying ConnectIQ bind in ${delaySec}s (attempt $bindRetryAttempt)")
        Schedulers.io().scheduleDirect({
            synchronized(bindLock) {
                if (state == State.DISPOSED) return@scheduleDirect
                state = State.BINDING
            }
            bindService()
        }, delaySec, TimeUnit.SECONDS)
    }

    override val connectedDevices: List<GarminDevice>
        get() = ciqService?.connectedDevices?.map { iqDevice -> GarminDevice(this, iqDevice) }
            ?: emptyList()

    override fun isDisposed() = state == State.DISPOSED
    override fun dispose() {
        executor.shutdown()
        broadcastReceiver.forEach { context.unregisterReceiver(it) }
        broadcastReceiver.clear()
        registeredActions.clear()
        try {
            context.unbindService(ciqServiceConnection)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.GARMIN, "unbind CIQ failed ${e.message}")
        }
        state = State.DISPOSED
    }

    /** Creates a unique action name for ConnectIQ callbacks. */
    private fun createAction(action: String) = "${javaClass.`package`!!.name}.$action"

    /** Registers a callback [BroadcastReceiver] under the given action that will
     * used by the ConnectIQ app for callbacks.*/
    private fun registerReceiver(action: String, receive: (intent: Intent) -> Unit) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                receive(intent)
            }
        }
        broadcastReceiver.add(recv)
        context.registerReceiver(recv, IntentFilter(action))
    }

    override fun registerForMessages(app: GarminApplication) {
        aapsLogger.info(LTag.GARMIN, "registerForMessage $name $app")
        val action = createAction("ON_MESSAGE_${app.device.id}_${app.id}")
        val iqApp = IQApp(app.id)
        synchronized(registeredActions) {
            if (!registeredActions.contains(action)) {
                registerReceiver(action) { intent: Intent -> onReceiveMessage(iqApp, intent) }
                ciqService?.registerApp(iqApp, action, context.packageName)
                registeredActions.add(action)
            } else {
                aapsLogger.info(LTag.GARMIN, "registerForMessage $action already registered")
            }
        }
    }

    @Suppress("Deprecation")
    private fun onReceiveMessage(iqApp: IQApp, intent: Intent) {
        val iqDevice = intent.getParcelableExtra(EXTRA_REMOTE_DEVICE) as IQDevice?
        val data = intent.getByteArrayExtra(EXTRA_PAYLOAD)
        if (iqDevice != null && data != null)
            receiver.onReceiveMessage(this, iqDevice.deviceIdentifier, iqApp.applicationId, data)
    }

    /** Receives callback from ConnectIQ about message transfers. */
    private fun onSendMessage(intent: Intent) {
        val statusOrd = intent.getIntExtra(EXTRA_STATUS, IQMessageStatus.FAILURE_UNKNOWN.ordinal)
        val status = IQMessageStatus.entries.firstOrNull { s -> s.ordinal == statusOrd } ?: IQMessageStatus.FAILURE_UNKNOWN
        val deviceId = getDevice(intent)
        val appId = intent.getStringExtra(EXTRA_APPLICATION_ID)?.uppercase()
        if (deviceId == null || appId == null) {
            aapsLogger.warn(LTag.GARMIN, "onSendMessage device='$deviceId' app='$appId'")
        } else {
            synchronized(messageQueues) {
                val queue = messageQueues[deviceId to appId]
                val msg = queue?.peek()
                if (queue == null || msg == null) {
                    aapsLogger.warn(LTag.GARMIN, "onSendMessage unknown message $deviceId, $appId, $status")
                    return
                }

                var errorMessage: String? = null
                when (status) {
                    IQMessageStatus.SUCCESS                 -> {}

                    IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED,
                    IQMessageStatus.FAILURE_DURING_TRANSFER -> {
                        if (msg.attempt < MAX_RETRIES) {
                            val delaySec = retryWaitFactor * msg.attempt
                            Schedulers.io().scheduleDirect({ retryMessage(deviceId, appId) }, delaySec, TimeUnit.SECONDS)
                            return
                        }
                    }

                    else                                    -> {
                        errorMessage = "error $status"
                    }
                }
                queue.poll()
                receiver.onSendMessage(this, msg.app.device.id, msg.app.id, errorMessage)
                if (queue.isNotEmpty()) {
                    Schedulers.io().scheduleDirect { retryMessage(deviceId, appId) }
                }
            }
        }
    }

    @Suppress("Deprecation")
    private fun getDevice(intent: Intent): Long? {
        val rawDevice = intent.extras?.get(EXTRA_REMOTE_DEVICE)
        return if (rawDevice is Long) rawDevice else (rawDevice as IQDevice?)?.deviceIdentifier
            ?: return null
    }

    private class Message(
        val app: GarminApplication,
        val data: ByteArray
    ) {

        var attempt: Int = 0
        val creation = Instant.now()
        var lastAttempt: Instant? = null
        val iqApp get() = IQApp(app.id, app.name, 0)
        val iqDevice get() = app.device.toIQDevice()
    }

    private val messageQueues = mutableMapOf<Pair<Long, String>, Queue<Message>>()

    override fun sendMessage(app: GarminApplication, data: ByteArray) {
        val msg = synchronized(messageQueues) {
            val msg = Message(app, data)
            val oldMessageCutOff = Instant.now().minusSeconds(30)
            val queue = messageQueues.getOrPut(app.device.id to app.id) { LinkedList() }
            while (true) {
                val oldMsg = queue.peek() ?: break
                if ((oldMsg.lastAttempt ?: oldMsg.creation).isBefore(oldMessageCutOff)) {
                    aapsLogger.warn(LTag.GARMIN, "remove old msg ${msg.app}")
                    queue.poll()
                } else {
                    break
                }
            }
            queue.add(msg)
            // Make sure we have only one outstanding message per app, so we ensure
            // that always the first message in the queue is currently send.
            if (queue.size == 1) msg else null
        }
        if (msg != null) sendMessage(msg)
    }

    private fun retryMessage(deviceId: Long, appId: String) {
        val msg = synchronized(messageQueues) {
            messageQueues[deviceId to appId]?.peek() ?: return
        }
        sendMessage(msg)
    }

    private fun sendMessage(msg: Message) {
        msg.attempt++
        msg.lastAttempt = Instant.now()
        val iqMsg = IQMessage(msg.data, context.packageName, sendMessageAction)
        ciqService?.sendMessage(iqMsg, msg.iqDevice, msg.iqApp)
    }

    override fun toString() = "$name[$state]"

    companion object {

        const val CONNECTIQ_SERVICE_ACTION = "com.garmin.android.apps.connectmobile.CONNECTIQ_SERVICE_ACTION"
        const val EXTRA_APPLICATION_ID = "com.garmin.android.connectiq.EXTRA_APPLICATION_ID"
        const val EXTRA_REMOTE_DEVICE = "com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE"
        const val EXTRA_PAYLOAD = "com.garmin.android.connectiq.EXTRA_PAYLOAD"
        const val EXTRA_STATUS = "com.garmin.android.connectiq.EXTRA_STATUS"
        val CONNECTIQ_SERVICE_COMPONENT = ComponentName(
            "com.garmin.android.apps.connectmobile",
            "com.garmin.android.apps.connectmobile.connectiq.ConnectIQService"
        )

        const val MAX_RETRIES = 10

        /** How long to wait for onServiceConnected before treating a bind attempt as failed. */
        const val BIND_CONNECT_TIMEOUT_SEC = 15L

        /** Backoff start/cap for [scheduleReconnect] - 10s, 20s, 40s, ... capped at 5 min. */
        const val BIND_RETRY_BASE_DELAY_SEC = 10L
        const val BIND_RETRY_MAX_DELAY_SEC = 300L
    }
}