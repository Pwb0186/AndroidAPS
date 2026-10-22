package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

enum class GarminStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val validator: StringValidator = StringValidator.NONE
) : StringPreferenceKey {

    RequestKey(key = "garmin_aaps_key", defaultValue = "", title = SyncStrings.garmin_request_key),
    // Commands (temp target, bolus, scenes, carbs, pump disconnect) need this key, which AAPS never
    // sends to a watch. Empty or shorter than 8 characters: commands are refused.
    CommandKey(
        key = "garmin_command_key", defaultValue = "", title = SyncStrings.garmin_command_key, isPassword = true,
        validator = StringValidator.regex(".{8,}", "At least 8 characters")
    ),
    ;

}
