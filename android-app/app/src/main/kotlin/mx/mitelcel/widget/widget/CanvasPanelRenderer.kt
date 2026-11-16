package mx.mitelcel.widget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import mx.mitelcel.widget.core.AmountParts
import mx.mitelcel.widget.core.Forecast
import mx.mitelcel.widget.core.UsageEntry
import mx.mitelcel.widget.core.UsageLevel
import mx.mitelcel.widget.data.LayoutStyle
import kotlin.math.min

/**
 * Dibuja el panel COMPLETO de una línea en una sola imagen, para los diseños nuevos
 * (anillo, número, gasto al día, cuadrado con barra y franja). Igual que el medidor original,
 * se hace con un Bitmap porque RemoteViews no admite vistas personalizadas. Como el texto va
 * dentro de la imagen, los tamaños se ajustan solos para que nada se corte.
 */
object CanvasPanelRenderer {
    private const val SQUARE = 360
    private const val STRIP_WIDTH = 900
    private const val STRIP_HEIGHT = 220
    private const val MARGIN = 20f
    private const val SUBTLE_ALPHA = 190

    /** Lo que se dibuja de la línea, ya resuelto (el color de estado y las frases salen de la lógica central). */
    class Content(
        val entry: UsageEntry,
        val title: String,
        val level: UsageLevel,
        val status: String,
        /** Aviso corto abajo (ej. "Actualizando…" o "☁ Sin actualizar desde ayer 22:15"); null = nada. */
        val note: String?,
        /** true = la nota es solo la fecha de la última consulta: se dibuja más chica y tenue para no estorbar. */
        val noteSubtle: Boolean = false,
    )

    /** Frase de estado: "Te alcanza", "Se acaba antes", "Casi sin datos" (vacía si no se puede saber). */
    fun statusWord(entry: UsageEntry, forecast: Forecast, level: UsageLevel): String = when {
        !entry.hasPlan -> ""
        entry.unlimited -> "Sin límite"
        level == UsageLevel.LOW -> "Casi sin datos"
        level == UsageLevel.WARN -> if (forecast is Forecast.RunsOut) "Se acaba antes" else "Quedan pocos"
        forecast == Forecast.Enough -> "Te alcanza"
        else -> ""
    }

    fun render(style: LayoutStyle, content: Content, palette: WidgetPalette): Bitmap {
        val width = if (style == LayoutStyle.STRIP) STRIP_WIDTH else SQUARE
        val height = if (style == LayoutStyle.STRIP) STRIP_HEIGHT else SQUARE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (!content.entry.hasPlan) {
            drawNoData(canvas, content, palette, width)
            return bitmap
        }
        when (style) {
            LayoutStyle.RING -> drawRing(canvas, content, palette)
            LayoutStyle.NUMBER -> drawNumber(canvas, content, palette)
            LayoutStyle.DAILY -> drawDaily(canvas, content, palette)
            LayoutStyle.SQUARE -> drawSquare(canvas, content, palette)
            LayoutStyle.STRIP -> drawStrip(canvas, content, palette)
            // Los dos diseños originales no pasan por aquí; si alguien se equivoca, se ve un anillo.
            LayoutStyle.GAUGE, LayoutStyle.BAR -> drawRing(canvas, content, palette)
        }
        return bitmap
    }

    // ------------------------------------------------------------------ diseños

    /** Anillo: lo que queda llena el aro; el color dice cómo vas. Número grande al centro. */
    private fun drawRing(canvas: Canvas, content: Content, palette: WidgetPalette) {
        val entry = content.entry
        val cx = SQUARE / 2f
        val cy = 150f
        val radius = 112f
        val stroke = 30f
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = palette.track
        }
        canvas.drawArc(bounds, 0f, 360f, false, arc)
        val ratio = entry.remainingRatio.toFloat()
        if (ratio > 0.004f) {
            arc.color = levelColor(content.level, palette)
            canvas.drawArc(bounds, -90f, 360f * ratio, false, arc) // -90° = arriba; sentido horario
        }

        val parts = entry.remainingParts
        val inner = 2 * (radius - stroke / 2) - 24f
        val number = paint(palette.primary, 92f, bold = true, align = Paint.Align.CENTER, palette = palette).fit(parts.value, inner)
        val unit = paint(palette.primary, 40f, bold = true, align = Paint.Align.CENTER, palette = palette).fit(parts.unit, inner)
        canvas.drawText(parts.value, cx, cy + 10f, number)
        canvas.drawText(parts.unit, cx, cy + 56f, unit)

        val days = paint(palette.forDaysLeft(entry.daysLeft), 38f, bold = true, align = Paint.Align.CENTER, palette = palette)
        canvas.drawText(entry.daysLeftText, cx, 318f, days.fit(entry.daysLeftText, SQUARE - 2 * MARGIN))
        drawNote(canvas, content, palette, SQUARE / 2f, 350f, Paint.Align.CENTER, SQUARE - 2 * MARGIN)
    }

    /** Solo el número: lo más fácil de leer de un vistazo. */
    private fun drawNumber(canvas: Canvas, content: Content, palette: WidgetPalette) {
        val entry = content.entry
        val maxWidth = SQUARE - 2 * MARGIN
        val parts = entry.remainingParts
        val number = paint(palette.primary, 150f, bold = true, palette = palette).fit(parts.value, maxWidth)
        canvas.drawText(parts.value, MARGIN, 165f, number)
        val unit = paint(palette.primary, 56f, bold = true, palette = palette).fit(parts.unit, maxWidth)
        canvas.drawText(parts.unit, MARGIN, 225f, unit)

        val line = "${entry.daysLeftText} · corte ${entry.shortCutoffDate}"
        canvas.drawText(line, MARGIN, 272f, paint(palette.secondary, 32f, palette = palette).fit(line, maxWidth))
        drawStatus(canvas, content, palette, MARGIN, 318f, Paint.Align.LEFT, maxWidth)
        drawNote(canvas, content, palette, MARGIN, 348f, Paint.Align.LEFT, maxWidth)
    }

    /** Número + cuánto puedes gastar por día para llegar al corte. */
    private fun drawDaily(canvas: Canvas, content: Content, palette: WidgetPalette) {
        val entry = content.entry
        val maxWidth = SQUARE - 2 * MARGIN
        drawAmount(canvas, entry.remainingParts, MARGIN, 112f, 104f, 48f, maxWidth, palette.primary, palette)
        val total = "de ${entry.totalText}"
        canvas.drawText(total, MARGIN, 156f, paint(palette.secondary, 32f, palette = palette).fit(total, maxWidth))
        drawBar(canvas, entry.remainingRatio.toFloat(), MARGIN, 180f, maxWidth, 22f, levelColor(content.level, palette), palette)

        val perDay = if (entry.unlimited) "Sin límite" else entry.dailyAllowanceText.removeSuffix("/d") + " al día"
        canvas.drawText(perDay, MARGIN, 258f, paint(palette.primary, 44f, bold = true, palette = palette).fit(perDay, maxWidth))
        val during = "${entry.daysLeftText} para el corte"
        canvas.drawText(during, MARGIN, 300f, paint(palette.secondary, 30f, palette = palette).fit(during, maxWidth))
        drawNote(canvas, content, palette, MARGIN, 344f, Paint.Align.LEFT, maxWidth)
    }

    /** Cuadrado con nombre y días arriba, número grande, barra y estado. */
    private fun drawSquare(canvas: Canvas, content: Content, palette: WidgetPalette) {
        val entry = content.entry
        val maxWidth = SQUARE - 2 * MARGIN
        val days = paint(palette.forDaysLeft(entry.daysLeft), 30f, bold = true, align = Paint.Align.RIGHT, palette = palette)
        canvas.drawText(entry.daysLeftText, SQUARE - MARGIN, 40f, days.fit(entry.daysLeftText, 120f))
        val title = paint(palette.primary, 30f, bold = true, palette = palette).fit(content.title, maxWidth - 130f)
        canvas.drawText(content.title, MARGIN, 40f, title)

        drawAmount(canvas, entry.remainingParts, MARGIN, 150f, 118f, 50f, maxWidth, palette.primary, palette)
        drawBar(canvas, entry.remainingRatio.toFloat(), MARGIN, 180f, maxWidth, 26f, levelColor(content.level, palette), palette)
        drawStatus(canvas, content, palette, MARGIN, 262f, Paint.Align.LEFT, maxWidth)
        val total = "de ${entry.totalText}"
        canvas.drawText(total, MARGIN, 306f, paint(palette.secondary, 30f, palette = palette).fit(total, maxWidth))
        drawNote(canvas, content, palette, MARGIN, 346f, Paint.Align.LEFT, maxWidth)
    }

    /** Franja 4×1: número a la izquierda; días y estado a la derecha. */
    private fun drawStrip(canvas: Canvas, content: Content, palette: WidgetPalette) {
        val entry = content.entry
        drawAmount(canvas, entry.remainingParts, 30f, 138f, 140f, 60f, 520f, palette.primary, palette)
        val total = "de ${entry.totalText}"
        canvas.drawText(total, 30f, 192f, paint(palette.secondary, 44f, palette = palette).fit(total, 520f))

        val right = STRIP_WIDTH - 30f
        val days = paint(palette.forDaysLeft(entry.daysLeft), 80f, bold = true, align = Paint.Align.RIGHT, palette = palette)
        canvas.drawText(entry.daysLeftText, right, 104f, days.fit(entry.daysLeftText, 300f))
        drawStatus(canvas, content, palette, right, 164f, Paint.Align.RIGHT, 300f, size = 46f)
        drawNote(canvas, content, palette, right, 206f, Paint.Align.RIGHT, 300f, size = 28f)
    }

    /** Sin plan o sin datos todavía: el nombre y un aviso; la razón concreta va en la nota. */
    private fun drawNoData(canvas: Canvas, content: Content, palette: WidgetPalette, width: Int) {
        val maxWidth = width - 2 * MARGIN
        val title = paint(palette.primary, 44f, bold = true, align = Paint.Align.CENTER, palette = palette).fit(content.title, maxWidth)
        canvas.drawText(content.title, width / 2f, 120f, title)
        val text = "Sin datos"
        val sub = paint(palette.secondary, 38f, align = Paint.Align.CENTER, palette = palette).fit(text, maxWidth)
        canvas.drawText(text, width / 2f, 180f, sub)
        drawNote(canvas, content, palette, width / 2f, 230f, Paint.Align.CENTER, maxWidth)
    }

    // --------------------------------------------------------------- utilidades

    private fun levelColor(level: UsageLevel, palette: WidgetPalette): Int = when (level) {
        UsageLevel.OK -> palette.available
        UsageLevel.WARN -> palette.warn
        UsageLevel.LOW -> palette.spent
    }

    private fun paint(
        color: Int,
        size: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        palette: WidgetPalette,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textAlign = align
        palette.textShadow?.let { setShadowLayer(5f, 0f, 2f, it) }
    }

    /** Achica el texto, si hace falta, para que quepa en [maxWidth]. Devuelve el mismo Paint para encadenar. */
    private fun Paint.fit(text: String, maxWidth: Float): Paint {
        val width = measureText(text)
        if (width > maxWidth && width > 0f) textSize *= maxWidth / width
        return this
    }

    /** Número grande + unidad pequeña en la misma línea ("51.9" "GB"), escalados juntos para que quepan. */
    private fun drawAmount(
        canvas: Canvas,
        parts: AmountParts,
        x: Float,
        baseline: Float,
        numberSize: Float,
        unitSize: Float,
        maxWidth: Float,
        color: Int,
        palette: WidgetPalette,
    ) {
        val number = paint(color, numberSize, bold = true, palette = palette)
        val unit = paint(color, unitSize, bold = true, palette = palette)
        val gap = 12f
        val total = number.measureText(parts.value) + gap + unit.measureText(parts.unit)
        val scale = if (total > maxWidth && total > 0f) maxWidth / total else 1f
        number.textSize *= scale
        unit.textSize *= scale
        canvas.drawText(parts.value, x, baseline, number)
        canvas.drawText(parts.unit, x + number.measureText(parts.value) + gap * scale, baseline, unit)
    }

    private fun drawBar(canvas: Canvas, ratio: Float, x: Float, y: Float, width: Float, height: Float, color: Int, palette: WidgetPalette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = height / 2f
        paint.color = palette.track
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), radius, radius, paint)
        val filled = width * min(1f, ratio)
        if (filled > 1f) {
            paint.color = color
            canvas.drawRoundRect(RectF(x, y, x + maxOf(height, filled), y + height), radius, radius, paint)
        }
    }

    private fun drawStatus(
        canvas: Canvas,
        content: Content,
        palette: WidgetPalette,
        x: Float,
        baseline: Float,
        align: Paint.Align,
        maxWidth: Float,
        size: Float = 38f,
    ) {
        if (content.status.isEmpty()) return
        val paint = paint(levelColor(content.level, palette), size, bold = true, align = align, palette = palette)
        canvas.drawText(content.status, x, baseline, paint.fit(content.status, maxWidth))
    }

    private fun drawNote(
        canvas: Canvas,
        content: Content,
        palette: WidgetPalette,
        x: Float,
        baseline: Float,
        align: Paint.Align,
        maxWidth: Float,
        size: Float = 26f,
    ) {
        val note = content.note ?: return
        val subtle = content.noteSubtle
        val paint = paint(palette.secondary, if (subtle) size - 5f else size, align = align, palette = palette)
        if (subtle) paint.alpha = SUBTLE_ALPHA
        canvas.drawText(note, x, baseline, paint.fit(note, maxWidth))
    }
}
