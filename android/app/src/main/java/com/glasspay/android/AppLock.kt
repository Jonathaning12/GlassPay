package com.glasspay.android

const val LOCK_TIMEOUT_MS = 5 * 60 * 1000L

fun shouldLockOnResume(
    backgroundedAtMs: Long?,
    nowMs: Long,
    timeoutMs: Long = LOCK_TIMEOUT_MS,
): Boolean {
    return backgroundedAtMs != null && nowMs - backgroundedAtMs >= timeoutMs
}

enum class DeviceAuthAvailability { AVAILABLE, NOT_CONFIGURED, UNSUPPORTED }

private val CURRENCY_SUFFIX = Regex(" [A-Z]{3}$")

fun maskAmount(formatted: String, visible: Boolean): String {
    if (visible) return formatted
    val suffix = CURRENCY_SUFFIX.find(formatted)?.value.orEmpty()
    return "\$ ••••$suffix"
}
