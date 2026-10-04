package app.aaps.plugins.sync.garmin

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Returns the value of the query parameter [name], or null if it is missing.
 *
 * Reads the raw (still encoded) query and decodes the value exactly once.
 * `uri.query` is already decoded, so decoding that again turned "+" into a space
 * and broke "%xx" (e.g. an AAPS key with "+" never matched). A value that is not
 * valid "%xx" encoding is returned as it is.
 *
 * Used by GarminPlugin and GarminSteps, so both read a request the same way.
 */
internal fun URI.queryParameter(name: String): String? {
    val raw = (rawQuery ?: "")
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
