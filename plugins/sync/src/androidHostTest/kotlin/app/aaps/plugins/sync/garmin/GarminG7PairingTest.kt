package app.aaps.plugins.sync.garmin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.sync.garmin.g7.G7DexcomCredentials
import app.aaps.plugins.sync.garmin.g7.G7JPake
import app.aaps.plugins.sync.garmin.g7.G7PCert
import app.aaps.plugins.sync.garmin.g7.G7PairingRelay
import app.aaps.plugins.sync.garmin.g7.hexToBytesOrNull
import app.aaps.plugins.sync.garmin.g7.toHex
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The phone's half of the watch's G7 pairing, through the HTTP handlers. A second [G7JPake] plays
 * the sensor's side of the key exchange: if both reach the same key, the relay really runs the
 * exchange. The full split pairing against a simulated sensor is proven in the G7Probe work.
 */
class GarminG7PairingTest {

    private val pairing = GarminG7Pairing(mock<AAPSLogger>())

    private fun call(handler: (URI) -> CharSequence, query: String): JsonObject =
        JsonParser.parseString(handler(URI("http://127.0.0.1:28891/x?$query")).toString()).asJsonObject

    private fun JsonObject.hex(name: String): ByteArray = get(name).asString.hexToBytesOrNull()!!

    @Test
    fun wholeRelay_givesTheSensorsKey_andAValidSignature() {
        val start = call(pairing::start, "code=1155")
        assertTrue(start.get("ok").asBoolean)
        val id = start.get("id").asString
        val certs = start.getAsJsonArray("certs").map { it.asString.hexToBytesOrNull()!! }
        assertEquals(G7DexcomCredentials.certificates.map { it.toHex() }, certs.map { it.toHex() })

        val sensor = G7JPake("1155")
        val s1 = sensor.makeRound1()
        val s2 = sensor.makeRound2()
        val s3 = sensor.makeRound3(G7PCert.decode(start.hex("r1")), G7PCert.decode(start.hex("r2")))
        val round3 = call(pairing::round3, "id=$id&r1=${s1.toHex()}&r2=${s2.toHex()}&r3=${s3.toHex()}")
        assertTrue(round3.get("ok").asBoolean)
        val sensorKey = sensor.deriveSharedSecret(G7PCert.decode(start.hex("r2")), G7PCert.decode(round3.hex("r3"))).copyOf(16)
        assertEquals(sensorKey.toHex(), round3.get("key").asString)

        val toSign = ByteArray(16) { (0xA0 + it).toByte() }
        val ack = byteArrayOf(0x0C, 0x00) + toSign
        val sign = call(pairing::sign, "id=$id&ack=${ack.toHex()}")
        assertTrue(sign.get("ok").asBoolean)
        assertTrue(verify(toSign, sign.hex("sig")))

        // The session ends with the signature.
        assertFalse(call(pairing::sign, "id=$id&ack=${ack.toHex()}").get("ok").asBoolean)
    }

    @Test
    fun badInput_isRefusedWithoutAThrow() {
        assertFalse(call(pairing::start, "code=12a4").get("ok").asBoolean)
        assertFalse(call(pairing::start, "").get("ok").asBoolean)
        val id = call(pairing::start, "code=1155").get("id").asString
        assertFalse(call(pairing::round3, "id=$id&r1=zz&r2=00&r3=00").get("ok").asBoolean)
        assertFalse(call(pairing::round3, "id=$id&r1=00&r2=00&r3=00").get("ok").asBoolean)
        assertFalse(call(pairing::sign, "id=other&ack=0c00").get("ok").asBoolean)
        assertTrue(call(pairing::cancel, "").get("ok").asBoolean)
        assertFalse(call(pairing::sign, "id=$id&ack=0c00${"00".repeat(16)}").get("ok").asBoolean)
    }

    @Test
    fun session_expiresAfterTtl() {
        var now = 1_000_000L
        val timed = GarminG7Pairing(mock<AAPSLogger>(), G7PairingRelay(now = { now }))
        val id = call(timed::start, "code=1155").get("id").asString
        val ack = "0c00" + "00".repeat(16)
        now += G7PairingRelay.SESSION_TTL_MS + 1
        val late = call(timed::sign, "id=$id&ack=$ack")
        assertFalse(late.get("ok").asBoolean)
        assertEquals("The pairing session has expired", late.get("error").asString)
    }

    @Test
    fun aNewStart_replacesTheOldSession() {
        val first = call(pairing::start, "code=1155").get("id").asString
        call(pairing::start, "code=2266")
        val ack = "0c00" + "00".repeat(16)
        assertFalse(call(pairing::sign, "id=$first&ack=$ack").get("ok").asBoolean)
    }

    private fun verify(signed: ByteArray, raw: ByteArray): Boolean {
        val spki = "3059301306072a8648ce3d020106082a8648ce3d030107034200".hexToBytesOrNull()!! + G7DexcomCredentials.challengePublicKey
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        return Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(publicKey)
            update(signed)
            verify(raw)
        }
    }
}
