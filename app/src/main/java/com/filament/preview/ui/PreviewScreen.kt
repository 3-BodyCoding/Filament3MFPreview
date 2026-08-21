package com.filament.preview.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.filament.preview.BuildPlateConfig
import com.filament.preview.MaterialSlot
import com.filament.preview.MaterialSlotId
import com.filament.preview.PlatePreview
import com.filament.preview.R
import com.filament.preview.RgbaColor
import com.filament.preview.XyzLengths
import java.util.Locale
import kotlin.math.roundToInt

data class AxisLabel(val text: String, val x: Float, val y: Float, val color: Color)

data class EditorPaletteColor(val nameRes: Int, val color: RgbaColor)

data class EditableColorState(
    val slot: MaterialSlot,
    val currentColor: RgbaColor,
)

enum class PreviewMode { All, Plate }

val EDITOR_COLORS = listOf(
    EditorPaletteColor(R.string.color_white, RgbaColor(1.0f, 1.0f, 1.0f)),
    EditorPaletteColor(R.string.color_gray, RgbaColor(0.45f, 0.48f, 0.52f)),
    EditorPaletteColor(R.string.color_black, RgbaColor(0.06f, 0.07f, 0.08f)),
    EditorPaletteColor(R.string.color_red, RgbaColor(0.87f, 0.12f, 0.16f)),
    EditorPaletteColor(R.string.color_orange, RgbaColor(0.95f, 0.34f, 0.12f)),
    EditorPaletteColor(R.string.color_yellow, RgbaColor(0.96f, 0.78f, 0.10f)),
    EditorPaletteColor(R.string.color_green, RgbaColor(0.10f, 0.68f, 0.34f)),
    EditorPaletteColor(R.string.color_cyan, RgbaColor(0.04f, 0.66f, 0.72f)),
    EditorPaletteColor(R.string.color_blue, RgbaColor(0.10f, 0.42f, 0.95f)),
    EditorPaletteColor(R.string.color_purple, RgbaColor(0.48f, 0.24f, 0.78f)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    loading: Boolean,
    status: String,
    selectedName: String?,
    selectedLengths: XyzLengths?,
    showBasePlate: Boolean,
    buildPlateConfig: BuildPlateConfig,
    editableColors: List<EditableColorState>,
    previewMode: PreviewMode,
    plates: List<PlatePreview>,
    selectedPlateIndex: Int,
    axisLabels: List<AxisLabel>,
    printAreaWarning: String?,
    onPickFile: (Uri) -> Unit,
    onColorChange: (MaterialSlotId, RgbaColor) -> Unit,
    onColorReset: (MaterialSlotId) -> Unit,
    onAllColorsReset: () -> Unit,
    onPreviewModeChange: (PreviewMode) -> Unit,
    onPlateChange: (Int) -> Unit,
    onBasePlateChange: (Boolean) -> Unit,
    onBuildPlateConfigChange: (BuildPlateConfig) -> Unit,
    surfaceFactory: (SurfaceView) -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickFile)
    }
    var colorDialogOpen by remember { mutableStateOf(false) }
    var plateMenuExpanded by remember { mutableStateOf(false) }
    var buildPlateDialogOpen by remember { mutableStateOf(false) }

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
                        Text(stringResource(R.string.btn_select_3mf))
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
                        Text(
                            if (editableColors.isEmpty()) stringResource(R.string.btn_no_editable_colors)
                            else stringResource(R.string.btn_edit_model_colors, editableColors.size)
                        )
                    }
                }
                if (plates.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.mode_by_plate))
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
                                    value = selectedPlate?.let {
                                        "${it.name} (${
                                            it.meshes.mapNotNull { m -> m.topLevelObjectId }
                                                .distinct().size
                                        })"
                                    } ?: stringResource(R.string.dropdown_select_plate),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.dropdown_current_plate)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            plateMenuExpanded
                                        )
                                    },
                                    modifier = Modifier.menuAnchor(
                                        ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                        enabled = true
                                    ),
                                )
                                ExposedDropdownMenu(
                                    expanded = plateMenuExpanded,
                                    onDismissRequest = { plateMenuExpanded = false },
                                ) {
                                    plates.forEachIndexed { index, plate ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${plate.name} (${
                                                        plate.meshes.mapNotNull { m -> m.topLevelObjectId }
                                                            .distinct().size
                                                    })"
                                                )
                                            },
                                            onClick = {
                                                onPlateChange(index); plateMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.label_all_plates_preview),
                                color = Color(0xFF475569)
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.label_base_plate))
                    Switch(checked = showBasePlate, onCheckedChange = onBasePlateChange)
                    selectedLengths?.let { lengths ->
                        Text(
                            text = stringResource(
                                R.string.label_selected_info,
                                selectedName ?: "Object",
                                lengths.x.format(),
                                lengths.y.format(),
                                lengths.z.format(),
                            ),
                            color = Color(0xFF1D4ED8),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } ?: Text(
                        stringResource(R.string.hint_tap_model),
                        color = Color(0xFF475569)
                    )
                }
                printAreaWarning?.let { warning ->
                    Text(
                        text = warning,
                        color = Color(0xFFDC2626),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = { buildPlateDialogOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.label_build_plate_settings))
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
                            modifier = Modifier.offset {
                                IntOffset(
                                    label.x.roundToInt() - 8,
                                    label.y.roundToInt() - 8
                                )
                            },
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
                    Text(stringResource(R.string.loading_3mf))
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
            if (buildPlateDialogOpen) {
                BuildPlateSettingsDialog(
                    config = buildPlateConfig,
                    onDismiss = { buildPlateDialogOpen = false },
                    onConfirm = { newConfig ->
                        onBuildPlateConfigChange(newConfig)
                        buildPlateDialogOpen = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuildPlateSettingsDialog(
    config: BuildPlateConfig,
    onDismiss: () -> Unit,
    onConfirm: (BuildPlateConfig) -> Unit,
) {
    var widthText by remember { mutableStateOf(config.widthMm.format()) }
    var depthText by remember { mutableStateOf(config.depthMm.format()) }
    var frontExtensionText by remember { mutableStateOf(config.frontExtensionMm.format()) }
    var brandWidthText by remember { mutableStateOf(config.brandAreaWidthMm.format()) }
    var brandFrontWidthText by remember { mutableStateOf(config.brandAreaFrontWidthMm.format()) }
    var brandText by remember { mutableStateOf(config.brandText) }
    var brandHeightText by remember { mutableStateOf(config.brandHeightMm.format()) }
    var plateColor by remember { mutableStateOf(config.plateColor.copyOf()) }
    var alpha by remember { mutableFloatStateOf(if (config.plateColor.size > 3) config.plateColor[3] else 1f) }

    fun channel(index: Int, default: Float): Float =
        if (plateColor.size > index) plateColor[index] else default

    fun currentColor(): RgbaColor = RgbaColor(
        channel(0, 0f),
        channel(1, 0f),
        channel(2, 0f),
        alpha.coerceIn(0f, 1f),
    )

    fun buildConfig(): BuildPlateConfig {
        val width = widthText.toFloatOrNull()?.takeIf { it > 0f } ?: config.widthMm
        val depth = depthText.toFloatOrNull()?.takeIf { it > 0f } ?: config.depthMm
        val front =
            frontExtensionText.toFloatOrNull()?.takeIf { it >= 0f } ?: config.frontExtensionMm
        val brandWidth =
            brandWidthText.toFloatOrNull()?.takeIf { it > 0f } ?: config.brandAreaWidthMm
        val brandFront =
            brandFrontWidthText.toFloatOrNull()?.takeIf { it > 0f } ?: config.brandAreaFrontWidthMm
        val brandHeight =
            brandHeightText.toFloatOrNull()?.takeIf { it > 0f } ?: config.brandHeightMm
        val newColor = floatArrayOf(
            channel(0, 0f),
            channel(1, 0f),
            channel(2, 0f),
            alpha.coerceIn(0f, 1f),
        )
        return config.copy(
            widthMm = width,
            depthMm = depth,
            frontExtensionMm = front,
            brandAreaWidthMm = brandWidth,
            brandAreaFrontWidthMm = brandFront,
            brandText = brandText,
            brandHeightMm = brandHeight,
            plateColor = newColor,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_build_plate_settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val plateSizes = listOf(
                    180f to 180f,
                    210f to 210f,
                    220f to 220f,
                    235f to 235f,
                    256f to 256f,
                    300f to 300f,
                    350f to 350f,
                )
                val selectedPlateSize = plateSizes.firstOrNull { (w, d) ->
                    w == (widthText.toFloatOrNull() ?: config.widthMm) &&
                        d == (depthText.toFloatOrNull() ?: config.depthMm)
                }
                var sizeMenuExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = sizeMenuExpanded,
                    onExpandedChange = { sizeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedPlateSize
                            ?.let { (w, d) -> "${w.toInt()} × ${d.toInt()} mm" }
                            ?: stringResource(R.string.dropdown_select_plate_size),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.label_plate_size)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sizeMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = sizeMenuExpanded,
                        onDismissRequest = { sizeMenuExpanded = false },
                    ) {
                        plateSizes.forEach { (w, d) ->
                            DropdownMenuItem(
                                text = { Text("${w.toInt()} × ${d.toInt()} mm") },
                                onClick = {
                                    widthText = w.format()
                                    depthText = d.format()
                                    sizeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = frontExtensionText,
                    onValueChange = { frontExtensionText = it },
                    label = { Text(stringResource(R.string.label_plate_front_extension)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brandWidthText,
                    onValueChange = { brandWidthText = it },
                    label = { Text(stringResource(R.string.label_brand_area_width)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brandFrontWidthText,
                    onValueChange = { brandFrontWidthText = it },
                    label = { Text(stringResource(R.string.label_brand_front_width)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brandText,
                    onValueChange = { brandText = it },
                    label = { Text(stringResource(R.string.label_brand_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brandHeightText,
                    onValueChange = { brandHeightText = it },
                    label = { Text(stringResource(R.string.label_brand_text_size)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.label_plate_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EDITOR_COLORS.take(6).forEach { palette ->
                        PaletteColorButton(
                            palette = palette,
                            selected = palette.color.red == channel(0, 0f) &&
                                    palette.color.green == channel(1, 0f) &&
                                    palette.color.blue == channel(2, 0f),
                            onClick = {
                                plateColor = floatArrayOf(
                                    palette.color.red,
                                    palette.color.green,
                                    palette.color.blue,
                                    alpha.coerceIn(0f, 1f),
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.label_opacity) + " ${
                        (alpha.coerceIn(
                            0f,
                            1f
                        ) * 100).roundToInt()
                    }%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = alpha.coerceIn(0f, 1f),
                    onValueChange = { alpha = it },
                    valueRange = 0f..1f,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(currentColor().toComposeColor(), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp)),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(buildConfig()) }) {
                Text(stringResource(R.string.btn_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
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
        title = { Text(stringResource(R.string.dialog_model_colors)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.header_material),
                        modifier = Modifier.weight(1.2f),
                        color = Color(0xFF64748B)
                    )
                    Text(
                        stringResource(R.string.header_original_color),
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF64748B)
                    )
                    Text(
                        stringResource(R.string.header_modified_color),
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF64748B)
                    )
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
                                state.slot.name?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.color_slot_default, index + 1),
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
                        stringResource(
                            R.string.label_modify,
                            state.slot.name?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.label_current_color),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    EDITOR_COLORS.chunked(5).forEach { rowColors ->
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
                        Text(stringResource(R.string.btn_reset_current_color))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_done)) }
        },
        dismissButton = {
            TextButton(onClick = onAllColorsReset, enabled = colors.isNotEmpty()) {
                Text(stringResource(R.string.btn_reset_all))
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
        Text(stringResource(palette.nameRes), style = MaterialTheme.typography.labelSmall)
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
