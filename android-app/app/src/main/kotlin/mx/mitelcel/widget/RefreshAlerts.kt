package mx.mitelcel.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mx.mitelcel.widget.data.Account
import mx.mitelcel.widget.data.RefreshResult

/**
 * Avisa cuando las consultas fallan varias veces seguidas por algo que NO es falta de señal
 * (la compañía cambió algo, la contraseña dejó de servir, etc.) y cuando Telcel no cierra la
 * sesión (dev.smapps.mx). Máximo un aviso al día por tema, para no molestar.
 */
class RefreshAlerts(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("refresh_alerts", Context.MODE_PRIVATE)

    /** Anota el resultado de la consulta de una línea y avisa si ya van [FAILURES_BEFORE_ALERT] fallos seguidos. */
    fun onRefresh(account: Account, result: RefreshResult) {
        val key = "fail_${account.id}"
        // Sin error = hubo consulta buena (o un dato reciente): se reinicia la cuenta. Sin señal no cuenta: ya lo marca el widget.
        if (result.error == null) {
            prefs.edit().remove(key).apply()
            return
        }
        if (result.retryable) return
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, count).apply()
        if (count < FAILURES_BEFORE_ALERT) return
        alert(
            topic = "line_${account.id}",
            id = account.id.hashCode() + 1,
            title = "${account.alias}: no se pudo actualizar",
            text = "Falló $count veces seguidas. Motivo: ${result.error}",
        )
    }

    /** Resultado de cerrar la sesión SSO de Telcel (null = salió bien). Si falla varias veces, avisa. */
    fun recordRevoke(error: Throwable?) {
        if (error == null) {
            prefs.edit().remove(REVOKE_KEY).apply()
            return
        }
        val count = prefs.getInt(REVOKE_KEY, 0) + 1
        prefs.edit().putInt(REVOKE_KEY, count).apply()
        if (count < FAILURES_BEFORE_ALERT) return
        alert(
            topic = "revoke",
            id = REVOKE_NOTIFICATION_ID,
            title = "Telcel no cerró tu sesión",
            text = "Falló $count veces seguidas el cierre de sesión (dev.smapps.mx). " +
                "Si se acumulan sesiones abiertas, Telcel puede bloquear el acceso. Motivo: ${error.message ?: error.javaClass.simpleName}",
        )
    }

    private fun alert(topic: String, id: Int, title: String, text: String) {
        val last = prefs.getLong("last_$topic", 0L)
        if (clock() - last < ALERT_EVERY_MS) return
        if (!canNotify()) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = "$text\n\nSi sigue pasando, mándale una captura de este aviso a Claude " +
            "para que revise el código (versión ${BuildConfig.VERSION_NAME})."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_data)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
        prefs.edit().putLong("last_$topic", clock()).apply()
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Fallos de actualización", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Aviso cuando la app no logra actualizar tus datos varias veces seguidas"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "refresh_alerts"
        const val FAILURES_BEFORE_ALERT = 3
        private const val ALERT_EVERY_MS = 24 * 60 * 60 * 1000L
        private const val REVOKE_KEY = "revoke_fail"
        private const val REVOKE_NOTIFICATION_ID = 7001
    }
}
