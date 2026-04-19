package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable

/**
 * User-configurable settings for the PikPak offline-download backend.
 *
 * The engine deletes each file from the user's PikPak drive immediately
 * after resolving its CDN URL (the URL survives deletion), so there is no
 * retention or folder-organization state to persist here.
 *
 * [deviceId] and [refreshToken] are written by the engine after a successful
 * signin/refresh (not user-editable). They let the next app launch skip the
 * rate-limited `/v1/auth/signin` endpoint and go straight to a cheap refresh.
 */
@Serializable
data class PikPakConfig(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val deviceId: String = "",
    val refreshToken: String = "",
    /**
     * How many distinct source buckets the engine keeps cached in its
     * working folder ("Animeko-Playing"). Real numeric values 1..13 are
     * bucket caps; the UI also offers a final "unlimited" stop (stored as
     * [SLOT_QUEUE_UNLIMITED]) that disables eviction entirely.
     */
    val slotQueueLength: Int = 1,
) {
    companion object {
        val Default = PikPakConfig()

        /** Last numeric step on the slider. */
        const val SLOT_QUEUE_MAX_NUMERIC: Int = 13

        /**
         * One step past [SLOT_QUEUE_MAX_NUMERIC]: the dedicated "no eviction"
         * stop. Any value ≥ this is treated as unlimited by the engine.
         */
        const val SLOT_QUEUE_UNLIMITED: Int = SLOT_QUEUE_MAX_NUMERIC + 1
    }
}
