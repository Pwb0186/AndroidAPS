package app.aaps.plugins.sync.garmin.g7


/**
 * The phone's half of a G7 pairing that a Garmin watch runs over its own Bluetooth link.
 *
 * A Connect IQ watch can talk to the sensor but cannot do the pairing's cryptography (EC-JPAKE over
 * P-256 and an ECDSA signature). So the watch relays: it connects to the sensor in the watch slot,
 * moves the bytes, and asks the phone for the three things only the phone can make. The Bluetooth bond
 * the sensor asks for at the end is then between the watch and the sensor, which is the point.
 *
 * The steps follow G7Authenticator in AndroidAPS PR #5397 exactly; only the parts that need the
 * secret code or Dexcom's key run here:
 *
 * 1. [start] before the watch connects: our key exchange rounds 1 and 2 (they do not depend on the
 *    sensor) and Dexcom's certificates.
 * 2. [round3] once the watch has the sensor's three rounds: our round 3 and the shared key.
 * 3. [sign] once the watch has the sensor's key challenge: the 64-byte signature.
 *
 * Everything else (the AES challenge, the certificate exchange, finalize and bond) the watch does
 * itself, because after step 2 it has the key.
 *
 * One pairing at a time. A session is dropped after [sign], on a new [start], or when it is older
 * than [SESSION_TTL_MS], so the code and the key exchange state do not linger on the phone.
 */
class G7PairingRelay(
    private val random: (Int) -> ByteArray = G7JPake::secureRandomBytes,
    private val now: () -> Long = System::currentTimeMillis,
    /** Short description of each step. Never carries the code, a key or a signature. */
    private val log: (String) -> Unit = {}
) {

    class Start(val sessionId: String, val round1: ByteArray, val round2: ByteArray, val certificates: List<ByteArray>)

    class Round3(val round3: ByteArray, val sharedKey: ByteArray)

    class RelayException(message: String) : Exception(message)

    private class Session(val id: String, val jpake: G7JPake, val createdAt: Long)

    private var session: Session? = null

    @Synchronized
    fun start(pairingCode: String): Start {
        if (!PAIRING_CODE.matches(pairingCode)) throw RelayException("The pairing code is 4 digits")
        val jpake = G7JPake(pairingCode, random)
        val id = random(8).toHex()
        val round1 = jpake.makeRound1()
        val round2 = jpake.makeRound2()
        session = Session(id, jpake, now())
        log("Relay pairing started")
        return Start(id, round1, round2, G7DexcomCredentials.certificates)
    }

    @Synchronized
    fun round3(sessionId: String, sensorRound1: ByteArray, sensorRound2: ByteArray, sensorRound3: ByteArray): Round3 {
        val s = current(sessionId)
        val r1 = decode(sensorRound1, 1)
        val r2 = decode(sensorRound2, 2)
        val r3 = decode(sensorRound3, 3)
        // Advisory only, as in G7Authenticator: the sensor's proofs do not verify under our transcript
        // format; the AES challenge the watch runs next is the real check.
        val proofsVerified = s.jpake.validateRound1Or2(r1) && s.jpake.validateRound1Or2(r2) && s.jpake.validateRound3(r1, r3)
        if (!proofsVerified) log("Relay: the sensor's proofs do not match our format; this is normal")
        val secret = s.jpake.deriveSharedSecret(r2, r3)
        val ours = s.jpake.makeRound3(r1, r2)
        log("Relay: key exchange complete")
        return Round3(ours, secret.copyOf(16))
    }

    /** [keyChallengeAcknowledgement] is the sensor's whole 0x0C acknowledgement, as the watch got it. */
    @Synchronized
    fun sign(sessionId: String, keyChallengeAcknowledgement: ByteArray): ByteArray {
        current(sessionId)
        if (keyChallengeAcknowledgement.size < 18 || keyChallengeAcknowledgement.u8(0) != 0x0C) {
            throw RelayException("Not a key challenge acknowledgement")
        }
        val signature = G7ChallengeSigner.sign(keyChallengeAcknowledgement)
        session = null
        log("Relay: key challenge signed, session closed")
        return signature
    }

    @Synchronized
    fun cancel() {
        session = null
    }

    private fun current(sessionId: String): Session {
        val s = session ?: throw RelayException("No pairing in progress")
        if (s.id != sessionId) throw RelayException("Unknown pairing session")
        if (now() - s.createdAt > SESSION_TTL_MS) {
            session = null
            throw RelayException("The pairing session has expired")
        }
        return s
    }

    private fun decode(data: ByteArray, round: Int): G7PCert {
        if (data.size != G7PCert.BYTE_COUNT) throw RelayException("Sensor round $round is ${data.size} bytes, expected ${G7PCert.BYTE_COUNT}")
        return G7PCert.decode(data)
    }

    companion object {

        /** Long enough for a pairing over the watch's link, short enough not to keep a code around. */
        const val SESSION_TTL_MS = 10 * 60 * 1000L

        private val PAIRING_CODE = Regex("^[0-9]{4}$")
    }
}
