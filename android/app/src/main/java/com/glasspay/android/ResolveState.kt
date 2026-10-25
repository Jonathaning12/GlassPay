package com.glasspay.android

sealed interface ResolveState {
    data object Scanning : ResolveState

    data class Resolving(
        val attemptId: Long,
        val token: String,
        val waking: Boolean,
    ) : ResolveState

    data class RetryableError(
        val attemptId: Long,
        val token: String,
        val message: String,
    ) : ResolveState

    data class Failed(
        val message: String,
    ) : ResolveState

    data class Identified(
        val displayName: String,
        val resolutionId: String,
        val expiresAt: String,
    ) : ResolveState
}

enum class ResolveFailureType {
    NETWORK,
    TIMEOUT,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    INVALID_RESPONSE,
}

sealed interface ResolveEvent {
    data class Begin(
        val attemptId: Long,
        val token: String,
        val waking: Boolean,
    ) : ResolveEvent

    data class Success(
        val attemptId: Long,
        val displayName: String,
        val resolutionId: String,
        val expiresAt: String,
    ) : ResolveEvent

    data class Failure(
        val attemptId: Long,
        val type: ResolveFailureType,
    ) : ResolveEvent

    data object Reset : ResolveEvent
}

class ResolveStateMachine(
    initialState: ResolveState = ResolveState.Scanning,
) {
    var state: ResolveState = initialState
        private set

    fun dispatch(event: ResolveEvent): ResolveState {
        state = when (event) {
            is ResolveEvent.Begin -> {
                if (state is ResolveState.Resolving && (state as ResolveState.Resolving).token == event.token) {
                    state
                } else {
                    ResolveState.Resolving(event.attemptId, event.token, event.waking)
                }
            }
            is ResolveEvent.Success -> {
                if (state.matches(event.attemptId)) {
                    ResolveState.Identified(event.displayName, event.resolutionId, event.expiresAt)
                } else {
                    state
                }
            }
            is ResolveEvent.Failure -> {
                if (!state.matches(event.attemptId)) {
                    state
                } else if (event.type == ResolveFailureType.NOT_FOUND) {
                    ResolveState.Scanning
                } else if (event.type == ResolveFailureType.NETWORK || event.type == ResolveFailureType.TIMEOUT) {
                    ResolveState.RetryableError(event.attemptId, tokenFor(event.attemptId), event.type.message())
                } else {
                    ResolveState.Failed(event.type.message())
                }
            }
            ResolveEvent.Reset -> ResolveState.Scanning
        }
        return state
    }

    private fun tokenFor(attemptId: Long): String {
        return when (val current = state) {
            is ResolveState.Resolving -> current.token
            is ResolveState.RetryableError -> current.token
            else -> ""
        }.also {
            check(it.isNotEmpty() || state !is ResolveState.RetryableError) {
                "Retryable failure must retain its QR token"
            }
        }
    }

    private fun ResolveState.matches(attemptId: Long): Boolean {
        return when (this) {
            is ResolveState.Resolving -> this.attemptId == attemptId
            is ResolveState.RetryableError -> this.attemptId == attemptId
            else -> false
        }
    }
}

private fun ResolveFailureType.message(): String {
    return when (this) {
        ResolveFailureType.NETWORK,
        ResolveFailureType.TIMEOUT -> "No pudimos conectar con GlassPay. Revisa tu conexión."
        ResolveFailureType.RATE_LIMITED -> "Demasiados intentos. Espera unos segundos."
        ResolveFailureType.SERVER -> "GlassPay no pudo procesar el QR. Intenta de nuevo."
        ResolveFailureType.INVALID_RESPONSE -> "Recibimos una respuesta inválida de GlassPay."
        ResolveFailureType.NOT_FOUND -> "El QR no es válido o ya venció."
    }
}
