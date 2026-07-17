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
    val topLevelObjectId: Int? = null,
) {
    /** 获取该模型在 3MF 原始坐标中的 X/Y/Z 长度。 */
    fun xyzLengths(): XyzLengths = originalBounds.xyzLengths()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SceneMesh

        if (objectId != other.objectId) return false
        if (topLevelObjectId != other.topLevelObjectId) return false
        if (name != other.name) return false
        if (!vertices.contentEquals(other.vertices)) return false
        if (!indices.contentEquals(other.indices)) return false
        if (originalBounds != other.originalBounds) return false
        if (renderBounds != other.renderBounds) return false
        if (!displayColor.contentEquals(other.displayColor)) return false
        if (materialLayout != other.materialLayout) return false

        return true
    }

    override fun hashCode(): Int {
        var result = objectId
        result = 31 * result + (topLevelObjectId ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + vertices.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + originalBounds.hashCode()
        result = 31 * result + renderBounds.hashCode()
        result = 31 * result + (displayColor?.contentHashCode() ?: 0)
        result = 31 * result + (materialLayout?.hashCode() ?: 0)
        return result
    }
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
        return listOf(sceneMesh.copy(displayColor = displayColor, materialLayout = layout, topLevelObjectId = topLevelObjectId))
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
        ).copy(displayColor = displayColor ?: mesh.displayColor, topLevelObjectId = topLevelObjectId)
    }
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

private data class MeshComponent(val vertices: FloatArray, val triangles: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshComponent

        if (!vertices.contentEquals(other.vertices)) return false
        if (!triangles.contentEquals(other.triangles)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = vertices.contentHashCode()
        result = 31 * result + triangles.contentHashCode()
        return result
    }
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

private const val MAX_COMPONENT_SPLIT_FLOATS = 3_000_000L
private const val MAX_COMPONENT_SPLIT_INDICES = 3_000_000L
