package mx.mitelcel.widget.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import mx.mitelcel.widget.widget.WidgetRenderer
import java.util.concurrent.TimeUnit

/**
 * Evita que "Actualizando…" se quede para siempre: a los 2 minutos revisa si la consulta terminó.
 * Si sigue pendiente, avisa por qué (sin conexión o Telcel lento); si ya terminó, solo redibuja.
 */
class StatusTimeoutWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val text = when (RefreshWorker.pendingState(applicationContext, accountId)) {
            null -> null
            WorkInfo.State.RUNNING -> "Telcel tarda en responder…"
            else -> "Sin conexión, reintentando…"
        }
        if (text == null) {
            WidgetRenderer.updateAll(applicationContext)
        } else {
            WidgetRenderer.showStatus(applicationContext, accountId, text)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val DELAY_MINUTES = 2L

        fun schedule(context: Context, accountId: String?) {
            val request = OneTimeWorkRequestBuilder<StatusTimeoutWorker>()
                .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
                .build()
            // REPLACE: tocar varias veces reinicia el reloj en vez de apilar avisos.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("mitelcel_status_timeout:${accountId ?: "all"}", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
