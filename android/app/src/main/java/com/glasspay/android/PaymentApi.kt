package com.glasspay.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PayerResolution(
    val displayName: String,
    val resolutionId: String,
    val expiresAt: String,
)

enum class ApiFailureType {
    NETWORK,
    TIMEOUT,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    INVALID_RESPONSE,
}

class ApiException(
    val type: ApiFailureType,
    val httpCode: Int? = null,
) : IOException()

class PaymentApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun warmUp() {
        execute("GET", "/health", null, 60_000)
    }

    suspend fun resolveQrToken(token: String, timeoutMs: Long): PayerResolution = withContext(Dispatchers.IO) {
        val body = JSONObject().put("token", "glasspay:$token").toString()
            .toRequestBody("application/json".toMediaType())
        val responseBody = execute("POST", "/api/payers/resolve", body, timeoutMs)
        try {
            val json = JSONObject(responseBody)
            PayerResolution(
                displayName = json.getString("displayName"),
                resolutionId = json.getString("resolutionId"),
                expiresAt = json.getString("expiresAt"),
            )
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: okhttp3.RequestBody?,
        timeoutMs: Long,
    ): String = suspendCancellableCoroutine { continuation ->
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
        if (method == "POST") {
            requestBuilder.post(requireNotNull(body))
        } else {
            requestBuilder.get()
        }
        val requestClient = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val call = requestClient.newCall(requestBuilder.build())
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (!continuation.isActive) return
                val type = if (call.isCanceled() || e is SocketTimeoutException) {
                    ApiFailureType.TIMEOUT
                } else {
                    ApiFailureType.NETWORK
                }
                continuation.resumeWithException(ApiException(type))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!continuation.isActive) return
                    val responseBody = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val type = when (it.code) {
                            404 -> ApiFailureType.NOT_FOUND
                            429 -> ApiFailureType.RATE_LIMITED
                            in 500..599 -> ApiFailureType.SERVER
                            else -> ApiFailureType.INVALID_RESPONSE
                        }
                        continuation.resumeWithException(ApiException(type, it.code))
                    } else {
                        continuation.resume(responseBody)
                    }
                }
            }
        })
    }
}
