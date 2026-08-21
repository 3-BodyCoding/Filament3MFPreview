package com.filament.preview.model

import com.filament.preview.BuildPlateConfig
import com.filament.preview.GlbSceneBuilder
import com.filament.preview.MaterialSlotId
import com.filament.preview.PlacedMeshData
import com.filament.preview.PlatePreview
import com.filament.preview.PrintAreaCheck
import com.filament.preview.RgbaColor
import com.filament.preview.SceneMesh
import com.filament.preview.arrangedForAllPreview
import com.filament.preview.scene.ScenePreparer
import com.filament.preview.scene.ScenePreparation
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class PreparedModel(
    val meshes: List<SceneMesh>,
    val glb: ByteBuffer?,
    val transformMs: Long,
    val buildMs: Long,
    val printAreaCheck: PrintAreaCheck? = null,
)

/**
 * 模型构建管线：在后台线程把场景装配结果构建成 Filament GLB 并缓存。
 * 负责线程调度、代际（generation）失效与视图缓存；结果通过回调交给调用方应用。
 */
class ModelBuildPipeline(
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val isDestroyedProvider: () -> Boolean,
    private val colorOverridesProvider: () -> Map<MaterialSlotId, RgbaColor>,
    private val onBuildError: (Throwable) -> Unit,
) {
    /** 构建代际计数：任何新请求都会使在途/预构建任务失效。 */
    val modelBuildGeneration = AtomicInteger()

    /** 视图缓存：key -> 已构建的模型。 */
    val viewCache = ConcurrentHashMap<String, PreparedModel>()

    private val modelBuildExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "glb-builder").apply { isDaemon = true }
    }
    private val viewPrebuildExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "view-prebuild").apply { isDaemon = true }
    }

    /** 预构建代际：新加载会使其失效。 */
    @Volatile
    var prewarmLoadId = 0

    /**
     * 提交一次模型构建。缓存命中则立即在主线程回调；否则后台构建完成后回调。
     * [applyResult] 始终在主线程被调用，需在构造时注入 runOnUiThread。
     */
    fun requestBuild(
        scenePreparation: () -> ScenePreparation,
        cacheKey: String?,
        applyResult: (model: PreparedModel, requestedAt: Long, fromCache: Boolean) -> Unit,
    ) {
        val requestId = modelBuildGeneration.incrementAndGet()
        val requestedAt = System.currentTimeMillis()
        val colorOverrides = colorOverridesProvider()

        if (cacheKey != null) {
            val cached = viewCache[cacheKey]
            if (cached != null) {
                runOnUiThread { applyResult(cached, requestedAt, true) }
                return
            }
        }

        modelBuildExecutor.execute {
            if (requestId != modelBuildGeneration.get()) return@execute
            val prepared = runCatching {
                val transformStart = System.currentTimeMillis()
                val preparation = scenePreparation()
                val preparedMeshes = preparation.meshes
                val transformDone = System.currentTimeMillis()
                if (requestId != modelBuildGeneration.get()) return@execute
                val glb = preparedMeshes.takeIf { it.isNotEmpty() }?.let {
                    GlbSceneBuilder.build(it, null, colorOverrides, preparation.buildPlate)
                }
                PreparedModel(
                    meshes = preparedMeshes,
                    glb = glb,
                    transformMs = transformDone - transformStart,
                    buildMs = System.currentTimeMillis() - transformDone,
                    printAreaCheck = preparation.printAreaCheck,
                )
            }
            runOnUiThread {
                if (requestId != modelBuildGeneration.get() || isDestroyedProvider()) return@runOnUiThread
                prepared.onSuccess { model ->
                    if (cacheKey != null) viewCache[cacheKey] = model
                    applyResult(model, requestedAt, false)
                }.onFailure { onBuildError(it) }
            }
        }
    }

    /** 后台预构建各视图的 GLB 并填充缓存，供后续快速切换。 */
    fun prewarm(
        keys: List<String>,
        buildPlateConfig: BuildPlateConfig,
        placedSnapshot: List<PlacedMeshData>,
        platesSnapshot: List<PlatePreview>,
    ) {
        val colorOverrides = colorOverridesProvider()
        val loadId = prewarmLoadId
        val buildGeneration = modelBuildGeneration.get()
        viewPrebuildExecutor.execute {
            for (key in keys) {
                if (viewCache.containsKey(key)) continue
                if (modelBuildGeneration.get() != buildGeneration) break
                if (loadId != prewarmLoadId) break
                val placed = if (key.contains(":plate:")) {
                    val plateIndex = key.substringAfter(":plate:").substringBefore(":bp:").toIntOrNull()
                    platesSnapshot.firstOrNull { it.index == plateIndex }?.meshes.orEmpty()
                } else {
                    placedSnapshot.arrangedForAllPreview(platesSnapshot)
                }
                if (placed.isEmpty()) continue
                val preparation = runCatching {
                    ScenePreparer.prepare(buildPlateConfig, placed)
                }.getOrNull() ?: continue
                if (modelBuildGeneration.get() != buildGeneration) break
                if (loadId != prewarmLoadId) break
                val glb = runCatching {
                    GlbSceneBuilder.build(
                        preparation.meshes,
                        null,
                        colorOverrides,
                        preparation.buildPlate
                    )
                }.getOrNull() ?: continue
                if (modelBuildGeneration.get() != buildGeneration) break
                if (loadId != prewarmLoadId) break
                viewCache[key] = PreparedModel(
                    meshes = preparation.meshes,
                    glb = glb,
                    transformMs = 0L,
                    buildMs = 0L,
                    printAreaCheck = preparation.printAreaCheck,
                )
            }
        }
    }

    /** 停止所有后台构建线程并令在途任务失效。 */
    fun shutdown() {
        modelBuildGeneration.incrementAndGet()
        modelBuildExecutor.shutdownNow()
        viewPrebuildExecutor.shutdownNow()
    }
}
