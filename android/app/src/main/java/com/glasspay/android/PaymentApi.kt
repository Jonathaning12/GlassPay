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
    val contactId: String? = null,
)

data class MyQr(val token: String, val expiresAt: String) {
    override fun toString(): String = "MyQr(expiresAt=$expiresAt)"
}

data class IncomingRequest(
    val requestId: String,
    val state: String,
    val amount: String,
    val currency: String,
    val concept: String,
    val approveUrl: String?,
    val expiresAt: String,
    val createdAt: String,
    val fromName: String?,
)

data class Contact(
    val contactId: String,
    val displayName: String,
    val createdAt: String,
)

data class InterpretResult(
    val proposal: ChargeProposal?,
    val clarificationQuestion: String?,
)

data class PaymentRequestCreated(
    val requestId: String,
    val statusToken: String,
)

data class PaymentStatus(
    val state: String,
    val amount: String,
    val currency: String,
    val concept: String,
)

data class CurrencyActivity(
    val currency: String,
    val paidTotal: String,
    val paidCount: Int,
    val inProgressCount: Int,
)

data class CollectorProfile(
    val displayName: String,
    val activity: List<CurrencyActivity>,
)

data class Movement(
    val movementId: String,
    val state: String,
    val amount: String,
    val currency: String,
    val concept: String,
    val payerName: String,
    val createdAt: String,
    val updatedAt: String,
)

enum class ApiFailureType {
    UNAUTHORIZED,
    CONFLICT,
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

    suspend fun resolveQrToken(session: CollectorSession, token: String, timeoutMs: Long): PayerResolution = withContext(Dispatchers.IO) {
        val body = JSONObject().put("token", "glasspay:$token").toString()
            .toRequestBody("application/json".toMediaType())
        val responseBody = execute("POST", "/api/payers/resolve", body, timeoutMs, session.headers())
        try {
            val json = JSONObject(responseBody)
            PayerResolution(
                displayName = json.getString("displayName"),
                resolutionId = json.getString("resolutionId"),
                expiresAt = json.getString("expiresAt"),
                contactId = if (json.isNull("contactId")) null else json.optString("contactId").ifEmpty { null },
            )
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun issueMyQr(session: CollectorSession): MyQr {
        val body = JSONObject().toString().toRequestBody("application/json".toMediaType())
        val responseBody = execute("POST", "/api/collectors/me/qr", body, 15_000, session.headers())
        try {
            val json = JSONObject(responseBody)
            return MyQr(json.getString("token"), json.getString("expiresAt"))
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun incomingRequests(session: CollectorSession): List<IncomingRequest> {
        val responseBody = execute("GET", "/api/collectors/me/incoming-requests", null, 15_000, session.headers())
        try {
            val items = JSONObject(responseBody).getJSONArray("requests")
            return (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                IncomingRequest(
                    requestId = item.getString("requestId"),
                    state = item.getString("state"),
                    amount = item.get("amount").toString(),
                    currency = item.getString("currency"),
                    concept = item.getString("concept"),
                    approveUrl = if (item.isNull("approveUrl")) null else item.optString("approveUrl").ifEmpty { null },
                    expiresAt = item.optString("expiresAt"),
                    createdAt = item.optString("createdAt"),
                    fromName = if (item.isNull("fromName")) null else item.optString("fromName").ifEmpty { null },
                )
            }
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun rejectIncoming(session: CollectorSession, requestId: String) {
        val body = JSONObject().toString().toRequestBody("application/json".toMediaType())
        execute("POST", "/api/collectors/me/incoming-requests/$requestId/reject", body, 15_000, session.headers())
    }

    suspend fun listContacts(session: CollectorSession): List<Contact> {
        val responseBody = execute("GET", "/api/collectors/me/contacts", null, 15_000, session.headers())
        try {
            val items = JSONObject(responseBody).getJSONArray("contacts")
            return (0 until items.length()).map { index -> parseContact(items.getJSONObject(index)) }
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun saveContact(session: CollectorSession, resolutionId: String): Contact {
        val body = JSONObject().put("resolutionId", resolutionId).toString()
            .toRequestBody("application/json".toMediaType())
        val responseBody = execute("POST", "/api/collectors/me/contacts", body, 15_000, session.headers())
        try {
            return parseContact(JSONObject(responseBody))
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun deleteContact(session: CollectorSession, contactId: String) {
        execute("DELETE", "/api/collectors/me/contacts/$contactId", null, 15_000, session.headers())
    }

    suspend fun contactResolution(session: CollectorSession, contactId: String): PayerResolution {
        val body = JSONObject().toString().toRequestBody("application/json".toMediaType())
        val responseBody = execute(
            "POST",
            "/api/collectors/me/contacts/$contactId/resolution",
            body,
            15_000,
            session.headers(),
        )
        try {
            val json = JSONObject(responseBody)
            return PayerResolution(
                displayName = json.getString("displayName"),
                resolutionId = json.getString("resolutionId"),
                expiresAt = json.getString("expiresAt"),
                contactId = if (json.isNull("contactId")) null else json.optString("contactId").ifEmpty { null },
            )
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    private fun parseContact(json: JSONObject) = Contact(
        contactId = json.getString("contactId"),
        displayName = json.getString("displayName"),
        createdAt = json.getString("createdAt"),
    )

    suspend fun interpret(
        session: CollectorSession,
        resolutionId: String,
        text: String,
    ): InterpretResult {
        var attempt = 0
        while (true) {
            try {
                val body = JSONObject()
                    .put("resolutionId", resolutionId)
                    .put("text", text)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val json = JSONObject(execute("POST", "/api/agent/interpret", body, 20_000, session.headers()))
                if (json.optBoolean("needsClarification")) {
                    return InterpretResult(null, json.optString("clarificationQuestion"))
                }
                return InterpretResult(
                    proposal = ChargeProposal(
                        proposalId = json.getString("proposalId"),
                        amount = json.getString("amount"),
                        currency = json.getString("currency"),
                        concept = json.getString("concept"),
                        expiresAt = json.getString("expiresAt"),
                    ),
                    clarificationQuestion = null,
                )
            } catch (exception: ApiException) {
                if (attempt == 0 && (exception.type == ApiFailureType.NETWORK || exception.type == ApiFailureType.TIMEOUT)) {
                    attempt += 1
                    continue
                }
                throw exception
            }
        }
    }

    suspend fun confirm(session: CollectorSession, proposalId: String): PaymentRequestCreated {
        val body = JSONObject()
            .put("proposalId", proposalId)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val json = JSONObject(execute("POST", "/api/agent/confirm", body, 20_000, session.headers()))
        return PaymentRequestCreated(
            requestId = json.getString("requestId"),
            statusToken = json.getString("statusToken"),
        )
    }

    suspend fun createCollector(displayName: String): CollectorSession {
        val body = JSONObject().put("displayName", displayName).toString()
            .toRequestBody("application/json".toMediaType())
        val responseBody = execute("POST", "/api/collectors", body, 60_000)
        try {
            val json = JSONObject(responseBody)
            return CollectorSession(
                collectorId = json.getString("collectorId"),
                sessionSecret = json.getString("sessionSecret"),
                displayName = json.getString("displayName"),
            )
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun getCollectorProfile(session: CollectorSession): CollectorProfile {
        val responseBody = execute("GET", "/api/collectors/me", null, 15_000, session.headers())
        try {
            val json = JSONObject(responseBody)
            val items = json.getJSONArray("activity")
            return CollectorProfile(
                displayName = json.getString("displayName"),
                activity = (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    CurrencyActivity(
                        currency = item.getString("currency"),
                        paidTotal = item.get("paidTotal").toString(),
                        paidCount = item.getInt("paidCount"),
                        inProgressCount = item.getInt("inProgressCount"),
                    )
                },
            )
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun getMovements(session: CollectorSession): List<Movement> {
        val responseBody = execute("GET", "/api/collectors/me/movements", null, 15_000, session.headers())
        try {
            val items = JSONObject(responseBody).getJSONArray("movements")
            return (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                Movement(
                    movementId = item.getString("movementId"),
                    state = item.getString("state"),
                    amount = item.get("amount").toString(),
                    currency = item.getString("currency"),
                    concept = item.getString("concept"),
                    payerName = item.getString("payerName"),
                    createdAt = item.getString("createdAt"),
                    updatedAt = item.getString("updatedAt"),
                )
            }
        } catch (_: Exception) {
            throw ApiException(ApiFailureType.INVALID_RESPONSE)
        }
    }

    suspend fun getPaymentStatus(requestId: String, statusToken: String): PaymentStatus {
        val responseBody = execute(
            method = "GET",
            path = "/api/payers/payment-requests/$requestId/status",
            body = null,
            timeoutMs = 15_000,
            headers = mapOf("X-Status-Token" to statusToken),
        )
        try {
            val json = JSONObject(responseBody)
            return PaymentStatus(
                state = json.getString("state"),
                amount = json.getString("amount"),
                currency = json.getString("currency"),
                concept = json.getString("concept"),
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
        headers: Map<String, String> = emptyMap(),
    ): String = suspendCancellableCoroutine { continuation ->
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        when (method) {
            "POST" -> requestBuilder.post(requireNotNull(body))
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
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
                            401 -> ApiFailureType.UNAUTHORIZED
                            409 -> ApiFailureType.CONFLICT
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
