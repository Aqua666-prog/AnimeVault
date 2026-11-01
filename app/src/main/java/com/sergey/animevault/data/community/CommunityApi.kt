package com.sergey.animevault.data.community

import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No logging/cache interceptor, redirects or arbitrary endpoints carrying authorization. */
open class CommunityApi(val configuration: CommunityConfiguration, baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
    }.followRedirects(false).followSslRedirects(false).cache(null)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
    val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    open suspend fun request(path: String, body: Any? = null, token: String? = null, query: Map<String, String> = emptyMap(), method: String = "POST"): JsonElement = withContext(Dispatchers.IO) {
        if (!configuration.ready) throw CommunityFailure("configuration")
        require(path.startsWith("auth/v1/") || path.startsWith("rest/v1/"))
        val url = (configuration.url + "/" + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("apikey", configuration.key).apply {
            token?.let { header("Authorization", "Bearer $it") }
            if (method == "GET") get() else method(method, gson.toJson(body ?: emptyMap<String,String>()).toRequestBody("application/json".toMediaType()))
        }.build()
        val response = try { client.newCall(request).await() } catch (_: IOException) { throw CommunityFailure("offline") }
        response.use {
            // Keep server payloads out of exceptions and logs: Auth errors can contain personal fields.
            val payload = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val kind = communityFailureKind(it.code,payload)
                throw CommunityFailure(kind)
            }
            if (payload.isBlank()) com.google.gson.JsonNull.INSTANCE else gson.fromJson(payload, JsonElement::class.java)
        }
    }
    suspend inline fun <reified T> rpc(name: String, body: Any, token: String? = null): T =
        gson.fromJson(request("rest/v1/rpc/$name", body, token), T::class.java)
}
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
    })
}
