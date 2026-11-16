package mx.mitelcel.widget.core

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Un monto ya formateado: [value] "51.9" y [unit] "GB" (o "847" y "MB"). */
data class AmountParts(val value: String, val unit: String) {
    val text: String get() = "$value $unit"
}

/** Una bolsa de datos (ej. "0-0-0" Plan Base, "0-1-0" Datos Promocionales). */
@Serializable
data class BucketUsage(val id: String, val name: String, val consumedMB: Double, val totalMB: Double, val availableMB: Double)

/**
 * Resultado de una consulta: suma de todas las bolsas + fecha de corte.
 * Equivalente a `TelcelUsageEntry` de la versión iOS.
 */
@Serializable
data class UsageEntry(
    val timestampMillis: Long,
    val consumedMB: Double,
    val totalMB: Double,
    val availableMB: Double,
    val daysLeft: Int,
    val cutoffDate: String,
    val planName: String,
    val buckets: List<BucketUsage>,
    val updatedAt: String,
    val isCached: Boolean = false,
    /** Telcel cuenta 1 GB = 1000 MB; Bait y AT&T, 1 GB = 1024 MB. Así cada widget coincide con su app oficial. */
    val mbPerGb: Int = 1000,
    /** Plan de datos sin límite (ej. Movistar Ilimitado): no hay total, así que no hay medidor que llenar. */
    val unlimited: Boolean = false,
) {
    /** Hay algo que mostrar: un plan con total o uno ilimitado. */
    val hasPlan: Boolean get() = totalMB > 0 || unlimited

    val hasPromo: Boolean get() = buckets.size > 1

    val remainingGB: Double get() = max(0.0, availableMB / mbPerGb)

    val totalGB: Double get() = totalMB / mbPerGb

    /** Lo que queda partido en número y unidad ("51.9" + "GB", o "847" + "MB"). */
    val remainingParts: AmountParts
        get() = if (unlimited) AmountParts("∞", "Ilimitado") else amountParts(max(0.0, availableMB))

    /** "51.9 GB" o "847 MB": lo mismo en todos los diseños (un solo lugar para el formato). */
    val remainingText: String get() = if (unlimited) "Ilimitado" else remainingParts.text

    /** Fracción de lo contratado que aún queda (0.0 a 1.0). Un plan ilimitado cuenta como lleno. */
    val remainingRatio: Double
        get() = when {
            unlimited -> 1.0
            totalMB > 0 -> min(1.0, max(0.0, availableMB / totalMB))
            else -> 0.0
        }

    /** "15.0 GB" */
    val totalText: String get() = fmt("%.1f GB", totalGB)

    /** Bajo la cantidad: "libres de 15.0 GB", o "datos sin límite" en un plan ilimitado. */
    val subtitleText: String get() = if (unlimited) "datos sin límite" else "libres de $totalText"

    /** Línea roja: "● 9.50 GB (63%)". En un plan ilimitado no hay porcentaje que mostrar. */
    val spentLine: String get() = if (unlimited) "● Ilimitado" else "● $spentShortText ($percentUsed%)"

    /** Línea verde: "● 608 MB/d" (lo que puedes gastar por día para llegar al corte). */
    val dailyLine: String get() = if (unlimited) "● Sin límite" else "● $dailyAllowanceText"

    val usageRatio: Double
        get() = if (totalMB > 0) min(1.0, max(0.0, consumedMB / totalMB)) else 0.0

    val percentUsed: Int get() = (usageRatio * 100.0).roundToInt()

    /** "28 de octubre" -> "28 oct" */
    val shortCutoffDate: String
        get() = cutoffDate.replace(SHORT_MONTH_REGEX, " $1")

    /** Datos disponibles por día hasta la fecha de corte. */
    val dailyAllowanceText: String
        get() {
            val dailyMB = max(0.0, availableMB) / max(1, daysLeft)
            return amountParts(dailyMB).text + "/d"
        }

    val spentShortText: String
        get() = amountParts(max(0.0, consumedMB)).text

    /**
     * Cantidad con un decimal en GB, o en MB enteros si es menos de 1 GB. Redondear 999.6 MB daría
     * "1000 MB": en ese caso se muestra "1.0 GB" para que nunca salgan 4 dígitos en MB.
     */
    internal fun amountParts(mb: Double): AmountParts {
        if (mb < mbPerGb) {
            val rounded = mb.roundToInt()
            if (rounded < mbPerGb) return AmountParts(rounded.toString(), "MB")
            return AmountParts("1.0", "GB")
        }
        return AmountParts(fmt("%.1f", mb / mbPerGb), "GB")
    }

    val daysLeftText: String get() = "$daysLeft ${if (daysLeft == 1) "día" else "días"}"

    companion object {
        private val SHORT_MONTH_REGEX =
            Regex("""\s+de\s+([a-záéíóú]{3})[a-záéíóú]*""", RegexOption.IGNORE_CASE)

        /**
         * Locale.US a propósito: con el locale del teléfono (ej. es-ES) "%.2f" daría "5,47"
         * y el widget mostraría formatos distintos según la configuración regional.
         */
        internal fun fmt(pattern: String, value: Double): String = String.format(Locale.US, pattern, value)

        val errorFallback = UsageEntry(
            timestampMillis = 0,
            consumedMB = 0.0,
            totalMB = 0.0,
            availableMB = 0.0,
            daysLeft = 0,
            cutoffDate = "Verificar cuenta",
            planName = "Sin conexión o credenciales",
            buckets = emptyList(),
            updatedAt = "--:--",
            isCached = true,
        )
    }
}
