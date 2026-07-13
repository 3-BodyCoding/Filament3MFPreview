package com.filament.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow

object GlbSceneBuilder {
    const val BASE_PLATE_NODE = "baseplate"
    fun meshNodeName(index: Int): String = "mesh_$index"
    fun markerNodeName(index: Int): String = "marker_$index"

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
            val layout = mesh.materialLayout
            val primitives = if (layout == null || layout.primitives.isEmpty()) {
                modelColors += overrideColor ?: mesh.displayColor ?: DEFAULT_MODEL_COLOR
                listOf(Primitive(mesh.vertices, mesh.indices, modelColors.lastIndex, 4))
            } else {
                layout.primitives.mapNotNull { source ->
                    val cornerSlots = source.cornerMaterialSlotIndices
                    if (cornerSlots != null) {
                        vertexColorPrimitive(
                            mesh = mesh,
                            source = source,
                            slots = layout.slots,
                            materialIndex = materialForVertexColors(),
                            overrideColor = overrideColor,
                            colorOverrides = colorOverrides,
                        )
                    } else {
                        val slot = layout.slots.getOrNull(source.materialSlotIndex) ?: return@mapNotNull null
                        Primitive(mesh.vertices, source.indices, materialFor(slot), 4)
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
        mesh: SceneMesh,
        source: MeshMaterialPrimitive,
        slots: List<MaterialSlot>,
        materialIndex: Int,
        overrideColor: FloatArray?,
        colorOverrides: Map<MaterialSlotId, RgbaColor>,
    ): Primitive? {
        val cornerSlots = source.cornerMaterialSlotIndices ?: return null
        if (cornerSlots.size != source.indices.size) return null
        val vertices = FloatArray(source.indices.size * 3)
        val colors = FloatArray(source.indices.size * 4)
        val indices = IntArray(source.indices.size) { it }
        source.indices.forEachIndexed { corner, sourceVertex ->
            val sourceOffset = sourceVertex * 3
            if (sourceOffset < 0 || sourceOffset + 2 >= mesh.vertices.size) return null
            mesh.vertices.copyInto(vertices, corner * 3, sourceOffset, sourceOffset + 3)
            val slot = slots.getOrNull(cornerSlots[corner])
            val color = overrideColor
                ?: slot?.let { colorOverrides[it.id]?.toFloatArray() ?: it.originalColor.toFloatArray() }
                ?: DEFAULT_MODEL_COLOR
            color.srgbToLinearRgba().copyInto(colors, corner * 4, 0, 4)
        }
        return Primitive(vertices, indices, materialIndex, 4, colors)
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
    ) {
        val normals: FloatArray = computeVertexNormals(vertices, indices)
        val indexComponentType: Int = if (vertices.size / 3 <= UShort.MAX_VALUE.toInt()) 5123 else 5125
        val indexByteLength: Int = indices.size * if (indexComponentType == 5123) 2 else 4

        fun putIndices(bin: BinarySink) {
            if (indexComponentType == 5123) {
                bin.putUShorts(indices)
            } else {
                bin.putUInts(indices)
            }
        }
    }
    private data class BufferView(val offset: Int, val length: Int, val target: Int)
    private data class Accessor(val bufferView: Int, val componentType: Int, val count: Int, val type: String, val bounds: Bounds?)

    internal const val MODEL_METALLIC = 0.0f
    internal const val MODEL_ROUGHNESS = 0.38f
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
        normals[i0] += nx; normals[i0 + 1] += ny; normals[i0 + 2] += nz
        normals[i1] += nx; normals[i1 + 1] += ny; normals[i1 + 2] += nz
        normals[i2] += nx; normals[i2 + 1] += ny; normals[i2 + 2] += nz
        i += 3
    }
    var n = 0
    while (n + 2 < normals.size) {
        val length = kotlin.math.sqrt(normals[n] * normals[n] + normals[n + 1] * normals[n + 1] + normals[n + 2] * normals[n + 2])
        if (length > 1e-6f) {
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
