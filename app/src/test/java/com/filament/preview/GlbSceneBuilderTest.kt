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
