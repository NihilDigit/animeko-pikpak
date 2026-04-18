package me.him188.ani.torrent.pikpak

import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import me.him188.ani.torrent.offline.ResolvedMedia
import me.him188.ani.torrent.pikpak.models.FileInfo
import me.him188.ani.torrent.pikpak.models.OfflineTask
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Credentials + enabled flag for the PikPak engine. Emitted by the app-level
 * SettingsRepository wrapper so the engine doesn't know about DataStore.
 */
data class PikPakCredentials(
    val username: String,
    val password: String,
) {
    val isValid: Boolean get() = username.isNotEmpty() && password.isNotEmpty()
}

/**
 * The PikPak implementation of [OfflineDownloadEngine].
 *
 * Accepts a [StateFlow] of current credentials (null when the user has
 * disabled the integration). On each [resolve] call it reuses an in-memory
 * [PikPakAuth] for the current credentials so bearer tokens can be cached
 * across resolves within the same account.
 */
class PikPakOfflineDownloadEngine(
    private val httpClient: ScopedHttpClient,
    private val credentials: StateFlow<PikPakCredentials?>,
    private val scope: CoroutineScope,
    private val tokenStore: PikPakTokenStore = InMemoryPikPakTokenStore(),
    private val pollInterval: kotlin.time.Duration = 2.seconds,
    private val resolveTimeout: kotlin.time.Duration = 5.minutes,
) : OfflineDownloadEngine {

    private val logger = logger<PikPakOfflineDownloadEngine>()

    override val id: String = "pikpak"
    override val displayName: String = "PikPak"

    override val isSupported: StateFlow<Boolean> = credentials
        .map { it != null && it.isValid }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = credentials.value?.isValid == true)

    @Volatile
    private var authEntry: Pair<PikPakCredentials, PikPakAuth>? = null

    init {
        // Pre-warm the bearer token whenever valid credentials are available.
        // The user likely toggled PikPak on well before they actually hit play,
        // so doing the signin round-trip eagerly turns the first `resolve()`
        // call of a session into a captcha+submit+poll path (the bearer is
        // already cached).
        credentials
            .filter { it != null && it.isValid }
            .distinctUntilChanged()
            .onEach { creds ->
                scope.launch {
                    runCatching { authFor(creds!!).getAccessToken() }
                        .onFailure { logger.warn(it) { "[pikpak] pre-warm signin failed (non-fatal)" } }
                }
            }
            .launchIn(scope)
    }

    override suspend fun resolve(
        uri: String,
        pickVideoFile: (candidateFilenames: List<String>) -> String?,
    ): ResolvedMedia =
        withTimeout(resolveTimeout) {
            val creds = credentials.value
                ?: throw me.him188.ani.torrent.offline.OfflineDownloadAuthException("PikPak not configured")
            if (!creds.isValid) {
                throw me.him188.ani.torrent.offline.OfflineDownloadAuthException("PikPak credentials incomplete")
            }
            val client = PikPakClient(httpClient, authFor(creds))

            // Track the latest *root* id we've seen — for single-file torrents
            // this is the file itself; for season packs it's the parent folder
            // PikPak created. We clean up the root on every exit path so the
            // user's drive stays empty regardless of whether the pipeline
            // succeeded or failed.
            var cleanupRootId: String? = null

            try {
                logger.info { "[pikpak] submit offline task for ${uri.take(60)}..." }
                val task = client.submitOfflineTask(uri, name = deriveTaskName(uri))
                logger.debug { "[pikpak] submitted task id=${task.id} file_id=${task.fileId} file_name=${task.fileName}" }
                if (task.fileId.isNotEmpty()) cleanupRootId = task.fileId

                val fileId = awaitCompletion(client, task) { observed ->
                    cleanupRootId = observed
                }
                cleanupRootId = fileId

                val rootInfo = client.getFileInfo(fileId)
                if (rootInfo.id.isNotEmpty()) cleanupRootId = rootInfo.id

                // Season-pack handling: if PikPak returned a folder, list its
                // children and pick the video that matches the episode hint.
                // The folder itself has no playable link; only its children
                // do. Deleting the folder cascades to the children on PikPak's
                // side, and the signed CDN URL of the chosen child survives
                // that delete (same mechanism as the single-file case).
                val videoFile = if (rootInfo.isFolder) {
                    val children = listAll(client, rootInfo.id)
                    val chosenName = pickVideoFile(children.map { it.name })
                        ?: throw OfflineDownloadRejectedException(
                            "PikPak folder ${rootInfo.id} contains no matching video " +
                                    "(files: ${children.joinToString(limit = 10) { it.name }})",
                        )
                    val chosenChild = children.firstOrNull { it.name == chosenName }
                        ?: throw OfflineDownloadRejectedException(
                            "pickVideoFile returned '$chosenName' which is not among the folder's children",
                        )
                    logger.info {
                        "[pikpak] season pack: picked '${chosenChild.name}' (${chosenChild.id}) " +
                                "out of ${children.size} children"
                    }
                    // Fetch the chosen child again — listFiles responses often
                    // omit `medias[]` / `web_content_link` and we need those.
                    client.getFileInfo(chosenChild.id)
                } else {
                    rootInfo
                }

                val resolved = buildResolvedMedia(videoFile)

                scheduleCleanup(client, cleanupRootId)

                resolved
            } catch (e: Throwable) {
                // Failure cleanup: if we've already observed a root id, wipe
                // it so the user's drive stays clean after a failed play
                // attempt. Covers timeouts, cancellation, and provider errors.
                scheduleCleanup(client, cleanupRootId)
                throw e
            }
        }

    private suspend fun listAll(client: PikPakClient, parentId: String): List<FileInfo> {
        val out = mutableListOf<FileInfo>()
        var pageToken: String? = null
        do {
            val page = client.listFiles(parentId, pageToken = pageToken)
            out += page.files
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    private fun scheduleCleanup(client: PikPakClient, fileId: String?) {
        if (fileId.isNullOrEmpty()) return
        scope.launch {
            runCatching { client.batchDelete(listOf(fileId)) }
                .onFailure { logger.warn(it) { "[pikpak] cleanup batchDelete failed id=$fileId" } }
        }
    }

    /**
     * PikPak requires a non-empty `name` on the submit request. The server
     * overwrites it with the torrent's real filename once metadata resolves,
     * so the only constraint is "non-empty". We try to be useful:
     *   1. For magnet URIs: use the `dn=` display name if present.
     *   2. For HTTP .torrent URLs: use the basename of the path.
     *   3. Fallback: a short placeholder — never empty.
     */
    internal fun deriveTaskName(uri: String): String {
        if (uri.startsWith("magnet:", ignoreCase = true)) {
            val dn = Regex("[?&]dn=([^&]*)").find(uri)?.groupValues?.get(1)
                ?.let { runCatching { it.decodeURLQueryComponent() }.getOrDefault(it) }
            if (!dn.isNullOrBlank()) return dn
            val infoHash = Regex("xt=urn:btih:([A-Za-z0-9]+)").find(uri)?.groupValues?.get(1)
            if (!infoHash.isNullOrBlank()) return "magnet-$infoHash"
        } else {
            val tail = uri.substringAfterLast('/').substringBefore('?')
            if (tail.isNotBlank()) return tail
        }
        return "ani-offline-task"
    }

    private fun authFor(creds: PikPakCredentials): PikPakAuth {
        val current = authEntry
        if (current != null && current.first == creds) return current.second
        // Reuse the persisted device fingerprint if we have one, so PikPak
        // treats subsequent signins as the same device (which, empirically,
        // keeps their rate limiter friendlier). Generate and write back on
        // first ever use.
        val deviceId = tokenStore.deviceId.ifEmpty { PikPakAuth.generateDeviceId() }
        val fresh = PikPakAuth(
            httpClient = httpClient,
            username = creds.username,
            password = creds.password,
            deviceId = deviceId,
            initialRefreshToken = tokenStore.refreshToken,
            onTokenUpdated = tokenStore::update,
        )
        authEntry = creds to fresh
        return fresh
    }

    private suspend fun awaitCompletion(
        client: PikPakClient,
        initialTask: OfflineTask,
        onFileIdObserved: (String) -> Unit = {},
    ): String {
        var fileId = initialTask.fileId
        var attempt = 0
        // Include PENDING so a freshly queued task doesn't look "already gone"
        // and trip the "Task completed but no file_id" branch below.
        val activePhases = "PHASE_TYPE_PENDING,PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR"
        while (true) {
            delay(pollInterval)
            attempt++
            val list = client.listOfflineTasks(phaseFilter = activePhases)
            val match = list.tasks.firstOrNull { it.id == initialTask.id }
            if (match == null) {
                // Task left the PENDING/RUNNING/ERROR filter => completed.
                logger.info { "[pikpak] task ${initialTask.id} completed after $attempt polls" }
                return fileId.ifEmpty {
                    throw OfflineDownloadRejectedException(
                        "Task completed but no file_id was observed; re-submit may be needed",
                    )
                }
            }
            if (match.fileId.isNotEmpty() && match.fileId != fileId) {
                fileId = match.fileId
                onFileIdObserved(fileId)
            }
            if (match.phase == "PHASE_TYPE_ERROR") {
                throw OfflineDownloadRejectedException(
                    "PikPak task failed: phase=${match.phase} message=${match.message}",
                )
            }
            logger.debug {
                "[pikpak] poll $attempt: phase=${match.phase} progress=${match.progress} file_id=$fileId"
            }
        }
    }

    private fun buildResolvedMedia(file: FileInfo): ResolvedMedia {
        // Prefer a media link (CDN streaming-rate) over web_content_link.
        // Within medias, prefer is_default, then highest priority, then is_origin.
        val primary = file.medias
            .filter { it.link?.url?.isNotEmpty() == true }
            .sortedWith(
                compareByDescending<me.him188.ani.torrent.pikpak.models.PikPakMedia> { it.isDefault }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.isOrigin },
            )
            .firstOrNull()

        val streamUrl = primary?.link?.url
            ?: file.webContentLink.takeIf { it.isNotEmpty() }
            ?: throw OfflineDownloadRejectedException(
                "PikPak file has no playable link (file_id=${file.id})",
            )

        val expiresAt: Instant? = primary?.link?.expire
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        return ResolvedMedia(
            streamUrl = streamUrl,
            expiresAt = expiresAt,
            fileName = file.name.takeIf { it.isNotEmpty() },
            fileSize = file.size.toLongOrNull(),
            providerFileId = file.id.takeIf { it.isNotEmpty() },
        )
    }
}
