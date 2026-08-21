package com.filament.preview

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.utils.IBLPrefilterContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A small procedural HDR studio, prefiltered by Filament for specular IBL. */
class StudioEnvironment private constructor(
    val indirectLight: IndirectLight,
    val skybox: Skybox,
    private val textures: List<Texture>,
) {
    fun destroy(engine: Engine) {
        engine.destroyIndirectLight(indirectLight)
        engine.destroySkybox(skybox)
        textures.forEach(engine::destroyTexture)
    }

    companion object {
        private const val WIDTH = 512
        private const val HEIGHT = 256

        fun create(engine: Engine): StudioEnvironment {
            val radiance = buildStudioRadiance(WIDTH, HEIGHT)
            val equirectangular = Texture.Builder()
                .width(WIDTH)
                .height(HEIGHT)
                .levels(fullMipLevelCount(WIDTH, HEIGHT))
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA16F)
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
                .build(engine)
            equirectangular.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(radiance.pixels, Texture.Format.RGBA, Texture.Type.FLOAT),
            )
            equirectangular.generateMipmaps(engine)

            val context = IBLPrefilterContext(engine)
            val converter = IBLPrefilterContext.EquirectangularToCubemap(context)
            val filter = IBLPrefilterContext.SpecularFilter(context)
            var skyboxTexture: Texture? = null
            var reflectionTexture: Texture? = null
            try {
                val generatedSkybox = converter.run(equirectangular)
                skyboxTexture = generatedSkybox
                val generatedReflections = filter.run(generatedSkybox)
                reflectionTexture = generatedReflections
                val indirectLight = IndirectLight.Builder()
                    .reflections(generatedReflections)
                    .irradiance(3, radiance.irradianceSh)
                    .intensity(16_000f)
                    .build(engine)
                val skybox = Skybox.Builder()
                    .color(0.10f, 0.12f, 0.15f, 1.0f)
                    .build(engine)
                return StudioEnvironment(
                    indirectLight = indirectLight,
                    skybox = skybox,
                    textures = listOf(equirectangular, generatedSkybox, generatedReflections),
                )
            } catch (error: Throwable) {
                reflectionTexture?.let(engine::destroyTexture)
                skyboxTexture?.let(engine::destroyTexture)
                engine.destroyTexture(equirectangular)
                throw error
            } finally {
                filter.destroy()
                converter.destroy()
                context.destroy()
            }
        }

        fun createFallback(engine: Engine): StudioEnvironment {
            val sh = FloatArray(27).also {
                it[0] = 0.58f
                it[1] = 0.62f
                it[2] = 0.70f
            }
            val indirectLight = IndirectLight.Builder()
                .irradiance(1, sh)
                .intensity(18_000f)
                .build(engine)
            val skybox = Skybox.Builder()
                .color(0.16f, 0.18f, 0.22f, 1.0f)
                .build(engine)
            return StudioEnvironment(indirectLight, skybox, emptyList())
        }

        private fun buildStudioRadiance(width: Int, height: Int): StudioRadiance {
            val pixels = ByteBuffer.allocateDirect(width * height * 4 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val sh = FloatArray(27)
            val deltaPhi = (2.0 * PI / width).toFloat()
            val deltaTheta = (PI / height).toFloat()
            for (y in 0 until height) {
                val theta = PI.toFloat() * (y + 0.5f) / height
                val sinTheta = sin(theta)
                val directionY = cos(theta)
                val solidAngle = deltaPhi * deltaTheta * sinTheta
                for (x in 0 until width) {
                    val phi = (2.0 * PI).toFloat() * (x + 0.5f) / width - PI.toFloat()
                    val direction = Direction(
                        x = sinTheta * cos(phi),
                        y = directionY,
                        z = sinTheta * sin(phi),
                    )
                    val color = studioColor(direction)
                    pixels.putFloat(color.red)
                    pixels.putFloat(color.green)
                    pixels.putFloat(color.blue)
                    pixels.putFloat(1.0f)
                    accumulateIrradianceSh(sh, direction, color, solidAngle)
                }
            }
            pixels.rewind()
            return StudioRadiance(pixels, sh)
        }

        private fun studioColor(direction: Direction): HdrColor {
            val horizon = (1.0f - direction.y * direction.y).coerceIn(0.0f, 1.0f)
            val floor = max(-direction.y, 0.0f)
            val key = direction.lobe(Direction(-0.58f, 0.56f, 0.59f), 110.0f) * 7.0f
            val fill = direction.lobe(Direction(0.78f, 0.28f, 0.56f), 70.0f) * 2.2f
            val rim = direction.lobe(Direction(0.08f, 0.80f, -0.60f), 130.0f) * 3.2f
            val spot = direction.lobe(Direction(-0.30f, 0.92f, 0.20f), 360.0f) * 9.0f
            return HdrColor(
                red = 0.045f + horizon * 0.04f + floor * 0.015f + key + fill * 0.70f + rim * 0.55f + spot,
                green = 0.055f + horizon * 0.045f + floor * 0.013f + key * 0.97f + fill * 0.80f + rim * 0.70f + spot * 0.98f,
                blue = 0.075f + horizon * 0.055f + floor * 0.010f + key * 0.92f + fill * 0.98f + rim * 0.98f + spot * 0.95f,
            )
        }

        private fun accumulateIrradianceSh(
            coefficients: FloatArray,
            direction: Direction,
            color: HdrColor,
            solidAngle: Float,
        ) {
            val x = direction.x
            val y = direction.y
            val z = direction.z
            val basis = floatArrayOf(
                0.282095f,
                0.488603f * y,
                0.488603f * z,
                0.488603f * x,
                1.092548f * x * y,
                1.092548f * y * z,
                0.315392f * (3.0f * z * z - 1.0f),
                1.092548f * x * z,
                0.546274f * (x * x - y * y),
            )
            val convolution = floatArrayOf(
                PI.toFloat(),
                (2.0 * PI / 3.0).toFloat(),
                (2.0 * PI / 3.0).toFloat(),
                (2.0 * PI / 3.0).toFloat(),
                (PI / 4.0).toFloat(),
                (PI / 4.0).toFloat(),
                (PI / 4.0).toFloat(),
                (PI / 4.0).toFloat(),
                (PI / 4.0).toFloat(),
            )
            basis.indices.forEach { index ->
                val scale = basis[index] * convolution[index] * solidAngle
                coefficients[index * 3] += color.red * scale
                coefficients[index * 3 + 1] += color.green * scale
                coefficients[index * 3 + 2] += color.blue * scale
            }
        }
    }
}

private data class StudioRadiance(val pixels: ByteBuffer, val irradianceSh: FloatArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StudioRadiance

        if (pixels != other.pixels) return false
        if (!irradianceSh.contentEquals(other.irradianceSh)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pixels.hashCode()
        result = 31 * result + irradianceSh.contentHashCode()
        return result
    }
}

private data class HdrColor(val red: Float, val green: Float, val blue: Float)

internal fun fullMipLevelCount(width: Int, height: Int): Int {
    var size = max(width, height).coerceAtLeast(1)
    var levels = 1
    while (size > 1) {
        size /= 2
        levels += 1
    }
    return levels
}

private data class Direction(val x: Float, val y: Float, val z: Float) {
    fun lobe(center: Direction, exponent: Float): Float {
        val centerLength = sqrt(center.x * center.x + center.y * center.y + center.z * center.z)
        val dot = (x * center.x + y * center.y + z * center.z) / centerLength
        return max(dot, 0.0f).pow(exponent)
    }
}
