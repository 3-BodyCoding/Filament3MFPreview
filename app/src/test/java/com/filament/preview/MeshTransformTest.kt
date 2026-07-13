package com.filament.preview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshTransformTest {
    @Test
    fun `3mf translation uses the final serialized triplet`() {
        val transform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 10 20 30")

        val result = transform.apply(Vec3(1.0f, 2.0f, 3.0f))

        assertEquals(Vec3(11.0f, 22.0f, 33.0f), result)
    }

    @Test
    fun `component transform is applied before its parent build transform`() {
        val buildTransform = MeshTransform.parse("0 1 0 -1 0 0 0 0 1 10 20 30")
        val componentTransform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 2 3 4")

        val result = buildTransform.compose(componentTransform).apply(Vec3(0.0f, 0.0f, 0.0f))

        assertEquals(Vec3(7.0f, 22.0f, 34.0f), result)
    }

    @Test
    fun `bunny egg world translation matches the 3mf hierarchy`() {
        val buildTransform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 435.2 127.99999 19.7824364")
        val eggComponentTransform = MeshTransform.parse(
            "1 0 0 0 1 0 0 0 1 -8.99849129 -6.83862209 -0.601898193",
        )

        val result = buildTransform.compose(eggComponentTransform).apply(Vec3(0.0f, 0.0f, 0.0f))

        assertEquals(426.2015f, result.x, 0.0001f)
        assertEquals(121.16137f, result.y, 0.0001f)
        assertEquals(19.180538f, result.z, 0.0001f)
    }

    @Test
    fun `filament matrix is column major and includes normalization`() {
        val transform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 10 20 30")

        val matrix = transform.toFilamentMatrix(
            center = Vec3(5.0f, 10.0f, 15.0f),
            scale = 2.0f,
            offset = Vec3(1.0f, 2.0f, 3.0f),
        )

        assertArrayEquals(
            floatArrayOf(
                2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 2.0f, 0.0f,
                12.0f, 24.0f, 36.0f, 1.0f,
            ),
            matrix,
            0.0001f,
        )
    }
}
