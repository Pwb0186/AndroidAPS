package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V2 push-to-pull support for GarminPlugin: dynamic app discovery (so a
 * third-party Connect IQ fork doesn't need to be on any hardcoded app-ID
 * list, unlike the V1 path below), the push-rate throttle, and the Garmin
 * messenger connection watchdog.
 *
 * Split out of GarminPlugin.kt purely to keep that file's size down - there
 * is no behavior change from having this inline there instead. GarminPlugin
 * still owns the actual GarminMessenger/HTTP server and calls into this
 * class for everything specific to the V2 discovery-and-push path; it
 * passes itself a `rebuildMessenger` callback rather than this class
 * reaching back into GarminPlugin's messenger fields directly. The V1 path
 * (glucoseAppIds, sendPhoneAppMessage(), getGlucoseMessage()) is unrelated
 * and stays in GarminPlugin.kt.
 *
 * V2 push is a latency optimization on top of the pull model, never a
 * replacement for it: getGlucoseMessageV2() below sends only a wake-up
 * ping, never the payload itself - the watch always re-fetches the full
 * dataset over HTTP (GarminPlugin.onGetBloodGlucose()).
 *
 * V3.7: the keep-alive push (every 15 min to any app seen in the last 24 h)
 * has been removed. It was meant to be the only way back in for a watch
 * face that had fallen out of PUSH_ACTIVE_WINDOW_MS, but the watch face
 * already has three faster ways back on its own: the view redraws once a
 * minute and calls BackgroundScheduler.schedule2(), the temporal event
 * polls every 5 min, and init2() polls ~2 s after any foreground start.
 * Measured 2026-09-17: switching back to the watch face produced a /get in
 * the same second (06:11:54), not five minutes later. The keep-alive
 * therefore only ever pushed at app IDs whose app was not running -
 * 05:30:15, 05:45:18 and 06:00:37 that night all went to a watch face that
 * did not exist - with no way to notice, since Garmin Connect Mobile
 * answers SUCCESS for a dead app (30 of 31 for the demo face, which ran
 * once in three hours).
 */
class GarminV2Push(
    private val aapsLogger: AAPSLogger,
    private val sp: SP,
    private val nowMillis: () -> Long,
) {
    companion object {
        private val WATCHDOG_MIN_REBUILD_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2)
        private val APP_ID_REGEX = Regex("^[0-9A-Fa-f]{32}$")
        private const val PUSH_ACTIVE_WINDOW_MS = 30 * 60 * 1000L      // 30 min active push window
        private const val TTL_EVICTION_MS = 7 * 24 * 60 * 60 * 1000L   // 7 days full cleanup
        private const val MAX_REGISTERED_APPS = 5
        private const val REGISTRY_SAVE_DEBOUNCE_MS = 5_000L
        private const val PREF_GARMIN_DYNAMIC_V2_APPS = "garmin_dynamic_v2_apps"

        /** Floor between two V2 pushes, unless a caller passes force=true. */
        const val MIN_PUSH_INTERVAL_MS = 3_000L
    }

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

    @VisibleForTesting
    val isConnected = AtomicBoolean(false)

    @VisibleForTesting
    val lastMessengerRebuildAt = AtomicLong(0)

    @VisibleForTesting
    val lastV2PushAt = AtomicLong(0)

    /** True if `appId` (already upper-cased) has the shape of a Connect IQ app id. */
    fun matchesAppIdFormat(appId: String): Boolean = APP_ID_REGEX.matches(appId)

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

    /** Called on every `/get` that carries a well-formed `appId` parameter. */
    fun registerOrTouchDynamicApp(appId: String) {
        val previousLastSeen: Long
        synchronized(appRegistryLock) {
            val registry = appRegistryCache
                ?: loadRegisteredV2Apps().toMutableMap().also { appRegistryCache = it }

            previousLastSeen = registry[appId] ?: 0L
            val now = nowMillis()
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

    fun flushDynamicAppRegistry() {
        synchronized(appRegistryLock) {
            appRegistryCache?.let { saveRegisteredV2Apps(it) }
        }
    }

    /** App ids that have polled within the last PUSH_ACTIVE_WINDOW_MS. */
    fun getActiveV2AppIds(): Set<String> {
        val now = nowMillis()
        return synchronized(appRegistryLock) {
            (appRegistryCache ?: loadRegisteredV2Apps().toMutableMap().also { appRegistryCache = it })
                .filter { (_, lastSeen) -> now - lastSeen < PUSH_ACTIVE_WINDOW_MS }
                .keys.toSet()
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
     * next one goes out through the new messenger. `rebuildMessenger` is expected to
     * synchronously swap in a fresh GarminMessenger and dispose of the old one.
     */
    fun watchdogCheck(activeIds: Set<String>, rebuildMessenger: () -> Unit): Boolean {
        if (activeIds.isEmpty()) return false
        if (isConnected.get()) return false

        val now = nowMillis()
        val prev = lastMessengerRebuildAt.get()
        if (now - prev < WATCHDOG_MIN_REBUILD_INTERVAL_MS) return false
        if (!lastMessengerRebuildAt.compareAndSet(prev, now)) return false

        aapsLogger.warn(
            LTag.GARMIN,
            "Garmin messenger watchdog: rebuilding (disconnected, activeIds=${activeIds.size})"
        )
        isConnected.set(false)
        rebuildMessenger()
        return true
    }

    /**
     * V2 wake-up signal: intentionally minimal - just the key and "updateWatch" command.
     * The watch ignores the payload and immediately does an HTTP GET (/get) to fetch
     * the full glucose dataset itself, eliminating the 30-second long-poll connection
     * used by the old V1 pull model. Full data is never sent via CIQ in V2.
     */
    fun getGlucoseMessageV2(key: String): Map<String, Any> {
        return mapOf(
            "key" to key,
            "command" to "updateWatch"
        )
    }
}
