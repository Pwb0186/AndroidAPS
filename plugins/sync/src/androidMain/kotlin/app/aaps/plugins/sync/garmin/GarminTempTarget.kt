package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.tempTargets.ttDurationMinutes
import app.aaps.core.interfaces.tempTargets.ttTargetMgdl
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * Temp target from a Garmin watch app, in two steps like the Wear OS watch.
 *
 * /tt/prepare?preset=... parks the temp target in AAPS through [BatchExecutor] (the path the Wear
 * watch uses, with the same limits and checks) and returns the confirmation text written by AAPS
 * and an id. The watch shows that text, and only when the user confirms it there, /tt/commit?id=...
 * applies the parked temp target, once.
 *
 * The endpoints are on localhost, where any app on the phone can call them. They therefore only
 * work when "Temp target from Garmin watch" is on (off by default), and the request handler only
 * lets them through with the AAPS key. Only the presets from the AAPS temp target settings can be
 * set, and only the newest id is accepted, for [PREPARED_TTL_MS].
 */
class GarminTempTarget(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val batchExecutor: BatchExecutor,
    private val activeTempTarget: () -> TT?,
    private val now: () -> Long,
) {

    companion object {

        /** How long a prepared temp target can be confirmed. The watch closes its confirmation after 60 s. */
        @VisibleForTesting
        const val PREPARED_TTL_MS = 120_000L
    }

    private class Prepared(val id: Long, val atMs: Long)

    private val lock = Any()
    private var prepared: Prepared? = null

    fun prepare(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val preset = uri.queryParameter("preset")
        val action = when (preset) {
            "activity" -> presetAction(TT.Reason.ACTIVITY)
            "eating"   -> presetAction(TT.Reason.EATING_SOON)
            "hypo"     -> presetAction(TT.Reason.HYPOGLYCEMIA)
            // Duration 0 cancels the running temp target, as from the Wear watch. Without a running
            // one AAPS would still answer "applied", which would tell the user something was cancelled.
            "cancel"   ->
                if (activeTempTarget() == null) return error("No temp target running")
                else BatchAction.TempTarget(TT.Reason.CUSTOM.text, 0.0, 0.0, 0, 0)
            else       -> return error("Unknown preset")
        }
        val progress = runBlocking { batchExecutor.prepare(listOf(action), Sources.Garmin, label()) }
        if (progress !is ActionProgress.Prepared) return error(failure(progress))
        synchronized(lock) { prepared = Prepared(progress.id, now()) }
        aapsLogger.info(LTag.GARMIN, "temp target $preset prepared, id ${progress.id}")
        val lines = JsonArray().apply { progress.lines.forEach { add(it.text) } }
        return JsonObject().apply {
            addProperty("ok", true)
            // As text: the id does not fit in a number on the watch (32 bit).
            addProperty("id", progress.id.toString())
            add("lines", lines)
        }.toString()
    }

    fun commit(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val id = uri.queryParameter("id")?.toLongOrNull() ?: return error("Missing id")
        // Taken out under the lock, so an id can only be committed once.
        val p = synchronized(lock) { prepared?.takeIf { it.id == id }?.also { prepared = null } }
            ?: return error("Not prepared or already used")
        if (now() - p.atMs > PREPARED_TTL_MS) return error("Expired, choose again")
        val progress = try {
            runBlocking { batchExecutor.commit(id, Sources.Garmin, label()) }
        } catch (e: Exception) {
            // The temp target may have been set before it failed.
            aapsLogger.error(LTag.GARMIN, "temp target id $id commit failed", e)
            return unknown()
        }
        aapsLogger.info(LTag.GARMIN, "temp target id $id committed: $progress")
        // Only a refusal proves that nothing was set.
        if (progress is ActionProgress.Rejected) return error(failure(progress))
        if (progress !is ActionProgress.Applied) return unknown()
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", "OK")
        }.toString()
    }

    private fun label() = rh.gs(CoreUiStrings.temporary_target)

    private fun presetAction(reason: TT.Reason): BatchAction.TempTarget {
        val mgdl = preferences.ttTargetMgdl(reason)
        return BatchAction.TempTarget(reason.text, mgdl, mgdl, preferences.ttDurationMinutes(reason), 0)
    }

    private fun refusal(): String? = when {
        !preferences.get(GarminBooleanKey.TempTargetFromWatch) -> "Temp target from Garmin is off in AAPS"
        else                                                   -> null
    }

    private fun failure(progress: ActionProgress): String = when (progress) {
        // The temp target may or may not be set: say so, so it is not sent again blindly.
        is ActionProgress.Unconfirmed -> "Unknown - check AAPS"
        is ActionProgress.Rejected    -> progress.detail ?: rh.gs(progress.reason.failText())
        else                          -> "Error"
    }

    /** The watch must not send it again blindly: it may have been done. */
    private fun unknown(): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "temp target outcome unknown")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", "Unknown - check AAPS")
            addProperty("unknown", true)
        }.toString()
    }

    private fun error(text: String): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "temp target refused: $text")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", text)
        }.toString()
    }
}
