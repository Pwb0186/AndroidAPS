package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Step counts sent by a Garmin watch face over HTTP (`/get?steps=<today's total>`).
 *
 * AAPS keeps the last total and stores the difference as one 5-minute record
 * (`steps5min`, see [ingestTotalSteps]).
 *
 * Kept out of GarminPlugin so the plugin stays about glucose and push, and so the step
 * logic can be tested on its own (GarminStepsTest).
 *
 * @param clock current time; a function so GarminPlugin's test clock is used.
 * @param zone time zone for the midnight check - the watch resets its counter at
 *   local midnight.
 */
class GarminSteps(
    private val aapsLogger: AAPSLogger,
    private val sp: SP,
    private val loopHub: LoopHub,
    private val clock: () -> Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    companion object {

        @VisibleForTesting
        const val PREF_GARMIN_LAST_STEPS = "garmin_http_last_steps"
        @VisibleForTesting
        const val PREF_GARMIN_LAST_TS = "garmin_http_last_steps_ts"

        // Longest gap between two step readings whose delta is still stored.
        // The delta is always stored as a single 5-minute record (steps5min), so a
        // delta that actually accumulated over a much longer time (phone out of
        // range, watch face not running, AAPS restarted) would show up as a false
        // activity spike. Beyond this gap only the baseline is moved. Same limit
        // as the midnight-rollover branch already used.
        @VisibleForTesting
        const val MAX_STEPS_GAP_MS = 12 * 60 * 1000L

        /** AAPS treats all Garmin step inputs as one stream; see [ingestTotalSteps]. */
        private const val CANONICAL_DEVICE = "Garmin"
    }

    // The HttpServer serves requests on a thread pool - two /get at the same time
    // must not both compute a delta from the same last total.
    private val ingestLock = Any()

    /** Reads `steps=<today's total>` from a `/get` request, if it has one. */
    fun receive(uri: URI) {
        val totalSteps = uri.queryParameter("steps")?.toIntOrNull() ?: return
        aapsLogger.debug(LTag.GARMIN, "Garmin sent steps: $totalSteps")
        if (totalSteps >= 0) ingestTotalSteps(totalSteps)
    }

    private fun ingestTotalSteps(totalSteps: Int) {
        // The record covers the 5 minutes up to now.
        val samplingEnd = clock().instant().epochSecond
        val samplingStart = samplingEnd - 300

        synchronized(ingestLock) {
            // Note: AAPS treats all Garmin step inputs as a single unified stream ("Garmin").
            // Per-device tracking is not supported, matching the global PREF_GARMIN_LAST_STEPS.
            val now = clock().millis()
            val lastTotal = sp.getInt(PREF_GARMIN_LAST_STEPS, -1)
            val lastTs = sp.getLong(PREF_GARMIN_LAST_TS, 0L)

            // First measurement ever → record baseline value only, no delta
            if (lastTotal < 0) {
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                aapsLogger.info(LTag.GARMIN, "[GarminHTTP] baseline steps=$totalSteps")
                return
            }

            val today = clock().instant().atZone(zone()).toLocalDate()
            val lastDate = if (lastTs > 0L) Instant.ofEpochMilli(lastTs).atZone(zone()).toLocalDate() else today
            val isNewDay = today.isAfter(lastDate)
            val delta = totalSteps - lastTotal
            val gapMs = now - lastTs

            // New day AND a lower count: the watch's counter was reset at midnight.
            // A new day with a count that did not drop is still yesterday's total - the
            // watch read it just before its midnight and AAPS got it just after the
            // phone's. Treating it as a reset would store the whole day's steps
            // (e.g. 12,000) as one 5-minute record, so it is an ordinary delta, handled below.
            if (isNewDay && delta < 0) {
                // Midnight rollover — the watch step counter was reset for the new day
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] midnight rollover detected (lastDate=$lastDate today=$today totalSteps=$totalSteps)"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                if (totalSteps > 0 && gapMs in 1..MAX_STEPS_GAP_MS) {
                    loopHub.storeStepsCount(
                        Instant.ofEpochSecond(samplingStart),
                        Instant.ofEpochSecond(samplingEnd),
                        totalSteps,
                        CANONICAL_DEVICE
                    )
                } else if (totalSteps > 0) {
                    aapsLogger.info(
                        LTag.GARMIN,
                        "[GarminHTTP] midnight rollover after long gap ($gapMs ms); adjusting baseline without storing spike"
                    )
                }
                return
            }

            if (delta < 0) {
                // Sensor glitch, watch reboot, or watch/watchface change on the same day.
                // Adjust baseline only without recording totalSteps as a 5-minute activity spike!
                aapsLogger.warn(
                    LTag.GARMIN,
                    "[GarminHTTP] step counter dropped from $lastTotal to $totalSteps on same day; adjusting baseline without storing spike"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                return
            }

            // delta == 0: No movement or duplicate call
            if (delta == 0) {
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                aapsLogger.debug(LTag.GARMIN, "[GarminHTTP] delta=0, skipping (Total: $totalSteps,)")
                return
            }

            // delta > 0 after a long gap (phone out of range, watch face not running,
            // AAPS restarted): the steps accumulated over the whole gap, but would be
            // stored as a single 5-minute record - a false activity spike. Move the
            // baseline only, the same way the midnight-rollover branch above does.
            if (gapMs !in 1..MAX_STEPS_GAP_MS) {
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] long gap ($gapMs ms, delta=$delta, Total: $totalSteps,); adjusting baseline without storing spike"
                )
                sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
                sp.putLong(PREF_GARMIN_LAST_TS, now)
                return
            }

            // delta > 0: Normal activity
            aapsLogger.info(
                LTag.GARMIN,
                "[GarminHTTP] steps delta=$delta (${Instant.ofEpochSecond(samplingStart)} → ${Instant.ofEpochSecond(samplingEnd)}) Total: $totalSteps"
            )

            sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
            sp.putLong(PREF_GARMIN_LAST_TS, now)
            loopHub.storeStepsCount(
                Instant.ofEpochSecond(samplingStart),
                Instant.ofEpochSecond(samplingEnd),
                delta,
                CANONICAL_DEVICE
            )
        }
    }
}
