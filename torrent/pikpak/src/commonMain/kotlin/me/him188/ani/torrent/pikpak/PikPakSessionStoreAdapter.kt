package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore

/**
 * Bridges the SDK's [SessionStore] to whatever refresh-token persistence the
 * app already has (typically [PikPakConfig] via DataStore). Only the refresh
 * token is persisted; the short-lived access token is recomputed on each app
 * start via a single refresh round-trip, which is cheaper than the schema
 * migration needed to widen [PikPakConfig] with accessToken/expiresAt/sub.
 *
 * [load] synthesises a [Session] with `expiresAt = 0` so the SDK treats the
 * cached access token as already stale and goes straight into refresh. If
 * [readRefreshToken] yields an empty string, we return `null` and the SDK
 * falls through to full credentials sign-in.
 */
class PikPakSessionStoreAdapter(
    private val readRefreshToken: () -> String,
    private val writeRefreshToken: suspend (String) -> Unit,
) : SessionStore {

    override suspend fun load(account: String): Session? {
        val rt = readRefreshToken()
        if (rt.isEmpty()) return null
        return Session(
            accessToken = "",
            refreshToken = rt,
            sub = "",
            expiresAt = 0L,
        )
    }

    override suspend fun save(account: String, session: Session) {
        writeRefreshToken(session.refreshToken)
    }

    override suspend fun clear(account: String) {
        writeRefreshToken("")
    }
}
