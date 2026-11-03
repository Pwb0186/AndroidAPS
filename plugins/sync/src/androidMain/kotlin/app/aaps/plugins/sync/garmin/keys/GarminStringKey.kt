package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

enum class GarminStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val validator: StringValidator = StringValidator.NONE
) : StringPreferenceKey {

    RequestKey(key = "garmin_aaps_key", defaultValue = "", title = SyncStrings.garmin_request_key),
    // Commands (temp target, bolus, scenes, carbs, pump disconnect) need this remote control PIN, which AAPS never
    // sends to a watch. Shown as a PIN so the phone offers a number keyboard; anything but 8-16 digits
    // counts as not set (see GarminKeyGuard), because the PIN dialog does not use a validator.
    CommandKey(
        key = "garmin_command_key", defaultValue = "", title = SyncStrings.garmin_command_key,
        summary = SyncStrings.garmin_command_key_summary, isPin = true
    ),
    ;

}
