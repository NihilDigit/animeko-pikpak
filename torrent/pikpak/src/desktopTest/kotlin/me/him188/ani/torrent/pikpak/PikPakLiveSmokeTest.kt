package me.him188.ani.torrent.pikpak

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.him188.ani.torrent.offline.ResolvedMedia
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.ktor.createDefaultHttpClient
import me.him188.ani.utils.ktor.registerLogging
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Hits the real PikPak service with credentials from env vars. Skipped in
 * CI / when creds absent.
 *
 * Env vars:
 *   PIKPAK_USERNAME
 *   PIKPAK_PASSWORD
 *   PIKPAK_MAGNET   optional; defaults to a well-seeded Arch Linux ISO magnet.
 *
 * Run with:
 *   ./gradlew :torrent:pikpak:jvmTest --tests '*PikPakLiveSmokeTest*' --info
 */
class PikPakLiveSmokeTest {

    @Test
    fun `resolve returns a playable stream url`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }
        val magnet = System.getenv("PIKPAK_MAGNET")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MAGNET

        // Hand-build the engine the way Koin would, but stripped down so the
        // test runs without the app's DI graph.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = createDefaultHttpClient().apply { registerLogging() }
        val http = client.asScopedHttpClient()
        val credentialsFlow = MutableStateFlow(PikPakCredentials(username, password))

        val engine = PikPakOfflineDownloadEngine(
            httpClient = http,
            credentials = credentialsFlow,
            scope = scope,
            pollInterval = 3.seconds,
            resolveTimeout = 3.minutes,
        )

        println("[1/2] first resolve: ${magnet.take(80)}...")
        val t0 = kotlin.time.TimeSource.Monotonic.markNow()
        val resolved: ResolvedMedia = engine.resolve(magnet)
        val d1 = t0.elapsedNow()
        println("[2/2] resolved in $d1 -> url=${resolved.streamUrl.take(120)}... fileName=${resolved.fileName} fileSize=${resolved.fileSize}")

        assertTrue(resolved.streamUrl.startsWith("http"), "streamUrl should be http(s)")

        // Second resolve in the same JVM — bearer cached + captcha cached,
        // so submit is a single HTTP round-trip. Useful as a rough eyeball
        // that Phase 1 speed work actually kicked in.
        val t1 = kotlin.time.TimeSource.Monotonic.markNow()
        val resolved2 = engine.resolve(magnet)
        val d2 = t1.elapsedNow()
        println("[bonus] second resolve in $d2 (first was $d1)")
        assertTrue(resolved2.streamUrl.startsWith("http"))

        client.close()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Probe for V1.1: once PikPak hands us a signed CDN URL, does that URL
     * keep working after the file is trashed / permanently deleted?
     *
     * Outcomes we care about:
     *   A — URL works after trash AND after batchDelete → we can safely
     *       fire-and-forget batchDelete right after resolve().
     *   B — URL works after trash, dies after batchDelete → use batchTrash
     *       on resolve; PikPak's own 30-day trash GC handles cleanup.
     *   C — URL dies as soon as file is trashed → fall back to folder + janitor.
     *
     * Prints each HEAD status so we can read the result from stdout without
     * making the test fail on any particular outcome.
     */
    @Test
    fun `probe - stream url survival after trash and delete`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }
        val magnet = System.getenv("PIKPAK_MAGNET")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MAGNET

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rawClient = createDefaultHttpClient().apply { registerLogging() }
        val http = rawClient.asScopedHttpClient()
        val credentialsFlow = MutableStateFlow(PikPakCredentials(username, password))

        val engine = PikPakOfflineDownloadEngine(
            httpClient = http,
            credentials = credentialsFlow,
            scope = scope,
            pollInterval = 3.seconds,
            resolveTimeout = 3.minutes,
        )

        println("[probe] resolving: ${magnet.take(80)}...")
        val resolved: ResolvedMedia = engine.resolve(magnet)
        val fileId = resolved.providerFileId
        println("[probe] resolved -> fileId=$fileId name=${resolved.fileName} size=${resolved.fileSize}")
        assertNotNull(fileId, "providerFileId should be populated by the PikPak engine")
        assertTrue(resolved.streamUrl.startsWith("http"), "streamUrl should be http(s)")

        // Build a client so we can call batchTrash / batchDelete directly.
        val auth = PikPakAuth(http, username, password)
        val pikpakClient = PikPakClient(http, auth)

        // PikPak's CDN rejects HEAD (406). Use a ranged GET of the first byte
        // — same shape mediamp will use when it starts playback.
        suspend fun probe(label: String): Int {
            val status = runCatching {
                rawClient.get(resolved.streamUrl) {
                    expectSuccess = false
                    // PikPak's CDN is picky: it refuses `Accept: application/json`
                    // (the default from ContentNegotiation) with 406. mediamp /
                    // libvlc would never send that; mimic a generic player.
                    header("Accept", "*/*")
                    header("User-Agent", "Mozilla/5.0")
                    header("Range", "bytes=0-0")
                }.status.value
            }.getOrElse { ex ->
                println("[probe] exception: ${ex::class.simpleName}: ${ex.message}")
                -1
            }
            println("[probe] GET $label -> status=$status")
            return status
        }

        // 1. sanity: fresh link works (200 OK or 206 Partial Content).
        probe("fresh")

        // 2. move to trash, check link.
        println("[probe] batchTrash($fileId)...")
        pikpakClient.batchTrash(listOf(fileId))
        delay(2.seconds)
        probe("after-trash")

        // 3. permanently delete from trash, check link.
        println("[probe] batchDelete($fileId)...")
        runCatching { pikpakClient.batchDelete(listOf(fileId)) }
            .onFailure { println("[probe] batchDelete threw: ${it.message}") }
        delay(5.seconds)
        probe("after-delete")

        // Give the CDN a bit longer in case caches are eventually-consistent.
        delay(20.seconds)
        probe("after-delete+20s")

        rawClient.close()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Probe for V1.1: what's the refresh-token lifecycle?
     *
     * Answers three questions so we can decide how to persist tokens:
     *   1. What `expires_in` does PikPak actually return? (Python SDK doc says
     *      7200 s — confirm live.)
     *   2. Does the refresh_token rotate on each `/v1/auth/token` call, or is
     *      it stable? Rotation means we MUST write the new value back to disk
     *      after every refresh; stable means one persist per signin is enough.
     *   3. Does refresh work repeatedly within a session? (Sanity check — if
     *      no, the whole persistence plan falls apart.)
     *
     * Does not try to measure how long a refresh_token survives across days;
     * that's an observation, not a unit test.
     */
    @Test
    fun `probe - refresh token rotation and TTL`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rawClient = createDefaultHttpClient().apply { registerLogging() }
        val http = rawClient.asScopedHttpClient()
        val auth = PikPakAuth(http, username, password)

        fun snapshot(label: String) {
            val now = kotlin.time.Clock.System.now()
            val ttl = auth.tokenExpiresAt - now
            println(
                "[probe] $label: access=${auth.accessToken.take(10)}... " +
                        "refresh=${auth.refreshToken.take(10)}... " +
                        "sub=${auth.userId.take(12)}... " +
                        "expiresIn=${ttl.inWholeSeconds}s"
            )
        }

        println("[probe] initial signin...")
        auth.getAccessToken()
        val access1 = auth.accessToken
        val refresh1 = auth.refreshToken
        snapshot("after signin")

        println("[probe] force refresh #1...")
        auth.invalidate()
        auth.getAccessToken()
        val access2 = auth.accessToken
        val refresh2 = auth.refreshToken
        snapshot("after refresh#1")

        println("[probe] force refresh #2...")
        auth.invalidate()
        auth.getAccessToken()
        val access3 = auth.accessToken
        val refresh3 = auth.refreshToken
        snapshot("after refresh#2")

        println("[probe] access_token rotates each call: ${access1 != access2 && access2 != access3}")
        println("[probe] refresh_token rotates on refresh: ${refresh1 != refresh2 || refresh2 != refresh3}")
        println("[probe] refresh_token across 2 refreshes same: ${refresh1 == refresh2 && refresh2 == refresh3}")

        rawClient.close()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        // Arch Linux ISO — widely seeded, PikPak caches it, resolves in seconds.
        private const val DEFAULT_MAGNET =
            "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8" +
                    "&dn=archlinux-2026.04.01-x86_64.iso"
    }
}
