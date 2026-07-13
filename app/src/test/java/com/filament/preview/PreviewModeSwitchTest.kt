package com.filament.preview

import io.lib3mf.android.MeshData
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PreviewModeSwitchTest {
    @Test
    fun `switching through all preview does not mutate plate source vertices`() {
        val firstVertices = floatArrayOf(
            0.0f, 0.0f, 0.0f,
            20.0f, 0.0f, 0.0f,
            0.0f, 10.0f, 5.0f,
        )
        val secondVertices = floatArrayOf(
            0.0f, 0.0f, 0.0f,
            15.0f, 0.0f, 0.0f,
            0.0f, 25.0f, 8.0f,
        )
        val firstSource = firstVertices.copyOf()
        val secondSource = secondVertices.copyOf()
        val first = PlacedMeshData(
            mesh = MeshData(1, firstVertices, intArrayOf(0, 1, 2), floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f)),
            name = "plate-1",
            plateIndex = 1,
            transform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 200 100 0"),
        )
        val second = PlacedMeshData(
            mesh = MeshData(2, secondVertices, intArrayOf(0, 1, 2), floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)),
            name = "plate-2",
            plateIndex = 2,
            transform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 600 100 0"),
        )
        val loaded = listOf(first, second)
        val plates = listOf(
            PlatePreview(1, "plate 1", listOf(first)),
            PlatePreview(2, "plate 2", listOf(second)),
        )

        val initialPlateScene = listOf(first).renderWithoutMutatingSources()
        loaded.arrangedForAllPreview(plates).renderWithoutMutatingSources()
        val restoredPlateScene = listOf(first).renderWithoutMutatingSources()

        assertArrayEquals(firstSource, first.mesh.vertices, 0.0f)
        assertArrayEquals(secondSource, second.mesh.vertices, 0.0f)
        assertArrayEquals(initialPlateScene.single().vertices, restoredPlateScene.single().vertices, 0.0f)
    }
}

private fun List<PlacedMeshData>.renderWithoutMutatingSources(): List<SceneMesh> {
    val (center, scale) = placedNormalization()
    return flatMap { it.toSceneMeshes(center, scale) }
}
