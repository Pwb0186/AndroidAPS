package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Step counts sent by a Garmin watch face over HTTP (`/get?steps=<today's total>`).
 *
 * AAPS keeps the last total and turns the difference into the steps of the last
 * 5, 10, 15, 30, 60 and 180 minutes (see [ingestTotalSteps] and [storeWindows]).
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
        // Over a longer gap (phone out of range, watch face not running, AAPS
        // stopped) we do not know when the steps were taken, so only the baseline
        // is moved and the windows start again.
        @VisibleForTesting
        const val MAX_STEPS_GAP_MS = 12 * 60 * 1000L

        /** The windows the Automation "Steps count" trigger and the Wear OS app use. */
        private val WINDOWS_MIN = listOf(5, 10, 15, 30, 60, 180)

        /** AAPS treats all Garmin step inputs as one stream; see [ingestTotalSteps]. */
        private const val CANONICAL_DEVICE = "Garmin"
    }

    /** Steps taken from [start] to [end] (epoch ms), from one delta of the total. */
    private class Interval(val start: Long, val end: Long, val steps: Int)

    // Deltas of the last 180 minutes, oldest first. Only in memory: after an AAPS
    // restart the windows start again, and a window is stored only once the history
    // covers it (see [storeWindows]).
    private val history = ArrayDeque<Interval>()

    // Start of the history without a gap. Null when there is no history.
    private var historyStart: Long? = null

    // The HttpServer serves requests on a thread pool - two /get at the same time
    // must not both compute a delta from the same last total.
    private val ingestLock = Any()

    /** Reads `steps=<today's total>` from a `/get` request, if it has one. */
    fun receive(uri: URI) {
        val totalSteps = queryParameter(uri, "steps")?.toIntOrNull() ?: return
        aapsLogger.debug(LTag.GARMIN, "Garmin sent steps: $totalSteps")
        if (totalSteps >= 0) ingestTotalSteps(totalSteps)
    }

    private fun ingestTotalSteps(totalSteps: Int) {
        synchronized(ingestLock) {
            // Note: AAPS treats all Garmin step inputs as a single unified stream ("Garmin").
            // Per-device tracking is not supported, matching the global PREF_GARMIN_LAST_STEPS.
            val now = clock().millis()
            val lastTotal = sp.getInt(PREF_GARMIN_LAST_STEPS, -1)
            val lastTs = sp.getLong(PREF_GARMIN_LAST_TS, 0L)

            // First measurement ever → record baseline value only, no delta
            if (lastTotal < 0) {
                moveBaseline(totalSteps, now)
                aapsLogger.info(LTag.GARMIN, "[GarminHTTP] baseline steps=$totalSteps")
                return
            }

            val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
            val lastDate = if (lastTs > 0L) Instant.ofEpochMilli(lastTs).atZone(zone()).toLocalDate() else today
            val isNewDay = today.isAfter(lastDate)
            val delta = totalSteps - lastTotal
            val gapMs = now - lastTs

            // Two /get at the same time with the same total: nothing new.
            if (gapMs == 0L && delta == 0) return

            // New day AND a lower count: the watch's counter was reset at midnight.
            // A new day with a count that did not drop is still yesterday's total - the
            // watch read it just before its midnight and AAPS got it just after the
            // phone's. Treating it as a reset would store the whole day's steps
            // (e.g. 12,000) as one record, so it is an ordinary delta, handled below.
            if (isNewDay && delta < 0) {
                if (gapMs in 1..MAX_STEPS_GAP_MS) {
                    aapsLogger.info(
                        LTag.GARMIN,
                        "[GarminHTTP] midnight rollover detected (lastDate=$lastDate today=$today totalSteps=$totalSteps)"
                    )
                    addInterval(lastTs, now, totalSteps, totalSteps)
                } else {
                    aapsLogger.info(
                        LTag.GARMIN,
                        "[GarminHTTP] midnight rollover after long gap ($gapMs ms); adjusting baseline without storing spike"
                    )
                    moveBaseline(totalSteps, now)
                }
                return
            }

            if (delta < 0) {
                // Sensor glitch, watch reboot, or watch/watchface change on the same day.
                // Adjust baseline only without recording totalSteps as an activity spike!
                aapsLogger.warn(
                    LTag.GARMIN,
                    "[GarminHTTP] step counter dropped from $lastTotal to $totalSteps on same day; adjusting baseline without storing spike"
                )
                moveBaseline(totalSteps, now)
                return
            }

            // Long gap (phone out of range, watch face not running, AAPS stopped): the
            // steps are from somewhere in the whole gap. Move the baseline only, the
            // same way the midnight-rollover branch above does.
            if (gapMs !in 1..MAX_STEPS_GAP_MS) {
                aapsLogger.info(
                    LTag.GARMIN,
                    "[GarminHTTP] long gap ($gapMs ms, delta=$delta, Total: $totalSteps,); adjusting baseline without storing spike"
                )
                moveBaseline(totalSteps, now)
                return
            }

            // delta >= 0: normal activity, or standing still (0 is stored too, so an
            // Automation rule like "fewer than 100 steps" can see it).
            val message = "[GarminHTTP] steps delta=$delta (${Instant.ofEpochMilli(lastTs)} → ${Instant.ofEpochMilli(now)}) Total: $totalSteps"
            if (delta > 0) aapsLogger.info(LTag.GARMIN, message) else aapsLogger.debug(LTag.GARMIN, message)
            addInterval(lastTs, now, delta, totalSteps)
        }
    }

    /** New starting point without a delta: the windows start again. */
    private fun moveBaseline(totalSteps: Int, now: Long) {
        sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
        sp.putLong(PREF_GARMIN_LAST_TS, now)
        history.clear()
        historyStart = null
    }

    private fun addInterval(start: Long, end: Long, steps: Int, totalSteps: Int) {
        sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
        sp.putLong(PREF_GARMIN_LAST_TS, end)
        history.addLast(Interval(start, end, steps))
        if (historyStart == null) historyStart = start
        val oldest = end - WINDOWS_MIN.last() * 60_000L
        while (history.isNotEmpty() && history.first().end <= oldest) history.removeFirst()
        storeWindows(end)
    }

    /** Stores the steps of every window that the history fully covers. A window that
     *  is not covered yet (just after a start, a long gap or a counter drop) is left
     *  out, so the Automation trigger finds no record for it instead of a count that
     *  is too low. Steps of an interval are spread evenly over its time. */
    private fun storeWindows(now: Long) {
        val start = historyStart ?: return
        val steps = WINDOWS_MIN
            .filter { now - it * 60_000L >= start }
            .associateWith { window -> stepsSince(now - window * 60_000L) }
        if (steps.isNotEmpty()) loopHub.storeStepsCount(Instant.ofEpochMilli(now), steps, CANONICAL_DEVICE)
    }

    private fun stepsSince(from: Long): Int =
        history.sumOf { i ->
            val overlap = i.end - maxOf(i.start, from)
            if (overlap <= 0) 0.0 else i.steps.toDouble() * overlap / (i.end - i.start)
        }.roundToInt()

    // Same parsing as GarminPlugin.getQueryParameter: decode the raw query once,
    // so "+" and "%xx" in a value come out right.
    private fun queryParameter(uri: URI, name: String): String? {
        val raw = (uri.rawQuery ?: "")
            .split("&")
            .map { kv -> kv.split("=", limit = 2) }
            .firstOrNull { kv -> kv.size == 2 && kv[0] == name }?.get(1)
            ?: return null
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            raw
        }
    }
}
