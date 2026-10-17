package com.pullup.player.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest

private const val BLUR_SIZE = 64
private const val BLUR_PASSES = 3
private const val BLUR_RADIUS = 2
private const val BACKDROP_DARKEN = 0.52f

suspend fun blurredBackdrop(context: Context, model: Any): Bitmap? {
    val source = loadSmall(context, model) ?: return null
    return blurImage(source)
}

suspend fun dominantColor(context: Context, model: Any, darken: Float = 0.55f): Color? {
    val source = loadSmall(context, model) ?: return null
    return dominantOfBitmap(source, darken)
}

fun dominantOfBitmap(bitmap: Bitmap, darken: Float = 0.55f): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)

    var r = 0.0
    var g = 0.0
    var b = 0.0
    var weight = 0.0
    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            val px = small.getPixel(x, y)
            val a = ((px ushr 24) and 0xFF) / 255.0
            r += ((px shr 16) and 0xFF) / 255.0 * a
            g += ((px shr 8) and 0xFF) / 255.0 * a
            b += (px and 0xFF) / 255.0 * a
            weight += a
        }
    }
    if (weight <= 0.0) return null

    val keep = (1f - darken).coerceIn(0f, 1f)
    return Color(
        red = (r / weight).toFloat() * keep,
        green = (g / weight).toFloat() * keep,
        blue = (b / weight).toFloat() * keep,
    )
}

fun blurBitmap(bitmap: Bitmap): Bitmap? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    return blurImage(bitmap)
}

// ---- 内部实现 ---------------------------------------------------------------

private suspend fun loadSmall(context: Context, model: Any): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(BLUR_SIZE)
        .allowHardware(false)
        .build()
    val drawable = runCatching { context.imageLoader.execute(request).drawable }.getOrNull() ?: return null
    return drawable.toBitmapOrNull()
}

private fun blurImage(source: Bitmap): Bitmap {
    val longest = maxOf(source.width, source.height)
    val small = if (longest > BLUR_SIZE) {
        val ratio = BLUR_SIZE.toFloat() / longest
        Bitmap.createScaledBitmap(
            source,
            maxOf((source.width * ratio).toInt(), 8),
            maxOf((source.height * ratio).toInt(), 8),
            true,
        )
    } else {
        source.copy(Bitmap.Config.ARGB_8888, true)
    }

    var current = small
    repeat(BLUR_PASSES) { current = boxBlur(current, BLUR_RADIUS) }

    // 模糊之后整体压暗，接收返回的新 Bitmap
    current = darkenInPlace(current, BACKDROP_DARKEN)
    return current
}

/**
 * 单次盒式模糊。
 *
 * **注意**：返回的 Bitmap 必须是 mutable，否则后续 darkenInPlace 里的
 * setPixels 会抛 IllegalStateException。`Bitmap.createBitmap(colors, ...)`
 * 那个重载返回的是 immutable，所以这里改成先 createBitmap(w, h, config)
 * （返回 mutable）再 setPixels 写入。
 */
private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    // 太小就返回一份 mutable 拷贝，别把不可变的 source 直接透传下去
    if (width < 2 || height < 2) {
        return source.copy(Bitmap.Config.ARGB_8888, true)
    }

    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val out = IntArray(width * height)

    for (y in 0 until height) {
        for (x in 0 until width) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            var count = 0
            for (dy in -radius..radius) {
                val sy = (y + dy).coerceIn(0, height - 1)
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val px = pixels[sy * width + sx]
                    sa += (px ushr 24) and 0xFF
                    sr += (px shr 16) and 0xFF
                    sg += (px shr 8) and 0xFF
                    sb += px and 0xFF
                    count++
                }
            }
            out[y * width + x] =
                ((sa / count) shl 24) or ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count)
        }
    }

    // 关键：createBitmap(width, height, config) 返回 mutable
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, width, 0, 0, width, height)
    return result
}

/**
 * 就地整体压暗（只乘 RGB，保留 alpha）。
 *
 * 防御式：如果传入的 Bitmap 不是 mutable，先 copy 一份可变副本再写。
 * 返回实际被压暗的 Bitmap（可能与传入的是同一个，也可能是新副本）。
 */
private fun darkenInPlace(bitmap: Bitmap, factor: Float): Bitmap {
    val width = bitmap.width
    val height = bitmap.height

    val target = if (bitmap.isMutable) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: return bitmap // 极端情况 copy 失败，直接返回原图
    }

    val pixels = IntArray(width * height)
    target.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val px = pixels[i]
        val a = (px ushr 24) and 0xFF
        val r = (((px shr 16) and 0xFF) * factor).toInt()
        val g = (((px shr 8) and 0xFF) * factor).toInt()
        val b = ((px and 0xFF) * factor).toInt()
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    target.setPixels(pixels, 0, width, 0, 0, width, height)
    return target
}