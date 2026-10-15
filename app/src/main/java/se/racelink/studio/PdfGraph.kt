package se.racelink.studio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.Locale

internal fun drawPdfGraph(canvas: Canvas, points: List<PdfGraphPoint>, top: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.DKGRAY }
    canvas.drawText("Tid (s) - lägre är snabbare | Åknummer | × = DNF", 40f, top, paint)
    val left = 85f; val right = 545f; val plotTop = top + 20f; val bottom = top + 160f
    val valid = points.mapNotNull { it.durationMs?.div(1000f) }
    val low = (valid.minOrNull() ?: 0f) - .3f
    val high = (valid.maxOrNull() ?: 1f) + .3f
    fun x(i: Int) = if (points.size == 1) (left + right) / 2 else left + (right - left) * i / (points.size - 1)
    fun y(ms: Long) = bottom - (ms / 1000f - low) / (high - low) * (bottom - plotTop)
    for (i in 0..3) {
        val value = low + (high - low) * i / 3
        val gridY = bottom - (value - low) / (high - low) * (bottom - plotTop)
        paint.color = Color.LTGRAY; paint.strokeWidth = .5f
        canvas.drawLine(left, gridY, right, gridY, paint)
        paint.color = Color.DKGRAY
        canvas.drawText(String.format(Locale.forLanguageTag("sv-SE"), "%.1f", value), 40f, gridY + 3f, paint)
    }
    points.forEachIndexed { i, point ->
        paint.color = Color.rgb(50, 102, 213); paint.strokeWidth = 1.8f
        point.durationMs?.let { ms ->
            if (i > 0 && points[i - 1].durationMs != null && point.number == points[i - 1].number + 1)
                canvas.drawLine(x(i - 1), y(points[i - 1].durationMs!!), x(i), y(ms), paint)
            canvas.drawCircle(x(i), y(ms), 3.2f, paint)
        }
        paint.color = Color.DKGRAY
        val label = "${point.number}${if (point.durationMs == null) " ×" else ""}"
        canvas.drawText(label, x(i) - paint.measureText(label) / 2, bottom + 16f, paint)
    }
}
