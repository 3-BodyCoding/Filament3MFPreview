package com.filament.preview.rendering

import com.filament.preview.Vec3
import com.filament.preview.cross
import com.filament.preview.normalizedOr
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 轨道相机控制器：持有相机轨道状态（yaw/pitch/radius/target），
 * 提供旋转/缩放/平移等纯数学操作，并通过 [onViewChanged] 回调把结果应用到渲染层。
 *
 * 该控制器不依赖 Filament / Android，便于独立测试。
 */
class OrbitCameraController(
    private val surfaceHeightProvider: () -> Int,
    private val verticalFovDegrees: Double,
    private val panSensitivity: Double,
    private val onViewChanged: (eye: Vec3, target: Vec3) -> Unit,
) {
    private var orbitYaw = atan2(-3.2, 0.0)
    private var orbitPitch = asin(2.2 / sqrt(3.2 * 3.2 + 2.2 * 2.2))
    private var orbitRadius = sqrt(3.2 * 3.2 + 2.2 * 2.2)
    private var orbitTarget = Vec3(0.0f, 0.0f, 0.0f)

    /** 单指旋转：调整 yaw/pitch 并刷新视图。 */
    fun rotate(dx: Float, dy: Float) {
        orbitYaw -= dx * 0.008
        orbitPitch =
            (orbitPitch + dy * 0.008).coerceIn(-PI / 2.0 + 0.04, PI / 2.0 - 0.04)
        update()
    }

    /** 双指缩放：按比例调整轨道半径（不立即刷新，由调用方决定何时 update）。 */
    fun zoom(scale: Float) {
        orbitRadius = (orbitRadius * scale).coerceIn(0.65, 12.0)
    }

    /** 双指平移：基于屏幕位移与可视世界高度换算轨道目标点（不立即刷新）。 */
    fun pan(dx: Float, dy: Float) {
        val surfaceHeight = surfaceHeightProvider().takeIf { it > 0 } ?: return
        val cp = cos(orbitPitch)
        val eyeOffset = Vec3(
            (orbitRadius * cp * cos(orbitYaw)).toFloat(),
            (orbitRadius * cp * sin(orbitYaw)).toFloat(),
            (orbitRadius * sin(orbitPitch)).toFloat(),
        )
        val forward = (eyeOffset * -1.0f).normalizedOr(Vec3(0.0f, 1.0f, 0.0f))
        val right = forward.cross(Vec3(0.0f, 0.0f, 1.0f)).normalizedOr(Vec3(1.0f, 0.0f, 0.0f))
        val up = right.cross(forward).normalizedOr(Vec3(0.0f, 0.0f, 1.0f))
        val visibleWorldHeight =
            2.0 * orbitRadius * tan(Math.toRadians(verticalFovDegrees * 0.5))
        val panScale = (visibleWorldHeight / surfaceHeight * panSensitivity).toFloat()
        orbitTarget = orbitTarget + right * (-dx * panScale) + up * (dy * panScale)
    }

    /** 重置为默认视角并刷新视图。 */
    fun reset() {
        orbitYaw = atan2(-3.2, 0.0)
        orbitPitch = asin(2.2 / sqrt(3.2 * 3.2 + 2.2 * 2.2))
        orbitRadius = sqrt(3.2 * 3.2 + 2.2 * 2.2)
        orbitTarget = Vec3(0.0f, 0.0f, 0.0f)
        update()
    }

    /** 根据当前轨道状态计算相机 eye/target 并通知渲染层。 */
    fun update() {
        val cp = cos(orbitPitch)
        val eyeX = orbitTarget.x + orbitRadius * cp * cos(orbitYaw)
        val eyeY = orbitTarget.y + orbitRadius * cp * sin(orbitYaw)
        val eyeZ = orbitTarget.z + orbitRadius * sin(orbitPitch)
        onViewChanged(
            Vec3(eyeX.toFloat(), eyeY.toFloat(), eyeZ.toFloat()),
            orbitTarget,
        )
    }
}
