package com.nascon.league.ui.deeplink

import androidx.annotation.StringRes
import com.nascon.league.R
import com.nascon.league.deeplink.NotFoundReason

data class NotFoundContent(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    val canRetry: Boolean,
)

/** Pure mapping from a [NotFoundReason] code to plain copy. Unknown or null reasons get the generic copy. */
object NotFoundCopy {
    fun contentFor(reason: String?): NotFoundContent = when (reason) {
        NotFoundReason.INVALID_ID ->
            NotFoundContent(R.string.dl_not_found_title, R.string.dl_msg_invalid_id, false)
        NotFoundReason.UNKNOWN_HOST,
        NotFoundReason.UNSUPPORTED_COMBINATION,
        NotFoundReason.UNSUPPORTED_SCHEME ->
            NotFoundContent(R.string.dl_not_found_title, R.string.dl_msg_unsupported, false)
        NotFoundReason.MISSING_ID ->
            NotFoundContent(R.string.dl_not_found_title, R.string.dl_msg_missing_id, false)
        NotFoundReason.EXTRA_SEGMENTS ->
            NotFoundContent(R.string.dl_not_found_title, R.string.dl_msg_extra_segments, false)
        NotFoundReason.EMPTY ->
            NotFoundContent(R.string.dl_not_found_title, R.string.dl_msg_empty, false)
        NotFoundReason.ENTITY_MISSING ->
            NotFoundContent(R.string.dl_not_found_title_missing, R.string.dl_msg_entity_missing, true)
        else ->
            NotFoundContent(R.string.dl_not_found_title, R.string.dl_msg_generic, true)
    }
}
