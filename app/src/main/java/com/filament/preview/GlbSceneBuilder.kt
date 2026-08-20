package com.filament.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object GlbSceneBuilder {
    const val BASE_PLATE_NODE = "baseplate"
    fun meshNodeName(index: Int): String = "mesh_$index"
    fun markerNodeName(index: Int): String = "marker_$index"

    private val shadingCache = Collections.synchronizedMap(IdentityHashMap<SceneMesh, MeshShading>())
    private const val MAX_SHADING_CACHE_SIZE = 128

    fun clearCache() {
        synchronized(shadingCache) {
            shadingCache.clear()
        }
    }

    @Synchronized
    fun build(
        meshes: List<SceneMesh>,
        overrideColor: FloatArray?,
        colorOverrides: Map<MaterialSlotId, RgbaColor> = emptyMap(),
    ): ByteBuffer {
        val renderBounds = meshes.bounds()
        val modelColors = mutableListOf<FloatArray>()
        val materialBySlot = linkedMapOf<MaterialSlotId, Int>()
        var vertexColorMaterial: Int? = null

        fun materialFor(slot: MaterialSlot): Int = materialBySlot.getOrPut(slot.id) {
            modelColors += overrideColor
                ?: colorOverrides[slot.id]?.toFloatArray()
                ?: slot.originalColor.toFloatArray()
            modelColors.lastIndex
        }

        fun materialForVertexColors(): Int = vertexColorMaterial ?: run {
            modelColors += floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
            modelColors.lastIndex.also { vertexColorMaterial = it }
        }

        val meshDefinitions = mutableListOf<MeshDefinition>()
        meshes.forEachIndexed { index, mesh ->
            // Build smoothing groups on the complete surface before splitting it by material.
            // SceneMesh instances are reused for color-only rebuilds, so cache the expensive
            // per-mesh normal computation by object identity. The decision is intentionally
            // per mesh: one large model must not force every other model back to vertex normals.
            val shading = synchronized(shadingCache) {
                if (shadingCache.size >= MAX_SHADING_CACHE_SIZE) shadingCache.clear()
                shadingCache[mesh] ?: MeshShading(mesh.vertices, mesh.indices)
                    .also { shadingCache[mesh] = it }
            }
            val layout = mesh.materialLayout
            val primitives = if (layout == null || layout.primitives.isEmpty()) {
                modelColors += overrideColor ?: mesh.displayColor ?: DEFAULT_MODEL_COLOR
                shading.indexedGeometry(mesh.indices)?.let { geometry ->
                    listOf(Primitive(geometry.vertices, geometry.indices, modelColors.lastIndex, 4, normals = geometry.normals))
                }.orEmpty()
            } else {
                layout.primitives.mapNotNull { source ->
                    val cornerSlots = source.cornerMaterialSlotIndices
                    if (cornerSlots != null) {
                        vertexColorPrimitive(
                            source = source,
                            slots = layout.slots,
                            materialIndex = materialForVertexColors(),
                            overrideColor = overrideColor,
                            colorOverrides = colorOverrides,
                            shading = shading,
                        )
                    } else {
                        val slot = layout.slots.getOrNull(source.materialSlotIndex) ?: return@mapNotNull null
                        val geometry = shading.indexedGeometry(source.indices) ?: return@mapNotNull null
                        Primitive(geometry.vertices, geometry.indices, materialFor(slot), 4, normals = geometry.normals)
                    }
                }
            }
            meshDefinitions += MeshDefinition(meshNodeName(index), primitives)
        }
        val basePlateMaterial = modelColors.size
        val markerMaterialStart = basePlateMaterial + 1
        meshDefinitions += MeshDefinition(
            BASE_PLATE_NODE,
            listOf(Primitive(basePlateVertices(renderBounds), intArrayOf(0, 1, 2, 0, 2, 3), basePlateMaterial, 4)),
        )
        meshes.forEachIndexed { index, mesh ->
            meshDefinitions += MeshDefinition(
                markerNodeName(index),
                markerPrimitives(mesh.renderBounds, markerMaterialStart),
                initiallyHidden = true,
            )
        }

        val bin = CountingBinarySink()
        val views = mutableListOf<BufferView>()
        val accessors = mutableListOf<Accessor>()
        val meshesJson = meshDefinitions.joinToString(prefix = "[", postfix = "]") { definition ->
            val primitivesJson = definition.primitives.joinToString(prefix = "[", postfix = "]") { primitive ->
                primitiveToJson(primitive, bin, views, accessors)
            }
            "{\"name\":\"${definition.name}\",\"primitives\":$primitivesJson}"
        }
        val nodesJson = meshDefinitions.mapIndexed { index, definition ->
            val hiddenScale = if (definition.initiallyHidden) ",\"scale\":[0,0,0]" else ""
            "{\"mesh\":$index,\"name\":\"${definition.name}\"$hiddenScale}"
        }.joinToString(prefix = "[", postfix = "]")
        val sceneNodes = meshDefinitions.indices.joinToString(prefix = "[", postfix = "]")

        val json = buildJson(bin.size, views, accessors, meshesJson, nodesJson, sceneNodes, modelColors)
        val jsonBytes = padded(json.toByteArray(Charsets.UTF_8), 0x20)
        val binLength = paddedSize(bin.size)
        val totalLength = 12 + 8 + jsonBytes.size + 8 + binLength
        return ByteBuffer.allocateDirect(totalLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(totalLength)
            putInt(jsonBytes.size)
            putInt(0x4E4F534A)
            put(jsonBytes)
            putInt(binLength)
            putInt(0x004E4942)
            val writer = ByteBufferBinarySink(this)
            meshDefinitions.forEach { definition ->
                definition.primitives.forEach { primitive -> writePrimitiveBinary(primitive, writer) }
            }
            while (writer.size < binLength) writer.putByte(0)
            rewind()
        }
    }

    private fun primitiveToJson(
        primitive: Primitive,
        bin: BinarySink,
        views: MutableList<BufferView>,
        accessors: MutableList<Accessor>,
    ): String {
        val vertexOffset = bin.align4()
        bin.putFloats(primitive.vertices)
        val vertexView = views.size
        views += BufferView(vertexOffset, primitive.vertices.size * 4, 34962)
        val positionAccessor = accessors.size
        accessors += Accessor(vertexView, 5126, primitive.vertices.size / 3, "VEC3", primitive.vertices.computeBounds())

        val normalOffset = bin.align4()
        bin.putFloats(primitive.normals)
        val normalView = views.size
        views += BufferView(normalOffset, primitive.normals.size * 4, 34962)
        val normalAccessor = accessors.size
        accessors += Accessor(normalView, 5126, primitive.normals.size / 3, "VEC3", null)

        val colorAttribute = primitive.colors?.let { colors ->
            val colorOffset = bin.align4()
            bin.putFloats(colors)
            val colorView = views.size
            views += BufferView(colorOffset, colors.size * 4, 34962)
            val colorAccessor = accessors.size
            accessors += Accessor(colorView, 5126, colors.size / 4, "VEC4", null)
            ",\"COLOR_0\":$colorAccessor"
        }.orEmpty()

        val indexOffset = bin.align4()
        primitive.putIndices(bin)
        val indexView = views.size
        views += BufferView(indexOffset, primitive.indexByteLength, 34963)
        val indexAccessor = accessors.size
        accessors += Accessor(indexView, primitive.indexComponentType, primitive.indices.size, "SCALAR", null)

        return "{\"attributes\":{\"POSITION\":$positionAccessor,\"NORMAL\":$normalAccessor$colorAttribute},\"indices\":$indexAccessor,\"material\":${primitive.materialIndex},\"mode\":${primitive.mode}}"
    }

    private fun writePrimitiveBinary(primitive: Primitive, bin: BinarySink) {
        bin.align4()
        bin.putFloats(primitive.vertices)
        bin.align4()
        bin.putFloats(primitive.normals)
        primitive.colors?.let {
            bin.align4()
            bin.putFloats(it)
        }
        bin.align4()
        primitive.putIndices(bin)
    }

    private fun buildJson(
        byteLength: Int,
        views: List<BufferView>,
        accessors: List<Accessor>,
        meshesJson: String,
        nodesJson: String,
        sceneNodes: String,
        modelColors: List<FloatArray>,
    ): String {
        val bufferViewsJson = views.joinToString(prefix = "[", postfix = "]") {
            "{\"buffer\":0,\"byteOffset\":${it.offset},\"byteLength\":${it.length},\"target\":${it.target}}"
        }
        val accessorsJson = accessors.joinToString(prefix = "[", postfix = "]") {
            buildString {
                append("{\"bufferView\":${it.bufferView},\"componentType\":${it.componentType},\"count\":${it.count},\"type\":\"${it.type}\"")
                it.bounds?.let { bounds -> append(",\"min\":${bounds.min.toJsonArray()},\"max\":${bounds.max.toJsonArray()}") }
                append('}')
            }
        }
        val modelMaterials = modelColors.joinToString(",") { color ->
            "{\"pbrMetallicRoughness\":{\"baseColorFactor\":${color.srgbToLinearRgba().toJsonArray()},\"metallicFactor\":$MODEL_METALLIC,\"roughnessFactor\":$MODEL_ROUGHNESS},\"doubleSided\":true}"
        }
        val fixedMaterials = """
            {"pbrMetallicRoughness":{"baseColorFactor":[0.68,0.70,0.72,0.8],"metallicFactor":0,"roughnessFactor":0.9},"alphaMode":"BLEND","doubleSided":true},
            {"pbrMetallicRoughness":{"baseColorFactor":[1,0.02,0.02,1],"metallicFactor":0,"roughnessFactor":0.35},"doubleSided":true},
            {"pbrMetallicRoughness":{"baseColorFactor":[0.02,0.9,0.08,1],"metallicFactor":0,"roughnessFactor":0.35},"doubleSided":true},
            {"pbrMetallicRoughness":{"baseColorFactor":[0.02,0.28,1,1],"metallicFactor":0,"roughnessFactor":0.35},"doubleSided":true}
        """.trimIndent()
        return """
            {
              "asset":{"version":"2.0","generator":"FilamentPreview lib3mf"},
              "scene":0,
              "scenes":[{"nodes":$sceneNodes}],
              "nodes":$nodesJson,
              "meshes":$meshesJson,
              "materials":[$modelMaterials,$fixedMaterials],
              "buffers":[{"byteLength":$byteLength}],
              "bufferViews":$bufferViewsJson,
              "accessors":$accessorsJson
            }
        """.trimIndent()
    }

    private fun vertexColorPrimitive(
        source: MeshMaterialPrimitive,
        slots: List<MaterialSlot>,
        materialIndex: Int,
        overrideColor: FloatArray?,
        colorOverrides: Map<MaterialSlotId, RgbaColor>,
        shading: MeshShading,
    ): Primitive? {
        val cornerSlots = source.cornerMaterialSlotIndices ?: return null
        if (cornerSlots.size != source.indices.size) return null
        val geometry = shading.expandedGeometry(source.indices) ?: return null
        val colors = FloatArray(source.indices.size * 4)
        val indices = IntArray(source.indices.size) { it }
        source.indices.forEachIndexed { corner, _ ->
            val slot = slots.getOrNull(cornerSlots[corner])
            val color = overrideColor
                ?: slot?.let { colorOverrides[it.id]?.toFloatArray() ?: it.originalColor.toFloatArray() }
                ?: DEFAULT_MODEL_COLOR
            color.srgbToLinearRgba().copyInto(colors, corner * 4, 0, 4)
        }
        return Primitive(geometry.vertices, indices, materialIndex, 4, colors, geometry.normals)
    }

    private fun markerPrimitives(bounds: Bounds, materialStart: Int): List<Primitive> {
        val span = max(bounds.size.x, max(bounds.size.y, bounds.size.z))
        val t = max(0.002f, span * 0.003f)
        val outlineBounds = bounds.expanded(t * 2.0f)
        val x = mergeBoxes(xEdges(outlineBounds, t))
        val y = mergeBoxes(yEdges(outlineBounds, t))
        val z = mergeBoxes(zEdges(outlineBounds, t))
        return listOf(
            Primitive(x.first, x.second, materialStart, 4),
            Primitive(y.first, y.second, materialStart + 1, 4),
            Primitive(z.first, z.second, materialStart + 2, 4),
        )
    }

    private fun xEdges(b: Bounds, t: Float): List<FloatArray> = listOf(b.min.y, b.max.y).flatMap { y ->
        listOf(b.min.z, b.max.z).map { z -> box(b.min.x, b.max.x, y - t, y + t, z - t, z + t) }
    }

    private fun yEdges(b: Bounds, t: Float): List<FloatArray> = listOf(b.min.x, b.max.x).flatMap { x ->
        listOf(b.min.z, b.max.z).map { z -> box(x - t, x + t, b.min.y, b.max.y, z - t, z + t) }
    }

    private fun zEdges(b: Bounds, t: Float): List<FloatArray> = listOf(b.min.x, b.max.x).flatMap { x ->
        listOf(b.min.y, b.max.y).map { y -> box(x - t, x + t, y - t, y + t, b.min.z, b.max.z) }
    }

    private fun mergeBoxes(boxes: List<FloatArray>): Pair<FloatArray, IntArray> {
        val vertices = FloatArray(boxes.size * 24)
        val indices = IntArray(boxes.size * CUBE_INDICES.size)
        boxes.forEachIndexed { boxIndex, box ->
            box.copyInto(vertices, destinationOffset = boxIndex * 24)
            val vertexOffset = boxIndex * 8
            CUBE_INDICES.forEachIndexed { i, index -> indices[boxIndex * CUBE_INDICES.size + i] = vertexOffset + index }
        }
        return vertices to indices
    }

    private fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float): FloatArray = floatArrayOf(
        x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0,
        x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
    )

    private fun basePlateVertices(bounds: Bounds): FloatArray {
        val cx = bounds.center.x
        val cy = bounds.center.y
        val span = max(bounds.size.x, bounds.size.y)
        val half = max(1.2f, span * 0.65f)
        val z = bounds.min.z - max(0.025f, bounds.size.z * 0.03f)
        return floatArrayOf(cx - half, cy - half, z, cx + half, cy - half, z, cx + half, cy + half, z, cx - half, cy + half, z)
    }

    private fun List<SceneMesh>.bounds(): Bounds {
        var min = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        var max = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        forEach {
            min = Vec3(kotlin.math.min(min.x, it.renderBounds.min.x), kotlin.math.min(min.y, it.renderBounds.min.y), kotlin.math.min(min.z, it.renderBounds.min.z))
            max = Vec3(kotlin.math.max(max.x, it.renderBounds.max.x), kotlin.math.max(max.y, it.renderBounds.max.y), kotlin.math.max(max.z, it.renderBounds.max.z))
        }
        return Bounds(min, max)
    }

    private fun padded(bytes: ByteArray, padByte: Int): ByteArray {
        val paddedSize = paddedSize(bytes.size)
        return bytes.copyOf(paddedSize).also { out -> for (i in bytes.size until paddedSize) out[i] = padByte.toByte() }
    }

    private fun paddedSize(size: Int): Int = (size + 3) and -4

    private interface BinarySink {
        val size: Int
        fun align4(): Int
        fun putByte(value: Int)
        fun putFloats(values: FloatArray)
        fun putUInts(values: IntArray)
        fun putUShorts(values: IntArray)
    }

    private class CountingBinarySink : BinarySink {
        override var size: Int = 0
            private set

        override fun align4(): Int { size = paddedSize(size); return size }
        override fun putByte(value: Int) { size += 1 }
        override fun putFloats(values: FloatArray) { size += values.size * 4 }
        override fun putUInts(values: IntArray) { size += values.size * 4 }
        override fun putUShorts(values: IntArray) { size += values.size * 2 }
    }

    private class ByteBufferBinarySink(private val buffer: ByteBuffer) : BinarySink {
        override var size: Int = 0
            private set

        override fun align4(): Int { while (size % 4 != 0) putByte(0); return size }
        override fun putByte(value: Int) { buffer.put(value.toByte()); size += 1 }
        override fun putFloats(values: FloatArray) { values.forEach { buffer.putFloat(it); size += 4 } }
        override fun putUInts(values: IntArray) { values.forEach { buffer.putInt(it); size += 4 } }
        override fun putUShorts(values: IntArray) { values.forEach { buffer.putShort(it.toShort()); size += 2 } }
    }

    private fun FloatArray.toJsonArray(): String = joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.6f", it) }
    private fun Vec3.toJsonArray(): String = floatArrayOf(x, y, z).toJsonArray()

    private val CUBE_INDICES = intArrayOf(
        0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6,
        0, 4, 5, 0, 5, 1, 1, 5, 6, 1, 6, 2,
        2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0,
    )
    private val DEFAULT_MODEL_COLOR = floatArrayOf(0.72f, 0.74f, 0.78f, 1.0f)
    private data class MeshDefinition(
        val name: String,
        val primitives: List<Primitive>,
        val initiallyHidden: Boolean = false,
    )
    private data class Primitive(
        val vertices: FloatArray,
        val indices: IntArray,
        val materialIndex: Int,
        val mode: Int,
        val colors: FloatArray? = null,
        val normals: FloatArray = computeVertexNormals(vertices, indices),
    ) {
        val indexComponentType: Int = if (vertices.size / 3 <= UShort.MAX_VALUE.toInt()) 5123 else 5125
        val indexByteLength: Int = indices.size * if (indexComponentType == 5123) 2 else 4

        fun putIndices(bin: BinarySink) {
            if (indexComponentType == 5123) {
                bin.putUShorts(indices)
            } else {
                bin.putUInts(indices)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Primitive

            if (materialIndex != other.materialIndex) return false
            if (mode != other.mode) return false
            if (indexComponentType != other.indexComponentType) return false
            if (indexByteLength != other.indexByteLength) return false
            if (!vertices.contentEquals(other.vertices)) return false
            if (!indices.contentEquals(other.indices)) return false
            if (!colors.contentEquals(other.colors)) return false
            if (!normals.contentEquals(other.normals)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = materialIndex
            result = 31 * result + mode
            result = 31 * result + indexComponentType
            result = 31 * result + indexByteLength
            result = 31 * result + vertices.contentHashCode()
            result = 31 * result + indices.contentHashCode()
            result = 31 * result + (colors?.contentHashCode() ?: 0)
            result = 31 * result + normals.contentHashCode()
            return result
        }
    }
    private data class BufferView(val offset: Int, val length: Int, val target: Int)
    private data class Accessor(val bufferView: Int, val componentType: Int, val count: Int, val type: String, val bounds: Bounds?)

    internal const val MODEL_METALLIC = 0.0f
    internal const val MODEL_ROUGHNESS = 0.38f
    // Retained as the explicit/test fallback; runtime meshes use an automatically estimated angle.
    internal const val MODEL_CREASE_ANGLE_DEGREES = 15.0f
}

internal fun FloatArray.srgbToLinearRgba(): FloatArray = copyOf().also { linear ->
    for (channel in 0 until minOf(3, linear.size)) {
        val srgb = linear[channel].coerceIn(0.0f, 1.0f)
        linear[channel] = if (srgb <= 0.04045f) {
            srgb / 12.92f
        } else {
            ((srgb + 0.055f) / 1.055f).pow(2.4f)
        }
    }
}

private class MeshShading(
    private val sourceVertices: FloatArray,
    private val sourceIndices: IntArray,
) {
    private val cornerNormals: FloatArray
    private val fallbackNormals: FloatArray
    private val triangleOffsets: Map<TriangleKey, Int>

    init {
        val triangleCount = sourceIndices.size / 3
        val useCreaseAwareNormals = triangleCount <= MAX_CREASE_AWARE_TRIANGLES
        cornerNormals = if (useCreaseAwareNormals) {
            // Derive the crease threshold from this mesh's dihedral-angle distribution.
            // This avoids making a model-specific angle a global rendering assumption.
            computeCreaseAwareCornerNormals(sourceVertices, sourceIndices, null)
        } else {
            // Avoid OOM on very large meshes: crease-aware normals need a large edge map.
            // Falling back to per-vertex normals keeps the preview usable with much less memory.
            FloatArray(0)
        }
        fallbackNormals = computeVertexNormals(sourceVertices, sourceIndices)
        if (useCreaseAwareNormals) {
            val offsets = linkedMapOf<TriangleKey, Int>()
            var offset = 0
            while (offset + 2 < sourceIndices.size) {
                val key = TriangleKey(sourceIndices[offset], sourceIndices[offset + 1], sourceIndices[offset + 2])
                // Valid 3MF meshes do not repeat the same oriented triangle. Keep the
                // first offset defensively so material primitives can be resolved without
                // mutable per-build cursor state.
                offsets.putIfAbsent(key, offset)
                offset += 3
            }
            triangleOffsets = offsets
        } else {
            triangleOffsets = emptyMap()
        }
    }

    fun indexedGeometry(indices: IntArray): PrimitiveGeometry? {
        if (indices.isEmpty()) return null
        val sourceTriangleOffsets = resolveTriangleOffsets(indices)
        val vertices = FloatCollector()
        val normals = FloatCollector()
        val remappedIndices = IntArray(indices.size)
        val vertexByKey = HashMap<ShadedVertexKey, Int>()
        indices.forEachIndexed { corner, sourceVertex ->
            val sourceOffset = sourceVertex * 3
            if (sourceOffset < 0 || sourceOffset + 2 >= sourceVertices.size) return null
            val resolvedNormalOffset = resolvedNormalOffset(sourceTriangleOffsets[corner / 3], corner % 3, sourceVertex)
            val normalSource = if (resolvedNormalOffset >= 0) cornerNormals else fallbackNormals
            val normalOffset = if (resolvedNormalOffset >= 0) resolvedNormalOffset else -resolvedNormalOffset - 1
            val normalX = normalSource[normalOffset]
            val normalY = normalSource[normalOffset + 1]
            val normalZ = normalSource[normalOffset + 2]
            val key = ShadedVertexKey(sourceVertex, normalX.toBits(), normalY.toBits(), normalZ.toBits())
            remappedIndices[corner] = vertexByKey.getOrPut(key) {
                vertices.add(sourceVertices[sourceOffset])
                vertices.add(sourceVertices[sourceOffset + 1])
                vertices.add(sourceVertices[sourceOffset + 2])
                normals.add(normalX)
                normals.add(normalY)
                normals.add(normalZ)
                vertexByKey.size
            }
        }
        return PrimitiveGeometry(vertices.toFloatArray(), normals.toFloatArray(), remappedIndices)
    }

    fun expandedGeometry(indices: IntArray): PrimitiveGeometry? {
        if (indices.isEmpty()) return null
        val sourceTriangleOffsets = resolveTriangleOffsets(indices)
        val vertices = FloatArray(indices.size * 3)
        val normals = FloatArray(indices.size * 3)
        indices.forEachIndexed { corner, sourceVertex ->
            val sourceOffset = sourceVertex * 3
            if (sourceOffset < 0 || sourceOffset + 2 >= sourceVertices.size) return null
            sourceVertices.copyInto(vertices, corner * 3, sourceOffset, sourceOffset + 3)
            val resolvedNormalOffset = resolvedNormalOffset(sourceTriangleOffsets[corner / 3], corner % 3, sourceVertex)
            val normalSource = if (resolvedNormalOffset >= 0) cornerNormals else fallbackNormals
            val normalOffset = if (resolvedNormalOffset >= 0) resolvedNormalOffset else -resolvedNormalOffset - 1
            normals[corner * 3] = normalSource[normalOffset]
            normals[corner * 3 + 1] = normalSource[normalOffset + 1]
            normals[corner * 3 + 2] = normalSource[normalOffset + 2]
        }
        return PrimitiveGeometry(vertices, normals, IntArray(indices.size) { it })
    }

    private fun resolveTriangleOffsets(indices: IntArray): IntArray {
        val resolved = IntArray((indices.size + 2) / 3) { -1 }
        if (triangleOffsets.isEmpty()) return resolved
        var offset = 0
        while (offset + 2 < indices.size) {
            val key = TriangleKey(indices[offset], indices[offset + 1], indices[offset + 2])
            triangleOffsets[key]?.let { resolved[offset / 3] = it }
            offset += 3
        }
        return resolved
    }

    private fun resolvedNormalOffset(triangleOffset: Int, localCorner: Int, sourceVertex: Int): Int {
        if (triangleOffset >= 0) {
            val normalOffset = (triangleOffset + localCorner) * 3
            if (normalOffset + 2 < cornerNormals.size) return normalOffset
        }
        return -(sourceVertex * 3) - 1
    }
}

internal fun computeCreaseAwareCornerNormals(
    vertices: FloatArray,
    indices: IntArray,
    creaseAngleDegrees: Float? = null,
): FloatArray {
    val triangleCount = indices.size / 3
    val faceNormals = FloatArray(triangleCount * 3)
    val cornerAngles = FloatArray(triangleCount * 3)
    val validFaces = BooleanArray(triangleCount)
    val edgeUses = HashMap<Long, EdgePair>()

    for (triangle in 0 until triangleCount) {
        val corner = triangle * 3
        val i0 = indices[corner] * 3
        val i1 = indices[corner + 1] * 3
        val i2 = indices[corner + 2] * 3
        if (!validVertexOffset(i0, vertices) || !validVertexOffset(i1, vertices) || !validVertexOffset(i2, vertices)) continue
        val ux = vertices[i1] - vertices[i0]
        val uy = vertices[i1 + 1] - vertices[i0 + 1]
        val uz = vertices[i1 + 2] - vertices[i0 + 2]
        val vx = vertices[i2] - vertices[i0]
        val vy = vertices[i2 + 1] - vertices[i0 + 1]
        val vz = vertices[i2 + 2] - vertices[i0 + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length <= NORMAL_EPSILON) continue
        faceNormals[corner] = nx / length
        faceNormals[corner + 1] = ny / length
        faceNormals[corner + 2] = nz / length
        cornerAngles[corner] = cornerAngle(vertices, i0, i1, i2)
        cornerAngles[corner + 1] = cornerAngle(vertices, i1, i2, i0)
        cornerAngles[corner + 2] = cornerAngle(vertices, i2, i0, i1)
        validFaces[triangle] = true
        addEdgeUse(edgeUses, indices, corner, corner + 1, triangle)
        addEdgeUse(edgeUses, indices, corner + 1, corner + 2, triangle)
        addEdgeUse(edgeUses, indices, corner + 2, corner, triangle)
    }

    val groups = DisjointSet(triangleCount * 3)
    val effectiveCreaseAngle = creaseAngleDegrees
        ?.takeIf { it.isFinite() && it > 0.0f }
        ?: estimateCreaseAngleDegrees(edgeUses, faceNormals)
    val creaseCosine = cos(Math.toRadians(effectiveCreaseAngle.toDouble())).toFloat()
    edgeUses.values.forEach { uses ->
        val second = uses.second ?: return@forEach
        if (!uses.isManifold) return@forEach
        val first = uses.first
        val firstFace = first.triangle * 3
        val secondFace = second.triangle * 3
        val dot = faceNormals[firstFace] * faceNormals[secondFace] +
            faceNormals[firstFace + 1] * faceNormals[secondFace + 1] +
            faceNormals[firstFace + 2] * faceNormals[secondFace + 2]
        if (dot + 1e-6f < creaseCosine) return@forEach
        unionMatchingEndpoint(groups, indices, first.firstCorner, second.firstCorner, second.secondCorner)
        unionMatchingEndpoint(groups, indices, first.secondCorner, second.firstCorner, second.secondCorner)
    }

    val groupedNormals = FloatArray(triangleCount * 3 * 3)
    for (corner in 0 until triangleCount * 3) {
        val triangle = corner / 3
        if (!validFaces[triangle]) continue
        val faceOffset = triangle * 3
        val rootOffset = groups.find(corner) * 3
        val weight = cornerAngles[corner]
        groupedNormals[rootOffset] += faceNormals[faceOffset] * weight
        groupedNormals[rootOffset + 1] += faceNormals[faceOffset + 1] * weight
        groupedNormals[rootOffset + 2] += faceNormals[faceOffset + 2] * weight
    }

    val result = FloatArray(triangleCount * 3 * 3)
    for (corner in 0 until triangleCount * 3) {
        val triangle = corner / 3
        val outputOffset = corner * 3
        val rootOffset = groups.find(corner) * 3
        val x = groupedNormals[rootOffset]
        val y = groupedNormals[rootOffset + 1]
        val z = groupedNormals[rootOffset + 2]
        val length = sqrt(x * x + y * y + z * z)
        if (length > NORMAL_EPSILON) {
            result[outputOffset] = x / length
            result[outputOffset + 1] = y / length
            result[outputOffset + 2] = z / length
        } else if (validFaces[triangle]) {
            val faceOffset = triangle * 3
            result[outputOffset] = faceNormals[faceOffset]
            result[outputOffset + 1] = faceNormals[faceOffset + 1]
            result[outputOffset + 2] = faceNormals[faceOffset + 2]
        } else {
            result[outputOffset + 2] = 1.0f
        }
    }
    return result
}

/**
 * Finds a mesh-local feature angle from the gaps in its manifold dihedral-angle distribution.
 * Sliced meshes usually contain a dense cluster of shallow tessellation angles followed by a
 * sparse cluster of intentional creases. If no reliable gap exists, retain a conservative
 * fallback rather than making a model-wide assumption.
 */
private fun estimateCreaseAngleDegrees(
    edgeUses: Map<Long, EdgePair>,
    faceNormals: FloatArray,
    minimum: Float = AUTO_CREASE_MIN_DEGREES,
    maximum: Float = AUTO_CREASE_MAX_DEGREES,
    fallback: Float = AUTO_CREASE_FALLBACK_DEGREES,
): Float {
    val angles = FloatArray(edgeUses.size)
    var count = 0
    edgeUses.values.forEach { uses ->
        val second = uses.second ?: return@forEach
        if (!uses.isManifold) return@forEach
        val firstOffset = uses.first.triangle * 3
        val secondOffset = second.triangle * 3
        val ax = faceNormals[firstOffset]
        val ay = faceNormals[firstOffset + 1]
        val az = faceNormals[firstOffset + 2]
        val bx = faceNormals[secondOffset]
        val by = faceNormals[secondOffset + 1]
        val bz = faceNormals[secondOffset + 2]
        val crossX = ay * bz - az * by
        val crossY = az * bx - ax * bz
        val crossZ = ax * by - ay * bx
        val crossLength = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
        val dot = (ax * bx + ay * by + az * bz).coerceIn(-1.0f, 1.0f)
        val angle = Math.toDegrees(atan2(crossLength, dot).toDouble()).toFloat()
        if (angle.isFinite()) angles[count++] = angle
    }
    if (count < MIN_AUTO_CREASE_SAMPLES) return fallback

    val sorted = angles.copyOf(count).apply { sort() }
    var bestGap = 0.0f
    var bestThreshold = fallback
    for (index in 0 until sorted.lastIndex) {
        val lower = sorted[index]
        val upper = sorted[index + 1]
        if (lower < minimum || upper > maximum) continue
        val gap = upper - lower
        if (gap > bestGap) {
            bestGap = gap
            bestThreshold = (lower + upper) * 0.5f
        }
    }
    return if (bestGap >= AUTO_CREASE_MIN_GAP_DEGREES) {
        // Never relax beyond the proven-safe fallback. Automatic detection may only make
        // smoothing stricter when the mesh provides strong evidence for a lower crease.
        bestThreshold.coerceIn(minimum, minOf(maximum, fallback))
    } else {
        fallback.coerceIn(minimum, maximum)
    }
}

private fun validVertexOffset(offset: Int, vertices: FloatArray): Boolean =
    offset >= 0 && offset + 2 < vertices.size

private fun cornerAngle(vertices: FloatArray, corner: Int, adjacentA: Int, adjacentB: Int): Float {
    val ax = vertices[adjacentA] - vertices[corner]
    val ay = vertices[adjacentA + 1] - vertices[corner + 1]
    val az = vertices[adjacentA + 2] - vertices[corner + 2]
    val bx = vertices[adjacentB] - vertices[corner]
    val by = vertices[adjacentB + 1] - vertices[corner + 1]
    val bz = vertices[adjacentB + 2] - vertices[corner + 2]
    val crossX = ay * bz - az * by
    val crossY = az * bx - ax * bz
    val crossZ = ax * by - ay * bx
    val crossLength = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
    val dot = ax * bx + ay * by + az * bz
    return atan2(crossLength, dot).takeIf { it.isFinite() } ?: 0.0f
}

private fun addEdgeUse(
    edges: MutableMap<Long, EdgePair>,
    indices: IntArray,
    firstCorner: Int,
    secondCorner: Int,
    triangle: Int,
) {
    val firstVertex = indices[firstCorner]
    val secondVertex = indices[secondCorner]
    if (firstVertex < 0 || secondVertex < 0 || firstVertex == secondVertex) return
    val min = minOf(firstVertex, secondVertex)
    val max = maxOf(firstVertex, secondVertex)
    val key = (min.toLong() shl 32) or (max.toLong() and 0xffffffffL)
    val use = EdgeUse(triangle, firstCorner, secondCorner)
    val pair = edges[key]
    when {
        pair == null -> edges[key] = EdgePair(use)
        pair.second == null -> pair.second = use
        else -> pair.isManifold = false
    }
}

private fun unionMatchingEndpoint(
    groups: DisjointSet,
    indices: IntArray,
    sourceCorner: Int,
    candidateA: Int,
    candidateB: Int,
) {
    when (indices[sourceCorner]) {
        indices[candidateA] -> groups.union(sourceCorner, candidateA)
        indices[candidateB] -> groups.union(sourceCorner, candidateB)
    }
}

private class DisjointSet(size: Int) {
    private val parent = IntArray(size) { it }

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

    fun union(first: Int, second: Int) {
        val firstRoot = find(first)
        val secondRoot = find(second)
        if (firstRoot != secondRoot) parent[secondRoot] = firstRoot
    }
}

private class FloatCollector(initialCapacity: Int = 256) {
    private var values = FloatArray(initialCapacity)
    private var size = 0

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(max(1, values.size * 2))
        values[size++] = value
    }

    fun toFloatArray(): FloatArray = values.copyOf(size)
}

private data class TriangleKey(val first: Int, val second: Int, val third: Int)
private data class ShadedVertexKey(val sourceVertex: Int, val normalX: Int, val normalY: Int, val normalZ: Int)
private data class PrimitiveGeometry(val vertices: FloatArray, val normals: FloatArray, val indices: IntArray)
private data class EdgeUse(val triangle: Int, val firstCorner: Int, val secondCorner: Int)
private data class EdgePair(val first: EdgeUse, var second: EdgeUse? = null, var isManifold: Boolean = true)

internal fun computeVertexNormals(vertices: FloatArray, indices: IntArray): FloatArray {
    val normals = FloatArray(vertices.size)
    var i = 0
    while (i + 2 < indices.size) {
        val i0 = indices[i] * 3
        val i1 = indices[i + 1] * 3
        val i2 = indices[i + 2] * 3
        if (i0 < 0 || i1 < 0 || i2 < 0 || i0 + 2 >= vertices.size || i1 + 2 >= vertices.size || i2 + 2 >= vertices.size) {
            i += 3
            continue
        }
        val ux = vertices[i1] - vertices[i0]
        val uy = vertices[i1 + 1] - vertices[i0 + 1]
        val uz = vertices[i1 + 2] - vertices[i0 + 2]
        val vx = vertices[i2] - vertices[i0]
        val vy = vertices[i2 + 1] - vertices[i0 + 1]
        val vz = vertices[i2 + 2] - vertices[i0 + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val faceLength = sqrt(nx * nx + ny * ny + nz * nz)
        if (faceLength > NORMAL_EPSILON) {
            val unitX = nx / faceLength
            val unitY = ny / faceLength
            val unitZ = nz / faceLength
            accumulateCornerNormal(normals, i0, i1, i2, vertices, unitX, unitY, unitZ)
            accumulateCornerNormal(normals, i1, i2, i0, vertices, unitX, unitY, unitZ)
            accumulateCornerNormal(normals, i2, i0, i1, vertices, unitX, unitY, unitZ)
        }
        i += 3
    }
    var n = 0
    while (n + 2 < normals.size) {
        val length = sqrt(normals[n] * normals[n] + normals[n + 1] * normals[n + 1] + normals[n + 2] * normals[n + 2])
        if (length > NORMAL_EPSILON) {
            normals[n] /= length
            normals[n + 1] /= length
            normals[n + 2] /= length
        } else {
            normals[n + 2] = 1.0f
        }
        n += 3
    }
    return normals
}

private fun accumulateCornerNormal(
    normals: FloatArray,
    corner: Int,
    adjacentA: Int,
    adjacentB: Int,
    vertices: FloatArray,
    faceX: Float,
    faceY: Float,
    faceZ: Float,
) {
    val ax = vertices[adjacentA] - vertices[corner]
    val ay = vertices[adjacentA + 1] - vertices[corner + 1]
    val az = vertices[adjacentA + 2] - vertices[corner + 2]
    val bx = vertices[adjacentB] - vertices[corner]
    val by = vertices[adjacentB + 1] - vertices[corner + 1]
    val bz = vertices[adjacentB + 2] - vertices[corner + 2]
    val crossX = ay * bz - az * by
    val crossY = az * bx - ax * bz
    val crossZ = ax * by - ay * bx
    val crossLength = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
    val dot = ax * bx + ay * by + az * bz
    val angle = atan2(crossLength, dot)
    if (!angle.isFinite() || angle <= 0.0f) return
    normals[corner] += faceX * angle
    normals[corner + 1] += faceY * angle
    normals[corner + 2] += faceZ * angle
}

private const val MAX_CREASE_AWARE_TRIANGLES = 120_000
// Keep automatic smoothing conservative. Larger feature angles are often intentional
// concave/convex boundaries in printable meshes and should not be rejoined globally.
private const val AUTO_CREASE_MIN_DEGREES = 10.0f
private const val AUTO_CREASE_MAX_DEGREES = 20.0f
private const val AUTO_CREASE_FALLBACK_DEGREES = 15.0f
private const val AUTO_CREASE_MIN_GAP_DEGREES = 2.0f
private const val MIN_AUTO_CREASE_SAMPLES = 32
private const val NORMAL_EPSILON = 1e-12f
