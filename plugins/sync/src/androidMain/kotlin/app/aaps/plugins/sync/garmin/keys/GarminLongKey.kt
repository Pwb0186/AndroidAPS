package app.aaps.plugins.sync.garmin.keys

import app.aaps.plugins.sync.SyncStrings
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.TextRef

enum class GarminLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val min: Long,
    override val max: Long,
    override val title: TextRef,
) : LongPreferenceKey {

    LastStepsTotalTs("garmin_last_steps_total_ts", 0L, 0L, Long.MAX_VALUE, title = SyncStrings.garmin),
    ;
}
