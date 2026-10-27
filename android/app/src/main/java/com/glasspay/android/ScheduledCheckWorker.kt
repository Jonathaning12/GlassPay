package com.glasspay.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "glasspay-scheduled-check"
private const val CHANNEL_ID = "programados"
private const val NOTIFICATION_ID = 4101
private const val PREFS = "glasspay_scheduled_check"
private const val KEY_LAST_NOTIFIED = "last_notified_count"

/** Solo se avisa cuando aparece algo nuevo esperando confirmación (no cada 15 minutos). */
fun shouldNotify(previousCount: Int, currentCount: Int): Boolean = currentCount > 0 && currentCount > previousCount

/** Texto de la notificación: sin montos ni nombres, porque puede verse con el teléfono bloqueado. */
fun confirmationNotificationText(count: Int): String =
    if (count == 1) "Tienes 1 programación esperando tu confirmación." else "Tienes $count programaciones esperando tu confirmación."

/**
 * Revisa cada ~15 min (mínimo de WorkManager, no exacto) si algo programado espera confirmación
 * y avisa con una notificación. No ejecuta nada: confirmar siempre ocurre dentro de la app.
 */
class ScheduledCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = runCatching {
            CollectorSessionStore(SharedPreferencesStore(applicationContext), KeystoreSecretCipher()).load()
        }.getOrNull() ?: return Result.success()
        val waiting = try {
            PaymentApi().getScheduled(session).count { it.state == "esperando_confirmacion" }
        } catch (exception: ApiException) {
            Log.i("GlassPay", "scheduled check failure type=${exception.type} httpCode=${exception.httpCode}")
            // Sin sesión válida no hay nada que revisar; la app pedirá una cuenta al abrirse.
            return if (exception.type == ApiFailureType.UNAUTHORIZED) Result.success() else Result.retry()
        } catch (_: Exception) {
            Log.i("GlassPay", "scheduled check failure type=UNKNOWN httpCode=none")
            return Result.retry()
        }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getInt(KEY_LAST_NOTIFIED, 0)
        if (shouldNotify(previous, waiting)) notifyWaiting(applicationContext, waiting)
        prefs.edit().putInt(KEY_LAST_NOTIFIED, waiting).apply()
        return Result.success()
    }
}

private fun notifyWaiting(context: Context, count: Int) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        return
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Programados", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos de cobros y pagos programados que esperan tu confirmación"
        },
    )
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_clock)
        .setContentTitle("GlassPay")
        .setContentText(confirmationNotificationText(count))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
    try {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    } catch (_: SecurityException) {
        // El permiso se revocó entre la comprobación y el aviso.
    }
}

fun scheduleConfirmationChecks(context: Context) {
    val request = PeriodicWorkRequestBuilder<ScheduledCheckWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

fun cancelConfirmationChecks(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
