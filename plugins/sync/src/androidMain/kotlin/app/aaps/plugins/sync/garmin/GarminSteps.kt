package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import java.net.URI
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
        /** The history of the windows as text, so a short AAPS restart keeps the windows. */
        @VisibleForTesting
        const val PREF_GARMIN_STEPS_HISTORY = "garmin_http_steps_history"

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

    // Deltas of the last 180 minutes, oldest first. Also kept in the preferences
    // (PREF_GARMIN_STEPS_HISTORY), so the windows survive a short AAPS restart. After a
    // restart with a gap over MAX_STEPS_GAP_MS the long-gap rule starts them again.
    private val history = ArrayDeque<Interval>()

    // Start of the history without a gap. Null when there is no history.
    private var historyStart: Long? = null

    // The saved history is read once, at the first reading after a start.
    private var historyLoaded = false

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
        synchronized(ingestLock) {
            // Note: AAPS treats all Garmin step inputs as a single unified stream ("Garmin").
            // Per-device tracking is not supported, matching the global PREF_GARMIN_LAST_STEPS.
            val now = clock().millis()
            val lastTotal = sp.getInt(PREF_GARMIN_LAST_STEPS, -1)
            val lastTs = sp.getLong(PREF_GARMIN_LAST_TS, 0L)
            loadHistory(lastTs)

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
        saveHistory()
    }

    private fun addInterval(start: Long, end: Long, steps: Int, totalSteps: Int) {
        sp.putInt(PREF_GARMIN_LAST_STEPS, totalSteps)
        sp.putLong(PREF_GARMIN_LAST_TS, end)
        history.addLast(Interval(start, end, steps))
        if (historyStart == null) historyStart = start
        val oldest = end - WINDOWS_MIN.last() * 60_000L
        while (history.isNotEmpty() && history.first().end <= oldest) history.removeFirst()
        saveHistory()
        storeWindows(end)
    }

    /** Saves the history as "historyStart|start:end:steps;start:end:steps;...". */
    private fun saveHistory() {
        val start = historyStart
        val text =
            if (start == null || history.isEmpty()) ""
            else "$start|" + history.joinToString(";") { "${it.start}:${it.end}:${it.steps}" }
        sp.putString(PREF_GARMIN_STEPS_HISTORY, text)
    }

    /** Reads the saved history once after a start. It is only used when it ends at the
     *  last reading ([lastTs]); text that cannot be read gives an empty history. */
    private fun loadHistory(lastTs: Long) {
        if (historyLoaded) return
        historyLoaded = true
        if (history.isNotEmpty()) return
        val (start, intervals) = parseHistory(sp.getString(PREF_GARMIN_STEPS_HISTORY, "")) ?: return
        if (intervals.last().end != lastTs) return
        historyStart = start
        history.addAll(intervals)
    }

    private fun parseHistory(text: String): Pair<Long, List<Interval>>? {
        val parts = text.split("|", limit = 2)
        if (parts.size != 2) return null
        return try {
            val start = parts[0].toLong()
            val intervals = parts[1].split(";").map { item ->
                val v = item.split(":")
                if (v.size != 3) return null
                Interval(v[0].toLong(), v[1].toLong(), v[2].toInt())
            }
            // Every interval must follow the one before it, as addInterval makes them.
            val valid = intervals.isNotEmpty() && start <= intervals.first().start &&
                intervals.all { it.end > it.start && it.steps >= 0 } &&
                intervals.zipWithNext().all { (a, b) -> b.start == a.end }
            if (valid) start to intervals else null
        } catch (_: NumberFormatException) {
            null
        }
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
}
