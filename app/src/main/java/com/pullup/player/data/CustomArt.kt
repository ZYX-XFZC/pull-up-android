package com.pullup.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * 用户自定义的封面与背景，只存本机。
 *
 * 移植自 Godot 版 scripts/CustomArt.gd。**文件存在与否就是状态**：删掉文件即恢复默认。
 * 覆盖优先级：自定义 > 歌曲封面 > 默认渐变。
 */
class CustomArt(context: Context) {

    companion object {
        const val DIR = "custom"
        const val MAX_EDGE = 1440

        const val ERR_NOT_FOUND = "文件不存在"
        const val ERR_UNDECODABLE = "这张图读不出来（支持 png / jpg / webp）"
        const val ERR_WRITE = "写入失败，换一张图试试"
    }

    private val dir: File = File(context.applicationContext.filesDir, DIR)
    private val coverFile = File(dir, "cover.png")
    private val backgroundFile = File(dir, "background.png")

    private val _cover = MutableStateFlow<Bitmap?>(null)
    private val _background = MutableStateFlow<Bitmap?>(null)

    val cover: StateFlow<Bitmap?> = _cover.asStateFlow()
    val background: StateFlow<Bitmap?> = _background.asStateFlow()

    init {
        _cover.value = decodeIfPresent(coverFile)
        _background.value = decodeIfPresent(backgroundFile)
    }

    fun hasCover(): Boolean = coverFile.exists()

    fun hasBackground(): Boolean = backgroundFile.exists()

    fun coverPath(): String = coverFile.absolutePath

    fun backgroundPath(): String = backgroundFile.absolutePath

    /** 设置自定义封面。返回空串表示成功，否则是给用户看的原因。 */
    fun setCoverFromPath(path: String): String = save(path, coverFile, _cover)

    fun setBackgroundFromPath(path: String): String = save(path, backgroundFile, _background)

    fun resetCover() {
        coverFile.delete()
        _cover.value = null
    }

    fun resetBackground() {
        backgroundFile.delete()
        _background.value = null
    }

    private fun save(path: String, target: File, sink: MutableStateFlow<Bitmap?>): String {
        val src = File(path)
        if (!src.exists()) return ERR_NOT_FOUND
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return ERR_UNDECODABLE
        val scaled = downscale(bmp)
        return runCatching {
            dir.mkdirs()
            FileOutputStream(target).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
            sink.value = scaled
            ""
        }.getOrElse { ERR_WRITE }
    }

    private fun decodeIfPresent(f: File): Bitmap? =
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null

    private fun downscale(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_EDGE) return bmp
        val scale = MAX_EDGE.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
