package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * Logs a workout from a Garmin watch app as a Careportal "Exercise" event.
 *
 * /exercise?start=<epoch s>&duration=<min>&note=<text> stores one [TE.Type.EXERCISE] event. It is only a
 * record for the graph and Nightscout: AAPS does not use it for any dosing, so the request handler
 * needs no remote control PIN, as for /get.
 *
 * The data field may send the same workout again, for example after a lost answer. The event is
 * stored with [PersistenceLayer.insertPumpTherapyEventIfNewByTimestamp], the call the Careportal uses.
 * It checks type and start time and writes in one database transaction, so a second request
 * for the same start time changes nothing.
 *
 * On a client the event would stay on the client and never reach the master, so it is refused there
 * with a notification, as /carbs is.
 */
class GarminExercise(
    private val aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseUnit: () -> GlucoseUnit,
    private val isClient: () -> Boolean,
    private val refuseOnClient: () -> Unit,
    private val now: () -> Long,
) {

    companion object {

        /** The watch clock may be a little ahead of the phone clock. */
        @VisibleForTesting
        const val MAX_FUTURE_MS = 2 * 60_000L

        /** An older workout is more likely a wrong clock than a real workout to log. */
        @VisibleForTesting
        const val MAX_AGE_MS = 24 * 60 * 60_000L

        @VisibleForTesting
        val DURATION_MIN = 1..720

        /** The Careportal note field is short, and a long note does not fit on the graph. */
        @VisibleForTesting
        const val MAX_NOTE_LENGTH = 60

        private const val MS_PER_MIN = 60_000L
    }

    fun log(uri: URI): CharSequence {
        if (isClient()) {
            refuseOnClient()
            return error("Not on AAPSClient")
        }
        val startSec = uri.queryParameter("start")?.toLongOrNull() ?: return error("Missing start")
        // Checked before the multiplication, so a huge value cannot wrap around into a valid time.
        if (startSec < 0 || startSec > Long.MAX_VALUE / 1000) return error("Invalid start")
        val startMs = startSec * 1000
        val nowMs = now()
        if (startMs > nowMs + MAX_FUTURE_MS) return error("Start is in the future")
        if (startMs < nowMs - MAX_AGE_MS) return error("Start is more than 24 h ago")
        val duration = uri.queryParameter("duration")?.toIntOrNull() ?: return error("Missing duration")
        if (duration !in DURATION_MIN) return error("Duration must be 1 to 720 min")
        val note = cleanNote(uri.queryParameter("note"))

        val event = TE(
            timestamp = startMs,
            type = TE.Type.EXERCISE,
            duration = duration * MS_PER_MIN,
            note = note.ifEmpty { null },
            enteredBy = "AAPS",
            glucoseUnit = glucoseUnit()
        )
        val result = try {
            runBlocking {
                persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = event,
                    action = Action.CAREPORTAL,
                    source = Sources.Garmin,
                    note = event.note,
                    listValues = listOf(ValueWithUnit.Timestamp(startMs), ValueWithUnit.TEType(event.type), ValueWithUnit.Minute(duration))
                )
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.GARMIN, "exercise at $startSec not stored", e)
            return error("Error")
        }
        val text = if (result.inserted.isEmpty()) "Already logged" else "OK"
        aapsLogger.info(LTag.GARMIN, "exercise at $startSec, $duration min: $text")
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", text)
        }.toString()
    }

    private fun cleanNote(raw: String?): String {
        val cut = (raw ?: "").trim().take(MAX_NOTE_LENGTH)
        // A cut in the middle of a surrogate pair (an emoji) would leave a broken character.
        return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    private fun error(text: String): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "exercise refused: $text")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", text)
        }.toString()
    }
}
