package com.filament.preview

import android.util.Log
import io.lib3mf.android.BuildItemInfo
import io.lib3mf.android.ComponentInfo
import io.lib3mf.android.MeshData
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import kotlin.math.max
import kotlin.math.sqrt

/** A mesh resource after applying the transforms from the 3MF build graph. */
data class PlacedMeshData(
    val mesh: MeshData,
    val name: String,
    val displayColor: FloatArray? = mesh.displayColor,
    val materialLayout: MeshMaterialLayout? = null,
    val topLevelObjectId: Int? = null,
    val buildItemIndex: Int? = null,
    val plateIndex: Int? = null,
    val objectPath: List<Int> = listOf(mesh.objectId),
    val transform: MeshTransform = MeshTransform.IDENTITY,
    val previewOffset: Vec3 = Vec3(0.0f, 0.0f, 0.0f),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PlacedMeshData

        if (topLevelObjectId != other.topLevelObjectId) return false
        if (buildItemIndex != other.buildItemIndex) return false
        if (plateIndex != other.plateIndex) return false
        if (mesh != other.mesh) return false
        if (name != other.name) return false
        if (!displayColor.contentEquals(other.displayColor)) return false
        if (materialLayout != other.materialLayout) return false
        if (objectPath != other.objectPath) return false
        if (transform != other.transform) return false
        if (previewOffset != other.previewOffset) return false

        return true
    }

    override fun hashCode(): Int {
        var result = topLevelObjectId ?: 0
        result = 31 * result + (buildItemIndex ?: 0)
        result = 31 * result + (plateIndex ?: 0)
        result = 31 * result + mesh.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (displayColor?.contentHashCode() ?: 0)
        result = 31 * result + (materialLayout?.hashCode() ?: 0)
        result = 31 * result + objectPath.hashCode()
        result = 31 * result + transform.hashCode()
        result = 31 * result + previewOffset.hashCode()
        return result
    }
}

data class PlatePreview(val index: Int, val name: String, val meshes: List<PlacedMeshData>)

class MeshTransform private constructor(private val values: FloatArray) {
    val isIdentity: Boolean
        get() = this === IDENTITY || values.contentEquals(IDENTITY_VALUES)

    fun transformX(x: Float, y: Float, z: Float): Float = values[0] * x + values[3] * y + values[6] * z + values[9]
    fun transformY(x: Float, y: Float, z: Float): Float = values[1] * x + values[4] * y + values[7] * z + values[10]
    fun transformZ(x: Float, y: Float, z: Float): Float = values[2] * x + values[5] * y + values[8] * z + values[11]

    fun apply(point: Vec3): Vec3 = Vec3(
        transformX(point.x, point.y, point.z),
        transformY(point.x, point.y, point.z),
        transformZ(point.x, point.y, point.z),
    )

    /**
     * 3MF serializes a row-vector 4x3 affine matrix as
     * m00 m01 m02, m10 m11 m12, m20 m21 m22, m30 m31 m32.
     * This prints the equivalent column-vector 4x4 matrix used by Filament.
     */
    fun debugString(): String = String.format(
        Locale.US,
        "[[%.5f,%.5f,%.5f,%.5f],[%.5f,%.5f,%.5f,%.5f],[%.5f,%.5f,%.5f,%.5f],[0,0,0,1]]",
        values[0], values[3], values[6], values[9],
        values[1], values[4], values[7], values[10],
        values[2], values[5], values[8], values[11],
    )

    override fun toString(): String = debugString()

    fun toFilamentMatrix(center: Vec3, scale: Float, offset: Vec3): FloatArray = floatArrayOf(
        values[0] * scale, values[1] * scale, values[2] * scale, 0.0f,
        values[3] * scale, values[4] * scale, values[5] * scale, 0.0f,
        values[6] * scale, values[7] * scale, values[8] * scale, 0.0f,
        (values[9] + offset.x - center.x) * scale,
        (values[10] + offset.y - center.y) * scale,
        (values[11] + offset.z - center.z) * scale,
        1.0f,
    )

    fun compose(local: MeshTransform): MeshTransform {
        if (isIdentity) return local
        if (local.isIdentity) return this

        val a = values
        val b = local.values
        val out = FloatArray(12)
        out[0] = a[0] * b[0] + a[3] * b[1] + a[6] * b[2]
        out[1] = a[1] * b[0] + a[4] * b[1] + a[7] * b[2]
        out[2] = a[2] * b[0] + a[5] * b[1] + a[8] * b[2]
        out[3] = a[0] * b[3] + a[3] * b[4] + a[6] * b[5]
        out[4] = a[1] * b[3] + a[4] * b[4] + a[7] * b[5]
        out[5] = a[2] * b[3] + a[5] * b[4] + a[8] * b[5]
        out[6] = a[0] * b[6] + a[3] * b[7] + a[6] * b[8]
        out[7] = a[1] * b[6] + a[4] * b[7] + a[7] * b[8]
        out[8] = a[2] * b[6] + a[5] * b[7] + a[8] * b[8]
        out[9] = a[0] * b[9] + a[3] * b[10] + a[6] * b[11] + a[9]
        out[10] = a[1] * b[9] + a[4] * b[10] + a[7] * b[11] + a[10]
        out[11] = a[2] * b[9] + a[5] * b[10] + a[8] * b[11] + a[11]
        return MeshTransform(out)
    }

    companion object {
        private val IDENTITY_VALUES = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        val IDENTITY = MeshTransform(IDENTITY_VALUES)

        fun parse(value: String?): MeshTransform {
            if (value.isNullOrBlank()) return IDENTITY
            val parsed = value.trim()
                .split(Regex("[\\s,]+"))
                .mapNotNull { it.toFloatOrNull() }
                .takeIf { it.size == 12 }
                ?.toFloatArray()
                ?: return IDENTITY
            return MeshTransform(parsed)
        }

        fun fromLib3mf(values: FloatArray): MeshTransform =
            if (values.size == 12) MeshTransform(values.copyOf()) else IDENTITY
    }
}

fun List<PlacedMeshData>.detectPlatePreviews(
    declaredPlateIndices: List<Int> = emptyList(),
    plateName: (Int) -> String = { "Plate $it" },
): List<PlatePreview> {
    val selected = resolvePlatePreviews(declaredPlateIndices, plateName)
    if (isEmpty()) return selected

    Log.d(THREE_MF_LOG_TAG, "Plate detection: selected=${selected.debugSummary()}")
    selected.forEach { plate ->
        Log.d(THREE_MF_LOG_TAG, "Final plate ${plate.index} -> ${plate.meshes.debugMapping()}")
    }
    return selected
}

internal fun List<PlacedMeshData>.resolvePlatePreviews(
    declaredPlateIndices: List<Int> = emptyList(),
    plateName: (Int) -> String = { "Plate $it" },
): List<PlatePreview> {
    if (declaredPlateIndices.isEmpty()) return emptyList()

    val plateIndices = declaredPlateIndices.distinct().sorted()
    return plateIndices.map { plate ->
        PlatePreview(
            index = plate,
            name = plateName(plate),
            meshes = filter { it.plateIndex == plate },
        )
    }
}

private fun List<PlatePreview>.debugSummary(): String =
    joinToString(prefix = "[", postfix = "]") { "${it.index}:${it.meshes.size}" }

private fun List<PlacedMeshData>.debugMapping(): String = joinToString(prefix = "[", postfix = "]") {
    "buildItem=${it.buildItemIndex},topObject=${it.topLevelObjectId},meshObject=${it.mesh.objectId},name=${it.name}"
}

fun List<PlacedMeshData>.arrangedForAllPreview(plates: List<PlatePreview>): List<PlacedMeshData> {
    if (isEmpty()) return this
    if (totalVertexFloats() > MAX_PLACED_COMPACT_FLOATS) return this
    val groups = if (plates.size >= 2) {
        val plateGroups = plates.map { it.meshes }.filter { it.isNotEmpty() }
        val unassigned = filter { it.plateIndex == null }
        if (unassigned.isNotEmpty()) plateGroups + listOf(unassigned) else plateGroups
    } else {
        clusterSparseXyGroups()
    }
    if (groups.size <= 1) return compactedForPreview()

    // Move whole plates only. Repacking individual meshes breaks component transforms.
    val normalizedGroups = groups.map { group -> group.recenteredOnXYOrigin() }
    val groupBounds = normalizedGroups.map { group -> group.map { it.placedBounds() }.combinedBounds() }
    val maxWidth = groupBounds.maxOf { max(it.size.x, 0.001f) }
    val totalArea = groupBounds.sumOf { (max(it.size.x, 0.001f) * max(it.size.y, 0.001f)).toDouble() }.toFloat()
    val margin = max(3.0f, groupBounds.maxOf { max(it.size.x, it.size.y) } * 0.05f)
    val rowWidth = max(maxWidth, sqrt(totalArea) * 1.2f) + margin

    var cursorX = 0.0f
    var cursorY = 0.0f
    var rowHeight = 0.0f
    val arranged = mutableListOf<PlacedMeshData>()
    normalizedGroups.forEachIndexed { index, group ->
        val bounds = groupBounds[index]
        val width = max(bounds.size.x, 0.001f)
        val height = max(bounds.size.y, 0.001f)
        if (cursorX > 0.0f && cursorX + width > rowWidth) {
            cursorX = 0.0f
            cursorY += rowHeight + margin
            rowHeight = 0.0f
        }
        val delta = Vec3(cursorX - bounds.min.x, cursorY - bounds.min.y, 0.0f)
        arranged += group.map { it.translated(delta) }
        cursorX += width + margin
        rowHeight = max(rowHeight, height)
    }

    val arrangedBounds = arranged.map { it.placedBounds() }.combinedBounds()
    val recenter = Vec3(-arrangedBounds.center.x, -arrangedBounds.center.y, 0.0f)
    return arranged.map { it.translated(recenter) }
}

/**
 * Bambu/Orca projects can contain source or multi-plate coordinates that are much wider
 * than the printable arrangement. Compact only when the parsed layout is clearly sparse
 * or overlapping, so normal 3MF build positions remain untouched.
 */
fun List<PlacedMeshData>.compactedForPreview(): List<PlacedMeshData> {
    if (size <= 1) return this
    if (totalVertexFloats() > MAX_PLACED_COMPACT_FLOATS) return this

    val bounds = map { it.placedBounds() }
    if (!bounds.hasSignificantXyOverlap() && !bounds.isSparseXyLayout()) return this

    val maxWidth = bounds.maxOf { max(it.size.x, 0.001f) }
    val totalArea = bounds.sumOf { (max(it.size.x, 0.001f) * max(it.size.y, 0.001f)).toDouble() }.toFloat()
    val margin = max(0.5f, bounds.maxOf { max(it.size.x, it.size.y) } * 0.08f)
    val rowWidth = max(maxWidth, sqrt(totalArea) * 1.35f) + margin

    var cursorX = 0.0f
    var cursorY = 0.0f
    var rowHeight = 0.0f
    val translations = bounds.map { bound ->
        val width = max(bound.size.x, 0.001f)
        val height = max(bound.size.y, 0.001f)
        if (cursorX > 0.0f && cursorX + width > rowWidth) {
            cursorX = 0.0f
            cursorY += rowHeight + margin
            rowHeight = 0.0f
        }
        val translation = Vec3(cursorX - bound.min.x, cursorY - bound.min.y, 0.0f)
        cursorX += width + margin
        rowHeight = max(rowHeight, height)
        translation
    }

    val packed = mapIndexed { index, placed -> placed.translated(translations[index]) }
    val packedBounds = packed.map { it.placedBounds() }.combinedBounds()
    val recenter = Vec3(-packedBounds.center.x, -packedBounds.center.y, 0.0f)
    return packed.map { placed -> placed.translated(recenter) }
}

private fun List<PlacedMeshData>.totalVertexFloats(): Long = sumOf { it.mesh.vertices.size.toLong() }

private fun List<PlacedMeshData>.clusterSparseXyGroups(): List<List<PlacedMeshData>> {
    if (size <= 1) return listOf(this)

    val bounds = map { it.placedBounds() }
    if (!bounds.isSparseXyLayout() && !bounds.hasSignificantXyOverlap()) return listOf(this)

    val spans = bounds.map { max(it.size.x, it.size.y) }.sorted()
    val medianSpan = spans[spans.size / 2].coerceAtLeast(0.001f)
    val padding = max(15.0f, medianSpan * 0.75f)
    val parent = IntArray(size) { it }

    fun find(value: Int): Int {
        var root = value
        while (parent[root] != root) root = parent[root]
        var current = value
        while (parent[current] != current) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }

    for (i in indices) {
        for (j in i + 1 until size) {
            if (bounds[i].inflatedXyOverlaps(bounds[j], padding)) union(i, j)
        }
    }

    return indices.groupBy { find(it) }
        .values
        .map { group -> group.map { this[it] } }
        .sortedWith(compareBy<List<PlacedMeshData>> { group -> group.groupBounds().min.x }
            .thenBy { group -> group.groupBounds().min.y })
}

private fun List<PlacedMeshData>.recenteredOnXYOrigin(): List<PlacedMeshData> {
    val bounds = groupBounds()
    val delta = Vec3(-bounds.min.x, -bounds.min.y, 0.0f)
    return map { it.translated(delta) }
}

private fun List<PlacedMeshData>.groupBounds(): Bounds = map { it.placedBounds() }.combinedBounds()


private fun Bounds.inflatedXyOverlaps(other: Bounds, amount: Float): Boolean =
    min.x - amount <= other.max.x && max.x + amount >= other.min.x &&
        min.y - amount <= other.max.y && max.y + amount >= other.min.y

private fun PlacedMeshData.translated(delta: Vec3): PlacedMeshData {
    if (delta.x == 0.0f && delta.y == 0.0f && delta.z == 0.0f) return this
    return copy(previewOffset = previewOffset + delta)
}

private fun List<Bounds>.hasSignificantXyOverlap(): Boolean {
    for (i in indices) {
        val a = this[i]
        val areaA = max(a.size.x, 0.001f) * max(a.size.y, 0.001f)
        for (j in i + 1 until size) {
            val b = this[j]
            val overlapX = minOf(a.max.x, b.max.x) - maxOf(a.min.x, b.min.x)
            val overlapY = minOf(a.max.y, b.max.y) - maxOf(a.min.y, b.min.y)
            if (overlapX <= 0.0f || overlapY <= 0.0f) continue
            val areaB = max(b.size.x, 0.001f) * max(b.size.y, 0.001f)
            val overlapRatio = overlapX * overlapY / minOf(areaA, areaB)
            if (overlapRatio > 0.12f) return true
        }
    }
    return false
}

private fun List<Bounds>.isSparseXyLayout(): Boolean {
    val combined = combinedBounds()
    val unionArea = max(combined.size.x, 0.001f) * max(combined.size.y, 0.001f)
    val itemArea = sumOf { (max(it.size.x, 0.001f) * max(it.size.y, 0.001f)).toDouble() }.toFloat()
    val averageSpan = map { max(it.size.x, it.size.y) }.average().toFloat().coerceAtLeast(0.001f)
    val maxSpan = max(combined.size.x, combined.size.y)
    return maxSpan / averageSpan > 5.0f && itemArea / unionArea < 0.18f
}

private fun List<Bounds>.combinedBounds(): Bounds {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    forEach { bound ->
        minX = minOf(minX, bound.min.x)
        minY = minOf(minY, bound.min.y)
        minZ = minOf(minZ, bound.min.z)
        maxX = maxOf(maxX, bound.max.x)
        maxY = maxOf(maxY, bound.max.y)
        maxZ = maxOf(maxZ, bound.max.z)
    }
    return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

object ThreeMfBuildParser {
    private var materialLayoutTotalMs: Long = 0
    private val PAINT_COLOR_BYTES = "paint_color".toByteArray()

    fun explicitPlateIndices(file: File): List<Int> = ZipFile(file).use { zip ->
        resolveExplicitPlateIndices(readProjectMetadata(zip))
    }

    fun hasExplicitMultiplePlates(file: File): Boolean = explicitPlateIndices(file).size >= 2

    fun parseProject(
        file: File,
        buildItems: List<BuildItemInfo>,
        componentsByObjectId: Map<Int, List<ComponentInfo>>,
        meshesByObjectId: Map<Int, MeshData>,
        namesByObjectId: Map<Int, String>,
    ): ParsedProject {
        materialLayoutTotalMs = 0
        return ZipFile(file).use { zip ->
            val metadata = readProjectMetadata(zip)
            val explicit = resolveExplicitPlateIndices(metadata)
            if (buildItems.isEmpty()) return@use ParsedProject(explicit, emptyList())
            val placed = placedMeshesInternal(
                zip = zip,
                metadata = metadata,
                buildItems = buildItems,
                componentsByObjectId = componentsByObjectId,
                meshesByObjectId = meshesByObjectId,
                namesByObjectId = namesByObjectId,
            )
            ParsedProject(explicit, placed)
        }
    }

    fun placedMeshes(
        file: File,
        buildItems: List<BuildItemInfo>,
        componentsByObjectId: Map<Int, List<ComponentInfo>>,
        meshesByObjectId: Map<Int, MeshData>,
        namesByObjectId: Map<Int, String>,
    ): List<PlacedMeshData> =
        parseProject(file, buildItems, componentsByObjectId, meshesByObjectId, namesByObjectId).placedMeshes

    private fun resolveExplicitPlateIndices(metadata: ProjectMetadata): List<Int> {
        metadata.plateConfig.plates.takeIf { it.isNotEmpty() }?.let { plates ->
            return plates.map { it.index }.distinct().sorted()
        }
        metadata.jsonPlateByObjectId.values.distinct().sorted().takeIf { it.isNotEmpty() }?.let {
            return it
        }
        metadata.scene?.buildItems.orEmpty()
            .mapNotNull { it.plateIndex }
            .distinct()
            .sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return emptyList()
    }

    private fun readProjectMetadata(zip: ZipFile): ProjectMetadata {
        val scene = parseModelFromZip(zip)
        val plateConfig = readPlateConfig(zip)
        // model_settings.config is the authoritative source. JSON plate files are only
        // consulted when no config exists, so stale plate_*.json cannot inflate plate count.
        val jsonPlateByObjectId = if (plateConfig.plates.isEmpty()) {
            val xmlObjectIds = scene?.buildItems.orEmpty().map { it.objectId }.toSet()
            readJsonPlateObjectIds(zip, xmlObjectIds)
        } else {
            emptyMap()
        }
        return ProjectMetadata(scene, plateConfig, jsonPlateByObjectId)
    }

    private fun placedMeshesInternal(
        zip: ZipFile,
        metadata: ProjectMetadata,
        buildItems: List<BuildItemInfo>,
        componentsByObjectId: Map<Int, List<ComponentInfo>>,
        meshesByObjectId: Map<Int, MeshData>,
        namesByObjectId: Map<Int, String>,
    ): List<PlacedMeshData> {
        val scene = metadata.scene
        val xmlBuildItems = scene?.buildItems.orEmpty()
        val jsonPlateByObjectId = metadata.jsonPlateByObjectId
        val plateConfig = metadata.plateConfig
        val configPlateByBuildIndex = matchConfigPlatesToBuildItems(xmlBuildItems, plateConfig)

        // ==================== Phase 1: Parse Hierarchy ====================
        val tPhase1 = System.currentTimeMillis()

        val volumeConfigs = readVolumeConfigs(zip)
        val objectCount = scene?.objectsById?.size ?: 0
        val componentCount = scene?.objectsById?.values?.sumOf { it.components.size } ?: 0
        logArchiveMetadata(zip)
        logModelHierarchy(buildItems, componentsByObjectId, meshesByObjectId, namesByObjectId)

        val tHierarchy = System.currentTimeMillis() - tPhase1
        Log.d("ThreeMfPerf", "parseHierarchy: $tHierarchy ms (objects=$objectCount, components=$componentCount)")

        // ==================== Phase 2: Parse Materials ====================
        val tPhase2 = System.currentTimeMillis()

        val filamentPalette = readFilamentPalette(zip)
        val slicerMaterialsByObjectId = readSlicerMaterials(zip, filamentPalette)

        val slicerPartResourceIds = slicerMaterialsByObjectId.values
            .flatMap { it.parts }
            .mapNotNull { it.modelResourceId }
            .toSet()
        val meshResourceIds = meshesByObjectId.values.map { it.modelResourceId }.toSet()
        val paintNeededIds = meshResourceIds.filter { mid ->
            mid !in slicerPartResourceIds && meshesByObjectId.values.any {
                it.modelResourceId == mid && it.propertyData.triangleResourceIds.all { rid -> rid == 0 }
            }
        }.toSet()

        val paintColorsByResource = readPaintColors(zip, paintNeededIds)
        paintColorsByResource.forEach { (resourceId, paintColors) ->
            val digitCounts = paintColors.groupBy { it }.mapValues { it.value.size }
            Log.d(THREE_MF_LOG_TAG, "Bambu paint_colors: resourceId=$resourceId, triangleCount=${paintColors.size}, digits=$digitCounts")
        }

        val tMaterial = System.currentTimeMillis() - tPhase2
        Log.d("ThreeMfPerf", "parseMaterial: $tMaterial ms (paint_needed=${paintNeededIds.size}, paint_parsed=${paintColorsByResource.size})")

        // ==================== Phase 3: Flatten & Apply ====================
        val tPhase3 = System.currentTimeMillis()

        Log.d(THREE_MF_LOG_TAG, "BuildItem count=${buildItems.size} (lib3mf), ${xmlBuildItems.size} (XML)")
        buildItems.forEachIndexed { index, item ->
            val xmlItem = xmlBuildItems.getOrNull(index)
            Log.d(
                THREE_MF_LOG_TAG,
                "BuildItem[$index]: uniqueObjectId=${item.objectResourceId}, xmlObjectId=${xmlItem?.objectId}, " +
                    "type=${item.objectResourceKind}, transform=${MeshTransform.fromLib3mf(item.transform).debugString()}, " +
                    "xmlPlate=${xmlItem?.plateIndex}, configPlate=${configPlateByBuildIndex[index]}, " +
                    "jsonPlate=${xmlItem?.objectId?.let(jsonPlateByObjectId::get)}",
            )
        }
        scene?.objectsById?.values?.forEach { objectInfo ->
            Log.d(THREE_MF_LOG_TAG, "XML object: localId=${objectInfo.id}, type=${objectInfo.type}, componentCount=${objectInfo.components.size}")
            objectInfo.components.forEachIndexed { index, component ->
                Log.d(THREE_MF_LOG_TAG, "  XML Component[$index]: parentLocalId=${objectInfo.id}, objectLocalId=${component.objectId}, transform=${component.transform.debugString()}")
            }
        }

        val placed = buildItems.flatMapIndexed { index, item ->
            val xmlItem = xmlBuildItems.getOrNull(index)
            val plateIndex = xmlItem?.plateIndex
                ?: configPlateByBuildIndex[index]
                ?: xmlItem?.objectId?.let(jsonPlateByObjectId::get)
            val slicerMaterial = xmlItem?.objectId?.let(slicerMaterialsByObjectId::get)
            flattenObject(
                objectId = item.objectResourceId,
                objectKind = item.objectResourceKind,
                topLevelObjectId = item.objectResourceId,
                buildItemIndex = index,
                plateIndex = plateIndex,
                slicerMaterial = slicerMaterial,
                transform = MeshTransform.fromLib3mf(item.transform),
                objectPath = listOf(item.objectResourceId),
                componentsByObjectId = componentsByObjectId,
                meshesByObjectId = meshesByObjectId,
                namesByObjectId = namesByObjectId,
                volumeConfigsByObjectId = volumeConfigs,
                activeStack = linkedSetOf(),
                paintColorsByResource = paintColorsByResource,
                filamentPalette = filamentPalette,
            )
        }

        val named = placed.withUniqueNames()
        named.groupBy { it.plateIndex }.toSortedMap(compareBy(nullsLast()) { it }).forEach { (plate, meshes) ->
            Log.d(THREE_MF_LOG_TAG, "Parsed plate ${plate ?: "unassigned"} -> ${meshes.debugMapping()}")
        }

        val tFlatten = System.currentTimeMillis() - tPhase3
        Log.d("ThreeMfPerf", "flattenApply: $tFlatten ms")
        Log.d("ThreeMfPerf", "Split material: $materialLayoutTotalMs ms")
        return named
    }

    private fun logArchiveMetadata(zip: ZipFile) {
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .filter { name ->
                name.startsWith("Metadata/", ignoreCase = true) ||
                    name.contains(Regex("plate[_-]?\\d+\\.json", RegexOption.IGNORE_CASE))
            }
            .toList()
        Log.d(THREE_MF_LOG_TAG, "3MF metadata entries (${entries.size}): ${entries.joinToString()}")
    }

    private fun logModelHierarchy(
        buildItems: List<BuildItemInfo>,
        componentsByObjectId: Map<Int, List<ComponentInfo>>,
        meshesByObjectId: Map<Int, MeshData>,
        namesByObjectId: Map<Int, String>,
    ) {
        val objectIds = linkedSetOf<Int>().apply {
            addAll(meshesByObjectId.keys)
            addAll(componentsByObjectId.keys)
            componentsByObjectId.values.flatten().forEach { add(it.objectResourceId) }
            buildItems.forEach { add(it.objectResourceId) }
        }
        Log.d(THREE_MF_LOG_TAG, "3MF hierarchy: Model")
        objectIds.forEach { objectId ->
            val mesh = meshesByObjectId[objectId]
            val components = componentsByObjectId[objectId].orEmpty()
            val kind = when {
                mesh != null -> "mesh"
                components.isNotEmpty() -> "components"
                else -> "unknown"
            }
            Log.d(
                THREE_MF_LOG_TAG,
                "  Object id=$objectId kind=$kind name=${namesByObjectId[objectId].orEmpty()}",
            )
            mesh?.let {
                Log.d(
                    THREE_MF_LOG_TAG,
                    "    Mesh objectId=$objectId vertices=${it.vertices.size / 3} triangles=${it.triangles.size / 3}",
                )
            }
            if (components.isNotEmpty()) Log.d(THREE_MF_LOG_TAG, "    Components count=${components.size}")
            components.forEachIndexed { componentIndex, component ->
                Log.d(
                    THREE_MF_LOG_TAG,
                    "      Component[$componentIndex] objectRef=${component.objectResourceId} " +
                        "kind=${component.objectResourceKind} transform=${MeshTransform.fromLib3mf(component.transform).debugString()}",
                )
            }
        }
        Log.d(THREE_MF_LOG_TAG, "  BuildItems count=${buildItems.size}")
        buildItems.forEachIndexed { index, item ->
            Log.d(
                THREE_MF_LOG_TAG,
                "    BuildItem[$index] objectRef=${item.objectResourceId} kind=${item.objectResourceKind} " +
                    "transform=${MeshTransform.fromLib3mf(item.transform).debugString()}",
            )
        }
    }

    private fun flattenObject(
        objectId: Int,
        objectKind: String,
        topLevelObjectId: Int,
        buildItemIndex: Int,
        plateIndex: Int?,
        slicerMaterial: SlicerObjectMaterial?,
        transform: MeshTransform,
        objectPath: List<Int>,
        componentsByObjectId: Map<Int, List<ComponentInfo>>,
        meshesByObjectId: Map<Int, MeshData>,
        namesByObjectId: Map<Int, String>,
        volumeConfigsByObjectId: Map<Int, List<SlicerVolumeConfig>>,
        activeStack: MutableSet<Int>,
        paintColorsByResource: Map<Int, IntArray>,
        filamentPalette: List<FloatArray>,
    ): List<PlacedMeshData> {
        if (!activeStack.add(objectId)) return emptyList()

        val placed = mutableListOf<PlacedMeshData>()
        if (objectKind == "mesh") meshesByObjectId[objectId]?.let { mesh ->
            val objectName = namesByObjectId[objectId].orObjectName(objectId)
            val volumeMeshes = mesh.splitByVolumes(volumeConfigsByObjectId[objectId])
            val paintColorDigits = paintColorsByResource[mesh.modelResourceId]
                ?.takeIf { it.size == mesh.triangles.size / 3 }
            placed += volumeMeshes.map { volumeMesh ->
                val partMaterial = slicerMaterial?.parts?.firstOrNull {
                    it.modelResourceId == volumeMesh.mesh.modelResourceId
                }
                val fallbackMaterial = partMaterial ?: slicerMaterial?.objectMaterial
                val meshPaintDigits = if (volumeMeshes.size == 1 && volumeMesh.mesh === mesh) {
                    paintColorDigits
                } else {
                    null
                }
                val layout = volumeMesh.mesh.buildMaterialLayout(
                    partMaterial = partMaterial,
                    objectMaterial = slicerMaterial?.objectMaterial,
                    volumeMaterials = slicerMaterial?.parts.orEmpty().filter {
                        it.firstTriangle != null && it.lastTriangle != null
                    },
                    paintColorDigits = meshPaintDigits,
                    filamentPalette = filamentPalette,
                )
                layout?.slots?.forEach { slot ->
                    val color = slot.originalColor
                    Log.d(
                        THREE_MF_LOG_TAG,
                        "Material slot: objectId=${volumeMesh.mesh.objectId}, " +
                            "localId=${volumeMesh.mesh.modelResourceId}, name=${partMaterial?.name ?: objectName}, " +
                            "source=${slot.id.source}, resourceId=${slot.id.resourceId}, " +
                            "index=${slot.id.propertyIndex}, extruder=${slot.id.extruderIndex}, " +
                            "rgba=${color.toDebugHex()}, triangles=${slot.triangleCount}",
                    )
                }
                PlacedMeshData(
                    mesh = volumeMesh.mesh,
                    name = partMaterial?.name
                        ?: volumeMesh.name?.let { "$objectName - $it" }
                        ?: objectName,
                    displayColor = layout?.slots?.singleOrNull()?.originalColor?.toFloatArray()
                        ?: volumeMesh.mesh.displayColor
                        ?: fallbackMaterial?.color?.toFloatArray(),
                    materialLayout = layout,
                    topLevelObjectId = topLevelObjectId,
                    buildItemIndex = buildItemIndex,
                    plateIndex = plateIndex,
                    objectPath = objectPath,
                    transform = transform,
                )
            }
            Log.d(
                THREE_MF_LOG_TAG,
                "Mesh instance: objectId=$objectId componentPath=${objectPath.joinToString(" -> ")} " +
                    "world3mf=${transform.debugString()}",
            )
        }

        componentsByObjectId[objectId].orEmpty().forEachIndexed { componentIndex, component ->
            val localTransform = MeshTransform.fromLib3mf(component.transform)
            val worldTransform = transform.compose(localTransform)
            Log.d(
                THREE_MF_LOG_TAG,
                "Component instance: parentObjectId=$objectId componentId=$componentIndex " +
                    "objectRef=${component.objectResourceId} local=$localTransform world=$worldTransform",
            )
            placed += flattenObject(
                objectId = component.objectResourceId,
                objectKind = component.objectResourceKind,
                topLevelObjectId = topLevelObjectId,
                buildItemIndex = buildItemIndex,
                plateIndex = plateIndex,
                slicerMaterial = slicerMaterial,
                transform = worldTransform,
                objectPath = objectPath + component.objectResourceId,
                componentsByObjectId = componentsByObjectId,
                meshesByObjectId = meshesByObjectId,
                namesByObjectId = namesByObjectId,
                volumeConfigsByObjectId = volumeConfigsByObjectId,
                activeStack = activeStack,
                paintColorsByResource = paintColorsByResource,
                filamentPalette = filamentPalette,
            )
        }

        activeStack.remove(objectId)
        return placed
    }

    private fun MeshData.buildMaterialLayout(
        partMaterial: SlicerPartMaterial?,
        objectMaterial: SlicerPartMaterial?,
        volumeMaterials: List<SlicerPartMaterial>,
        paintColorDigits: IntArray? = null,
        filamentPalette: List<FloatArray> = emptyList(),
    ): MeshMaterialLayout? {
        val t0 = System.currentTimeMillis()
        return buildMaterialLayoutInternal(partMaterial, objectMaterial, volumeMaterials, paintColorDigits, filamentPalette).also {
            materialLayoutTotalMs += System.currentTimeMillis() - t0
        }
    }

    private fun MeshData.buildMaterialLayoutInternal(
        partMaterial: SlicerPartMaterial?,
        objectMaterial: SlicerPartMaterial?,
        volumeMaterials: List<SlicerPartMaterial>,
        paintColorDigits: IntArray? = null,
        filamentPalette: List<FloatArray> = emptyList(),
    ): MeshMaterialLayout? {
        val propertyByKey = propertyData.properties.associateBy { it.resourceId to it.propertyIndex }
        val slots = mutableListOf<MaterialSlot>()
        val slotById = linkedMapOf<MaterialSlotId, Int>()
        val triangleCounts = mutableListOf<Int>()

        fun addSlot(slot: MaterialSlot): Int = slotById.getOrPut(slot.id) {
            triangleCounts += 0
            slots += slot
            slots.lastIndex
        }

        fun coreSlot(resourceId: Int, propertyIndex: Int): Int? {
            val property = propertyByKey[resourceId to propertyIndex] ?: return null
            val color = property.rgba?.let(RgbaColor::fromRgba8) ?: return null
            return addSlot(
                MaterialSlot(
                    id = MaterialSlotId(
                        source = MaterialSlotSource.CORE_3MF,
                        packagePath = property.packagePath,
                        modelResourceId = property.modelResourceId,
                        resourceId = resourceId,
                        propertyIndex = propertyIndex,
                    ),
                    name = property.name,
                    originalColor = color,
                ),
            )
        }

        fun slicerSlot(material: SlicerPartMaterial?): Int? {
            material ?: return null
            return addSlot(
                MaterialSlot(
                    id = MaterialSlotId(
                        source = MaterialSlotSource.SLICER_FILAMENT,
                        packagePath = packagePath,
                        modelResourceId = 0,
                        resourceId = 0,
                        propertyIndex = material.extruderIndex - 1,
                        extruderIndex = material.extruderIndex,
                    ),
                    name = "Filament ${material.extruderIndex}",
                    originalColor = material.color,
                ),
            )
        }

        fun defaultSlot(): Int? = displayColor?.let(RgbaColor::fromFloatArray)?.let { color ->
            addSlot(
                MaterialSlot(
                    id = MaterialSlotId(
                        source = MaterialSlotSource.DEFAULT,
                        packagePath = packagePath,
                        modelResourceId = modelResourceId,
                        resourceId = objectId,
                        propertyIndex = -1,
                    ),
                    name = null,
                    originalColor = color,
                ),
            )
        }

        if (paintColorDigits != null && paintColorDigits.isNotEmpty() && filamentPalette.isNotEmpty()) {
            val paintTriangleCount = minOf(paintColorDigits.size, triangles.size / 3)
            val digitFreq = IntArray(10)
            for (ti in 0 until paintTriangleCount) {
                val d = paintColorDigits[ti]
                if (d in 0..9) digitFreq[d]++
            }
            val sortedDigits = (0..9).filter { digitFreq[it] > 0 }
                .sortedByDescending { digitFreq[it] }
            val digitToPalette = sortedDigits.mapIndexed { index, digit ->
                digit to (index.coerceAtMost(filamentPalette.lastIndex))
            }.toMap()
            val digitToSlot = mutableMapOf<Int, Int>()
            sortedDigits.forEach { digit ->
                val paletteIndex = digitToPalette[digit] ?: return@forEach
                val color = filamentPalette.getOrNull(paletteIndex)?.let(RgbaColor::fromFloatArray) ?: return@forEach
                digitToSlot[digit] = addSlot(
                    MaterialSlot(
                        id = MaterialSlotId(
                            source = MaterialSlotSource.BAMBU_PAINT,
                            packagePath = packagePath,
                            modelResourceId = 0,
                            resourceId = 0,
                            propertyIndex = paletteIndex,
                        ),
                        name = "Filament ${paletteIndex + 1}",
                        originalColor = color,
                    ),
                )
            }
            val flatBySlot = linkedMapOf<Int, IntCollector>()
            for (triangleIndex in 0 until paintTriangleCount) {
                val digit = paintColorDigits[triangleIndex]
                val slot = digitToSlot[digit] ?: continue
                val collector = flatBySlot.getOrPut(slot) { IntCollector() }
                val indexOffset = triangleIndex * 3
                collector.add(triangles.getOrElse(indexOffset) { continue })
                collector.add(triangles.getOrElse(indexOffset + 1) { continue })
                collector.add(triangles.getOrElse(indexOffset + 2) { continue })
                triangleCounts[slot] += 1
            }
            if (flatBySlot.isNotEmpty()) {
                Log.d(
                    THREE_MF_LOG_TAG,
                    "Bambu paint colors: objectId=$objectId localId=$modelResourceId " +
                        "triangles=$paintTriangleCount digits=$sortedDigits mapping=$digitToPalette",
                )
                return MeshMaterialLayout(
                    slots = slots.mapIndexed { index, value -> value.copy(triangleCount = triangleCounts[index]) },
                    primitives = flatBySlot.map { (slot, indices) ->
                        MeshMaterialPrimitive(indices.toIntArray(), slot)
                    },
                )
            }
        }

        val objectCoreSlot = propertyData.takeIf { it.hasObjectProperty }?.let {
            coreSlot(it.objectPropertyResourceId, it.objectPropertyIndex)
        }
        val baseFallbackSlot = slicerSlot(partMaterial)
            ?: objectCoreSlot
            ?: slicerSlot(objectMaterial)
            ?: defaultSlot()

        val rangeSlots = volumeMaterials.mapNotNull { material ->
            val first = material.firstTriangle ?: return@mapNotNull null
            val last = material.lastTriangle ?: return@mapNotNull null
            slicerSlot(material)?.let { slot -> first..last to slot }
        }
        val triangleCount = triangles.size / 3
        val resourceIds = propertyData.triangleResourceIds
        val propertyIndices = propertyData.trianglePropertyIndices
        val hasExplicitProperties = resourceIds.any { it != 0 }
        if (!hasExplicitProperties && rangeSlots.isEmpty()) {
            val slot = baseFallbackSlot ?: return null
            triangleCounts[slot] = triangleCount
            return MeshMaterialLayout(
                slots = slots.mapIndexed { index, value -> value.copy(triangleCount = triangleCounts[index]) },
                primitives = listOf(MeshMaterialPrimitive(triangles, slot)),
            )
        }

        val flatIndices = linkedMapOf<Int, IntCollector>()
        val mixedIndices = IntCollector()
        val mixedCornerSlots = IntCollector()
        for (triangleIndex in 0 until triangleCount) {
            val indexOffset = triangleIndex * 3
            val resourceId = resourceIds.getOrElse(triangleIndex) { 0 }
            val explicitSlots = if (resourceId != 0) {
                IntArray(3) { corner ->
                    coreSlot(resourceId, propertyIndices.getOrElse(indexOffset + corner) { 0 }) ?: -1
                }
            } else {
                null
            }
            val rangedSlot = rangeSlots.firstOrNull { triangleIndex in it.first }?.second
            val fallbackSlot = rangedSlot ?: baseFallbackSlot
            val cornerSlots = explicitSlots?.takeIf { slotsForTriangle -> slotsForTriangle.all { it >= 0 } }

            if (cornerSlots != null && !cornerSlots.all { it == cornerSlots[0] }) {
                repeat(3) { corner ->
                    mixedIndices.add(triangles[indexOffset + corner])
                    mixedCornerSlots.add(cornerSlots[corner])
                }
                cornerSlots.distinct().forEach { triangleCounts[it] += 1 }
            } else {
                val slot = cornerSlots?.firstOrNull() ?: fallbackSlot ?: continue
                val collector = flatIndices.getOrPut(slot) { IntCollector() }
                repeat(3) { corner -> collector.add(triangles[indexOffset + corner]) }
                triangleCounts[slot] += 1
            }
        }

        val primitives = buildList {
            flatIndices.forEach { (slot, indices) ->
                add(MeshMaterialPrimitive(indices.toIntArray(), slot))
            }
            if (mixedIndices.isNotEmpty()) {
                add(
                    MeshMaterialPrimitive(
                        indices = mixedIndices.toIntArray(),
                        materialSlotIndex = -1,
                        cornerMaterialSlotIndices = mixedCornerSlots.toIntArray(),
                    ),
                )
            }
        }
        if (primitives.isEmpty()) return null
        return MeshMaterialLayout(
            slots = slots.mapIndexed { index, value -> value.copy(triangleCount = triangleCounts[index]) },
            primitives = primitives,
        )
    }

    private fun MeshData.splitByVolumes(configs: List<SlicerVolumeConfig>?): List<VolumeMeshData> {
        val volumes = configs.orEmpty().filter { it.firstTriangle <= it.lastTriangle }
        if (volumes.isEmpty()) return listOf(VolumeMeshData(this, null))
        if (vertices.size.toLong() > MAX_VOLUME_SPLIT_FLOATS || triangles.size.toLong() > MAX_VOLUME_SPLIT_INDICES) {
            return listOf(VolumeMeshData(this, null))
        }

        return volumes.mapNotNull { volume ->
            val first = volume.firstTriangle.coerceAtLeast(0)
            val last = volume.lastTriangle.coerceAtMost(triangles.size / 3 - 1)
            if (first > last) return@mapNotNull null

            val remap = linkedMapOf<Int, Int>()
            val newTriangles = IntArray((last - first + 1) * 3)
            var out = 0
            for (tri in first..last) {
                val triOffset = tri * 3
                repeat(3) { local ->
                    val oldIndex = triangles[triOffset + local]
                    val newIndex = remap.getOrPut(oldIndex) { remap.size }
                    newTriangles[out++] = newIndex
                }
            }

            val newVertices = FloatArray(remap.size * 3)
            remap.forEach { (oldIndex, newIndex) ->
                val oldOffset = oldIndex * 3
                val newOffset = newIndex * 3
                newVertices[newOffset] = vertices[oldOffset]
                newVertices[newOffset + 1] = vertices[oldOffset + 1]
                newVertices[newOffset + 2] = vertices[oldOffset + 2]
            }

            VolumeMeshData(
                mesh = copy(
                    vertices = newVertices,
                    triangles = newTriangles,
                    propertyData = propertyData.copy(
                        triangleResourceIds = propertyData.triangleResourceIds.copyOfRange(
                            first.coerceAtMost(propertyData.triangleResourceIds.size),
                            (last + 1).coerceAtMost(propertyData.triangleResourceIds.size),
                        ),
                        trianglePropertyIndices = propertyData.trianglePropertyIndices.copyOfRange(
                            (first * 3).coerceAtMost(propertyData.trianglePropertyIndices.size),
                            ((last + 1) * 3).coerceAtMost(propertyData.trianglePropertyIndices.size),
                        ),
                    ),
                ),
                name = volume.name,
            )
        }.ifEmpty { listOf(VolumeMeshData(this, null)) }
    }

    private fun readPaintColors(
        zip: ZipFile,
        neededIds: Set<Int>,
    ): Map<Int, IntArray> {
        if (neededIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, IntArray>()
        val remaining = neededIds.toMutableSet()

        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("3D/Objects/") && it.name.endsWith(".model") }
            .takeWhile { remaining.isNotEmpty() }
            .forEach { entry ->
                val (objectId, colors) = zip.getInputStream(entry).use { input ->
                    parsePaintColorsWithId(input)
                } ?: return@forEach
                if (objectId !in remaining) return@forEach
                if (colors.isNotEmpty()) {
                    result[objectId] = colors
                    remaining.remove(objectId)
                }
            }

        return result
    }

    private fun parsePaintColorsWithId(input: java.io.InputStream): Pair<Int, IntArray>? {
        val bytes = input.readBytes()
        if (!bytes.containsBytes(PAINT_COLOR_BYTES)) return null

        var objectId = -1
        val paintColors = mutableListOf<Int>()
        val handler = object : DefaultHandler() {
            var inMesh = false
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val name = elementName(localName, qName)
                if (name == "object") {
                    val id = attributes.intValue("id")
                    if (id != null) objectId = id
                } else if (name == "mesh") {
                    inMesh = true
                } else if (inMesh && name == "triangle") {
                    val pc = attributes.stringValue("paint_color")
                    if (pc.isNotEmpty()) {
                        val digit = pc[0].digitToIntOrNull()
                        if (digit != null) paintColors.add(digit)
                    }
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (elementName(localName, qName) == "mesh") inMesh = false
            }
        }
        return runCatching {
            saxParser.parse(ByteArrayInputStream(bytes), handler)
            if (objectId < 0 || paintColors.isEmpty()) null
            else objectId to paintColors.toIntArray()
        }.getOrNull()
    }

    private fun ByteArray.containsBytes(target: ByteArray): Boolean {
        val max = size - target.size
        if (max < 0) return false
        val first = target[0]
        var i = 0
        while (i <= max) {
            if (this[i] == first) {
                var match = true
                for (j in 1 until target.size) {
                    if (this[i + j] != target[j]) { match = false; break }
                }
                if (match) return true
            }
            i++
        }
        return false
    }

    private fun readVolumeConfigs(zip: ZipFile): Map<Int, List<SlicerVolumeConfig>> = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".config", ignoreCase = true) }
        .mapNotNull { entry ->
            val xml = zip.getInputStream(entry).use { it.readBytes() }
            parseSlicerConfig(xml)?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()
        .orEmpty()

    private fun readFilamentPalette(zip: ZipFile): List<FloatArray> {
        val entry = zip.getEntry(PROJECT_CONFIG_PATH)
            ?: zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith("project_settings.config", ignoreCase = true)
            }
            ?: return emptyList()
        val json = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return runCatching {
            val settings = JSONObject(json)
            val colors = settings.optJSONArray("filament_colour")
                ?: settings.optJSONArray("filament_color")
                ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until colors.length()) {
                    colors.optString(index).parseHexColor()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readSlicerMaterials(zip: ZipFile, palette: List<FloatArray>): Map<Int, SlicerObjectMaterial> {
        if (palette.isEmpty()) return emptyMap()
        val entry = zip.getEntry(CONFIG_PATH)
            ?: zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith("model_settings.config", ignoreCase = true)
            }
            ?: return emptyMap()
        val xml = zip.getInputStream(entry).use { it.readBytes() }
        return runCatching {
                val document = documentBuilder.parse(ByteArrayInputStream(xml))
                document.documentElement.descendantElements("object")
                    .mapNotNull { element ->
                        val objectId = element.intAttribute("id") ?: return@mapNotNull null
                        val objectMaterial = element.slicerMaterial(palette, modelResourceId = null)
                        val parts = buildList {
                            element.childElements("part").mapNotNullTo(this) { part ->
                                part.slicerMaterial(palette, part.intAttribute("id"))
                            }
                            element.descendantElements("volume").mapNotNullTo(this) { volume ->
                                volume.slicerMaterial(
                                    palette = palette,
                                    modelResourceId = volume.intAttribute("id"),
                                    firstTriangle = volume.intAttribute("firstid"),
                                    lastTriangle = volume.intAttribute("lastid"),
                                )
                            }
                        }
                        if (objectMaterial == null && parts.isEmpty()) null
                        else objectId to SlicerObjectMaterial(objectMaterial, parts)
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
    }

    private fun Element.slicerMaterial(
        palette: List<FloatArray>,
        modelResourceId: Int?,
        firstTriangle: Int? = null,
        lastTriangle: Int? = null,
    ): SlicerPartMaterial? {
        val extruder = metadataValue("extruder")?.toIntOrNull() ?: return null
        val color = palette.getOrNull(extruder - 1)?.let(RgbaColor::fromFloatArray) ?: return null
        return SlicerPartMaterial(
            modelResourceId = modelResourceId,
            extruderIndex = extruder,
            color = color,
            name = metadataValue("name"),
            firstTriangle = firstTriangle,
            lastTriangle = lastTriangle,
        )
    }

    private fun String.parseHexColor(): FloatArray? {
        val hex = trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        val hasAlpha = hex.length == 8
        val red = if (hasAlpha) value shr 24 else value shr 16
        val green = if (hasAlpha) value shr 16 else value shr 8
        val blue = if (hasAlpha) value shr 8 else value
        val alpha = if (hasAlpha) value else 255L
        return floatArrayOf(
            (red and 0xff).toFloat() / 255f,
            (green and 0xff).toFloat() / 255f,
            (blue and 0xff).toFloat() / 255f,
            (alpha and 0xff).toFloat() / 255f,
        )
    }

    private fun RgbaColor.toDebugHex(): String = String.format(
        Locale.US,
        "#%02X%02X%02X%02X",
        (red * 255.0f + 0.5f).toInt().coerceIn(0, 255),
        (green * 255.0f + 0.5f).toInt().coerceIn(0, 255),
        (blue * 255.0f + 0.5f).toInt().coerceIn(0, 255),
        (alpha * 255.0f + 0.5f).toInt().coerceIn(0, 255),
    )

    private fun readJsonPlateObjectIds(zip: ZipFile, candidateObjectIds: Set<Int>): Map<Int, Int> {
        if (candidateObjectIds.isEmpty()) return emptyMap()
        val plateByObjectId = linkedMapOf<Int, Int>()
        val plateFile = Regex("(?:^|/)plate[_-]?(\\d+)\\.json$", RegexOption.IGNORE_CASE)

        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            .forEach { entry ->
                val plate = plateFile.find(entry.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val json = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                candidateObjectIds.forEach { objectId ->
                    if (json.referencesObjectId(objectId)) plateByObjectId.putIfAbsent(objectId, plate)
                }
            }
        return plateByObjectId
    }

    private fun String.referencesObjectId(objectId: Int): Boolean {
        val id = Regex.escape(objectId.toString())
        val keyPattern = "(?:object[_-]?id|objectid|object[_-]?ids|objectids|model[_-]?id|modelid|model[_-]?ids|modelids|identify[_-]?id|identifyid)"
        return Regex("\"$keyPattern\"\\s*:\\s*\"?$id\"?", RegexOption.IGNORE_CASE).containsMatchIn(this)
    }

    private fun parseSlicerConfig(configXml: ByteArray): Map<Int, List<SlicerVolumeConfig>>? = runCatching {
        val document = documentBuilder.parse(ByteArrayInputStream(configXml))
        document.documentElement.descendantElements("object")
            .mapNotNull { objectElement ->
                val objectId = objectElement.intAttribute("id") ?: return@mapNotNull null
                val volumes = objectElement.descendantElements("volume")
                    .mapNotNull { volumeElement ->
                        val first = volumeElement.intAttribute("firstid") ?: return@mapNotNull null
                        val last = volumeElement.intAttribute("lastid") ?: return@mapNotNull null
                        SlicerVolumeConfig(
                            firstTriangle = first,
                            lastTriangle = last,
                            name = volumeElement.metadataValue("name"),
                        )
                    }
                objectId to volumes
            }
            .filter { (_, volumes) -> volumes.isNotEmpty() }
            .toMap()
    }.getOrNull()

    private fun parseModelFromZip(zip: ZipFile): ThreeMfScene? {
        val entry = zip.getEntry(STANDARD_MODEL_PATH)
            ?: zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".model", ignoreCase = true) }
            ?: return null
        return zip.getInputStream(entry).use { input ->
            runCatching { parseModelStream(input) }.getOrNull()
        }
    }

    private fun parseModelStream(input: java.io.InputStream): ThreeMfScene {
        val objects = linkedMapOf<Int, ThreeMfObject>()
        val buildItems = mutableListOf<ThreeMfBuildItem>()
        val metadataPlateNames = setOf("plate", "plate_id", "plateid", "plate_index", "plateindex", "plater_id", "platerid")

        var inResources = false
        var inBuild = false
        var inComponents = false
        var currentObjectId: Int? = null
        var currentObjectHasMesh = false
        var currentComponents = mutableListOf<ThreeMfComponent>()
        var currentObjectPath: String? = null
        var currentBuildItem: PendingBuildItem? = null
        var currentPlateMetadata: StringBuilder? = null

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val name = elementName(localName, qName)
                when (name) {
                    "resources" -> inResources = true
                    "build" -> inBuild = true
                    "object" -> if (inResources && currentObjectId == null) {
                        currentObjectId = attributes.intValue("id")
                        currentObjectPath = attributes.stringValue("p:path")
                            .ifBlank { attributes.stringValue("path") }
                            .ifBlank { null }
                        currentObjectHasMesh = false
                        currentComponents = mutableListOf()
                    }
                    "mesh" -> if (currentObjectId != null) currentObjectHasMesh = true
                    "components" -> if (currentObjectId != null) inComponents = true
                    "component" -> if (inComponents) {
                        val objectId = attributes.intValue("objectid") ?: return
                        currentComponents += ThreeMfComponent(
                            objectId = objectId,
                            transform = MeshTransform.parse(attributes.stringValue("transform")),
                        )
                    }
                    "item" -> if (inBuild) {
                        val objectId = attributes.intValue("objectid") ?: return
                        currentBuildItem = PendingBuildItem(
                            objectId = objectId,
                            transform = MeshTransform.parse(attributes.stringValue("transform")),
                            plateIndex = attributes.plateIndex(),
                        )
                    }
                    "metadata" -> currentBuildItem?.let { item ->
                        val key = attributes.stringValue("key")
                            .ifBlank { attributes.stringValue("name") }
                            .lowercase(Locale.US)
                        if (key in metadataPlateNames) {
                            val value = attributes.stringValue("value").trim().toIntOrNull()
                            if (value != null) {
                                item.plateIndex = value
                            } else {
                                currentPlateMetadata = StringBuilder()
                            }
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                currentPlateMetadata?.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = elementName(localName, qName)
                when (name) {
                    "metadata" -> {
                        currentPlateMetadata?.toString()?.trim()?.toIntOrNull()?.let { plate ->
                            currentBuildItem?.plateIndex = plate
                        }
                        currentPlateMetadata = null
                    }
                    "item" -> {
                        currentBuildItem?.let { item ->
                            buildItems += ThreeMfBuildItem(item.objectId, item.transform, item.plateIndex)
                        }
                        currentBuildItem = null
                    }
                    "components" -> inComponents = false
                    "object" -> currentObjectId?.let { id ->
                        objects[id] = ThreeMfObject(id, currentObjectHasMesh, currentComponents, currentObjectPath)
                        currentObjectId = null
                        currentObjectHasMesh = false
                        currentObjectPath = null
                        currentComponents = mutableListOf()
                    }
                    "build" -> inBuild = false
                    "resources" -> inResources = false
                }
            }
        }

        saxParser.parse(input, handler)
        return ThreeMfScene(objects, buildItems)
    }

    private fun List<PlacedMeshData>.withUniqueNames(): List<PlacedMeshData> {
        val totals = groupingBy { it.name }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return map { placed ->
            val total = totals[placed.name] ?: 0
            if (total <= 1) placed else {
                val index = (seen[placed.name] ?: 0) + 1
                seen[placed.name] = index
                placed.copy(name = "${placed.name} #$index")
            }
        }
    }

    private fun Element.childElements(localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (element.matchesName(localName)) result += element
        }
        return result
    }

    private fun Element.descendantElements(localName: String): List<Element> {
        val result = mutableListOf<Element>()
        fun collect(element: Element) {
            element.childElements(localName).forEach { result += it }
            val nodes = element.childNodes
            for (i in 0 until nodes.length) {
                (nodes.item(i) as? Element)?.let(::collect)
            }
        }
        collect(this)
        return result.distinct()
    }

    private fun Element.metadataValue(key: String): String? = childElements("metadata")
        .firstOrNull { it.getAttribute("key") == key }
        ?.getAttribute("value")
        ?.takeIf { it.isNotBlank() }

    private val saxParser by lazy { SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }.newSAXParser() }

    private val documentBuilder by lazy { DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder() }

    private fun Attributes.plateIndex(): Int? {
        val attributeNames = listOf("plate", "plate_id", "plateid", "plate_index", "plateindex")
        return attributeNames.firstNotNullOfOrNull { name -> stringValue(name).toIntOrNull() }
    }

    private fun Attributes.intValue(name: String): Int? = stringValue(name).toIntOrNull()

    private fun Attributes.stringValue(name: String): String {
        for (i in 0 until length) {
            if (elementName(getLocalName(i), getQName(i)) == name) return getValue(i).orEmpty()
        }
        return getValue(name).orEmpty()
    }

    private fun elementName(localName: String?, qName: String?): String =
        (localName?.takeIf { it.isNotBlank() } ?: qName.orEmpty().substringAfter(':')).lowercase(Locale.US)

    private fun Element.matchesName(expected: String): Boolean {
        val local = localName ?: tagName.substringAfter(':')
        return local == expected
    }

    private fun Element.intAttribute(name: String): Int? = getAttribute(name).toIntOrNull()

    private fun String?.orObjectName(objectId: Int): String =
        this?.takeIf { it.isNotBlank() } ?: "Object $objectId"

    private fun readPlateConfig(zip: ZipFile): PlateConfig {
        val entry = zip.getEntry(CONFIG_PATH)
            ?: zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith("model_settings.config", ignoreCase = true)
            }
            ?: return PlateConfig.EMPTY
        return zip.getInputStream(entry).use { input ->
            runCatching { parsePlateConfig(input) }.getOrDefault(PlateConfig.EMPTY)
        }
    }

    private fun parsePlateConfig(input: java.io.InputStream): PlateConfig {
        val plates = mutableListOf<PlateInfo>()
        var currentPlateIndex: Int? = null
        var currentPlateName: String? = null
        var currentPlateLocked = false
        val currentInstances = mutableListOf<PlateModelInstance>()
        var inModelInstance = false
        var currentObjectId: Int? = null
        var currentInstanceId: Int? = null
        var currentIdentifyId: Int? = null

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val name = elementName(localName, qName)
                when (name) {
                    "plate" -> {
                        // model_settings.config may omit plater_id; fall back to document order.
                        currentPlateIndex = plates.size + 1
                        currentPlateName = null
                        currentPlateLocked = false
                        currentInstances.clear()
                    }
                    "model_instance" -> {
                        inModelInstance = true
                        currentObjectId = null
                        currentInstanceId = null
                        currentIdentifyId = null
                    }
                    "metadata" -> {
                        val key = attributes.stringValue("key").trim().lowercase(Locale.US)
                        val value = attributes.stringValue("value").trim()
                        if (inModelInstance) {
                            when (key) {
                                "object_id" -> currentObjectId = value.toIntOrNull()
                                "instance_id" -> currentInstanceId = value.toIntOrNull()
                                "identify_id" -> currentIdentifyId = value.toIntOrNull()
                            }
                        } else {
                            when (key) {
                                "plater_id" -> currentPlateIndex = value.toIntOrNull() ?: currentPlateIndex
                                "plater_name", "plate_name", "name" -> currentPlateName = value.takeIf { it.isNotBlank() }
                                "locked" -> currentPlateLocked = value.equals("true", ignoreCase = true)
                            }
                        }
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = elementName(localName, qName)
                when (name) {
                    "model_instance" -> {
                        currentObjectId?.let { objectId ->
                            currentInstances += PlateModelInstance(objectId, currentInstanceId, currentIdentifyId)
                        }
                        inModelInstance = false
                    }
                    "plate" -> {
                        val index = currentPlateIndex ?: (plates.size + 1)
                        plates += PlateInfo(index, currentPlateName, currentPlateLocked, currentInstances.toList())
                        currentPlateIndex = null
                        currentInstances.clear()
                    }
                }
            }
        }

        saxParser.parse(input, handler)
        return PlateConfig(plates)
    }

    private fun matchConfigPlatesToBuildItems(
        buildItems: List<ThreeMfBuildItem>,
        plateConfig: PlateConfig,
    ): Map<Int, Int> {
        val assignmentsByObject = plateConfig.assignments.groupBy { it.second.objectId }
        val nextInstanceByObject = mutableMapOf<Int, Int>()
        return buildMap {
            buildItems.forEachIndexed { buildIndex, item ->
                val instanceIndex = nextInstanceByObject.getOrDefault(item.objectId, 0)
                nextInstanceByObject[item.objectId] = instanceIndex + 1
                val candidates = assignmentsByObject[item.objectId].orEmpty()
                // instance_id is the reliable key. identify_id is parsed but not used as a
                // heuristic fallback because it has no safe build-item mapping without the
                // production-extension UUID context.
                val assignment = candidates.firstOrNull { it.second.instanceId == instanceIndex }
                    ?: candidates.singleOrNull { it.second.instanceId == null }
                if (assignment != null) {
                    put(buildIndex, assignment.first)
                } else {
                    Log.w(
                        THREE_MF_LOG_TAG,
                        "No plate config match for BuildItem[$buildIndex]: " +
                            "xmlObjectId=${item.objectId}, inferredInstanceId=$instanceIndex",
                    )
                }
            }
        }
    }

    private const val STANDARD_MODEL_PATH = "3D/3dmodel.model"
    private const val CONFIG_PATH = "Metadata/model_settings.config"
    private const val PROJECT_CONFIG_PATH = "Metadata/project_settings.config"
}

private data class VolumeMeshData(val mesh: MeshData, val name: String?)

private data class SlicerObjectMaterial(
    val objectMaterial: SlicerPartMaterial?,
    val parts: List<SlicerPartMaterial>,
)

private data class SlicerPartMaterial(
    val modelResourceId: Int?,
    val extruderIndex: Int,
    val color: RgbaColor,
    val name: String?,
    val firstTriangle: Int? = null,
    val lastTriangle: Int? = null,
)

private class IntCollector(initialCapacity: Int = 16) {
    private var values = IntArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun isNotEmpty(): Boolean = size > 0

    fun toIntArray(): IntArray = values.copyOf(size)
}

private data class SlicerVolumeConfig(
    val firstTriangle: Int,
    val lastTriangle: Int,
    val name: String?,
)

private data class ThreeMfScene(
    val objectsById: Map<Int, ThreeMfObject>,
    val buildItems: List<ThreeMfBuildItem>,
)

private data class ThreeMfObject(
    val id: Int,
    val hasMesh: Boolean,
    val components: List<ThreeMfComponent>,
    val path: String? = null,
) {
    val type: String
        get() = when {
            hasMesh && components.isNotEmpty() -> "Mesh+Components"
            hasMesh -> "Mesh"
            components.isNotEmpty() -> "Components"
            else -> "Other"
        }
}
private data class ThreeMfComponent(val objectId: Int, val transform: MeshTransform)
private data class ThreeMfBuildItem(val objectId: Int, val transform: MeshTransform, val plateIndex: Int?)
private data class PendingBuildItem(val objectId: Int, val transform: MeshTransform, var plateIndex: Int?)
private data class PlateModelInstance(
    val objectId: Int,
    val instanceId: Int?,
    val identifyId: Int? = null,
)

private data class PlateInfo(
    val index: Int,
    val name: String? = null,
    val locked: Boolean = false,
    val instances: List<PlateModelInstance> = emptyList(),
)

private data class PlateConfig(
    val plates: List<PlateInfo>,
) {
    val plateIndices: List<Int>
        get() = plates.map { it.index }.distinct().sorted()

    val assignments: List<Pair<Int, PlateModelInstance>>
        get() = plates.flatMap { plate -> plate.instances.map { plate.index to it } }

    companion object {
        val EMPTY = PlateConfig(emptyList())
    }
}

private data class ProjectMetadata(
    val scene: ThreeMfScene?,
    val plateConfig: PlateConfig,
    val jsonPlateByObjectId: Map<Int, Int>,
)

data class ParsedProject(
    val explicitPlateIndices: List<Int>,
    val placedMeshes: List<PlacedMeshData>,
)

private const val MAX_PLACED_COMPACT_FLOATS = 3_000_000L
private const val MAX_VOLUME_SPLIT_FLOATS = 3_000_000L
private const val MAX_VOLUME_SPLIT_INDICES = 3_000_000L
private const val THREE_MF_LOG_TAG = "ThreeMfDebug"
