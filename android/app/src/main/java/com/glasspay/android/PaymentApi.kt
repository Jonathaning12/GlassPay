package com.glasspay.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PayerResolution(
    val displayName: String,
    val resolutionId: String,
)

class PaymentApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun resolveQrToken(token: String): PayerResolution = withContext(Dispatchers.IO) {
        val body = JSONObject().put("token", "glasspay:$token").toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/payers/resolve")
            .post(body)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(if (response.code == 404) {
                    "El QR no es válido o ya venció."
                } else {
                    "No pudimos resolver el QR. Revisa tu conexión."
                })
            }
            val json = JSONObject(responseBody)
            PayerResolution(
                displayName = json.getString("displayName"),
                resolutionId = json.getString("resolutionId"),
            )
        }
    }
}

class ApiException(message: String) : IOException(message)
