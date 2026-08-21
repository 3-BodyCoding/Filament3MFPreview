package com.filament.preview

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import kotlin.math.hypot
import kotlin.math.max

/**
 * Android 实现：使用系统字体把文字转成 2D 轮廓。
 */
class AndroidTextOutlineProvider : TextOutlineProvider {

    override fun outlines(text: String, fontHeightPx: Float): List<List<Vec2>> {
        if (text.isEmpty()) return emptyList()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = fontHeightPx.coerceAtLeast(1f)
        }

        val path = Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)

        val measure = PathMeasure()
        measure.setPath(path, false)
        if (measure.length <= 0f) return emptyList()

        val step = max(0.25f, fontHeightPx / 240f)
        val contours = mutableListOf<List<Vec2>>()
        do {
            val length = measure.length
            if (length <= 0f) continue

            val points = mutableListOf<Vec2>()
            var distance = 0f
            val position = FloatArray(2)
            while (distance <= length) {
                measure.getPosTan(distance, position, null)
                val point = Vec2(position[0], position[1])
                if (points.isEmpty() || distanceTo(points.last(), point) > 1e-4f) {
                    points += point
                }
                distance += step
            }

            // 保证闭合：首尾距离过近时去掉重复尾点
            if (points.size > 1) {
                val first = points.first()
                val last = points.last()
                if (distanceTo(first, last) <= 1e-3f) {
                    points.removeAt(points.size - 1)
                }
            }
            if (points.size >= 3) {
                contours += points.toList()
            }
        } while (measure.nextContour())

        return contours
    }

    private fun distanceTo(a: Vec2, b: Vec2): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
