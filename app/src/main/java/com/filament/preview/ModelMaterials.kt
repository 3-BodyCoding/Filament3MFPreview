package com.filament.preview

enum class MaterialSlotSource {
    CORE_3MF,
    SLICER_FILAMENT,
    DEFAULT,
}

data class RgbaColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float = 1.0f,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(red, green, blue, alpha)

    companion object {
        fun fromFloatArray(value: FloatArray): RgbaColor? = value.takeIf { it.size >= 4 }?.let {
            RgbaColor(it[0], it[1], it[2], it[3])
        }

        fun fromRgba8(value: IntArray): RgbaColor? = value.takeIf { it.size >= 4 }?.let {
            RgbaColor(it[0] / 255.0f, it[1] / 255.0f, it[2] / 255.0f, it[3] / 255.0f)
        }
    }
}

/** Stable identity for editing a color without conflating unrelated materials with the same RGBA. */
data class MaterialSlotId(
    val source: MaterialSlotSource,
    val packagePath: String,
    val modelResourceId: Int,
    val resourceId: Int,
    val propertyIndex: Int,
    val extruderIndex: Int? = null,
)

data class MaterialSlot(
    val id: MaterialSlotId,
    val name: String?,
    val originalColor: RgbaColor,
    val triangleCount: Int = 0,
)

/** Indices remain in the source mesh's vertex space. Corner slots are present for 3MF color interpolation. */
data class MeshMaterialPrimitive(
    val indices: IntArray,
    val materialSlotIndex: Int,
    val cornerMaterialSlotIndices: IntArray? = null,
)

data class MeshMaterialLayout(
    val slots: List<MaterialSlot>,
    val primitives: List<MeshMaterialPrimitive>,
)

class ModelColorController(private val onColorsChanged: () -> Unit = {}) {
    private val availableSlots = linkedMapOf<MaterialSlotId, MaterialSlot>()
    private val overrides = mutableMapOf<MaterialSlotId, RgbaColor>()

    fun replaceAvailableSlots(meshes: List<SceneMesh>) {
        availableSlots.clear()
        meshes.asSequence()
            .flatMap { it.materialLayout?.slots.orEmpty().asSequence() }
            .forEach { availableSlots.putIfAbsent(it.id, it) }
        overrides.keys.retainAll(availableSlots.keys)
    }

    fun getColorSlots(): List<MaterialSlot> = availableSlots.values.toList()

    fun getEffectiveColor(slotId: MaterialSlotId): RgbaColor? =
        overrides[slotId] ?: availableSlots[slotId]?.originalColor

    fun setColor(slotId: MaterialSlotId, color: RgbaColor) {
        require(slotId in availableSlots) { "Unknown material slot: $slotId" }
        overrides[slotId] = color
        onColorsChanged()
    }

    fun setColors(colors: Map<MaterialSlotId, RgbaColor>) {
        colors.forEach { (slotId, color) ->
            require(slotId in availableSlots) { "Unknown material slot: $slotId" }
            overrides[slotId] = color
        }
        if (colors.isNotEmpty()) onColorsChanged()
    }

    fun resetColor(slotId: MaterialSlotId) {
        if (overrides.remove(slotId) != null) onColorsChanged()
    }

    fun resetAllColors() {
        if (overrides.isEmpty()) return
        overrides.clear()
        onColorsChanged()
    }

    internal fun clearForNewModel() {
        availableSlots.clear()
        overrides.clear()
    }

    internal fun overrideSnapshot(): Map<MaterialSlotId, RgbaColor> = overrides.toMap()
}
