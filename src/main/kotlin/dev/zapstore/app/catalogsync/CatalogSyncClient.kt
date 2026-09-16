package dev.zapstore.app.catalogsync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class UpdatesResponse {
    data class Delta(val body: ByteArray) : UpdatesResponse()
    data object NotModified : UpdatesResponse()
    data class Error(val status: Int, val code: String?, val reason: String?) : UpdatesResponse()
}

class CatalogSyncClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun requestUpdates(request: UpdatesRequest): UpdatesResponse {
        val call = http.newCall(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/updates")
                .header("Content-Type", "application/json")
                .post(request.toJson().toRequestBody(JSON_MEDIA))
                .build(),
        )
        call.execute().use { response ->
            return when (response.code) {
                200 -> UpdatesResponse.Delta(response.body.bytes())
                304 -> UpdatesResponse.NotModified
                else -> UpdatesResponse.Error(
                    status = response.code,
                    code = response.header("X-Error-Code"),
                    reason = response.header("X-Reason"),
                )
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
