package com.pullup.player.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest

/**
 * 图像处理小工具：把封面做成「真模糊背景」和「主色」。移植自 `scripts/ui/ImageFX.gd`。
 *
 * 原版特意**不用** 24×24 拉伸：那样放大后能看见一个个色块，用户的原话是
 * 「感觉就像是低分辨率的封面在后面」（`ImageFX.gd:5-6`）。这里的做法是
 * **先降采样再多次盒式模糊**（图像层面的高斯近似），得到平滑过渡，没有可见像素格。
 *
 * 三个常量照抄 `ImageFX.gd:11-16`：降采样到最长边 64、盒式模糊 3 次、半径 2，
 * 模糊后再整体压暗 0.52（模糊背景只负责氛围，不压暗的话亮封面会把整页冲成灰白，
 * 白字在上面读不出来）。
 */
private const val BLUR_SIZE = 64

/** 盒式模糊次数：3 次就非常接近高斯了（`ImageFX.gd:13`）。 */
private const val BLUR_PASSES = 3

/** 盒式模糊半径（`ImageFX.gd:14`）。 */
private const val BLUR_RADIUS = 2

/** 模糊背景的压暗系数（0.5 ≈ 半亮，保证白字可读，`ImageFX.gd:16`）。 */
private const val BACKDROP_DARKEN = 0.52f

/**
 * 把一张封面图变成柔和的模糊背景。
 *
 * 对应原版 `ImageFX.blur_image`：降采样 → 3 次盒式模糊 → 整体压暗。
 * 原版把这段丢到 `WorkerThreadPool` 后台线程（同步算实测 ~54ms，正好卡掉一帧动画，
 * `ImageFX.gd:44-45`）；Kotlin 侧这里是 `suspend`，调用方放在 `LaunchedEffect` 里即等价。
 */
suspend fun blurredBackdrop(context: Context, model: Any): Bitmap? {
    val source = loadSmall(context, model) ?: return null
    return blurImage(source)
}

/**
 * 主色：降采样后取平均，再往暗处压一点（背景不该抢封面）。对应原版 `ImageFX.dominant_color`。
 *
 * [darken] 是压暗系数：播放页用 0.88（要压得很暗，白字才压得住），
 * 环境光/封面槽用 0.74 或 0.55。压暗是**直接线性乘**而不是 `lerp` 回原色 ——
 * 原版注释写明 lerp 会把压暗抵消掉、结果还是一块灰白（`ImageFX.gd:134-135`）。
 *
 * 取平均时按 alpha 加权，透明部分不参与（有些封面是带透明边的 PNG，`ImageFX.gd:128-130`）。
 */
suspend fun dominantColor(context: Context, model: Any, darken: Float = 0.55f): Color? {
    val source = loadSmall(context, model) ?: return null
    return dominantOfBitmap(source, darken)
}

/** 对已经在本地的 Bitmap 直接取主色（自定义封面/背景走这条，不用再过一遍 Coil）。 */
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

    // 保色相、压亮度：直接乘，不要 lerp 回原色。
    val keep = (1f - darken).coerceIn(0f, 1f)
    return Color(
        red = (r / weight).toFloat() * keep,
        green = (g / weight).toFloat() * keep,
        blue = (b / weight).toFloat() * keep,
    )
}

/** 对已经在本地的 Bitmap 做模糊（自定义背景走这条）。 */
fun blurBitmap(bitmap: Bitmap): Bitmap? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    return blurImage(bitmap)
}

// ---- 内部实现 ---------------------------------------------------------------

/**
 * 把源图缩到最长边 ≤ 64（原版 `ImageFX.gd:87-95`；下限 8px 防止极端长条缩没）。
 *
 * 封面可能是远程 URL（交给 Coil，与界面上的 `AsyncImage` 共用内存/磁盘缓存，
 * 不会为此多下一趟网），也可能是已经在本地的 `Bitmap`（自定义封面/背景）。
 * `allowHardware(false)` 是因为要逐像素读。
 */
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

    // 模糊之后整体压暗（原版 `ImageFX.gd:100-108`）。
    darkenInPlace(current, BACKDROP_DARKEN)
    return current
}

/**
 * 单次盒式模糊（原版 `ImageFX.gd:141-160`）。
 *
 * 直接用 `Bitmap` 逐像素读会比 GDScript 快很多，但 64×64×3 次仍是十几万次采样，
 * 所以调用方应当在 `LaunchedEffect`/后台协程里跑，别放在组合期。
 */
private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width < 2 || height < 2) return source

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

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

/** 就地整体压暗（只乘 RGB，保留 alpha）。 */
private fun darkenInPlace(bitmap: Bitmap, factor: Float) {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val px = pixels[i]
        val a = (px ushr 24) and 0xFF
        val r = (((px shr 16) and 0xFF) * factor).toInt()
        val g = (((px shr 8) and 0xFF) * factor).toInt()
        val b = ((px and 0xFF) * factor).toInt()
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}
