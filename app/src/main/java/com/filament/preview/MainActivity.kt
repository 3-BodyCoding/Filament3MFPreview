package com.filament.preview

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.filament.preview.ui.theme.FilamentPreviewTheme
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import io.lib3mf.android.open3mf
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class MainActivity : ComponentActivity() {
    private var modelViewer: ModelViewer? = null
    private var studioEnvironment: StudioEnvironment? = null
    private var filamentSurface: SurfaceView? = null
    private val pickHandler by lazy { Handler(Looper.getMainLooper()) }
    private val choreographer by lazy { Choreographer.getInstance() }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            modelViewer?.render(frameTimeNanos)
            updateAxisLabels()
        }
    }

    private var isLoading by mutableStateOf(false)
    private var status by mutableStateOf("选择一个 3MF 文件开始预览")
    private var meshes by mutableStateOf<List<SceneMesh>>(emptyList())
    private var loadedFileName by mutableStateOf("")
    private var loadedPlacedMeshes by mutableStateOf<List<PlacedMeshData>>(emptyList())
    private var plates by mutableStateOf<List<PlatePreview>>(emptyList())
    private var previewMode by mutableStateOf(PreviewMode.All)
    private var selectedPlateIndex by mutableIntStateOf(0)
    private var selectedIndex by mutableStateOf<Int?>(null)
    private var selectedIndices by mutableStateOf(emptySet<Int>())
    private var showBasePlate by mutableStateOf(true)
    private var editableColors by mutableStateOf<List<EditableColorState>>(emptyList())
    private var basePlateEntities = IntArray(0)
    private var modelEntities = emptyList<IntArray>()
    private var markerEntities = emptyList<IntArray>()
    private var entityToMeshIndex = emptyMap<Int, Int>()
    private var axisLabels by mutableStateOf<List<AxisLabel>>(emptyList())
    private var orbitYaw = atan2(-3.2, 0.0)
    private var orbitPitch = asin(2.2 / sqrt(3.2 * 3.2 + 2.2 * 2.2))
    private var orbitRadius = sqrt(3.2 * 3.2 + 2.2 * 2.2)
    private var orbitTarget = Vec3(0.0f, 0.0f, 0.0f)
    private val modelColorController = ModelColorController {
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

    private fun refreshEditableColors() {
        editableColors = modelColorController.getColorSlots().map { slot ->
            EditableColorState(
                slot = slot,
                currentColor = modelColorController.getEffectiveColor(slot.id) ?: slot.originalColor,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Utils.init()
        setContent {
            FilamentPreviewTheme {
                PreviewScreen(
                    loading = isLoading,
                    status = status,
                    selectedName = selectedIndex?.let { meshes.getOrNull(it)?.name },
                    selectedLengths = meshes.selectedXyzLengths(selectedIndex),
                    showBasePlate = showBasePlate,
                    editableColors = editableColors,
                    previewMode = previewMode,
                    plates = plates,
                    selectedPlateIndex = selectedPlateIndex,
                    axisLabels = axisLabels,
                    onPickFile = ::load3mf,
                    onColorChange = ::setEditableColor,
                    onColorReset = ::resetEditableColor,
                    onAllColorsReset = ::resetAllEditableColors,
                    onPreviewModeChange = { mode -> previewMode = mode; applyCurrentPreview() },
                    onPlateChange = { index -> selectedPlateIndex = index; applyCurrentPreview() },
                    onBasePlateChange = { showBasePlate = it; applyBasePlateVisibility() },
                    surfaceFactory = { surface -> setupFilament(surface) },
                )
            }
        }
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
        modelViewer?.let { viewer ->
            viewer.scene.skybox = null
            viewer.scene.indirectLight = null
            studioEnvironment?.destroy(viewer.engine)
            viewer.destroy()
        }
        studioEnvironment = null
        modelViewer = null
    }

    private fun setupFilament(surfaceView: SurfaceView) {
        if (modelViewer != null) return
        filamentSurface = surfaceView
        modelViewer = ModelViewer(surfaceView, manipulator = null).also { viewer ->
            val environment = runCatching { StudioEnvironment.create(viewer.engine) }
                .onFailure { Log.e("FilamentPreview", "Studio IBL creation failed; using fallback lighting", it) }
                .getOrElse { StudioEnvironment.createFallback(viewer.engine) }
            studioEnvironment = environment
            viewer.scene.skybox = environment.skybox
            viewer.scene.indirectLight = environment.indirectLight
            configureFilamentView(viewer)
            updateOrbitCamera()
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
                            orbitRadius = (orbitRadius * scale).coerceIn(0.65, 12.0)
                        }
                        panOrbitTarget(focusX - lastFocusX, focusY - lastFocusY)
                        updateOrbitCamera()
                        lastFocusX = focusX
                        lastFocusY = focusY
                        lastPinchSpan = span
                    } else if (!multiTouch) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        orbitYaw -= dx * 0.008
                        orbitPitch = (orbitPitch + dy * 0.008).coerceIn(-PI / 2.0 + 0.04, PI / 2.0 - 0.04)
                        updateOrbitCamera()
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

    private fun updateOrbitCamera() {
        val viewer = modelViewer ?: return
        val cp = cos(orbitPitch)
        val eyeX = orbitTarget.x + orbitRadius * cp * cos(orbitYaw)
        val eyeY = orbitTarget.y + orbitRadius * cp * sin(orbitYaw)
        val eyeZ = orbitTarget.z + orbitRadius * sin(orbitPitch)
        viewer.camera.lookAt(
            eyeX, eyeY, eyeZ,
            orbitTarget.x.toDouble(), orbitTarget.y.toDouble(), orbitTarget.z.toDouble(),
            0.0, 0.0, 1.0,
        )
    }

    private fun resetOrbitCamera() {
        orbitYaw = atan2(-3.2, 0.0)
        orbitPitch = asin(2.2 / sqrt(3.2 * 3.2 + 2.2 * 2.2))
        orbitRadius = sqrt(3.2 * 3.2 + 2.2 * 2.2)
        orbitTarget = Vec3(0.0f, 0.0f, 0.0f)
        updateOrbitCamera()
    }

    private fun panOrbitTarget(dx: Float, dy: Float) {
        val surfaceHeight = filamentSurface?.height?.takeIf { it > 0 } ?: return
        val cp = cos(orbitPitch)
        val eyeOffset = Vec3(
            (orbitRadius * cp * cos(orbitYaw)).toFloat(),
            (orbitRadius * cp * sin(orbitYaw)).toFloat(),
            (orbitRadius * sin(orbitPitch)).toFloat(),
        )
        val forward = (eyeOffset * -1.0f).normalizedOr(Vec3(0.0f, 1.0f, 0.0f))
        val right = forward.cross(Vec3(0.0f, 0.0f, 1.0f)).normalizedOr(Vec3(1.0f, 0.0f, 0.0f))
        val up = right.cross(forward).normalizedOr(Vec3(0.0f, 0.0f, 1.0f))
        val visibleWorldHeight = 2.0 * orbitRadius * tan(Math.toRadians(CAMERA_VERTICAL_FOV_DEGREES * 0.5))
        val panScale = (visibleWorldHeight / surfaceHeight * PAN_SENSITIVITY).toFloat()
        orbitTarget = orbitTarget + right * (-dx * panScale) + up * (dy * panScale)
    }

    private fun load3mf(uri: Uri) {
        isLoading = true
        status = "Loading..."
        selectedIndex = null
        selectedIndices = emptySet()
        modelColorController.clearForNewModel()
        editableColors = emptyList()
        thread(name = "3mf-loader") {
            runCatching {
                val source = copyToCache(uri)
                val usePlateLogic = ThreeMfBuildParser.hasExplicitMultiplePlates(source)
                val perfStart = System.currentTimeMillis()
                val loaded = open3mf(source.absolutePath).use { document ->
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
                        .associate { it.resourceId to document.getComponents(it.resourceId).toList() }
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
                    val meshesByObjectId = rawMeshes.associate { (info, mesh) -> info.resourceId to mesh }
                    val placedMeshes = ThreeMfBuildParser.placedMeshes(
                        file = source,
                        buildItems = buildItems,
                        componentsByObjectId = componentsByObjectId,
                        meshesByObjectId = meshesByObjectId,
                        namesByObjectId = namesByObjectId,
                    ).ifEmpty {
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
                            initialSceneMeshes = placedMeshes.toSceneMeshes(),
                        )
                    } else {
                        Loaded3mf(source.name, placedMeshes, placedMeshes.detectPlatePreviews(), initialSceneMeshes = null)
                    }
                }
                loaded
            }.onSuccess { loaded ->
                runOnUiThread {
                    loadedFileName = loaded.fileName
                    loadedPlacedMeshes = loaded.meshes
                    plates = loaded.plates
                    previewMode = if (loaded.plates.isNotEmpty()) PreviewMode.Plate else PreviewMode.All
                    selectedPlateIndex = 0
                    if (loaded.initialSceneMeshes != null) {
                        meshes = loaded.initialSceneMeshes
                        selectedIndex = null
                        selectedIndices = emptySet()
                        resetOrbitCamera()
                        reloadModel()
                        updateStatus()
                    } else {
                        applyCurrentPreview()
                    }
                    isLoading = false
                }
            }.onFailure { error ->
                Log.d("onFailure", error.message ?: error.javaClass.simpleName)
                runOnUiThread {
                    isLoading = false
                    status = "加载失败"
                    Toast.makeText(
                        this,
                        error.message ?: error.javaClass.simpleName,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex("_display_name")
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "selected.3mf"
        val target = File(cacheDir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun selectAt(x: Int, y: Int) {
        val viewer = modelViewer ?: return
        val surface = filamentSurface ?: return
        if (meshes.isEmpty()) return
        val pickY = surface.height - y
        viewer.view.pick(x, pickY, pickHandler) { result ->
            val hit = entityToMeshIndex[result.renderable] ?: return@pick
            val hitMesh = meshes.getOrNull(hit) ?: return@pick
            val groupId = hitMesh.topLevelObjectId
            if (groupId != null) {
                val group = meshes.indices.filter { meshes[it].topLevelObjectId == groupId }.toSet()
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

    private fun applyCurrentPreview() {
        if (loadedPlacedMeshes.isEmpty()) return
        meshes = runCatching {
            val selectedPlate = plates.getOrNull(selectedPlateIndex)
            val placed = when {
                previewMode == PreviewMode.Plate && selectedPlate != null -> {
                    selectedPlate.meshes
                }
                plates.size >= 2 -> loadedPlacedMeshes.arrangedForAllPreview(plates)
                else -> loadedPlacedMeshes
            }
            placed.toSceneMeshes()
        }.getOrElse {
            runCatching { loadedPlacedMeshes.toSceneMeshes() }.getOrDefault(emptyList())
        }
        selectedIndex = null
        resetOrbitCamera()
        reloadModel()
        updateStatus()
    }

    private fun List<PlacedMeshData>.toSceneMeshes(): List<SceneMesh> {
        if (isEmpty()) return emptyList()
        val (center, scale) = placedNormalization()
        val topLevelCounts = groupingBy { it.topLevelObjectId }.eachCount()
        Log.d("ThreeMfDebug", "toSceneMeshes: placed=${size}, topLevelCounts=$topLevelCounts")
        return flatMap { placed ->
            Log.d(
                "ThreeMfDebug",
                "Filament mesh: objectId=${placed.mesh.objectId} " +
                    "topLevelObjectId=${placed.topLevelObjectId} " +
                    "componentPath=${placed.objectPath.joinToString(" -> ")} " +
                    "world3mf=${placed.transform.debugString()} " +
                    "finalFilament=${placed.transform.toFilamentMatrix(center, scale, placed.previewOffset).debugMatrix()} " +
                    "nodeTransform=identity (transform baked into POSITION)",
            )
            // Plate and all-preview layouts share MeshData, so source vertices must remain immutable.
            placed.toSceneMeshes(center, scale)
        }
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

    private fun updateStatus() {
        val fileName = loadedFileName.ifBlank { "selected.3mf" }
        val triangleCount = meshes.sumOf { it.indices.size / 3 }
        val modeText = if (previewMode == PreviewMode.Plate && plates.isNotEmpty()) {
            plates.getOrNull(selectedPlateIndex)?.name ?: "按盘"
        } else {
            "全部"
        }
        val topIds = meshes.mapNotNull { it.topLevelObjectId }.distinct()
        Log.d("ThreeMfDebug", "updateStatus: meshes=${meshes.size}, topLevelIds=$topIds, plates=${plates.size}")
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
        val viewer = modelViewer ?: return
        if (currentMeshes.isEmpty()) {
            viewer.destroyModel()
            cacheSceneEntities()
            updateAxisLabels()
            return
        }
        viewer.destroyModel()
        modelColorController.replaceAvailableSlots(currentMeshes)
        refreshEditableColors()
        val tFilamentStart = System.currentTimeMillis()
        val glb = GlbSceneBuilder.build(
            currentMeshes,
            null,
            modelColorController.overrideSnapshot(),
        )
        val tGlbDone = System.currentTimeMillis()
        viewer.loadModelGlb(glb)
        val tLoadDone = System.currentTimeMillis()
        Log.d("ThreeMfPerf", "createFilament: ${tLoadDone - tFilamentStart} ms (GLB=${tGlbDone - tFilamentStart} ms, load=${tLoadDone - tGlbDone} ms)")
        updateOrbitCamera()
        cacheSceneEntities()
        applySceneVisibility()
        updateAxisLabels()
    }

    private fun configureFilamentView(viewer: ModelViewer) {
        val msaa = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
        }
        viewer.view.multiSampleAntiAliasingOptions = msaa
        viewer.view.antiAliasing = View.AntiAliasing.FXAA
        viewer.view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = true
            radius = 0.28f
            intensity = 0.45f
            power = 0.8f
            quality = View.QualityLevel.LOW
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
        basePlateEntities = renderablesByName[GlbSceneBuilder.BASE_PLATE_NODE]?.toIntArray()
            ?: renderables.getOrNull(meshes.size)?.let { intArrayOf(it) }
            ?: asset.getEntitiesByName(GlbSceneBuilder.BASE_PLATE_NODE)
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
        setEntitiesVisible(basePlateEntities, showBasePlate)
    }

    private fun applyMarkerVisibility() {
        markerEntities.forEachIndexed { index, entities ->
            setMarkerEntitiesVisible(entities, index == selectedIndex)
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
        val viewer = modelViewer ?: run { axisLabels = emptyList(); return }
        val surface = filamentSurface ?: run { axisLabels = emptyList(); return }
        val mesh = selectedIndex?.let { meshes.getOrNull(it) } ?: run { axisLabels = emptyList(); return }
        if (surface.width == 0 || surface.height == 0) {
            axisLabels = emptyList()
            return
        }
        val bounds = mesh.renderBounds
        val span = maxOf(bounds.size.x, bounds.size.y, bounds.size.z)
        val pad = maxOf(0.03f, span * 0.08f)
        val frontX = bounds.max.x
        val frontY = bounds.min.y
        axisLabels = listOfNotNull(
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
    }

    private fun projectLabel(viewer: ModelViewer, surface: SurfaceView, text: String, point: Vec3, color: Color): AxisLabel? {
        val view = viewer.camera.getViewMatrix(FloatArray(16))
        val projection = viewer.camera.getProjectionMatrix(DoubleArray(16))
        val eye = multiply(view, doubleArrayOf(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(), 1.0))
        val clip = multiply(projection, eye)
        if (clip[3] <= 0.0001) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        if (ndcX !in -1.4..1.4 || ndcY !in -1.4..1.4) return null
        val x = ((ndcX * 0.5 + 0.5) * surface.width).toFloat().coerceIn(18f, surface.width - 18f)
        val y = ((1.0 - (ndcY * 0.5 + 0.5)) * surface.height).toFloat().coerceIn(18f, surface.height - 18f)
        return AxisLabel(text, x, y, color)
    }

    private fun multiply(matrix: FloatArray, vector: DoubleArray): DoubleArray = DoubleArray(4) { row ->
        matrix[row].toDouble() * vector[0] +
            matrix[4 + row].toDouble() * vector[1] +
            matrix[8 + row].toDouble() * vector[2] +
            matrix[12 + row].toDouble() * vector[3]
    }

    private fun multiply(matrix: DoubleArray, vector: DoubleArray): DoubleArray = DoubleArray(4) { row ->
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
        val EDITOR_COLORS = listOf(
            EditorPaletteColor("白", RgbaColor(1.0f, 1.0f, 1.0f)),
            EditorPaletteColor("灰", RgbaColor(0.45f, 0.48f, 0.52f)),
            EditorPaletteColor("黑", RgbaColor(0.06f, 0.07f, 0.08f)),
            EditorPaletteColor("红", RgbaColor(0.87f, 0.12f, 0.16f)),
            EditorPaletteColor("橙", RgbaColor(0.95f, 0.34f, 0.12f)),
            EditorPaletteColor("黄", RgbaColor(0.96f, 0.78f, 0.10f)),
            EditorPaletteColor("绿", RgbaColor(0.10f, 0.68f, 0.34f)),
            EditorPaletteColor("青", RgbaColor(0.04f, 0.66f, 0.72f)),
            EditorPaletteColor("蓝", RgbaColor(0.10f, 0.42f, 0.95f)),
            EditorPaletteColor("紫", RgbaColor(0.48f, 0.24f, 0.78f)),
        )
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

private fun Vec3.cross(other: Vec3): Vec3 = Vec3(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x,
)

private fun Vec3.normalizedOr(fallback: Vec3): Vec3 {
    val length = sqrt(x * x + y * y + z * z)
    return if (length > 1e-6f) Vec3(x / length, y / length, z / length) else fallback
}

data class AxisLabel(val text: String, val x: Float, val y: Float, val color: Color)

data class EditorPaletteColor(val name: String, val color: RgbaColor)

data class EditableColorState(
    val slot: MaterialSlot,
    val currentColor: RgbaColor,
)

enum class PreviewMode { All, Plate }

private data class Loaded3mf(
    val fileName: String,
    val meshes: List<PlacedMeshData>,
    val plates: List<PlatePreview>,
    val initialSceneMeshes: List<SceneMesh>?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(
    loading: Boolean,
    status: String,
    selectedName: String?,
    selectedLengths: XyzLengths?,
    showBasePlate: Boolean,
    editableColors: List<EditableColorState>,
    previewMode: PreviewMode,
    plates: List<PlatePreview>,
    selectedPlateIndex: Int,
    axisLabels: List<AxisLabel>,
    onPickFile: (Uri) -> Unit,
    onColorChange: (MaterialSlotId, RgbaColor) -> Unit,
    onColorReset: (MaterialSlotId) -> Unit,
    onAllColorsReset: () -> Unit,
    onPreviewModeChange: (PreviewMode) -> Unit,
    onPlateChange: (Int) -> Unit,
    onBasePlateChange: (Boolean) -> Unit,
    surfaceFactory: (SurfaceView) -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickFile)
    }
    var colorDialogOpen by remember { mutableStateOf(false) }
    var plateMenuExpanded by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFEFF3F8))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        picker.launch(
                            arrayOf(
                                "model/3mf",
                                "application/zip",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    }) {
                        Text("选择 3MF")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { colorDialogOpen = true },
                        enabled = editableColors.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (editableColors.isEmpty()) "暂无可编辑颜色" else "编辑模型颜色 (${editableColors.size})")
                    }
                }
                if (plates.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("按盘")
                        Switch(
                            checked = previewMode == PreviewMode.Plate,
                            onCheckedChange = { checked ->
                                onPreviewModeChange(if (checked) PreviewMode.Plate else PreviewMode.All)
                            },
                        )
                        if (previewMode == PreviewMode.Plate) {
                            ExposedDropdownMenuBox(
                                expanded = plateMenuExpanded,
                                onExpandedChange = { plateMenuExpanded = !plateMenuExpanded },
                                modifier = Modifier.weight(1f),
                            ) {
                                val selectedPlate = plates.getOrNull(selectedPlateIndex)
                                TextField(
                                    value = selectedPlate?.let { "${it.name} (${it.meshes.mapNotNull { m -> m.topLevelObjectId }.distinct().size})" } ?: "选择盘",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("当前盘") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(plateMenuExpanded) },
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                                )
                                ExposedDropdownMenu(
                                    expanded = plateMenuExpanded,
                                    onDismissRequest = { plateMenuExpanded = false },
                                ) {
                                    plates.forEachIndexed { index, plate ->
                                        DropdownMenuItem(
                                            text = { Text("${plate.name} (${plate.meshes.mapNotNull { m -> m.topLevelObjectId }.distinct().size})") },
                                            onClick = { onPlateChange(index); plateMenuExpanded = false },
                                        )
                                    }
                                }
                            }
                        } else {
                            Text("全部盘一起预览", color = Color(0xFF475569))
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("底板")
                    Switch(checked = showBasePlate, onCheckedChange = onBasePlateChange)
                    selectedLengths?.let { lengths ->
                        Text(
                            text = "选中: ${selectedName ?: "Object"}  X=${lengths.x.format()}  Y=${lengths.y.format()}  Z=${lengths.z.format()}",
                            color = Color(0xFF1D4ED8),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } ?: Text(
                        "点击模型可显示/隐藏红(X)、绿(Y)、蓝(Z)包围线",
                        color = Color(0xFF475569)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { SurfaceView(context).also(surfaceFactory) },
                    )
                    axisLabels.forEach { label ->
                        Text(
                            text = label.text,
                            color = label.color,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.offset { IntOffset(label.x.roundToInt() - 8, label.y.roundToInt() - 8) },
                        )
                    }
                }
            }
            if (loading) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.White, RoundedCornerShape(18.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading 3MF...")
                }
            }
            if (colorDialogOpen) {
                ColorEditorDialog(
                    colors = editableColors,
                    onDismiss = { colorDialogOpen = false },
                    onColorChange = onColorChange,
                    onColorReset = onColorReset,
                    onAllColorsReset = onAllColorsReset,
                )
            }
        }
    }
}

@Composable
private fun ColorEditorDialog(
    colors: List<EditableColorState>,
    onDismiss: () -> Unit,
    onColorChange: (MaterialSlotId, RgbaColor) -> Unit,
    onColorReset: (MaterialSlotId) -> Unit,
    onAllColorsReset: () -> Unit,
) {
    var selectedSlotId by remember { mutableStateOf<MaterialSlotId?>(colors.firstOrNull()?.slot?.id) }
    val selected = colors.firstOrNull { it.slot.id == selectedSlotId } ?: colors.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型颜色") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("材质", modifier = Modifier.weight(1.2f), color = Color(0xFF64748B))
                    Text("原有颜色", modifier = Modifier.weight(1f), color = Color(0xFF64748B))
                    Text("改变后颜色", modifier = Modifier.weight(1f), color = Color(0xFF64748B))
                }

                colors.forEachIndexed { index, state ->
                    val selectedRow = state.slot.id == selected?.slot?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selectedRow) Color(0xFFE8F0FE) else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text(
                                state.slot.name?.takeIf { it.isNotBlank() } ?: "颜色 ${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${state.slot.triangleCount} triangles",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                            )
                        }
                        ColorValue(
                            color = state.slot.originalColor,
                            modifier = Modifier.weight(1f),
                        )
                        ColorValue(
                            color = state.currentColor,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedSlotId = state.slot.id },
                            emphasized = selectedRow,
                        )
                    }
                }

                selected?.let { state ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "修改：${state.slot.name?.takeIf { it.isNotBlank() } ?: "当前颜色"}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    MainActivity.EDITOR_COLORS.chunked(5).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            rowColors.forEach { palette ->
                                PaletteColorButton(
                                    palette = palette,
                                    selected = palette.color == state.currentColor,
                                    onClick = { onColorChange(state.slot.id, palette.color) },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { onColorReset(state.slot.id) }) {
                        Text("恢复当前颜色")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onAllColorsReset, enabled = colors.isNotEmpty()) {
                Text("全部恢复")
            }
        },
    )
}

@Composable
private fun ColorValue(
    color: RgbaColor,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(color.toComposeColor(), RoundedCornerShape(8.dp))
                .border(
                    width = if (emphasized) 2.dp else 1.dp,
                    color = if (emphasized) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                    shape = RoundedCornerShape(8.dp),
                ),
        )
        Text(color.toHex(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PaletteColorButton(
    palette: EditorPaletteColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(46.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(palette.color.toComposeColor(), RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                    shape = RoundedCornerShape(10.dp),
                ),
        )
        Text(palette.name, style = MaterialTheme.typography.labelSmall)
    }
}

private fun RgbaColor.toComposeColor(): Color = Color(red, green, blue, alpha)

private fun RgbaColor.toHex(): String = String.format(
    Locale.US,
    "#%02X%02X%02X",
    (red * 255.0f + 0.5f).toInt().coerceIn(0, 255),
    (green * 255.0f + 0.5f).toInt().coerceIn(0, 255),
    (blue * 255.0f + 0.5f).toInt().coerceIn(0, 255),
)

private fun Float.format(): String = String.format(Locale.US, "%.2f", this)
