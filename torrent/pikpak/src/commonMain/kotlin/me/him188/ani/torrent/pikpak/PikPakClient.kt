package me.him188.ani.torrent.pikpak

import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import me.him188.ani.torrent.pikpak.models.BatchIdsRequest
import me.him188.ani.torrent.pikpak.models.FileInfo
import me.him188.ani.torrent.pikpak.models.FileListResponse
import me.him188.ani.torrent.pikpak.models.OfflineTask
import me.him188.ani.torrent.pikpak.models.SubmitOfflineTaskRequest
import me.him188.ani.torrent.pikpak.models.SubmitOfflineTaskResponse
import me.him188.ani.torrent.pikpak.models.TaskListResponse
import me.him188.ani.utils.ktor.ScopedHttpClient

/**
 * High-level PikPak API client: submit magnet, poll tasks, fetch file info.
 *
 * Thin wrapper around [PikPakAuth] + [ScopedHttpClient] that handles token
 * injection and surfaces typed exceptions for the engine layer.
 */
internal class PikPakClient(
    private val httpClient: ScopedHttpClient,
    private val auth: PikPakAuth,
) {
    /** Submit a magnet URI or `.torrent` URL to the offline-download queue. */
    suspend fun submitOfflineTask(uri: String, name: String): OfflineTask {
        val url = "https://${PikPakConstants.API_HOST}/drive/v1/files"
        val response = postWithCaptcha(url) {
            contentType(ContentType.Application.Json)
            setBody(
                SubmitOfflineTaskRequest(
                    name = name,
                    url = SubmitOfflineTaskRequest.UrlUpload(uri),
                ),
            )
        }
        val body = response.decodeOrReject<SubmitOfflineTaskResponse>("submit offline task")
        return body.task
            ?: throw OfflineDownloadRejectedException(
                "PikPak submit returned no task: status=${response.status}",
            )
    }

    /**
     * Returns the list of offline tasks matching [phaseFilter]. Useful phase
     * filters are `"PHASE_TYPE_RUNNING"`, `"PHASE_TYPE_ERROR"`, and combos
     * like `"PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR"`.
     */
    suspend fun listOfflineTasks(
        phaseFilter: String = "PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR",
        limit: Int = 10_000,
    ): TaskListResponse {
        val url = "https://${PikPakConstants.API_HOST}/drive/v1/tasks"
        val response = authedGet(url) {
            parameter("type", "offline")
            parameter("thumbnail_size", "SIZE_SMALL")
            parameter("limit", limit)
            parameter(
                "filters",
                """{"phase":{"in":"$phaseFilter"}}""",
            )
            parameter("with", "reference_resource")
        }
        return response.decodeOrReject("list offline tasks")
    }

    /** Fetch file metadata (including `medias[]` for videos). */
    suspend fun getFileInfo(fileId: String): FileInfo {
        val url = "https://${PikPakConstants.API_HOST}/drive/v1/files/$fileId"
        val response = authedGet(url)
        return response.decodeOrReject("get file info")
    }

    /**
     * List non-trashed immediate children of a folder. Used to pick an episode
     * out of a season-pack folder. Caller should follow [FileListResponse.nextPageToken]
     * for large folders; most season packs fit in one page (default 200).
     */
    suspend fun listFiles(
        parentId: String,
        limit: Int = 200,
        pageToken: String? = null,
    ): FileListResponse {
        val url = "https://${PikPakConstants.API_HOST}/drive/v1/files"
        val response = authedGet(url) {
            parameter("parent_id", parentId)
            parameter("limit", limit)
            parameter("filters", """{"trashed":{"eq":false}}""")
            if (!pageToken.isNullOrEmpty()) parameter("page_token", pageToken)
        }
        return response.decodeOrReject("list files")
    }

    /**
     * Move the given file ids to the PikPak recycle bin. Files there survive
     * ~30 days before PikPak auto-purges them; a caller can still restore via
     * the web UI in the meantime.
     */
    suspend fun batchTrash(ids: List<String>) {
        if (ids.isEmpty()) return
        val url = "https://${PikPakConstants.API_HOST}/drive/v1/files:batchTrash"
        val response = postWithCaptcha(url) {
            contentType(ContentType.Application.Json)
            setBody(BatchIdsRequest(ids))
        }
        if (!response.status.isSuccess()) {
            val snippet = runCatching { response.bodyAsText().take(400) }.getOrDefault("")
            throw OfflineDownloadRejectedException(
                "PikPak batchTrash failed: status=${response.status} body=$snippet",
            )
        }
    }

    /**
     * Permanently delete the given file ids. Bypasses the recycle bin — the
     * files are gone immediately. Use [batchTrash] unless you specifically
     * need this.
     */
    suspend fun batchDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        val url = "https://${PikPakConstants.API_HOST}/drive/v1/files:batchDelete"
        val response = postWithCaptcha(url) {
            contentType(ContentType.Application.Json)
            setBody(BatchIdsRequest(ids))
        }
        if (!response.status.isSuccess()) {
            val snippet = runCatching { response.bodyAsText().take(400) }.getOrDefault("")
            throw OfflineDownloadRejectedException(
                "PikPak batchDelete failed: status=${response.status} body=$snippet",
            )
        }
    }

    /**
     * Decode the body as [T] if the status is 2xx; otherwise read the body as
     * plain text and raise an [OfflineDownloadRejectedException] containing
     * the provider's error payload. Keeps the "malformed payload is a bug"
     * distinction from "provider returned an error" — the latter is a routine
     * rejection that upstream maps to NO_MATCHING_RESOURCE.
     */
    private suspend inline fun <reified T> HttpResponse.decodeOrReject(what: String): T {
        if (!status.isSuccess()) {
            val snippet = runCatching { bodyAsText().take(400) }.getOrDefault("")
            throw OfflineDownloadRejectedException(
                "PikPak $what failed: status=$status body=$snippet",
            )
        }
        return body()
    }

    /**
     * POST a captcha-gated endpoint. Refreshes the per-action captcha token
     * first (the cache in [PikPakAuth] makes this ~free inside the 5-minute
     * TTL window) and then sends the request via [authedPost], which handles
     * 401-driven bearer retries on its own.
     */
    private suspend fun postWithCaptcha(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        auth.getAccessToken()
        auth.ensureCaptchaFor("POST:$url")
        return authedPost(url, block)
    }

    // --- transport helpers ---

    private suspend fun authedGet(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = authed { token ->
        httpClient.use {
            get(url) {
                expectSuccess = false
                headers { standardHeaders(token) }
                block()
            }
        }
    }

    private suspend fun authedPost(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = authed { token ->
        httpClient.use {
            post(url) {
                expectSuccess = false
                headers { standardHeaders(token) }
                block()
            }
        }
    }

    /**
     * Runs [send] with the current token; if the response is 401 (token
     * expired after we checked), invalidate + retry once.
     */
    private suspend fun authed(send: suspend (token: String) -> HttpResponse): HttpResponse {
        val response = send(auth.getAccessToken())
        if (response.status != HttpStatusCode.Unauthorized) return response
        auth.invalidate()
        return send(auth.getAccessToken())
    }

    private fun io.ktor.http.HeadersBuilder.standardHeaders(token: String) {
        append(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
        append(HttpHeaders.Authorization, "Bearer $token")
        append("X-Client-Id", PikPakConstants.CLIENT_ID)
        append("X-Client-Version", PikPakConstants.CLIENT_VERSION)
        append("X-Device-Id", auth.deviceId)
        auth.captchaToken.takeIf { it.isNotEmpty() }?.let { append("X-Captcha-Token", it) }
    }
}
