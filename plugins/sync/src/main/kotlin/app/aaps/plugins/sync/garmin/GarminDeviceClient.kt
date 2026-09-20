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
import java.util.concurrent.CopyOnWriteArrayList
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
    private var isBound = false
    /**
     * Returns the current ConnectIQ service binder, reconnecting if needed.
     *
     * WARNING: This property has significant side-effects — it is NOT a plain getter.
     * If the binder is dead it will:
     *   1. Set [state] = RECONNECTING and call [bindService] (starts an async bind).
     *   2. Block the calling thread for up to 2 seconds waiting for [onServiceConnected].
     *
     * Call sites should be aware they may block. A future refactor could make this
     * an explicit ensureConnected(): IConnectIQService? method to surface this at call sites.
     */
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
    // CopyOnWriteArrayList: registerReceiver() adds under synchronized(registeredActions),
    // onServiceDisconnected/dispose() iterate+clear from different threads. Safe for concurrent access.
    private val broadcastReceiver = CopyOnWriteArrayList<BroadcastReceiver>()
    @Volatile private var state = State.DISCONNECTED
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
            synchronized(bindLock) {
                aapsLogger.info(LTag.GARMIN, "ConnectIQ App connected")
                val ciq = IConnectIQService.Stub.asInterface(service)
                state = State.CONNECTED
                ciqService = ciq
                bindLock.notifyAll()
            }
            // Always notify - previously suppressed when state==RECONNECTING (getter branch),
            // causing the "send fresh data on reconnect" path to be silently skipped.
            // GarminMessenger.onConnect now guards against duplicate client entries.
            receiver.onConnect(this@GarminDeviceClient)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                aapsLogger.info(LTag.GARMIN, "ConnectIQ App disconnected")
                ciqService = null
                isBound = false
                if (state != State.DISPOSED) state = State.DISCONNECTED
            }
            broadcastReceiver.forEach { br ->
                try {
                    context.unregisterReceiver(br)
                } catch (e: IllegalArgumentException) {
                    // Receiver already unregistered
                }
            }
            broadcastReceiver.clear()
            synchronized(registeredActions) {
                registeredActions.clear()
            }
            val droppedMessages = mutableListOf<Message>()
            synchronized(messageQueues) {
                messageQueues.values.forEach { q -> droppedMessages.addAll(q) }
                messageQueues.clear()
            }
            droppedMessages.forEach { msg ->
                receiver.onSendMessage(this@GarminDeviceClient, msg.app.device.id, msg.app.id, "dropped: service disconnected")
            }
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
     * Binds to the ConnectIQ service. No retry of its own: a failed bind shows up
     * as a disconnect, and GarminMessenger then starts a new client. A bind retry
     * with backoff was tried, but it never fired in any of the logs, so it was
     * removed again.
     */
    private fun bindService() {
        val started = try {
            context.bindService(serviceIntent, Context.BIND_AUTO_CREATE, executor, ciqServiceConnection)
        } catch (e: Exception) {
            aapsLogger.error(LTag.GARMIN, "bindService() threw", e)
            false
        }
        if (started) {
            synchronized(bindLock) { isBound = true }
        } else {
            aapsLogger.warn(LTag.GARMIN, "bindService() returned false")
        }
    }

    override val connectedDevices: List<GarminDevice>
        get() = ciqService?.connectedDevices?.map { iqDevice -> GarminDevice(this, iqDevice) }
            ?: emptyList()

    override fun isDisposed() = state == State.DISPOSED
    override fun dispose() {
        // Set DISPOSED first under bindLock, so a sendMessage() or no-answer check
        // that runs at the same time sees it.
        synchronized(bindLock) {
            if (state == State.DISPOSED) return
            state = State.DISPOSED
            bindLock.notifyAll()
            if (isBound) {
                try {
                    context.unbindService(ciqServiceConnection)
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.GARMIN, "unbind CIQ failed ${e.message}")
                }
                isBound = false
            }
        }
        executor.shutdown()
        broadcastReceiver.forEach { br ->
            try {
                context.unregisterReceiver(br)
            } catch (e: IllegalArgumentException) {
                // Receiver already unregistered
            }
        }
        broadcastReceiver.clear()
        synchronized(registeredActions) {
            registeredActions.clear()
        }
        synchronized(messageQueues) {
            messageQueues.clear()
        }
    }

    /** Creates a unique action name for ConnectIQ callbacks. */
    private fun createAction(action: String) = "${javaClass.`package`!!.name}.$action"

    /** Registers a callback [BroadcastReceiver] under the given action that will
     * be used by the ConnectIQ app for callbacks.
     * RECEIVER_EXPORTED is required because broadcasts are sent by the external
     * Garmin Connect Mobile app (a different package). */
    private fun registerReceiver(action: String, receive: (intent: Intent) -> Unit) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                receive(intent)
            }
        }
        broadcastReceiver.add(recv)
        // Android 13+ requires an explicit exported/not-exported flag.
        // These receivers must be exported because broadcasts come from Garmin Connect Mobile.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(recv, IntentFilter(action), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(recv, IntentFilter(action))
        }
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
                        } else {
                            errorMessage = "max retries reached: $status"
                        }
                    }

                    else                                    -> {
                        errorMessage = "error $status"
                    }
                }
                queue.poll()
                if (queue.isEmpty()) {
                    messageQueues.remove(deviceId to appId)
                }
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
        /** True once this message was sent again because Garmin Connect gave no answer. */
        var noAnswerResent: Boolean = false
        val creation = Instant.now()
        var lastAttempt: Instant? = null
        val iqApp get() = IQApp(app.id, app.name ?: app.id, 0)
        val iqDevice get() = app.device.toIQDevice()
    }

    private val messageQueues = mutableMapOf<Pair<Long, String>, Queue<Message>>()

    override fun sendMessage(app: GarminApplication, data: ByteArray) {
        val droppedOldMsgs = mutableListOf<Message>()
        val msg = synchronized(messageQueues) {
            val msg = Message(app, data)
            val oldMessageCutOff = Instant.now().minusSeconds(30)
            val queue = messageQueues.getOrPut(app.device.id to app.id) { LinkedList() }
            while (true) {
                val oldMsg = queue.peek() ?: break
                if ((oldMsg.lastAttempt ?: oldMsg.creation).isBefore(oldMessageCutOff)) {
                    aapsLogger.warn(LTag.GARMIN, "remove old msg ${msg.app}")
                    queue.poll()?.let { droppedOldMsgs.add(it) }
                } else {
                    break
                }
            }
            queue.add(msg)
            // Make sure we have only one outstanding message per app, so we ensure
            // that always the first message in the queue is currently send.
            if (queue.size == 1) msg else null
        }
        droppedOldMsgs.forEach { oldMsg ->
            receiver.onSendMessage(this, oldMsg.app.device.id, oldMsg.app.id, "dropped: 30s timeout")
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
        val service = ciqService
        if (service == null) {
            // ciqService unavailable - the getter already attempted reconnect and waited 2s.
            // Previously this was a silent no-op with no log and no retry.
            // Now: log explicitly and schedule a retry like FAILURE_DEVICE_NOT_CONNECTED.
            aapsLogger.warn(LTag.GARMIN, "sendMessage: ciqService unavailable for ${msg.app} (attempt ${msg.attempt})")
            if (msg.attempt < MAX_RETRIES) {
                val delaySec = retryWaitFactor * msg.attempt
                Schedulers.io().scheduleDirect({ retryMessage(msg.app.device.id, msg.app.id) }, delaySec, TimeUnit.SECONDS)
            } else {
                aapsLogger.warn(LTag.GARMIN, "sendMessage: max retries reached for ${msg.app}, dropping message")
                synchronized(messageQueues) {
                    val q = messageQueues[msg.app.device.id to msg.app.id]
                    q?.poll()
                    if (q?.isEmpty() == true) {
                        messageQueues.remove(msg.app.device.id to msg.app.id)
                    }
                }
                receiver.onSendMessage(this, msg.app.device.id, msg.app.id, "ciqService unavailable after ${msg.attempt} attempts")
            }
            return
        }
        service.sendMessage(iqMsg, msg.iqDevice, msg.iqApp)
        val sentAttempt = msg.attempt
        Schedulers.io().scheduleDirect({ onNoAnswer(msg, sentAttempt) }, NO_ANSWER_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    /**
     * Runs [NO_ANSWER_TIMEOUT_SEC] after a message was sent. Normally Garmin Connect
     * answers within 1-2 s. When the Bluetooth link to the watch drops for a moment,
     * no answer ever comes, and the message used to block its queue (only one
     * message per app is in flight) until the next new message, often 5 min later.
     * Seen 2026-09-20 13:00-13:10: two pushes waited in the queue while the link
     * was already back.
     *
     * Now: if a newer message waits, drop this one and send the newer one at once.
     * If not, send this one again, once.
     */
    private fun onNoAnswer(msg: Message, sentAttempt: Int) {
        if (state == State.DISPOSED) return
        val key = msg.app.device.id to msg.app.id
        var dropped = false
        val toSend: Message? = synchronized(messageQueues) {
            val queue = messageQueues[key]
            // Answered, retried or removed in the meantime - nothing to do.
            if (queue == null || queue.peek() !== msg || msg.attempt != sentAttempt) return
            if (queue.size > 1 || msg.noAnswerResent) {
                queue.poll()
                dropped = true
                if (queue.isEmpty()) {
                    messageQueues.remove(key)
                    null
                } else {
                    queue.peek()
                }
            } else {
                msg.noAnswerResent = true
                msg
            }
        }
        if (dropped) {
            aapsLogger.warn(LTag.GARMIN, "no answer for ${msg.app} after ${NO_ANSWER_TIMEOUT_SEC}s, dropped")
            receiver.onSendMessage(this, msg.app.device.id, msg.app.id, "dropped: no answer")
        } else {
            aapsLogger.warn(LTag.GARMIN, "no answer for ${msg.app} after ${NO_ANSWER_TIMEOUT_SEC}s, resending")
        }
        if (toSend != null) sendMessage(toSend)
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

        /** How long to wait for Garmin Connect's answer to a sent message. See [onNoAnswer]. */
        const val NO_ANSWER_TIMEOUT_SEC = 20L
    }
}