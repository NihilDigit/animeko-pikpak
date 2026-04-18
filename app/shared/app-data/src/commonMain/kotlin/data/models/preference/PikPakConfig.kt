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
) {
    companion object {
        val Default = PikPakConfig()
    }
}
