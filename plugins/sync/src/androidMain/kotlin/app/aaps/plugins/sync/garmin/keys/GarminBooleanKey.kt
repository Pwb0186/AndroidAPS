package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

enum class GarminBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val defaultedBySM: Boolean = false,
    override val hideParentScreenIfHidden: Boolean = false,
) : BooleanPreferenceKey {

    LocalHttpServer("communication_http", false, title = SyncStrings.garmin_local_http_server, defaultedBySM = true, hideParentScreenIfHidden = true),
    // Off by default: lets a watch app set a temp target (see GarminTempTarget).
    TempTargetFromWatch("garmin_tt_from_watch", false, title = SyncStrings.garmin_tt_from_watch),
    // Off by default: lets a watch app give a bolus (wizard, Quick Wizard; see GarminBolus).
    BolusFromWatch("garmin_bolus_from_watch", false, title = SyncStrings.garmin_bolus_from_watch),
    ;

}
