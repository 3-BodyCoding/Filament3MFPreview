package com.filament.preview

import org.maplibre.earcut4j.Earcut
import kotlin.math.max
import kotlin.math.min

/** 2D point used by text outlines and plate polygon generation. */
data class Vec2(val x: Float, val y: Float)

/**
 * 用户可配置的 3D 打印底板。
 *
 * 坐标约定：
 * - X = 底板宽度方向
 * - Y = 底板深度方向，前方/品牌区为 -Y
 * - Z = 高度方向，打印面 Z=0
 */
data class BuildPlateConfig(
    val widthMm: Float = 256f,
    val depthMm: Float = 256f,
    val thicknessMm: Float = 6f,
    val frontExtensionMm: Float = 24f,
    val brandAreaWidthMm: Float = 120f,
    val brandAreaFrontWidthMm: Float = brandAreaWidthMm * 0.6f,
    val brandText: String = "MY BRAND",
    val brandHeightMm: Float = 8f,
    val brandExtrusionMm: Float = 1.2f,
    val maxBrandLength: Int = 20,
    val maxBrandWidthRatio: Float = 0.7f,
    val plateColor: FloatArray = floatArrayOf(0.05f, 0.06f, 0.08f, 0.55f),
    val brandAreaColor: FloatArray = floatArrayOf(0.08f, 0.09f, 0.11f, 0.55f),
    val boundaryColor: FloatArray = floatArrayOf(0.72f, 0.74f, 0.78f, 1.0f),
    val textColor: FloatArray = floatArrayOf(0.92f, 0.93f, 0.95f, 1.0f),
) {
    /** 品牌文字按配置长度截断；只保留常规可见字符，空字符返回空串。 */
    fun effectiveBrandText(): String {
        val normalized = brandText.trim()
        if (normalized.isEmpty()) return ""
        return normalized.take(maxBrandLength.coerceAtLeast(0))
    }
}

/** 实际允许放置模型的打印区域，单位 mm，局部坐标以打印区中心为原点。 */
data class PrintArea(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    val width: Float get() = maxX - minX
    val depth: Float get() = maxY - minY
}

/** AABB 越界检测结果，单位为 mm。 */
data class PrintAreaCheck(
    val isInside: Boolean,
    val overflowMinX: Float = 0f,
    val overflowMaxX: Float = 0f,
    val overflowMinY: Float = 0f,
    val overflowMaxY: Float = 0f,
) {
    val hasOverflow: Boolean get() = !isInside
}

fun checkBoundsInsidePrintArea(bounds: Bounds, printArea: PrintArea): PrintAreaCheck {
    val overflowMinX = (printArea.minX - bounds.min.x).coerceAtLeast(0f)
    val overflowMaxX = (bounds.max.x - printArea.maxX).coerceAtLeast(0f)
    val overflowMinY = (printArea.minY - bounds.min.y).coerceAtLeast(0f)
    val overflowMaxY = (bounds.max.y - printArea.maxY).coerceAtLeast(0f)
    val inside = overflowMinX == 0f && overflowMaxX == 0f &&
        overflowMinY == 0f && overflowMaxY == 0f
    return PrintAreaCheck(inside, overflowMinX, overflowMaxX, overflowMinY, overflowMaxY)
}

/** 检查一组已放置模型是否都在打印区域内（只检查 X/Y，Z 不做限制）。 */
fun List<PlacedMeshData>.printAreaCheck(
    config: BuildPlateConfig,
    plateCenterX: Float,
    plateCenterY: Float,
): PrintAreaCheck {
    val printArea = PrintArea(
        minX = plateCenterX - config.widthMm / 2f,
        maxX = plateCenterX + config.widthMm / 2f,
        minY = plateCenterY - config.depthMm / 2f,
        maxY = plateCenterY + config.depthMm / 2f,
    )
    var result = PrintAreaCheck(isInside = true)
    for (placed in this) {
        val check = checkBoundsInsidePrintArea(placed.placedBounds(), printArea)
        if (!check.isInside) {
            result = PrintAreaCheck(
                isInside = false,
                overflowMinX = maxOf(result.overflowMinX, check.overflowMinX),
                overflowMaxX = maxOf(result.overflowMaxX, check.overflowMaxX),
                overflowMinY = maxOf(result.overflowMinY, check.overflowMinY),
                overflowMaxY = maxOf(result.overflowMaxY, check.overflowMaxY),
            )
        }
    }
    return result
}

/** 生成后的 Mesh 数据，坐标已经转换到 Filament/GLB 使用的归一化空间。 */
data class GeneratedMesh(
    val vertices: FloatArray,
    val indices: IntArray,
    val color: FloatArray,
)

/** 一整套底板生成结果。 */
data class BuildPlateGeometry(
    val physicalPlate: GeneratedMesh,
    val boundary: GeneratedMesh?,
    val brandText: GeneratedMesh?,
    val printArea: PrintArea,
)

/** 把毫米世界坐标映射到当前 GLB 场景坐标（归一化空间）。 */
data class SceneNormalization(
    val center: Vec3,
    val scale: Float,
) {
    fun applyX(x: Float): Float = (x - center.x) * scale
    fun applyY(y: Float): Float = (y - center.y) * scale
    fun applyZ(z: Float): Float = (z - center.z) * scale

    fun apply(x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf(applyX(x), applyY(y), applyZ(z))
}

/** 文字轮廓，每个 contour 是一组按顺序连接的二维点。 */
data class TextOutline(val contours: List<List<Vec2>>)

/** 从用户文字提取 2D 轮廓的抽象；Android 实现使用 Typeface/Paint/PathMeasure。 */
interface TextOutlineProvider {
    fun outlines(text: String, fontHeightPx: Float): List<List<Vec2>>
}

/**
 * 底板 Mesh 生成器。
 *
 * 输入为毫米配置 + 场景归一化 + 文字轮廓，输出归一化空间内的 Mesh。
 * 该对象不依赖 Android 图形 API，便于 JVM 单测。
 */
object BuildPlateMeshGenerator {
    private const val BOUNDARY_HEIGHT_MM = 0.15f
    private const val BOUNDARY_WIDTH_MM = 1.0f

    fun generate(
        config: BuildPlateConfig,
        normalization: SceneNormalization,
        textOutlines: List<List<Vec2>>,
    ): BuildPlateGeometry {
        val physical = generatePhysicalPlate(config, normalization)
        val boundary = generateBoundary(config, normalization)
        val text = generateTextMesh(config, normalization, textOutlines)
        val printArea = PrintArea(
            minX = -config.widthMm / 2f,
            maxX = config.widthMm / 2f,
            minY = -config.depthMm / 2f,
            maxY = config.depthMm / 2f,
        )
        return BuildPlateGeometry(
            physicalPlate = physical,
            boundary = boundary,
            brandText = text,
            printArea = printArea,
        )
    }

    // ------------------------------------------------------------------
    // 物理底板
    // ------------------------------------------------------------------

    private fun generatePhysicalPlate(
        config: BuildPlateConfig,
        normalization: SceneNormalization,
    ): GeneratedMesh {
        val polygon = platePolygon(config)
        return extrudePolygon(
            polygon = polygon,
            zBottom = -config.thicknessMm,
            zTop = 0f,
            color = config.plateColor,
            normalization = normalization,
        )
    }

    /** 打印区 + 前方中间梯形品牌区的联合外轮廓。 */
    private fun platePolygon(config: BuildPlateConfig): List<Vec2> {
        val hw = config.widthMm / 2f
        val hd = config.depthMm / 2f
        val bwBack = (config.brandAreaWidthMm / 2f).coerceIn(0f, hw)
        val bwFront = (config.brandAreaFrontWidthMm / 2f).coerceIn(0f, hw)
        val fe = config.frontExtensionMm

        if (fe <= 0f || (bwBack <= 0f && bwFront <= 0f)) {
            return listOf(
                Vec2(-hw, -hd),
                Vec2(hw, -hd),
                Vec2(hw, hd),
                Vec2(-hw, hd),
            )
        }

        return listOf(
            Vec2(-hw, -hd),
            Vec2(-bwBack, -hd),
            Vec2(-bwFront, -hd - fe),
            Vec2(bwFront, -hd - fe),
            Vec2(bwBack, -hd),
            Vec2(hw, -hd),
            Vec2(hw, hd),
            Vec2(-hw, hd),
        )
    }

    // ------------------------------------------------------------------
    // 打印区边界
    // ------------------------------------------------------------------

    private fun generateBoundary(
        config: BuildPlateConfig,
        normalization: SceneNormalization,
    ): GeneratedMesh? {
        if (config.widthMm <= 0f || config.depthMm <= 0f) return null
        val hw = config.widthMm / 2f
        val hd = config.depthMm / 2f
        val w = min(BOUNDARY_WIDTH_MM, config.widthMm / 2f)
        val h = min(BOUNDARY_WIDTH_MM, config.depthMm / 2f)

        val boxes = listOf(
            // bottom
            box(-hw, hw, -hd, -hd + h, 0f, BOUNDARY_HEIGHT_MM),
            // right
            box(hw - w, hw, -hd, hd, 0f, BOUNDARY_HEIGHT_MM),
            // top
            box(-hw, hw, hd - h, hd, 0f, BOUNDARY_HEIGHT_MM),
            // left
            box(-hw, -hw + w, -hd, hd, 0f, BOUNDARY_HEIGHT_MM),
        )
        return mergeBoxes(boxes, config.boundaryColor, normalization)
    }

    // ------------------------------------------------------------------
    // 品牌文字 3D Mesh
    // ------------------------------------------------------------------

    private fun generateTextMesh(
        config: BuildPlateConfig,
        normalization: SceneNormalization,
        outlines: List<List<Vec2>>,
    ): GeneratedMesh? {
        val text = config.effectiveBrandText()
        if (text.isEmpty() || outlines.isEmpty()) return null

        val allBounds = outlinesBounds(outlines) ?: return null
        val textWidth = allBounds.maxX - allBounds.minX
        val textHeight = allBounds.maxY - allBounds.minY
        if (textWidth <= 0f || textHeight <= 0f) return null

        val maxTextWidth = config.brandAreaWidthMm * config.maxBrandWidthRatio
        val scale = min(
            if (textWidth > 0f) maxTextWidth / textWidth else Float.MAX_VALUE,
            if (textHeight > 0f) config.brandHeightMm / textHeight else Float.MAX_VALUE,
        ).coerceAtLeast(0.0001f)

        val brandCenterX = 0f
        val brandCenterY = -config.depthMm / 2f - config.frontExtensionMm / 2f
        val textCenterX = (allBounds.minX + allBounds.maxX) / 2f
        val textCenterY = (allBounds.minY + allBounds.maxY) / 2f

        fun map(point: Vec2): Vec2 = Vec2(
            x = brandCenterX + (point.x - textCenterX) * scale,
            // Android Path 的 y 向下为正；世界坐标中 -Y 是前方。
            // 翻转 y 后，文字从正前方看才是正常方向。
            y = brandCenterY - (point.y - textCenterY) * scale,
        )

        val scaledContours = outlines.map { contour -> contour.map(::map) }
        return extrudeTextContours(
            contours = scaledContours,
            zBottom = 0f,
            zTop = config.brandExtrusionMm,
            color = config.textColor,
            normalization = normalization,
        )
    }

    private fun extrudeTextContours(
        contours: List<List<Vec2>>,
        zBottom: Float,
        zTop: Float,
        color: FloatArray,
        normalization: SceneNormalization,
    ): GeneratedMesh? {
        if (contours.isEmpty()) return null
        val groups = groupContoursWithHoles(contours) ?: return null
        if (groups.isEmpty()) return null

        val allVertices = mutableListOf<Float>()
        val allIndices = mutableListOf<Int>()

        groups.forEach { (outer, holes) ->
            val polygon = buildList {
                addAll(outer)
                holes.forEach { addAll(it) }
            }
            if (polygon.size < 3) return@forEach

            val holeStartIndices = mutableListOf<Int>()
            var cursor = outer.size
            holes.forEach { hole ->
                holeStartIndices += cursor
                cursor += hole.size
            }

            val data = DoubleArray(polygon.size * 2) { index ->
                if (index % 2 == 0) polygon[index / 2].x.toDouble() else polygon[index / 2].y.toDouble()
            }
            val triangles = try {
                Earcut.earcut(
                    data,
                    if (holeStartIndices.isEmpty()) null else holeStartIndices.toIntArray(),
                    2,
                )
            } catch (_: Throwable) {
                return@forEach
            }
            if (triangles.isEmpty()) return@forEach

            val baseVertex = allVertices.size / 3

            // Top face vertices (z = zTop)
            polygon.forEach { p ->
                val n = normalization.toLocalWorld(p.x, p.y, zTop)
                allVertices += n[0]; allVertices += n[1]; allVertices += n[2]
            }
            // Bottom vertices used only for side walls (z = zBottom)
            polygon.forEach { p ->
                val n = normalization.toLocalWorld(p.x, p.y, zBottom)
                allVertices += n[0]; allVertices += n[1]; allVertices += n[2]
            }

            // Top triangles
            triangles.forEach { index ->
                allIndices += baseVertex + index
            }

            // Side walls for every contour (outer + holes)
            val allContours = buildList {
                add(outer)
                addAll(holes)
            }
            var contourStart = 0
            allContours.forEach { contour ->
                if (contour.size < 2) {
                    contourStart += contour.size
                    return@forEach
                }
                for (i in contour.indices) {
                    val j = (i + 1) % contour.size
                    val a = baseVertex + contourStart + i
                    val b = baseVertex + contourStart + j
                    val c = baseVertex + polygon.size + contourStart + j
                    val d = baseVertex + polygon.size + contourStart + i
                    allIndices += a; allIndices += b; allIndices += c
                    allIndices += a; allIndices += c; allIndices += d
                }
                contourStart += contour.size
            }
        }

        if (allVertices.isEmpty() || allIndices.isEmpty()) return null
        return GeneratedMesh(
            vertices = allVertices.toFloatArray(),
            indices = allIndices.toIntArray(),
            color = color,
        )
    }

    /** 按包含关系把轮廓分成 outer + holes。 */
    private fun groupContoursWithHoles(contours: List<List<Vec2>>): List<Pair<List<Vec2>, List<List<Vec2>>>>? {
        val valid = contours.filter { it.size >= 3 }
        if (valid.isEmpty()) return null

        val centroids = valid.map { contour ->
            val sx = contour.sumOf { it.x.toDouble() }.toFloat() / contour.size
            val sy = contour.sumOf { it.y.toDouble() }.toFloat() / contour.size
            Vec2(sx, sy)
        }

        val result = mutableListOf<Pair<List<Vec2>, List<List<Vec2>>>>()
        valid.forEachIndexed { index, contour ->
            val centroid = centroids[index]
            val insideOther = valid.indices.any { otherIndex ->
                otherIndex != index && pointInPolygon(centroid, valid[otherIndex])
            }
            if (!insideOther) {
                val holes = valid.indices
                    .filter { holeIndex ->
                        holeIndex != index && pointInPolygon(centroids[holeIndex], contour)
                    }
                    .map { valid[it] }
                result += contour to holes
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // 基础几何工具
    // ------------------------------------------------------------------

    private fun extrudePolygon(
        polygon: List<Vec2>,
        zBottom: Float,
        zTop: Float,
        color: FloatArray,
        normalization: SceneNormalization,
    ): GeneratedMesh {
        val count = polygon.size
        val vertices = FloatArray(count * 2 * 3)
        val indices = mutableListOf<Int>()

        polygon.forEachIndexed { i, p ->
            val top = normalization.toLocalWorld(p.x, p.y, zTop)
            val bottom = normalization.toLocalWorld(p.x, p.y, zBottom)
            val ti = i * 3
            val bi = (count + i) * 3
            vertices[ti] = top[0]; vertices[ti + 1] = top[1]; vertices[ti + 2] = top[2]
            vertices[bi] = bottom[0]; vertices[bi + 1] = bottom[1]; vertices[bi + 2] = bottom[2]
        }

        val data = DoubleArray(count * 2) { index ->
            if (index % 2 == 0) polygon[index / 2].x.toDouble() else polygon[index / 2].y.toDouble()
        }
        val topTriangles = runCatching { Earcut.earcut(data, null, 2) }.getOrDefault(emptyList())
        topTriangles.forEach { index ->
            indices += index
        }
        // Bottom faces, reversed winding
        topTriangles.chunked(3).forEach { tri ->
            if (tri.size == 3) {
                indices += tri[0] + count
                indices += tri[2] + count
                indices += tri[1] + count
            }
        }
        // Side walls
        for (i in 0 until count) {
            val j = (i + 1) % count
            val a = i
            val b = j
            val c = count + j
            val d = count + i
            indices += a; indices += b; indices += c
            indices += a; indices += c; indices += d
        }

        return GeneratedMesh(vertices, indices.toIntArray(), color)
    }

    private fun mergeBoxes(
        boxes: List<FloatArray>,
        color: FloatArray,
        normalization: SceneNormalization,
    ): GeneratedMesh {
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        boxes.forEach { box ->
            val base = vertices.size / 3
            var i = 0
            while (i + 2 < box.size) {
                val n = normalization.toLocalWorld(box[i], box[i + 1], box[i + 2])
                vertices += n[0]; vertices += n[1]; vertices += n[2]
                i += 3
            }
            CUBE_INDICES.forEach { index ->
                indices += base + index
            }
        }
        return GeneratedMesh(vertices.toFloatArray(), indices.toIntArray(), color)
    }

    private fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float): FloatArray =
        floatArrayOf(
            x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0,
            x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
        )

    private fun outlinesBounds(contours: List<List<Vec2>>): Bounds2? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        contours.forEach { contour ->
            contour.forEach { p ->
                minX = min(minX, p.x); minY = min(minY, p.y)
                maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            }
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return null
        return Bounds2(minX, minY, maxX, maxY)
    }

    private fun pointInPolygon(point: Vec2, polygon: List<Vec2>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > point.y) != (pj.y > point.y) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private data class Bounds2(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    /** 把底板局部 mm 坐标（打印区中心为原点）转成世界 mm 后再归一化。 */
    private fun SceneNormalization.toLocalWorld(localX: Float, localY: Float, localZ: Float): FloatArray =
        apply(localX + center.x, localY + center.y, localZ)

    private val CUBE_INDICES = intArrayOf(
        0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6,
        0, 4, 5, 0, 5, 1, 1, 5, 6, 1, 6, 2,
        2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0,
    )
}
