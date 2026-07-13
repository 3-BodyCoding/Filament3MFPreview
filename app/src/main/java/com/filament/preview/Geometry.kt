package com.filament.preview

import io.lib3mf.android.MeshData
import kotlin.math.max
import kotlin.math.min

/** Original 3MF-axis lengths for a selected object. */
data class XyzLengths(val x: Float, val y: Float, val z: Float)

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z
}

data class Bounds(val min: Vec3, val max: Vec3) {
    val center: Vec3 = Vec3((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f)
    val size: Vec3 = Vec3(max.x - min.x, max.y - min.y, max.z - min.z)

    fun xyzLengths(): XyzLengths = XyzLengths(size.x, size.y, size.z)
    fun expanded(amount: Float): Bounds = Bounds(
        Vec3(min.x - amount, min.y - amount, min.z - amount),
        Vec3(max.x + amount, max.y + amount, max.z + amount),
    )
}

data class SceneMesh(
    val objectId: Int,
    val name: String,
    val vertices: FloatArray,
    val indices: IntArray,
    val originalBounds: Bounds,
    val renderBounds: Bounds,
    val displayColor: FloatArray? = null,
    val materialLayout: MeshMaterialLayout? = null,
) {
    /** 获取该模型在 3MF 原始坐标中的 X/Y/Z 长度。 */
    fun xyzLengths(): XyzLengths = originalBounds.xyzLengths()
}

fun List<SceneMesh>.compactedSceneMeshes(): List<SceneMesh> {
    if (size <= 1) return this
    if (sumOf { it.vertices.size.toLong() } > MAX_COMPACT_FLOATS) return this
    val currentBounds = map { it.renderBounds }
    if (currentBounds.any { !it.isFinite() }) return this
    if (!currentBounds.hasSignificantXyOverlap() && !currentBounds.isSparseXyLayout()) return this

    val maxWidth = currentBounds.maxOf { max(it.size.x, 0.001f) }
    val totalArea = currentBounds.sumOf { (max(it.size.x, 0.001f) * max(it.size.y, 0.001f)).toDouble() }.toFloat()
    val margin = max(0.025f, currentBounds.maxOf { max(it.size.x, it.size.y) } * 0.045f)
    val rowWidth = max(maxWidth, kotlin.math.sqrt(totalArea) * 1.25f) + margin

    var cursorX = 0.0f
    var cursorY = 0.0f
    var rowHeight = 0.0f
    val translated = mapIndexed { index, mesh ->
        val bounds = currentBounds[index]
        val width = max(bounds.size.x, 0.001f)
        val height = max(bounds.size.y, 0.001f)
        if (cursorX > 0.0f && cursorX + width > rowWidth) {
            cursorX = 0.0f
            cursorY += rowHeight + margin
            rowHeight = 0.0f
        }
        val moved = mesh.translated(Vec3(cursorX - bounds.min.x, cursorY - bounds.min.y, 0.0f))
        cursorX += width + margin
        rowHeight = max(rowHeight, height)
        moved
    }

    val bounds = translated.sceneBounds()
    val recenter = Vec3(-bounds.center.x, -bounds.center.y, 0.0f)
    return translated.map { it.translated(recenter) }
}

fun List<SceneMesh>.selectedXyzLengths(selectedIndex: Int?): XyzLengths? =
    selectedIndex?.let { index -> getOrNull(index)?.xyzLengths() }

fun PlacedMeshData.placedBounds(): Bounds = mesh.vertices.computeBounds(transform, previewOffset)

fun List<PlacedMeshData>.placedNormalization(): Pair<Vec3, Float> {
    val bounds = computeCombinedPlacedBounds()
    val span = max(0.0001f, max(bounds.size.x, max(bounds.size.y, bounds.size.z)))
    return Vec3(bounds.center.x, bounds.center.y, bounds.min.z) to (2.0f / span)
}

fun PlacedMeshData.toSceneMeshes(center: Vec3, scale: Float): List<SceneMesh> {
    materialLayout?.let { layout ->
        val sceneMesh = buildSceneMeshTransformed(
            objectId = mesh.objectId,
            name = name,
            sourceVertices = mesh.vertices,
            sourceTriangles = mesh.triangles,
            transform = transform,
            previewOffset = previewOffset,
            center = center,
            scale = scale,
        )
        return listOf(sceneMesh.copy(displayColor = displayColor, materialLayout = layout))
    }

    val components = splitConnectedComponents(mesh.vertices, mesh.triangles)
    if (components.isEmpty()) return emptyList()
    return components.mapIndexed { index, component ->
        buildSceneMeshTransformed(
            objectId = mesh.objectId,
            name = if (components.size == 1) name else "$name #${index + 1}",
            sourceVertices = component.vertices,
            sourceTriangles = component.triangles,
            transform = transform,
            previewOffset = previewOffset,
            center = center,
            scale = scale,
        ).copy(displayColor = displayColor ?: mesh.displayColor)
    }
}

private fun SceneMesh.translated(delta: Vec3): SceneMesh {
    if (delta.x == 0.0f && delta.y == 0.0f && delta.z == 0.0f) return this
    val moved = vertices.copyOf()
    var i = 0
    while (i + 2 < moved.size) {
        moved[i] += delta.x
        moved[i + 1] += delta.y
        moved[i + 2] += delta.z
        i += 3
    }
    return copy(vertices = moved, renderBounds = moved.computeBounds())
}

private fun Bounds.isFinite(): Boolean =
    min.x.isFinite() && min.y.isFinite() && min.z.isFinite() &&
        max.x.isFinite() && max.y.isFinite() && max.z.isFinite()

private fun List<SceneMesh>.sceneBounds(): Bounds {
    var minPoint = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    var maxPoint = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    forEach { mesh ->
        minPoint = Vec3(min(minPoint.x, mesh.renderBounds.min.x), min(minPoint.y, mesh.renderBounds.min.y), min(minPoint.z, mesh.renderBounds.min.z))
        maxPoint = Vec3(max(maxPoint.x, mesh.renderBounds.max.x), max(maxPoint.y, mesh.renderBounds.max.y), max(maxPoint.z, mesh.renderBounds.max.z))
    }
    return Bounds(minPoint, maxPoint)
}

private fun List<Bounds>.hasSignificantXyOverlap(): Boolean {
    for (i in indices) {
        val a = this[i]
        val areaA = max(a.size.x, 0.001f) * max(a.size.y, 0.001f)
        for (j in i + 1 until size) {
            val b = this[j]
            val overlapX = min(a.max.x, b.max.x) - max(a.min.x, b.min.x)
            val overlapY = min(a.max.y, b.max.y) - max(a.min.y, b.min.y)
            if (overlapX <= 0.0f || overlapY <= 0.0f) continue
            val areaB = max(b.size.x, 0.001f) * max(b.size.y, 0.001f)
            if (overlapX * overlapY / min(areaA, areaB) > 0.08f) return true
        }
    }
    return false
}

private fun List<Bounds>.isSparseXyLayout(): Boolean {
    val combined = combinedBounds()
    val unionArea = max(combined.size.x, 0.001f) * max(combined.size.y, 0.001f)
    val itemArea = sumOf { (max(it.size.x, 0.001f) * max(it.size.y, 0.001f)).toDouble() }.toFloat()
    val averageSpan = map { max(it.size.x, it.size.y) }.average().toFloat().coerceAtLeast(0.001f)
    val maxSpan = max(combined.size.x, combined.size.y)
    return maxSpan / averageSpan > 4.0f && itemArea / unionArea < 0.22f
}

private fun List<Bounds>.combinedBounds(): Bounds {
    var minPoint = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    var maxPoint = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    forEach { bounds ->
        minPoint = Vec3(min(minPoint.x, bounds.min.x), min(minPoint.y, bounds.min.y), min(minPoint.z, bounds.min.z))
        maxPoint = Vec3(max(maxPoint.x, bounds.max.x), max(maxPoint.y, bounds.max.y), max(maxPoint.z, bounds.max.z))
    }
    return Bounds(minPoint, maxPoint)
}

fun MeshData.toSceneMeshes(name: String, center: Vec3, scale: Float): List<SceneMesh> {
    val components = splitConnectedComponents(vertices, triangles)
    if (components.size == 1 && components.first().vertices === vertices) {
        return listOf(buildSceneMeshInPlace(objectId, name, vertices, triangles, center, scale)
            .copy(displayColor = displayColor))
    }
    return components.mapIndexed { index, component ->
        buildSceneMesh(
            objectId = objectId,
            name = if (components.size == 1) name else "$name #${index + 1}",
            sourceVertices = component.vertices,
            sourceTriangles = component.triangles,
            center = center,
            scale = scale,
        ).copy(displayColor = displayColor)
    }
}

fun MeshData.toSceneMeshNoSplitInPlace(name: String, center: Vec3, scale: Float): SceneMesh {
    val originalBounds = vertices.computeBounds()
    var i = 0
    while (i + 2 < vertices.size) {
        vertices[i] = (vertices[i] - center.x) * scale
        vertices[i + 1] = (vertices[i + 1] - center.y) * scale
        vertices[i + 2] = (vertices[i + 2] - center.z) * scale
        i += 3
    }
    return SceneMesh(
        objectId = objectId,
        name = name,
        vertices = vertices,
        indices = triangles,
        originalBounds = originalBounds,
        renderBounds = vertices.computeBounds(),
        displayColor = displayColor,
    )
}

private fun buildSceneMeshInPlace(
    objectId: Int,
    name: String,
    sourceVertices: FloatArray,
    sourceTriangles: IntArray,
    center: Vec3,
    scale: Float,
): SceneMesh {
    val originalBounds = sourceVertices.computeBounds()
    var i = 0
    while (i + 2 < sourceVertices.size) {
        sourceVertices[i] = (sourceVertices[i] - center.x) * scale
        sourceVertices[i + 1] = (sourceVertices[i + 1] - center.y) * scale
        sourceVertices[i + 2] = (sourceVertices[i + 2] - center.z) * scale
        i += 3
    }
    return SceneMesh(
        objectId = objectId,
        name = name,
        vertices = sourceVertices,
        indices = sourceTriangles,
        originalBounds = originalBounds,
        renderBounds = sourceVertices.computeBounds(),
    )
}

private fun buildSceneMesh(
    objectId: Int,
    name: String,
    sourceVertices: FloatArray,
    sourceTriangles: IntArray,
    center: Vec3,
    scale: Float,
): SceneMesh {
    val normalized = FloatArray(sourceVertices.size)
    var i = 0
    while (i + 2 < sourceVertices.size) {
        normalized[i] = (sourceVertices[i] - center.x) * scale
        normalized[i + 1] = (sourceVertices[i + 1] - center.y) * scale
        normalized[i + 2] = (sourceVertices[i + 2] - center.z) * scale
        i += 3
    }
    return SceneMesh(
        objectId = objectId,
        name = name,
        vertices = normalized,
        indices = sourceTriangles,
        originalBounds = sourceVertices.computeBounds(),
        renderBounds = normalized.computeBounds(),
    )
}

private fun buildSceneMeshTransformed(
    objectId: Int,
    name: String,
    sourceVertices: FloatArray,
    sourceTriangles: IntArray,
    transform: MeshTransform,
    previewOffset: Vec3,
    center: Vec3,
    scale: Float,
): SceneMesh {
    val normalized = FloatArray(sourceVertices.size)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var i = 0
    while (i + 2 < sourceVertices.size) {
        val sourceX = sourceVertices[i]
        val sourceY = sourceVertices[i + 1]
        val sourceZ = sourceVertices[i + 2]
        val x = transform.transformX(sourceX, sourceY, sourceZ) + previewOffset.x
        val y = transform.transformY(sourceX, sourceY, sourceZ) + previewOffset.y
        val z = transform.transformZ(sourceX, sourceY, sourceZ) + previewOffset.z
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
        normalized[i] = (x - center.x) * scale
        normalized[i + 1] = (y - center.y) * scale
        normalized[i + 2] = (z - center.z) * scale
        i += 3
    }
    return SceneMesh(
        objectId = objectId,
        name = name,
        vertices = normalized,
        indices = sourceTriangles,
        originalBounds = Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ)),
        renderBounds = normalized.computeBounds(),
    )
}

private fun splitConnectedComponents(vertices: FloatArray, triangles: IntArray): List<MeshComponent> {
    val vertexCount = vertices.size / 3
    if (vertexCount == 0 || triangles.size < 3) return emptyList()
    if (vertices.size.toLong() > MAX_COMPONENT_SPLIT_FLOATS || triangles.size.toLong() > MAX_COMPONENT_SPLIT_INDICES) {
        return listOf(MeshComponent(vertices, triangles))
    }

    val parent = IntArray(vertexCount) { it }
    fun find(value: Int): Int {
        var root = value
        while (parent[root] != root) root = parent[root]
        var current = value
        while (parent[current] != current) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }

    var i = 0
    while (i + 2 < triangles.size) {
        val a = triangles[i]
        val b = triangles[i + 1]
        val c = triangles[i + 2]
        if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
            union(a, b)
            union(a, c)
        }
        i += 3
    }

    val groupedTriangles = linkedMapOf<Int, MutableList<Int>>()
    i = 0
    while (i + 2 < triangles.size) {
        val root = find(triangles[i].coerceIn(0, vertexCount - 1))
        groupedTriangles.getOrPut(root) { mutableListOf() }.add(i)
        i += 3
    }
    if (groupedTriangles.size <= 1) return listOf(MeshComponent(vertices, triangles))

    return groupedTriangles.values.map { triangleOffsets ->
        val remap = linkedMapOf<Int, Int>()
        val newIndices = IntArray(triangleOffsets.size * 3)
        var outIndex = 0
        triangleOffsets.forEach { offset ->
            repeat(3) { local ->
                val oldIndex = triangles[offset + local]
                val newIndex = remap.getOrPut(oldIndex) { remap.size }
                newIndices[outIndex++] = newIndex
            }
        }
        val newVertices = FloatArray(remap.size * 3)
        remap.forEach { (oldIndex, newIndex) ->
            val oldOffset = oldIndex * 3
            val newOffset = newIndex * 3
            newVertices[newOffset] = vertices[oldOffset]
            newVertices[newOffset + 1] = vertices[oldOffset + 1]
            newVertices[newOffset + 2] = vertices[oldOffset + 2]
        }
        MeshComponent(newVertices, newIndices)
    }
}

private data class MeshComponent(val vertices: FloatArray, val triangles: IntArray)

fun List<MeshData>.normalization(): Pair<Vec3, Float> {
    val bounds = computeCombinedBounds()
    val span = max(0.0001f, max(bounds.size.x, max(bounds.size.y, bounds.size.z)))
    return Vec3(bounds.center.x, bounds.center.y, bounds.min.z) to (2.0f / span)
}

private fun List<MeshData>.computeCombinedBounds(): Bounds {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    forEach { mesh ->
        val vertices = mesh.vertices
        var i = 0
        while (i + 2 < vertices.size) {
            val x = vertices[i]
            val y = vertices[i + 1]
            val z = vertices[i + 2]
            minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
            maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            i += 3
        }
    }
    return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

fun FloatArray.computeBounds(): Bounds {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var i = 0
    while (i + 2 < size) {
        val x = this[i]
        val y = this[i + 1]
        val z = this[i + 2]
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
        i += 3
    }
    return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

fun FloatArray.computeBounds(transform: MeshTransform, offset: Vec3): Bounds {
    if (transform.isIdentity) {
        val bounds = computeBounds()
        if (offset.x == 0.0f && offset.y == 0.0f && offset.z == 0.0f) return bounds
        return Bounds(bounds.min + offset, bounds.max + offset)
    }

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var i = 0
    while (i + 2 < size) {
        val sourceX = this[i]
        val sourceY = this[i + 1]
        val sourceZ = this[i + 2]
        val x = transform.transformX(sourceX, sourceY, sourceZ) + offset.x
        val y = transform.transformY(sourceX, sourceY, sourceZ) + offset.y
        val z = transform.transformZ(sourceX, sourceY, sourceZ) + offset.z
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
        i += 3
    }
    return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

private fun List<PlacedMeshData>.computeCombinedPlacedBounds(): Bounds {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    forEach { placed ->
        val bounds = placed.placedBounds()
        minX = min(minX, bounds.min.x); minY = min(minY, bounds.min.y); minZ = min(minZ, bounds.min.z)
        maxX = max(maxX, bounds.max.x); maxY = max(maxY, bounds.max.y); maxZ = max(maxZ, bounds.max.z)
    }
    return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

fun rayAabbDistance(origin: Vec3, direction: Vec3, bounds: Bounds): Float? {
    var tMin = Float.NEGATIVE_INFINITY
    var tMax = Float.POSITIVE_INFINITY

    fun axis(o: Float, d: Float, minValue: Float, maxValue: Float): Boolean {
        if (kotlin.math.abs(d) < 1e-6f) return o in minValue..maxValue
        val inv = 1.0f / d
        var t1 = (minValue - o) * inv
        var t2 = (maxValue - o) * inv
        if (t1 > t2) t1 = t2.also { t2 = t1 }
        tMin = max(tMin, t1)
        tMax = min(tMax, t2)
        return tMin <= tMax
    }

    if (!axis(origin.x, direction.x, bounds.min.x, bounds.max.x)) return null
    if (!axis(origin.y, direction.y, bounds.min.y, bounds.max.y)) return null
    if (!axis(origin.z, direction.z, bounds.min.z, bounds.max.z)) return null
    return if (tMax >= 0f) max(0f, tMin) else null
}

private const val MAX_COMPACT_FLOATS = 3_000_000L
private const val MAX_COMPONENT_SPLIT_FLOATS = 3_000_000L
private const val MAX_COMPONENT_SPLIT_INDICES = 3_000_000L
