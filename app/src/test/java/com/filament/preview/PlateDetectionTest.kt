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
    fun `declared plates do not absorb unassigned meshes but all preview keeps them`() {
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
        assertEquals(1, plates.single { it.index == 1 }.meshes.size)
        assertTrue(plates.single { it.index == 2 }.meshes.isEmpty())
        assertEquals(1, plates.single { it.index == 3 }.meshes.size)
        assertTrue(plates.single { it.index == 4 }.meshes.isEmpty())
        // Unassigned meshes stay out of plate views, but the All preview still includes them.
        assertEquals(2, plates.sumOf { it.meshes.size })
        assertEquals(6, meshes.arrangedForAllPreview(plates).size)
    }

    @Test
    fun `no plate metadata never invents plates from sparse coordinates`() {
        val meshes = List(6) { index ->
            PlacedMeshData(
                mesh = triangleMesh(index + 1),
                name = "mesh-${index + 1}",
                plateIndex = null,
                transform = MeshTransform.parse("1 0 0 0 1 0 0 0 1 ${index * 100} 0 0"),
            )
        }

        assertTrue(meshes.resolvePlatePreviews().isEmpty())
    }

    @Test
    fun `stale plate json does not inflate plate count when config is authoritative`() {
        val archive = temporaryFolder.newFile("stale-plate-json.3mf")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(
                """
                <config>
                  <plate><metadata key="plater_id" value="1"/></plate>
                  <plate><metadata key="plater_id" value="2"/></plate>
                  <plate><metadata key="plater_id" value="3"/></plate>
                  <plate><metadata key="plater_id" value="4"/></plate>
                </config>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/plate_5.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        assertEquals(listOf(1, 2, 3, 4), ThreeMfBuildParser.explicitPlateIndices(archive))
    }

    @Test
    fun `json plates are used when config is absent and json references model objects`() {
        val archive = temporaryFolder.newFile("json-plates.3mf")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(modelXml().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/plate_1.json"))
            zip.write("""{"object_id": 1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/plate_2.json"))
            zip.write("""{"object_id": 2}""".toByteArray())
            zip.closeEntry()
        }

        assertEquals(listOf(1, 2), ThreeMfBuildParser.explicitPlateIndices(archive))
    }

    private fun modelXml(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
          <resources>
            <object id="1" type="model"/>
            <object id="2" type="model"/>
          </resources>
          <build>
            <item objectid="1"/>
            <item objectid="2"/>
          </build>
        </model>
        """.trimIndent()

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
