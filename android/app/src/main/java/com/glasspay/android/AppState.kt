package com.glasspay.android

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AppTab { HOME, MOVEMENTS, CHARGE, CONTACTS }

enum class StatusKind { SUCCESS, PENDING, FAILURE, NEUTRAL }

/** La barra inferior solo se permite cuando cambiar de pestaña no pierde un cobro en curso. */
fun allowsTabSwitch(resolve: ResolveState, charge: ChargeState): Boolean {
    if (resolve is ResolveState.Resolving) return false
    if (resolve !is ResolveState.Identified) return true
    return charge is ChargeState.Ready ||
        (charge is ChargeState.Error && charge.recoverable) ||
        (charge is ChargeState.Created && isTerminal(charge.payment))
}

/** Solo se abre el enlace de aprobación si es https y el host es exactamente el de PayPal Sandbox. */
fun isAllowedApproveUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val uri = java.net.URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("www.sandbox.paypal.com", ignoreCase = true) &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    } catch (_: Exception) {
        false
    }
}

fun movementStatusLabel(state: String, kind: String = "cobro"): String {
    if (kind == "envio") {
        return when (state) {
            "procesando" -> "Enviando"
            "completada" -> "Enviado"
            "no_reclamada" -> "Sin reclamar"
            "fallida" -> "Falló"
            else -> "Desconocido"
        }
    }
    return when (state) {
        "pagada" -> "Pagado"
        "aprobada", "enviada" -> "Procesando"
        "pendiente_de_pagador", "creada" -> "Esperando al pagador"
        "rechazada" -> "Rechazado"
        "expirada" -> "Expirado"
        "fallida" -> "Falló"
        else -> "Desconocido"
    }
}

fun movementStatusKind(state: String, kind: String = "cobro"): StatusKind {
    if (kind == "envio") {
        return when (state) {
            "procesando", "no_reclamada" -> StatusKind.PENDING
            "completada" -> StatusKind.SUCCESS
            "fallida" -> StatusKind.FAILURE
            else -> StatusKind.NEUTRAL
        }
    }
    return when (state) {
        "pagada" -> StatusKind.SUCCESS
        "aprobada", "enviada", "pendiente_de_pagador", "creada" -> StatusKind.PENDING
        "rechazada", "expirada", "fallida" -> StatusKind.FAILURE
        else -> StatusKind.NEUTRAL
    }
}

fun formatMoney(amount: String, currency: String): String {
    val number = try {
        BigDecimal(amount.trim())
    } catch (_: NumberFormatException) {
        return "$amount $currency"
    }
    return "\$" + number.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency
}

fun formatMovementDate(iso: String): String {
    return try {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("es-MX"))
            .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
    } catch (_: Exception) {
        iso
    }
}
