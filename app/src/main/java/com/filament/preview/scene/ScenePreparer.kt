package com.filament.preview.scene

import android.util.Log
import com.filament.preview.AndroidTextOutlineProvider
import com.filament.preview.Bounds
import com.filament.preview.BuildPlateConfig
import com.filament.preview.BuildPlateGeometry
import com.filament.preview.BuildPlateMeshGenerator
import com.filament.preview.PlacedMeshData
import com.filament.preview.PrintAreaCheck
import com.filament.preview.SceneMesh
import com.filament.preview.SceneNormalization
import com.filament.preview.Vec3
import com.filament.preview.combinedPlacedBounds
import com.filament.preview.printAreaCheck
import com.filament.preview.toSceneMeshes
import java.util.Locale

data class ScenePreparation(
    val placed: List<PlacedMeshData>,
    val meshes: List<SceneMesh>,
    val normalization: SceneNormalization,
    val buildPlate: BuildPlateGeometry?,
    val printAreaCheck: PrintAreaCheck? = null,
)

/**
 * 场景装配：把摆放好的 mesh（PlacedMeshData）结合打印平台配置，
 * 归一化、抬升、生成 Filament SceneMesh 与底板几何体。
 */
object ScenePreparer {

    private const val MODEL_LIFT_RATIO = 0.005f

    fun prepare(config: BuildPlateConfig, placed: List<PlacedMeshData>): ScenePreparation {
        if (placed.isEmpty()) {
            val normalization = currentSceneNormalization(config, emptyList())
            return ScenePreparation(emptyList(), emptyList(), normalization, null)
        }
        val normalization = currentSceneNormalization(config, placed)
        val meshes = runCatching { placed.toSceneMeshes(normalization) }
            .getOrElse { emptyList() }
        val buildPlate = runCatching { createBuildPlateGeometry(config, placed, normalization) }.getOrNull()
        val printAreaCheck = if (placed.isNotEmpty()) {
            placed.printAreaCheck(config, normalization.center.x, normalization.center.y)
        } else {
            null
        }
        return ScenePreparation(placed, meshes, normalization, buildPlate, printAreaCheck)
    }

    private fun List<PlacedMeshData>.toSceneMeshes(
        normalization: SceneNormalization,
    ): List<SceneMesh> {
        if (isEmpty()) return emptyList()
        val modelBounds = combinedPlacedBounds()
        // dropZ 让模型底面落到底板顶面 Z=0（真实摆放位置）。
        val dropZ = -modelBounds.min.z
        // 渲染抬升：仅用于规避模型底面与底板半透明顶面共面引发的 z-fighting/闪烁，
        // 通过独立的 renderLiftZ 附加到渲染坐标，不影响真实摆放位置。
        val modelSize = maxOf(modelBounds.size.x, modelBounds.size.y, modelBounds.size.z)
        val liftZ = if (modelSize > 0f) modelSize * MODEL_LIFT_RATIO else 0f
        val center = normalization.center
        val scale = normalization.scale
        val topLevelCounts = groupingBy { it.topLevelObjectId }.eachCount()
        Log.d("ThreeMfDebug", "toSceneMeshes: placed=${size}, topLevelCounts=$topLevelCounts")
        return flatMap { placed ->
            val dropped = if (dropZ != 0f) {
                placed.copy(
                    previewOffset = placed.previewOffset.copy(z = placed.previewOffset.z + dropZ),
                    renderLiftZ = placed.renderLiftZ + liftZ,
                )
            } else {
                placed.copy(renderLiftZ = placed.renderLiftZ + liftZ)
            }
            Log.d(
                "ThreeMfDebug",
                "Filament mesh: objectId=${placed.mesh.objectId} " +
                        "topLevelObjectId=${placed.topLevelObjectId} " +
                        "componentPath=${placed.objectPath.joinToString(" -> ")} " +
                        "world3mf=${placed.transform.debugString()} " +
                        "finalFilament=${
                            placed.transform.toFilamentMatrix(
                                center,
                                scale,
                                dropped.previewOffset
                            ).debugMatrix()
                        } " +
                        "renderLiftZ=${dropped.renderLiftZ} " +
                        "nodeTransform=identity (transform baked into POSITION)",
            )
            // Plate and all-preview layouts share MeshData, so source vertices must remain immutable.
            dropped.toSceneMeshes(center, scale)
        }
    }

    private fun currentSceneNormalization(
        config: BuildPlateConfig,
        placed: List<PlacedMeshData>,
    ): SceneNormalization {
        if (placed.isEmpty()) {
            val span = maxOf(
                config.widthMm,
                config.depthMm + config.frontExtensionMm + 1f,
                config.thicknessMm * 2f,
            )
            return SceneNormalization(Vec3(0f, 0f, 0f), 2f / span)
        }
        val modelBounds = placed.combinedPlacedBounds()
        val centerX = modelBounds.center.x
        val centerY = modelBounds.center.y
        val hw = config.widthMm / 2f
        val hd = config.depthMm / 2f
        val plateMinX = centerX - hw
        val plateMaxX = centerX + hw
        val plateMinY = centerY - hd - config.frontExtensionMm
        val plateMaxY = centerY + hd
        val modelDroppedMinZ = 0f
        val modelDroppedMaxZ = modelBounds.size.z
        val allMinX = minOf(modelBounds.min.x, plateMinX)
        val allMaxX = maxOf(modelBounds.max.x, plateMaxX)
        val allMinY = minOf(modelBounds.min.y, plateMinY)
        val allMaxY = maxOf(modelBounds.max.y, plateMaxY)
        val allMinZ = minOf(modelDroppedMinZ, -config.thicknessMm)
        val allMaxZ = maxOf(modelDroppedMaxZ, 0f)
        val combined = Bounds(
            Vec3(allMinX, allMinY, allMinZ),
            Vec3(allMaxX, allMaxY, allMaxZ),
        )
        val span = maxOf(0.0001f, combined.size.x, combined.size.y, combined.size.z)
        return SceneNormalization(
            center = Vec3(centerX, centerY, 0f),
            scale = 2f / span,
        )
    }

    private fun createBuildPlateGeometry(
        config: BuildPlateConfig,
        _placed: List<PlacedMeshData>,
        normalization: SceneNormalization,
    ): BuildPlateGeometry? {
        val text = config.effectiveBrandText()
        val outlines = if (text.isNotEmpty()) {
            AndroidTextOutlineProvider().outlines(text, 96f)
        } else {
            emptyList()
        }
        return BuildPlateMeshGenerator.generate(config, normalization, outlines)
    }

    private fun FloatArray.debugMatrix(): String {
        if (size != 16) return contentToString()
        return String.format(
            Locale.US,
            "[[%.5f,%.5f,%.5f,%.5f],[%.5f,%.5f,%.5f,%.5f],[%.5f,%.5f,%.5f,%.5f],[%.5f,%.5f,%.5f,%.5f]]",
            this[0], this[4], this[8], this[12],
            this[1], this[5], this[9], this[13],
            this[2], this[6], this[10], this[14],
            this[3], this[7], this[11], this[15],
        )
    }
}
