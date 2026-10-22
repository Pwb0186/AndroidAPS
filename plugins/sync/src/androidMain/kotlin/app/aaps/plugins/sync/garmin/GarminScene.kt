package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.scenes.ActiveSceneSync
import app.aaps.core.interfaces.scenes.SceneActions
import app.aaps.core.interfaces.scenes.SceneAutomationApi
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * Scenes from a Garmin watch app, in the same two steps as the temp target and the Wear OS watch.
 *
 * /scene/prepare?id= lets AAPS check the scene and write the confirmation text ([SceneActions], the
 * path the Wear watch uses); /scene/commit?id= starts it once, after the user confirmed it on the
 * watch. /scene/stop ends the running scene without starting a follow-up scene, so the watch never
 * starts something the user did not see.
 *
 * A scene can change the profile and the loop mode, so it needs "Scenes from Garmin watch" (off by
 * default) next to the AAPS key the request handler checks.
 */
@SingleIn(AppScope::class)
@Inject
class GarminScene(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val sceneApi: SceneAutomationApi,
    private val sceneActions: SceneActions,
    private val activeSceneSync: ActiveSceneSync,
) {

    companion object {

        /** How long a prepared scene can be started. The watch closes its confirmation after 60 s. */
        @VisibleForTesting
        const val PREPARED_TTL_MS = 120_000L
    }

    @VisibleForTesting
    var now: () -> Long = { System.currentTimeMillis() }

    private class Prepared(val id: Long, val atMs: Long)

    private val lock = Any()
    private var prepared: Prepared? = null

    /** The scenes that can be started (disabled ones are hidden in AAPS too). */
    fun list(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val items = JsonArray()
        sceneApi.getScenes().filter { it.isEnabled }.forEach {
            items.add(JsonObject().apply {
                addProperty("id", it.id)
                addProperty("name", it.name)
            })
        }
        return JsonObject().apply {
            addProperty("ok", true)
            add("items", items)
        }.toString()
    }

    fun prepare(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        val sceneId = uri.queryParameter("id") ?: return error("Missing id")
        val progress = runBlocking { sceneActions.prepareStart(sceneId) }
        if (progress !is ActionProgress.Prepared) return error(failure(progress))
        synchronized(lock) { prepared = Prepared(progress.id, now()) }
        aapsLogger.info(LTag.GARMIN, "scene $sceneId prepared, id ${progress.id}")
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
        val progress = runBlocking { sceneActions.commitStart(id) }
        aapsLogger.info(LTag.GARMIN, "scene id $id committed: $progress")
        if (progress !is ActionProgress.Applied) return error(failure(progress))
        return ok("OK")
    }

    fun stop(uri: URI): CharSequence {
        refusal()?.let { return error(it) }
        if (!sceneApi.hasSceneToStop()) return error("No scene running")
        val progress = runBlocking { sceneActions.stop(triggerChain = false) }
        aapsLogger.info(LTag.GARMIN, "scene stopped from the watch: $progress")
        if (progress !is ActionProgress.Applied) return error(failure(progress))
        return ok("Scene ended")
    }

    /** For /status: whether the watch should offer scenes and "End scene". Not behind the setting: it only reads. */
    fun addStatus(json: JsonObject) {
        val active = sceneApi.hasSceneToStop()
        json.addProperty("sceneActive", active)
        if (active) activeSceneSync.getActiveState()?.let { json.addProperty("sceneName", it.scene.name) }
        json.addProperty("sceneCount", sceneApi.getScenes().count { it.isEnabled })
    }

    private fun refusal(): String? =
        if (!preferences.get(GarminBooleanKey.SceneFromWatch)) "Scenes from Garmin are off in AAPS" else null

    private fun failure(progress: ActionProgress): String = when (progress) {
        // The scene may or may not be started: say so, so it is not sent again blindly.
        is ActionProgress.Unconfirmed -> "Unknown - check AAPS"
        is ActionProgress.Rejected    -> progress.detail ?: rh.gs(progress.reason.failText())
        else                          -> "Error"
    }

    private fun ok(text: String): CharSequence = JsonObject().apply {
        addProperty("ok", true)
        addProperty("text", text)
    }.toString()

    private fun error(text: String): CharSequence {
        aapsLogger.warn(LTag.GARMIN, "scene refused: $text")
        return JsonObject().apply {
            addProperty("ok", false)
            addProperty("error", text)
        }.toString()
    }
}
