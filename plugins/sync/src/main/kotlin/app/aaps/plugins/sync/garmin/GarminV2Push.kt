package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * V2 push-to-pull support for GarminPlugin: dynamic app discovery (so a
 * third-party Connect IQ fork doesn't need to be on any hardcoded app-ID
 * list, unlike the V1 path below) and the push-rate throttle.
 *
 * Split out of GarminPlugin.kt purely to keep that file's size down - there
 * is no behavior change from having this inline there instead. GarminPlugin
 * still owns the actual GarminMessenger/HTTP server and calls into this
 * class for everything specific to the V2 discovery-and-push path. The V1 path
 * (glucoseAppIds, sendPhoneAppMessage(), getGlucoseMessage()) is unrelated
 * and stays in GarminPlugin.kt.
 *
 * V2 push is a latency optimization on top of the pull model, never a
 * replacement for it: getGlucoseMessageV2() below sends only a wake-up
 * ping, never the payload itself - the watch always re-fetches the full
 * dataset over HTTP (GarminPlugin.onGetBloodGlucose()).
 *
 * There is deliberately no keep-alive push (a periodic push to every app
 * seen in the last 24 h, regardless of whether it is still active). It
 * looks like it should be the way back in for a watch face that has fallen
 * out of PUSH_ACTIVE_WINDOW_MS, but the watch face already has three
 * faster ways back on its own: the view redraws once a minute and calls
 * BackgroundScheduler.schedule2(), the temporal event polls every 5 min,
 * and init2() polls ~2 s after any foreground start. Measured: switching
 * back to a watch face produced a /get in the same second, not five
 * minutes later. A keep-alive would therefore only ever push to app IDs
 * whose app is not running any more - with no way to notice, since Garmin
 * Connect Mobile answers SUCCESS for a dead app just as often as for a
 * live one.
 */
class GarminV2Push(
    private val aapsLogger: AAPSLogger,
    private val sp: SP,
    private val nowMillis: () -> Long,
) {
    companion object {
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

    // There is deliberately no per-app failure counter, exclusion set or backoff
    // ladder here. The status Garmin Connect Mobile returns for a send only means
    // "GCM accepted the bytes", not that anything received it - measured, an app
    // that was not running at all was still answered SUCCESS almost every time.
    // Real failures are rare and self-healing; a scheme built on per-app send
    // status can't distinguish a dead app from a live one. What does help is the
    // no-answer timeout per message in GarminDeviceClient.

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
