package com.filament.preview.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.filament.preview.BuildPlateConfig
import com.filament.preview.PlacedMeshData
import com.filament.preview.PlatePreview
import com.filament.preview.SceneMesh
import com.filament.preview.ThreeMfBuildParser
import com.filament.preview.detectPlatePreviews
import com.filament.preview.scene.ScenePreparer
import io.lib3mf.android.open3mf
import java.io.File
import java.io.FileOutputStream

data class Loaded3mf(
    val fileName: String,
    val meshes: List<PlacedMeshData>,
    val plates: List<PlatePreview>,
    val initialSceneMeshes: List<SceneMesh>?,
)

/**
 * 3MF 文件加载器：负责把 URI 复制到缓存并解析成 [Loaded3mf]。
 * 纯加载职责，不触碰任何 UI 状态；状态更新由调用方处理。
 */
object ThreeMfLoader {

    /** 同步加载 3MF，返回解析结果；失败时抛出异常由调用方捕获。 */
    fun load(
        context: Context,
        uri: Uri,
        config: BuildPlateConfig,
        plateName: (Int) -> String,
    ): Loaded3mf {
        val source = copyToCache(context, uri)
        val perfStart = System.currentTimeMillis()
        return open3mf(source.absolutePath).use { document ->
            Log.d("ThreeMfPerf", "Load 3MF: ${System.currentTimeMillis() - perfStart} ms")
            Log.d("ThreeMfDebug", "lib3mf version=${document.getLibraryVersion()}")
            val objects = document.getObjects()
            Log.d("ThreeMfDebug", "lib3mf GetObjects count=${objects.size}")
            objects.forEach { info ->
                Log.d(
                    "ThreeMfDebug",
                    "lib3mf object: id=${info.resourceId}, localId=${info.modelResourceId}, " +
                            "package=${info.packagePath}, kind=${info.resourceKind}, type=${info.type}, " +
                            "name=${info.name}, vertices=${info.vertexCount}, triangles=${info.triangleCount}, " +
                            "components=${info.componentCount}",
                )
            }
            val buildItems = document.getBuildItems().toList()
            val componentsByObjectId = objects
                .filter { it.resourceKind == "components" }
                .associate {
                    it.resourceId to document.getComponents(it.resourceId).toList()
                }
            val meshObjects = objects.filter {
                it.resourceKind == "mesh" && it.vertexCount > 0 && it.triangleCount > 0
            }
            val tMeshStart = System.currentTimeMillis()
            val rawMeshes = meshObjects.map { info ->
                val mesh = document.getMeshData(info.resourceId)
                val properties = mesh.propertyData
                val propertyGroups = if (properties.triangleResourceIds.none { it != 0 }) {
                    mapOf("pid=0,p=0/0/0" to properties.triangleResourceIds.size)
                } else {
                    val groups = linkedMapOf<String, Int>()
                    var omitted = 0
                    properties.triangleResourceIds.indices.forEach { triangle ->
                        val offset = triangle * 3
                        val key = "pid=${properties.triangleResourceIds[triangle]}," +
                                "p=${properties.trianglePropertyIndices.getOrElse(offset) { 0 }}/" +
                                "${properties.trianglePropertyIndices.getOrElse(offset + 1) { 0 }}/" +
                                "${properties.trianglePropertyIndices.getOrElse(offset + 2) { 0 }}"
                        if (key in groups || groups.size < 32) {
                            groups[key] = groups.getOrDefault(key, 0) + 1
                        } else {
                            omitted += 1
                        }
                    }
                    if (omitted > 0) groups["other-property-combinations"] = omitted
                    groups
                }
                Log.d(
                    "ThreeMfDebug",
                    "Material properties: objectId=${mesh.objectId}, localId=${mesh.modelResourceId}, " +
                            "package=${mesh.packagePath}, triangles=${mesh.triangles.size / 3}, " +
                            "objectProperty=${properties.hasObjectProperty}:" +
                            "${properties.objectPropertyResourceId}/${properties.objectPropertyIndex}, " +
                            "groups=$propertyGroups, resolved=${properties.properties.size}",
                )
                info to mesh
            }
            Log.d("ThreeMfPerf", "parseMesh: ${System.currentTimeMillis() - tMeshStart} ms")
            val namesByObjectId = objects.associate { info -> info.resourceId to info.name }
            val meshesByObjectId =
                rawMeshes.associate { (info, mesh) -> info.resourceId to mesh }
            val parsed = ThreeMfBuildParser.parseProject(
                file = source,
                buildItems = buildItems,
                componentsByObjectId = componentsByObjectId,
                meshesByObjectId = meshesByObjectId,
                namesByObjectId = namesByObjectId,
            )
            val explicitPlateIndices = parsed.explicitPlateIndices
            val usePlateLogic = explicitPlateIndices.size >= 2
            val placedMeshes = parsed.placedMeshes.ifEmpty {
                rawMeshes.map { (info, mesh) ->
                    PlacedMeshData(
                        mesh = mesh,
                        name = info.name.ifBlank { "Object ${info.resourceId}" },
                        topLevelObjectId = info.resourceId,
                    )
                }
            }
            if (!usePlateLogic) {
                Loaded3mf(
                    fileName = source.name,
                    meshes = emptyList(),
                    plates = emptyList(),
                    initialSceneMeshes = ScenePreparer.prepare(config, placedMeshes).meshes,
                )
            } else {
                Loaded3mf(
                    source.name,
                    placedMeshes,
                    placedMeshes.detectPlatePreviews(explicitPlateIndices, plateName),
                    initialSceneMeshes = null,
                )
            }
        }
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex("_display_name")
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "selected.3mf"
        val target = File(context.cacheDir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }
}
