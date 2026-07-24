package com.filament.preview

import io.lib3mf.android.MeshData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlateDetectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `plate config preserves declared empty plates`() {
        val archive = temporaryFolder.newFile("four-plates.3mf")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(
                """
                <config>
                  <plate>
                    <metadata key="plater_id" value="1"/>
                    <model_instance>
                      <metadata key="object_id" value="2"/>
                      <metadata key="instance_id" value="0"/>
                    </model_instance>
                  </plate>
                  <plate><metadata key="plater_id" value="2"/></plate>
                  <plate><metadata key="plater_id" value="3"/></plate>
                  <plate><metadata key="plater_id" value="4"/></plate>
                </config>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }

        assertEquals(listOf(1, 2, 3, 4), ThreeMfBuildParser.explicitPlateIndices(archive))
    }

    @Test
    fun `declared plates override six coordinate clusters without dropping meshes`() {
        val meshes = List(6) { index ->
            PlacedMeshData(
                mesh = triangleMesh(index + 1),
                name = "mesh-${index + 1}",
                plateIndex = when (index) {
                    0 -> 1
                    1 -> 3
                    else -> null
                },
                transform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 ${index * 100} 0 0"),
            )
        }

        val plates = meshes.resolvePlatePreviews(listOf(1, 2, 3, 4))

        assertEquals(listOf(1, 2, 3, 4), plates.map { it.index })
        assertTrue(plates.single { it.index == 2 }.meshes.isEmpty())
        assertTrue(plates.single { it.index == 4 }.meshes.isEmpty())
        assertEquals(6, plates.sumOf { it.meshes.size })
        assertEquals(6, meshes.arrangedForAllPreview(plates).size)
    }

    private fun triangleMesh(id: Int) = MeshData(
        id,
        floatArrayOf(
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
        ),
        intArrayOf(0, 1, 2),
        floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
    )
}
