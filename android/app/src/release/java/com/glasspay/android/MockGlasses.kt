package com.glasspay.android

import android.content.Context

/** En release no hay lentes simulados: Mock Device Kit solo se incluye en la versión de depuración. */
object MockGlasses {
    const val AVAILABLE = false

    fun isEnabled(context: Context): Boolean = false

    fun enable(context: Context): String? = null

    fun disable(context: Context) = Unit
}
