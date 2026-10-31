package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.sync.garmin.g7.G7PairingRelay
import app.aaps.plugins.sync.garmin.g7.hexToBytesOrNull
import app.aaps.plugins.sync.garmin.g7.toHex
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

/**
 * Experimental: lets a Garmin watch pair with a Dexcom G7 in the sensor's watch slot.
 *
 * The watch connects to the sensor and moves the bytes; the parts of the pairing a Connect IQ watch
 * cannot compute run here (see [G7PairingRelay]):
 *
 * - `/g7pair/start?code=1234` - our key exchange rounds 1 and 2 and Dexcom's certificates.
 * - `/g7pair/round3?id=..&r1=..&r2=..&r3=..` - from the sensor's three rounds: our round 3 and the
 *   16-byte key the watch keeps for every later reconnect.
 * - `/g7pair/sign?id=..&ack=..` - the signature for the sensor's key challenge. Ends the session.
 * - `/g7pair/cancel` - drops a session the watch gave up on.
 *
 * All values are hex. The endpoints need no key: nothing here changes treatment or the AAPS BG
 * source (the watch only shows what it reads), and the phone holds no secret in it - the sensor key
 * comes from the code the caller sends, and Dexcom's certificates and signing key are public
 * protocol data. Their requests and answers are not written to the log: they carry the pairing code
 * and the sensor key.
 */
class GarminG7Pairing(
    private val aapsLogger: AAPSLogger,
    private val relay: G7PairingRelay = G7PairingRelay(log = { aapsLogger.info(LTag.GARMIN, it) })
) {

    fun start(uri: URI): CharSequence = handle {
        val start = relay.start(uri.queryParameter("code") ?: "")
        // Log the first 8 bytes of each round, enough to compare against the watch's log.
        // These are J-PAKE round public values, not the shared key or the code.
        aapsLogger.info(
            LTag.GARMIN,
            "G7 relay start id=${start.sessionId} " +
                "r1=${start.round1.copyOf(8).toHex()}... " +
                "r2=${start.round2.copyOf(8).toHex()}... " +
                "certs=${start.certificates.map { it.size }}"
        )
        JsonObject().apply {
            addProperty("ok", true)
            addProperty("id", start.sessionId)
            addProperty("r1", start.round1.toHex())
            addProperty("r2", start.round2.toHex())
            add("certs", JsonArray().apply { start.certificates.forEach { add(it.toHex()) } })
        }
    }

    fun round3(uri: URI): CharSequence = handle {
        val result = relay.round3(id(uri), bytes(uri, "r1"), bytes(uri, "r2"), bytes(uri, "r3"))
        // First 8 bytes of our round 3; no part of the shared key reaches the log.
        aapsLogger.info(LTag.GARMIN, "G7 relay round3 id=${id(uri)} r3=${result.round3.copyOf(8).toHex()}...")
        JsonObject().apply {
            addProperty("ok", true)
            addProperty("r3", result.round3.toHex())
            addProperty("key", result.sharedKey.toHex())
        }
    }

    fun sign(uri: URI): CharSequence = handle {
        val signature = relay.sign(id(uri), bytes(uri, "ack"))
        JsonObject().apply {
            addProperty("ok", true)
            addProperty("sig", signature.toHex())
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun cancel(uri: URI): CharSequence {
        relay.cancel()
        aapsLogger.info(LTag.GARMIN, "G7 pairing from watch cancelled")
        return JsonObject().apply { addProperty("ok", true) }.toString()
    }

    private fun id(uri: URI): String = uri.queryParameter("id") ?: throw G7PairingRelay.RelayException("Missing id")

    private fun bytes(uri: URI, name: String): ByteArray =
        uri.queryParameter(name)?.hexToBytesOrNull() ?: throw G7PairingRelay.RelayException("Missing or bad $name")

    private fun handle(action: () -> JsonObject): CharSequence =
        try {
            action().toString()
        } catch (e: G7PairingRelay.RelayException) {
            error(e.message ?: "refused")
        } catch (e: Exception) {
            // A malformed key exchange round (not a point on the curve and the like), or the
            // platform crypto failing (GeneralSecurityException is not a RuntimeException).
            aapsLogger.warn(LTag.GARMIN, "G7 pairing from watch: bad data: ${e.message}")
            error("Bad data")
        }

    private fun error(text: String): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "G7 pairing from watch refused: $text")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", text)
        }.toString()
    }
}
