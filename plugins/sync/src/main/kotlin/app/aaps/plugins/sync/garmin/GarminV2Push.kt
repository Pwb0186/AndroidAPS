package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * V2 push-to-pull: dynamic app discovery (a watch app registers itself by sending
 * its appId on /get) and the push-rate throttle. The push is only a wake-up ping;
 * the watch always fetches the data itself over HTTP (/get).
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
        private const val PREF_GARMIN_DYNAMIC_V2_APPS = "garmin_dynamic_v2_apps"

        /** Floor between two V2 pushes, unless a caller passes force=true. */
        const val MIN_PUSH_INTERVAL_MS = 3_000L
    }

    private val appRegistryLock = Any()
    private var appRegistryCache: MutableMap<String, Long>? = null

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
        synchronized(appRegistryLock) {
            val registry = appRegistryCache
                ?: loadRegisteredV2Apps().toMutableMap().also { appRegistryCache = it }
            val now = nowMillis()
            registry[appId] = now

            // TTL eviction (7 days)
            val cutoff = now - TTL_EVICTION_MS
            registry.entries.removeAll { entry -> entry.value < cutoff }

            // Quota eviction (max 5)
            while (registry.size > MAX_REGISTERED_APPS) {
                registry.minByOrNull { it.value }?.key?.let { registry.remove(it) }
            }

            saveRegisteredV2Apps(registry)
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
