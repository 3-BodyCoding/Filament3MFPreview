package com.filament.preview

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.filament.preview.model.ModelBuildPipeline
import com.filament.preview.model.PreparedModel
import com.filament.preview.model.ThreeMfLoader
import com.filament.preview.rendering.OrbitCameraController
import com.filament.preview.scene.ScenePreparation
import com.filament.preview.scene.ScenePreparer
import com.filament.preview.ui.AxisLabel
import com.filament.preview.ui.EditableColorState
import com.filament.preview.ui.PreviewMode
import com.filament.preview.ui.PreviewScreen
import com.filament.preview.ui.theme.FilamentPreviewTheme
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    @Volatile
    private var modelViewer: ModelViewer? = null

    @Volatile
    private var studioEnvironment: StudioEnvironment? = null
    private var filamentSurface: SurfaceView? = null
    private val pickHandler by lazy { Handler(Looper.getMainLooper()) }
    private val choreographer by lazy { Choreographer.getInstance() }
    private val modelBuildPipeline: ModelBuildPipeline = ModelBuildPipeline(
        runOnUiThread = { action -> runOnUiThread(action) },
        isDestroyedProvider = { isDestroyed },
        colorOverridesProvider = { modelColorController.overrideSnapshot() },
        onBuildError = { error ->
            isLoading = false
            Log.e("ThreeMfPerf", "GLB preparation failed", error)
            Toast.makeText(
                this,
                error.message ?: error.javaClass.simpleName,
                Toast.LENGTH_LONG,
            ).show()
        },
    )
    private val filamentThread = HandlerThread("filament-thread").also { it.start() }
    private val filamentHandler = Handler(filamentThread.looper)
    private val filamentScope =
        CoroutineScope(SupervisorJob() + filamentHandler.asCoroutineDispatcher())

    @Volatile
    private var colorVersion = 0

    @Volatile
    private var renderingEnabled = true

    /** 保证同一时刻队列里至多一个 render 任务，避免渲染积压。 */
    private val renderPending = AtomicBoolean(false)

    /** 世代令牌：切换/销毁/暂停时递增，使已排队但未执行的 render / load 任务直接失效。 */
    private val renderGeneration = AtomicInteger()

    /** 记录渲染是否暂停（surfaceDestroyed / onPause 时为 true）。 */
    @Volatile
    private var renderingPaused = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!renderingEnabled || renderingPaused) return
            choreographer.postFrameCallback(this)
            // 若上一帧的 render 任务仍在队列中未执行，则跳过本帧，避免积压。
            if (!renderPending.compareAndSet(false, true)) return
            val gen = renderGeneration.get()
            filamentScope.launch {
                try {
                    if (!renderingEnabled || renderingPaused || renderGeneration.get() != gen) {
                        return@launch
                    }
                    val viewer = modelViewer ?: return@launch
                    viewer.render(frameTimeNanos)
                } finally {
                    renderPending.set(false)
                }
            }
            updateAxisLabels()
        }
    }

    private var isLoading by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var meshes by mutableStateOf<List<SceneMesh>>(emptyList())
    private var loadedFileName by mutableStateOf("")
    private var loadedPlacedMeshes by mutableStateOf<List<PlacedMeshData>>(emptyList())
    private var plates by mutableStateOf<List<PlatePreview>>(emptyList())
    private var previewMode by mutableStateOf(PreviewMode.All)
    private var selectedPlateIndex by mutableIntStateOf(0)
    private var selectedIndex by mutableStateOf<Int?>(null)
    private var selectedIndices by mutableStateOf(emptySet<Int>())
    private var showBasePlate by mutableStateOf(true)
    private var buildPlateConfig by mutableStateOf(BuildPlateConfig())
    private var buildPlateVersion by mutableIntStateOf(0)
    private var printAreaWarning by mutableStateOf<String?>(null)
    private var editableColors by mutableStateOf<List<EditableColorState>>(emptyList())
    private var basePlateEntities = IntArray(0)
    private var modelEntities = emptyList<IntArray>()
    private var markerEntities = emptyList<IntArray>()
    private var entityToMeshIndex = emptyMap<Int, Int>()
    private var axisLabels by mutableStateOf<List<AxisLabel>>(emptyList())
    private val orbitCamera = OrbitCameraController(
        surfaceHeightProvider = { filamentSurface?.height ?: 0 },
        verticalFovDegrees = CAMERA_VERTICAL_FOV_DEGREES,
        panSensitivity = PAN_SENSITIVITY,
        onViewChanged = { eye, target ->
            filamentScope.launch {
                val viewer = modelViewer ?: return@launch
                viewer.camera.lookAt(
                    eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
                    target.x.toDouble(), target.y.toDouble(), target.z.toDouble(),
                    0.0, 0.0, 1.0,
                )
            }
        },
    )
    private val modelColorController: ModelColorController = ModelColorController {
        colorVersion++
        modelBuildPipeline.viewCache.clear()
        runOnUiThread { reloadModel() }
    }

    fun getEditableColorSlots(): List<MaterialSlot> = modelColorController.getColorSlots()

    fun setEditableColor(slotId: MaterialSlotId, color: RgbaColor) {
        modelColorController.setColor(slotId, color)
    }

    fun setEditableColors(colors: Map<MaterialSlotId, RgbaColor>) {
        modelColorController.setColors(colors)
    }

    fun resetEditableColor(slotId: MaterialSlotId) {
        modelColorController.resetColor(slotId)
    }

    fun resetAllEditableColors() {
        modelColorController.resetAllColors()
    }

    private fun updateBuildPlateConfig(config: BuildPlateConfig) {
        if (config == buildPlateConfig) return
        buildPlateConfig = config
        buildPlateVersion++
        modelBuildPipeline.viewCache.clear()
        if (loadedPlacedMeshes.isNotEmpty()) reloadModel()
    }

    private fun refreshEditableColors() {
        editableColors = modelColorController.getColorSlots().map { slot ->
            EditableColorState(
                slot = slot,
                currentColor = modelColorController.getEffectiveColor(slot.id)
                    ?: slot.originalColor,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.init()
        if (status.isEmpty()) status = getString(R.string.status_initial)
        setContent {
            FilamentPreviewTheme {
                PreviewScreen(
                    loading = isLoading,
                    status = status,
                    selectedName = selectedIndex?.let { meshes.getOrNull(it)?.name },
                    selectedLengths = meshes.selectedXyzLengths(selectedIndex),
                    showBasePlate = showBasePlate,
                    buildPlateConfig = buildPlateConfig,
                    editableColors = editableColors,
                    previewMode = previewMode,
                    plates = plates,
                    selectedPlateIndex = selectedPlateIndex,
                    axisLabels = axisLabels,
                    printAreaWarning = printAreaWarning,
                    onPickFile = ::load3mf,
                    onColorChange = ::setEditableColor,
                    onColorReset = ::resetEditableColor,
                    onAllColorsReset = ::resetAllEditableColors,
                    onPreviewModeChange = { mode -> previewMode = mode; applyCurrentPreview() },
                    onPlateChange = { index -> selectedPlateIndex = index; applyCurrentPreview() },
                    onBasePlateChange = { showBasePlate = it; applyBasePlateVisibility() },
                    onBuildPlateConfigChange = ::updateBuildPlateConfig,
                    surfaceFactory = { surface -> setupFilament(surface) },
                )
            }
        }
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        pauseRendering()
    }

    override fun onResume() {
        super.onResume()
        resumeRendering()
    }

    override fun onDestroy() {
        renderingEnabled = false
        renderingPaused = true
        renderGeneration.incrementAndGet()
        modelBuildPipeline.shutdown()
        choreographer.removeFrameCallback(frameCallback)
        // 所有 render / surface 回调都已通过 generation + pause 停止，下面在
        // filament 线程上串行执行销毁，不会与在途帧交叉。
        filamentScope.launch {
            destroyViewer()
            filamentThread.quitSafely()
        }
        super.onDestroy()
    }

    private fun pauseRendering() {
        renderingPaused = true
        renderGeneration.incrementAndGet()
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun resumeRendering() {
        if (!renderingEnabled || isDestroyed) return
        renderingPaused = false
        // surface 可用时才恢复；surface 尚未创建时 resume 会由 surfaceCreated 触发重建。
        if (modelViewer != null) choreographer.postFrameCallback(frameCallback)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFilament(surfaceView: SurfaceView) {
        filamentSurface = surfaceView
        // 在创建 ModelViewer（内部会注册 SurfaceHolder.Callback）之前，先注册我们自己的
        // SurfaceHolder.Callback。注册更早，surfaceDestroyed 时我们先收到回调并停止渲染，
        // 再轮到 ModelViewer 内部回调销毁 swapchain，从而避免在途帧与 swapchain 销毁竞争。
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // ModelViewer detach 后即失效，surface 重建时必须重建 viewer。
                if (modelViewer == null) {
                    createViewer(surfaceView)
                }
                resumeRendering()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // 先停渲染、丢弃在途/排队任务，再在 filament 线程串行销毁 viewer。
                pauseRendering()
                renderGeneration.incrementAndGet()
                filamentScope.launch { destroyViewer() }
            }
        })
        if (surfaceView.holder.surface?.isValid == true && modelViewer == null) {
            createViewer(surfaceView)
        }
        val tapSlop = ViewConfiguration.get(surfaceView.context).scaledTouchSlop.toDouble()
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var lastX = 0f
        var lastY = 0f
        var lastFocusX = 0f
        var lastFocusY = 0f
        var lastPinchSpan = 0f
        var multiTouch = false
        surfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    downTime = event.eventTime
                    lastPinchSpan = 0f
                    multiTouch = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    lastFocusX = event.focusX()
                    lastFocusY = event.focusY()
                    lastPinchSpan = event.pinchSpan()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val focusX = event.focusX()
                        val focusY = event.focusY()
                        val span = event.pinchSpan()
                        if (lastPinchSpan > 0f && span > 0f) {
                            val scale = (lastPinchSpan / span).coerceIn(0.92f, 1.08f)
                            orbitCamera.zoom(scale)
                        }
                        orbitCamera.pan(focusX - lastFocusX, focusY - lastFocusY)
                        orbitCamera.update()
                        lastFocusX = focusX
                        lastFocusY = focusY
                        lastPinchSpan = span
                    } else if (!multiTouch) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        orbitCamera.rotate(dx, dy)
                        lastX = event.x
                        lastY = event.y
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    lastPinchSpan = 0f
                }

                MotionEvent.ACTION_UP -> {
                    val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                    if (!multiTouch && moved < tapSlop && event.eventTime - downTime < 350L) {
                        selectAt(event.x.toInt(), event.y.toInt())
                    }
                    multiTouch = false
                    lastPinchSpan = 0f
                }

                MotionEvent.ACTION_CANCEL -> {
                    multiTouch = false
                    lastPinchSpan = 0f
                }
            }
            true
        }
    }

    /** 在 filament 线程上创建 ModelViewer 并绑定当前模型场景。 */
    private fun createViewer(surfaceView: SurfaceView) {
        if (modelViewer != null) return
        val gen = renderGeneration.get()
        filamentScope.launch {
            if (renderGeneration.get() != gen) return@launch
            val viewer = ModelViewer(surfaceView, manipulator = null)
            val environment = runCatching { StudioEnvironment.create(viewer.engine) }
                .onFailure {
                    Log.e(
                        "FilamentPreview",
                        "Studio IBL creation failed; using fallback lighting",
                        it
                    )
                }
                .getOrElse { StudioEnvironment.createFallback(viewer.engine) }
            studioEnvironment = environment
            viewer.scene.skybox = environment.skybox
            viewer.scene.indirectLight = environment.indirectLight
            configureFilamentView(viewer)
            modelViewer = viewer
            // 恢复已加载模型的场景（若有）。
            orbitCamera.update()
            withContext(Dispatchers.Main) {
                if (meshes.isNotEmpty()) applyPreparedModelStateFromViewer()
                if (!renderingPaused) choreographer.postFrameCallback(frameCallback)
            }
        }
    }

    /** 在 filament 线程上销毁 viewer，序列化执行。 */
    private fun destroyViewer() {
        modelViewer?.let { viewer ->
            runCatching { viewer.scene.skybox = null }
            runCatching { viewer.scene.indirectLight = null }
            studioEnvironment?.let { runCatching { it.destroy(viewer.engine) } }
            studioEnvironment = null
            runCatching { viewer.destroy() }
        }
        modelViewer = null
    }

    /** viewer 重建后，在主线程重新触发当前场景的模型加载，复用现有构建管线。 */
    private fun applyPreparedModelStateFromViewer() {
        if (meshes.isEmpty() && loadedPlacedMeshes.isEmpty()) return
        requestModelBuild(
            scenePreparation = {
                ScenePreparer.prepare(buildPlateConfig, loadedPlacedMeshes.ifEmpty { emptyList() }.let { placed ->
                    when {
                        previewMode == PreviewMode.Plate -> plates.getOrNull(selectedPlateIndex)?.meshes
                            ?: placed

                        plates.size >= 2 -> placed.arrangedForAllPreview(plates)
                        else -> placed
                    }
                })
            },
            replaceSceneMeshes = false,
            resetCamera = false,
            cacheKey = currentViewCacheKey(),
        )
    }

    /** 加载新模型前清空渲染场景，避免短暂显示上一个模型。 */
    private fun clearViewerScene() {
        filamentScope.launch { modelViewer?.destroyModel() }
        meshes = emptyList()
        loadedPlacedMeshes = emptyList()
        plates = emptyList()
        basePlateEntities = IntArray(0)
        modelEntities = emptyList()
        markerEntities = emptyList()
        entityToMeshIndex = emptyMap()
        axisLabels = emptyList()
    }

    private fun load3mf(uri: Uri) {
        // A result prepared for the previous file must never replace the newly selected model.
        modelBuildPipeline.modelBuildGeneration.incrementAndGet()
        modelBuildPipeline.viewCache.clear()
        GlbSceneBuilder.clearCache()
        colorVersion = 0
        modelBuildPipeline.prewarmLoadId++
        isLoading = true
        clearViewerScene()
        status = ""
        selectedIndex = null
        selectedIndices = emptySet()
        modelColorController.clearForNewModel()
        editableColors = emptyList()
        printAreaWarning = null
        thread(name = "3mf-loader") {
            runCatching {
                ThreeMfLoader.load(this, uri, buildPlateConfig) {
                    getString(R.string.plate_name, it)
                }
            }.onSuccess { loaded ->
                runOnUiThread {
                    loadedFileName = loaded.fileName
                    loadedPlacedMeshes = loaded.meshes
                    plates = loaded.plates
                    previewMode =
                        if (loaded.plates.isNotEmpty()) PreviewMode.Plate else PreviewMode.All
                    selectedPlateIndex = 0
                    if (loaded.initialSceneMeshes != null) {
                        meshes = loaded.initialSceneMeshes
                        selectedIndex = null
                        selectedIndices = emptySet()
                        orbitCamera.reset()
                        reloadModel()
                        updateStatus()
                    } else {
                        applyCurrentPreview()
                    }
                    prewarmViews()
                }
            }.onFailure { error ->
                Log.d("onFailure", error.message ?: error.javaClass.simpleName)
                runOnUiThread {
                    isLoading = false
                    status = getString(R.string.status_load_failed)
                    Toast.makeText(
                        this,
                        error.message ?: error.javaClass.simpleName,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun selectAt(x: Int, y: Int) {
        val surface = filamentSurface ?: return
        if (meshes.isEmpty()) return
        val pickY = surface.height - y
        filamentScope.launch {
            val viewer = modelViewer ?: return@launch
            viewer.view.pick(x, pickY, pickHandler) { result ->
                val hit = entityToMeshIndex[result.renderable] ?: return@pick
                val hitMesh = meshes.getOrNull(hit) ?: return@pick
                val groupId = hitMesh.topLevelObjectId
                if (groupId != null) {
                    val group =
                        meshes.indices.filter { meshes[it].topLevelObjectId == groupId }.toSet()
                    if (hit in selectedIndices) {
                        selectedIndex = null
                        selectedIndices = emptySet()
                    } else {
                        selectedIndex = hit
                        selectedIndices = group
                    }
                } else {
                    selectedIndex = if (hit == selectedIndex) null else hit
                    selectedIndices = selectedIndex?.let { setOf(it) } ?: emptySet()
                }
                applyMarkerVisibility()
                updateAxisLabels()
            }
        }
    }

    private fun applyCurrentPreview() {
        if (loadedPlacedMeshes.isEmpty()) return
        val requestedMode = previewMode
        val selectedPlate = plates.getOrNull(selectedPlateIndex)
        val allPlacedMeshes = loadedPlacedMeshes
        val currentPlates = plates
        selectedIndex = null
        selectedIndices = emptySet()
        requestModelBuild(
            scenePreparation = {
                val placed = when {
                    requestedMode == PreviewMode.Plate && selectedPlate != null -> selectedPlate.meshes
                    currentPlates.size >= 2 -> allPlacedMeshes.arrangedForAllPreview(currentPlates)
                    else -> allPlacedMeshes
                }
                ScenePreparer.prepare(buildPlateConfig, placed)
            },
            replaceSceneMeshes = true,
            resetCamera = true,
            cacheKey = currentViewCacheKey(),
        )
    }

    private fun currentViewCacheKey(): String? {
        if (previewMode == PreviewMode.Plate) {
            val plate = plates.getOrNull(selectedPlateIndex) ?: return null
            return "file:$loadedFileName:plate:${plate.index}:bp:$buildPlateVersion:$colorVersion"
        }
        return "file:$loadedFileName:all:bp:$buildPlateVersion:$colorVersion"
    }

    private fun List<PlacedMeshData>.toSceneMeshes(): List<SceneMesh> =
        ScenePreparer.prepare(buildPlateConfig, this).meshes

    private fun updateStatus() {
        val fileName = loadedFileName.ifBlank { "selected.3mf" }
        val triangleCount = meshes.sumOf { it.indices.size / 3 }
        val modeText = if (previewMode == PreviewMode.Plate && plates.isNotEmpty()) {
            plates.getOrNull(selectedPlateIndex)?.name ?: getString(R.string.mode_by_plate)
        } else {
            getString(R.string.mode_all)
        }
        val topIds = meshes.mapNotNull { it.topLevelObjectId }.distinct()
        Log.d(
            "ThreeMfDebug",
            "updateStatus: meshes=${meshes.size}, topLevelIds=$topIds, plates=${plates.size}"
        )
        val modelCount = topIds.count().takeIf { it > 0 } ?: meshes.size
        status = String.format(
            Locale.US,
            "%s: %s, %d model(s), %d triangles",
            fileName,
            modeText,
            modelCount,
            triangleCount,
        )
    }

    private fun reloadModel() {
        val currentMeshes = meshes
        modelColorController.replaceAvailableSlots(currentMeshes)
        refreshEditableColors()
        requestModelBuild(
            scenePreparation = {
                ScenePreparer.prepare(buildPlateConfig, loadedPlacedMeshes.ifEmpty { emptyList() }.let { placed ->
                    when {
                        previewMode == PreviewMode.Plate -> plates.getOrNull(selectedPlateIndex)?.meshes
                            ?: placed

                        plates.size >= 2 -> placed.arrangedForAllPreview(plates)
                        else -> placed
                    }
                })
            },
            replaceSceneMeshes = false,
            resetCamera = false,
            cacheKey = currentViewCacheKey(),
        )
    }

    private fun requestModelBuild(
        scenePreparation: () -> ScenePreparation,
        replaceSceneMeshes: Boolean,
        resetCamera: Boolean,
        cacheKey: String? = null,
    ) {
        modelBuildPipeline.requestBuild(
            scenePreparation = scenePreparation,
            cacheKey = cacheKey,
        ) { model, requestedAt, fromCache ->
            applyPreparedModelState(
                model,
                replaceSceneMeshes,
                resetCamera,
                requestedAt,
                fromCache,
            )
        }
    }

    private fun applyPreparedModelState(
        model: PreparedModel,
        replaceSceneMeshes: Boolean,
        resetCamera: Boolean,
        requestedAt: Long,
        fromCache: Boolean,
    ) {
        if (replaceSceneMeshes) meshes = model.meshes
        modelColorController.replaceAvailableSlots(model.meshes)
        refreshEditableColors()
        if (resetCamera) orbitCamera.reset()
        applyPreparedModel(model, requestedAt)
        printAreaWarning = model.printAreaCheck?.takeIf { !it.isInside }?.let {
            getString(R.string.warn_out_of_print_area)
        }
        updateStatus()
        if (fromCache) {
            Log.d("ThreeMfPerf", "view cache hit: ${model.meshes.size} meshes")
        }
    }

    private fun prewarmViews() {
        val platesSnapshot = plates
        val placedSnapshot = loadedPlacedMeshes
        val currentKey = currentViewCacheKey()
        val keys = buildList {
            val allKey = "file:$loadedFileName:all:bp:$buildPlateVersion:$colorVersion"
            if (allKey != currentKey) add(allKey)
            platesSnapshot.forEach { plate ->
                val key = "file:$loadedFileName:plate:${plate.index}:bp:$buildPlateVersion:$colorVersion"
                if (key != currentKey) add(key)
            }
        }.distinct()
        modelBuildPipeline.prewarm(
            keys = keys,
            buildPlateConfig = buildPlateConfig,
            placedSnapshot = placedSnapshot,
            platesSnapshot = platesSnapshot,
        )
    }

    private fun applyPreparedModel(model: PreparedModel, requestedAt: Long) {
        val loadStart = System.currentTimeMillis()
        val loadGeneration = modelBuildPipeline.modelBuildGeneration.get()
        filamentScope.launch {
            if (modelBuildPipeline.modelBuildGeneration.get() != loadGeneration) return@launch
            val viewer = modelViewer ?: return@launch
            viewer.destroyModel()
            model.glb?.rewind()?.let { viewer.loadModelGlb(it) }
            val loadDone = System.currentTimeMillis()
            Log.d(
                "ThreeMfPerf",
                "createFilament: ${loadDone - requestedAt} ms " +
                        "(transform=${model.transformMs} ms, GLB=${model.buildMs} ms, load=${loadDone - loadStart} ms)",
            )
            withContext(Dispatchers.Main) {
                orbitCamera.update()
                cacheSceneEntities()
                applySceneVisibility()
                updateAxisLabels()
                isLoading = false
            }
        }
    }

    private fun configureFilamentView(viewer: ModelViewer) {
        val msaa = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
        }
        viewer.view.multiSampleAntiAliasingOptions = msaa
        viewer.view.antiAliasing = View.AntiAliasing.FXAA
        viewer.view.temporalAntiAliasingOptions = View.TemporalAntiAliasingOptions().apply {
            enabled = true
        }
        viewer.view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = true
            // Scene geometry is normalized to a maximum span of 2, so keep AO local to small cavities.
            radius = 0.06f
            intensity = 0.32f
            power = 1.0f
            quality = View.QualityLevel.HIGH
            lowPassFilter = View.QualityLevel.HIGH
            upsampling = View.QualityLevel.HIGH
        }
        viewer.view.isTransparentPickingEnabled = false

        val lightManager = viewer.engine.lightManager
        if (lightManager.hasComponent(viewer.light)) {
            val light = lightManager.getInstance(viewer.light)
            lightManager.setColor(light, 1.0f, 0.98f, 0.94f)
            lightManager.setIntensity(light, 34_000f)
            lightManager.setDirection(light, -0.45f, -0.55f, -1.0f)
            lightManager.setShadowCaster(light, false)
        }
    }

    private fun cacheSceneEntities() {
        val asset = modelViewer?.asset ?: return
        val renderables = asset.renderableEntities
        val renderablesByName = renderables.groupBy { entity -> asset.getName(entity) }
        val plateNames = listOf(
            GlbSceneBuilder.BASE_PLATE_NODE,
            GlbSceneBuilder.BASE_PLATE_BOUNDARY_NODE,
            GlbSceneBuilder.BASE_PLATE_TEXT_NODE,
        )
        basePlateEntities = plateNames.flatMap { name ->
            renderablesByName[name] ?: asset.getEntitiesByName(name).toList()
        }.toIntArray()
        modelEntities = meshes.indices.map { index ->
            renderablesByName[GlbSceneBuilder.meshNodeName(index)]?.toIntArray()
                ?: renderables.getOrNull(index)?.let { intArrayOf(it) }
                ?: asset.getEntitiesByName(GlbSceneBuilder.meshNodeName(index))
        }
        markerEntities = meshes.indices.map { index ->
            renderablesByName[GlbSceneBuilder.markerNodeName(index)]?.toIntArray()
                ?: renderables.getOrNull(meshes.size + 1 + index)?.let { intArrayOf(it) }
                ?: asset.getEntitiesByName(GlbSceneBuilder.markerNodeName(index))
        }
        entityToMeshIndex = buildMap {
            modelEntities.forEachIndexed { index, entities -> entities.forEach { put(it, index) } }
            markerEntities.forEachIndexed { index, entities -> entities.forEach { put(it, index) } }
        }
    }

    private fun applySceneVisibility() {
        applyBasePlateVisibility()
        applyMarkerVisibility()
    }

    private fun applyBasePlateVisibility() {
        filamentScope.launch {
            setEntitiesVisible(basePlateEntities, showBasePlate)
        }
    }

    private fun applyMarkerVisibility() {
        filamentScope.launch {
            markerEntities.forEachIndexed { index, entities ->
                setMarkerEntitiesVisible(entities, index == selectedIndex)
            }
        }
    }

    private fun setMarkerEntitiesVisible(entities: IntArray, visible: Boolean) {
        if (entities.isEmpty()) return
        val transformManager = modelViewer?.engine?.transformManager ?: return
        val transform = if (visible) IDENTITY_TRANSFORM else HIDDEN_TRANSFORM
        entities.forEach { entity ->
            if (transformManager.hasComponent(entity)) {
                transformManager.setTransform(transformManager.getInstance(entity), transform)
            }
        }
    }

    private fun updateAxisLabels() {
        val currentSelectedIndex = selectedIndex
        val currentMeshes = meshes
        filamentScope.launch {
            val viewer = modelViewer ?: run {
                withContext(Dispatchers.Main) { axisLabels = emptyList() }
                return@launch
            }
            val surface = filamentSurface ?: run {
                withContext(Dispatchers.Main) { axisLabels = emptyList() }
                return@launch
            }
            val mesh = currentSelectedIndex?.let { currentMeshes.getOrNull(it) }
                ?: run {
                    withContext(Dispatchers.Main) { axisLabels = emptyList() }
                    return@launch
                }
            if (surface.width == 0 || surface.height == 0) {
                withContext(Dispatchers.Main) { axisLabels = emptyList() }
                return@launch
            }
            val bounds = mesh.renderBounds
            val span = maxOf(bounds.size.x, bounds.size.y, bounds.size.z)
            val pad = maxOf(0.03f, span * 0.08f)
            val frontX = bounds.max.x
            val frontY = bounds.min.y
            val labels = listOfNotNull(
                projectLabel(
                    viewer,
                    surface,
                    "X",
                    Vec3(bounds.center.x, frontY - pad, bounds.max.z + pad),
                    Color(0xFFE11D48),
                ),
                projectLabel(
                    viewer,
                    surface,
                    "Y",
                    Vec3(frontX + pad, bounds.center.y, bounds.max.z + pad),
                    Color(0xFF16A34A),
                ),
                projectLabel(
                    viewer,
                    surface,
                    "Z",
                    Vec3(frontX + pad, frontY - pad, bounds.center.z),
                    Color(0xFF2563EB),
                ),
            )
            withContext(Dispatchers.Main) { axisLabels = labels }
        }
    }

    private fun projectLabel(
        viewer: ModelViewer,
        surface: SurfaceView,
        text: String,
        point: Vec3,
        color: Color
    ): AxisLabel? {
        val view = viewer.camera.getViewMatrix(FloatArray(16))
        val projection = viewer.camera.getProjectionMatrix(DoubleArray(16))
        val eye = multiply(
            view,
            doubleArrayOf(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(), 1.0)
        )
        val clip = multiply(projection, eye)
        if (clip[3] <= 0.0001) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        if (ndcX !in -1.4..1.4 || ndcY !in -1.4..1.4) return null
        val x = ((ndcX * 0.5 + 0.5) * surface.width).toFloat().coerceIn(18f, surface.width - 18f)
        val y = ((1.0 - (ndcY * 0.5 + 0.5)) * surface.height).toFloat()
            .coerceIn(18f, surface.height - 18f)
        return AxisLabel(text, x, y, color)
    }

    private fun multiply(matrix: FloatArray, vector: DoubleArray): DoubleArray =
        DoubleArray(4) { row ->
            matrix[row].toDouble() * vector[0] +
                    matrix[4 + row].toDouble() * vector[1] +
                    matrix[8 + row].toDouble() * vector[2] +
                    matrix[12 + row].toDouble() * vector[3]
        }

    private fun multiply(matrix: DoubleArray, vector: DoubleArray): DoubleArray =
        DoubleArray(4) { row ->
            matrix[row] * vector[0] +
                    matrix[4 + row] * vector[1] +
                    matrix[8 + row] * vector[2] +
                    matrix[12 + row] * vector[3]
        }

    private fun setEntitiesVisible(entities: IntArray, visible: Boolean) {
        if (entities.isEmpty()) return
        val scene = modelViewer?.scene ?: return
        if (visible) {
            scene.addEntities(entities)
        } else {
            scene.removeEntities(entities)
        }
    }

    companion object {
        private val IDENTITY_TRANSFORM = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )
        private val HIDDEN_TRANSFORM = floatArrayOf(
            0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        )
        private const val CAMERA_VERTICAL_FOV_DEGREES = 45.0
        private const val PAN_SENSITIVITY = 1.0
    }
}

private fun MotionEvent.pinchSpan(): Float {
    if (pointerCount < 2) return 0f
    return hypot((getX(0) - getX(1)).toDouble(), (getY(0) - getY(1)).toDouble()).toFloat()
}

private fun MotionEvent.focusX(): Float {
    var sum = 0f
    for (i in 0 until pointerCount) sum += getX(i)
    return sum / pointerCount
}

private fun MotionEvent.focusY(): Float {
    var sum = 0f
    for (i in 0 until pointerCount) sum += getY(i)
    return sum / pointerCount
}