package com.filament.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbSceneBuilderTest {
    @Test
    fun `studio equirectangular texture allocates a complete mip chain`() {
        assertEquals(8, fullMipLevelCount(128, 64))
        assertEquals(1, fullMipLevelCount(1, 1))
    }

    @Test
    fun `srgb colors are converted to linear without changing alpha`() {
        val linear = floatArrayOf(0.0f, 0.5f, 1.0f, 0.4f).srgbToLinearRgba()

        assertArrayEquals(floatArrayOf(0.0f, 0.21404114f, 1.0f, 0.4f), linear, 0.000001f)
    }

    @Test
    fun `computed vertex normals follow triangle winding`() {
        val vertices = floatArrayOf(
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
        )

        val normals = computeVertexNormals(vertices, intArrayOf(0, 1, 2, 0, 2, 3))

        normals.toList().chunked(3).forEach { normal ->
            assertEquals(0.0f, normal[0], 0.000001f)
            assertEquals(0.0f, normal[1], 0.000001f)
            assertEquals(1.0f, normal[2], 0.000001f)
        }
    }

    @Test
    fun `vertex normals use corner angles instead of triangle area`() {
        val vertices = floatArrayOf(
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            100.0f, 0.0f, 0.0f,
            0.0f, 0.0f, -100.0f,
        )

        val normals = computeVertexNormals(vertices, intArrayOf(0, 1, 2, 0, 3, 4))
        val diagonal = (1.0 / kotlin.math.sqrt(2.0)).toFloat()

        assertArrayEquals(floatArrayOf(0.0f, diagonal, diagonal), normals.copyOfRange(0, 3), 0.000001f)
    }

    @Test
    fun `degenerate triangles do not contaminate valid normals`() {
        val vertices = floatArrayOf(
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            2.0f, 0.0f, 0.0f,
        )

        val normals = computeVertexNormals(vertices, intArrayOf(0, 1, 2, 0, 1, 3))

        assertArrayEquals(floatArrayOf(0.0f, 0.0f, 1.0f), normals.copyOfRange(0, 3), 0.000001f)
        assertTrue(normals.all(Float::isFinite))
    }

    @Test
    fun `material primitives share normals from the complete mesh`() {
        val vertices = smoothBentSurfaceVertices()
        val firstIndices = intArrayOf(0, 1, 2)
        val secondIndices = intArrayOf(1, 0, 3)
        val meshIndices = firstIndices + secondIndices
        val mesh = sceneMesh(
            vertices,
            meshIndices,
            MeshMaterialLayout(
                slots = testMaterialSlots(),
                primitives = listOf(
                    MeshMaterialPrimitive(firstIndices, 0),
                    MeshMaterialPrimitive(secondIndices, 1),
                ),
            ),
        )

        val bin = GlbSceneBuilder.build(listOf(mesh), null).binaryChunk()
        val vertexBytes = 3 * 3 * Float.SIZE_BYTES
        val firstNormal = bin.float3(vertexBytes)
        val secondPrimitivePositionOffset = align4(vertexBytes * 2 + firstIndices.size * Short.SIZE_BYTES)
        val secondNormal = bin.float3(secondPrimitivePositionOffset + vertexBytes + 3 * Float.SIZE_BYTES)
        val cornerNormals = computeCreaseAwareCornerNormals(vertices, meshIndices, GlbSceneBuilder.MODEL_CREASE_ANGLE_DEGREES)
        val expected = cornerNormals.copyOfRange(0, 3)

        assertArrayEquals(expected, firstNormal, 0.000001f)
        assertArrayEquals(expected, secondNormal, 0.000001f)
    }

    @Test
    fun `corner colors preserve hard edge normals`() {
        val vertices = hardBentSurfaceVertices()
        val indices = intArrayOf(0, 1, 2, 1, 0, 3)
        val mesh = sceneMesh(
            vertices,
            indices,
            MeshMaterialLayout(
                slots = testMaterialSlots(),
                primitives = listOf(
                    MeshMaterialPrimitive(
                        indices = indices,
                        materialSlotIndex = -1,
                        cornerMaterialSlotIndices = intArrayOf(0, 1, 0, 1, 0, 1),
                    ),
                ),
            ),
        )

        val bin = GlbSceneBuilder.build(listOf(mesh), null).binaryChunk()
        val expandedPositionBytes = indices.size * 3 * Float.SIZE_BYTES
        val firstCopy = bin.float3(expandedPositionBytes)
        val secondCopy = bin.float3(expandedPositionBytes + 4 * 3 * Float.SIZE_BYTES)
        val cornerNormals = computeCreaseAwareCornerNormals(vertices, indices, GlbSceneBuilder.MODEL_CREASE_ANGLE_DEGREES)

        assertArrayEquals(cornerNormals.copyOfRange(0, 3), firstCopy, 0.000001f)
        assertArrayEquals(cornerNormals.copyOfRange(4 * 3, 5 * 3), secondCopy, 0.000001f)
        assertTrue(firstCopy[0] * secondCopy[0] + firstCopy[1] * secondCopy[1] + firstCopy[2] * secondCopy[2] < 0.1f)
    }

    @Test
    fun `thirty degree crease policy preserves shallow manufactured edges`() {
        val belowCrease = computeCreaseAwareCornerNormals(
            bentSurfaceVertices(25.0),
            intArrayOf(0, 1, 2, 1, 0, 3),
            GlbSceneBuilder.MODEL_CREASE_ANGLE_DEGREES,
        )
        val aboveCrease = computeCreaseAwareCornerNormals(
            bentSurfaceVertices(35.0),
            intArrayOf(0, 1, 2, 1, 0, 3),
            GlbSceneBuilder.MODEL_CREASE_ANGLE_DEGREES,
        )

        assertTrue(dot(belowCrease, 0, belowCrease, 12) > 0.999f)
        assertTrue(dot(aboveCrease, 0, aboveCrease, 12) < 0.9f)
    }

    @Test
    fun `generated glb contains lit plastic material and normals`() {
        val vertices = floatArrayOf(
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
        )
        val indices = intArrayOf(0, 1, 2)
        val mesh = SceneMesh(
            objectId = 1,
            name = "triangle",
            vertices = vertices,
            indices = indices,
            originalBounds = vertices.computeBounds(),
            renderBounds = vertices.computeBounds(),
            displayColor = floatArrayOf(0.5f, 0.5f, 0.5f, 1.0f),
        )

        val json = GlbSceneBuilder.build(listOf(mesh), null).jsonChunk()

        assertTrue(json.contains("\"NORMAL\":"))
        assertTrue(json.contains("\"metallicFactor\":0.0"))
        assertTrue(json.contains("\"roughnessFactor\":0.38"))
        assertTrue(json.contains("\"baseColorFactor\":[0.214041, 0.214041, 0.214041, 1.000000]"))
        assertArrayEquals(floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f), vertices, 0.0f)
        assertArrayEquals(intArrayOf(0, 1, 2), indices)
    }
}

private fun smoothBentSurfaceVertices(): FloatArray = floatArrayOf(
    0.0f, 0.0f, 0.0f,
    1.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f,
    0.0f, -0.8660254f, 0.5f,
)

private fun hardBentSurfaceVertices(): FloatArray = floatArrayOf(
    0.0f, 0.0f, 0.0f,
    1.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f,
    0.0f, 0.0f, 1.0f,
)

private fun bentSurfaceVertices(angleDegrees: Double): FloatArray {
    val radians = Math.toRadians(angleDegrees)
    return floatArrayOf(
        0.0f, 0.0f, 0.0f,
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, -kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat(),
    )
}

private fun dot(first: FloatArray, firstOffset: Int, second: FloatArray, secondOffset: Int): Float =
    first[firstOffset] * second[secondOffset] +
        first[firstOffset + 1] * second[secondOffset + 1] +
        first[firstOffset + 2] * second[secondOffset + 2]

private fun sceneMesh(vertices: FloatArray, indices: IntArray, layout: MeshMaterialLayout): SceneMesh = SceneMesh(
    objectId = 1,
    name = "test",
    vertices = vertices,
    indices = indices,
    originalBounds = vertices.computeBounds(),
    renderBounds = vertices.computeBounds(),
    materialLayout = layout,
)

private fun testMaterialSlots(): List<MaterialSlot> = listOf(
    testMaterialSlot(0, RgbaColor(0.8f, 0.2f, 0.1f)),
    testMaterialSlot(1, RgbaColor(0.1f, 0.3f, 0.8f)),
)

private fun testMaterialSlot(index: Int, color: RgbaColor): MaterialSlot = MaterialSlot(
    id = MaterialSlotId(MaterialSlotSource.CORE_3MF, "", 1, 1, index),
    name = "material-$index",
    originalColor = color,
    triangleCount = 1,
)

private fun ByteBuffer.jsonChunk(): String {
    val source = duplicate().order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(0x46546C67, source.int)
    assertEquals(2, source.int)
    source.int
    val jsonLength = source.int
    assertEquals(0x4E4F534A, source.int)
    val json = ByteArray(jsonLength)
    source.get(json)
    return json.toString(Charsets.UTF_8).trimEnd()
}

private fun ByteBuffer.binaryChunk(): ByteBuffer {
    val source = duplicate().order(ByteOrder.LITTLE_ENDIAN)
    source.position(12)
    val jsonLength = source.int
    assertEquals(0x4E4F534A, source.int)
    source.position(20 + jsonLength)
    val binaryLength = source.int
    assertEquals(0x004E4942, source.int)
    return source.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(binaryLength) }
}

private fun ByteBuffer.float3(offset: Int): FloatArray = floatArrayOf(
    getFloat(offset),
    getFloat(offset + Float.SIZE_BYTES),
    getFloat(offset + Float.SIZE_BYTES * 2),
)

private fun align4(value: Int): Int = (value + 3) and -4
