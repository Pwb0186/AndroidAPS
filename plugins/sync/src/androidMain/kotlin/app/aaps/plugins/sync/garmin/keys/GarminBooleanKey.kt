package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.garmin.GarminKeyGuard

/** The watch switches can only be turned on once a control key is set: without it every command is refused. */
private val controlKeySet = PreferenceEnabledCondition { ctx ->
    ctx.preferences.get(GarminStringKey.CommandKey).length >= GarminKeyGuard.MIN_KEY_LENGTH
}

enum class GarminBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val defaultedBySM: Boolean = false,
    override val hideParentScreenIfHidden: Boolean = false,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
) : BooleanPreferenceKey {

    LocalHttpServer("communication_http", false, title = SyncStrings.garmin_local_http_server, defaultedBySM = true, hideParentScreenIfHidden = true),
    // Off by default: lets a watch app set a temp target (see GarminTempTarget).
    TempTargetFromWatch("garmin_tt_from_watch", false, title = SyncStrings.garmin_tt_from_watch, enabledCondition = controlKeySet),
    // Off by default: lets a watch app give a bolus (wizard, Quick Wizard, treatment with insulin; see GarminBolus).
    BolusFromWatch("garmin_bolus_from_watch", false, title = SyncStrings.garmin_bolus_from_watch, enabledCondition = controlKeySet),
    // Off by default: lets a watch app add carbs without insulin (treatment; see GarminBolus).
    CarbsFromWatch("garmin_carbs_from_watch", false, title = SyncStrings.garmin_carbs_from_watch, enabledCondition = controlKeySet),
    // Off by default: lets a watch app start and end a scene (see GarminScene).
    SceneFromWatch("garmin_scene_from_watch", false, title = SyncStrings.garmin_scene_from_watch, enabledCondition = controlKeySet),
    ;

}
