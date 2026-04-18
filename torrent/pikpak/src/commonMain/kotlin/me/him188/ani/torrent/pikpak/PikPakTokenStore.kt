package me.him188.ani.torrent.pikpak

/**
 * Narrow contract for persisting PikPak auth state between app runs.
 *
 * PikPak's `/v1/auth/signin` endpoint is aggressively rate-limited per device
 * + IP (observed: minutes-long lockouts after a handful of signins in a short
 * window). To keep the app usable across restarts we persist [deviceId] plus
 * the [refreshToken]; with both, the next session's first auth call can go
 * through `/v1/auth/token` (refresh) instead of `/v1/auth/signin`.
 *
 * `access_token` is *not* persisted — it's short-lived (~2 h) and always
 * re-derivable from the refresh token, so storing it would only add surface
 * area without saving round-trips.
 *
 * Implementations must be safe to read from any thread; [update] is called
 * from `PikPakAuth` right after a successful signin or refresh and may run
 * concurrently with reads (writes should be atomic w.r.t. reads, but reads
 * don't need to see writes strictly in order).
 */
interface PikPakTokenStore {
    /**
     * Persisted device fingerprint. Empty means "generate a fresh one" — the
     * caller (the PikPak engine) does the generation, then writes it back via
     * [update] so the next session reuses it.
     */
    val deviceId: String

    /**
     * Persisted refresh token. Empty means "no prior session — a full signin
     * is required". The engine handles this transparently.
     */
    val refreshToken: String

    /**
     * Called after a successful signin / refresh. [refreshToken] may equal the
     * previous value (if PikPak doesn't rotate on refresh) or differ (rotation);
     * implementations should always write the new value regardless.
     */
    suspend fun update(deviceId: String, refreshToken: String)
}

/**
 * Non-persisting token store — keeps values in memory only. Useful for tests
 * and as a default when the app layer hasn't wired DataStore-backed storage
 * yet. Thread-safe via `@Volatile` on the backing fields.
 */
class InMemoryPikPakTokenStore(
    initialDeviceId: String = "",
    initialRefreshToken: String = "",
) : PikPakTokenStore {
    @Volatile
    override var deviceId: String = initialDeviceId
        private set

    @Volatile
    override var refreshToken: String = initialRefreshToken
        private set

    override suspend fun update(deviceId: String, refreshToken: String) {
        this.deviceId = deviceId
        this.refreshToken = refreshToken
    }
}
