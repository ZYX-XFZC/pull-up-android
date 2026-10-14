package com.pullup.player.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 本地曲库：把 mp3/ogg/wav **拷进应用私有目录**（原文件随后可以搬走），
 * 并以负 id 的普通歌曲形式暴露给上层。
 *
 * 移植自 Godot 版 scripts/LocalLibrary.gd（目录 user://music，索引 user://local_library.cfg）。
 * 时长缓存沿用原口径：key 是文件名，值是**秒**。
 */
class LocalLibrary(private val context: Context) {

    companion object {
        const val DIR = "music"

        /** 负 id 基址，保证与网易云正 id 永不冲突。 */
        const val ID_BASE = -100_000L
        const val ID_SPAN = 90_000L

        val EXTENSIONS = listOf("mp3", "ogg", "wav")
    }

    private val musicDir: File = File(context.applicationContext.filesDir, DIR)
    private val indexFile = JsonFile.file(context, "local_library.json")

    /** 文件名 → 时长（秒）。 */
    private val durations = mutableMapOf<String, Float>()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    init {
        loadIndex()
        refresh()
    }

    fun list(): List<Track> = _tracks.value

    fun count(): Int = _tracks.value.size

    /** 目录路径，供上层展示/导入。 */
    fun directory(): File = musicDir

    /**
     * 导入若干本地文件。返回 (成功数, 跳过的文件名, 失败的文件名)。
     *
     * 目标文件已存在时**直接跳过，且不算失败**（与 Godot 版一致）。
     */
    fun import(paths: List<String>): ImportResult {
        musicDir.mkdirs()
        var imported = 0
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        paths.forEach { raw ->
            val src = File(raw)
            val name = src.name
            if (!src.exists() || !src.isFile) {
                failed.add(name)
                return@forEach
            }
            if (src.extension.lowercase() !in EXTENSIONS) {
                failed.add(name)
                return@forEach
            }
            val target = File(musicDir, name)
            if (target.exists()) {
                skipped.add(name)
                return@forEach
            }
            val ok = runCatching {
                src.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                true
            }.getOrDefault(false)
            if (ok) imported++ else failed.add(name)
        }

        if (imported > 0) refresh()
        return ImportResult(imported, skipped, failed)
    }

    /**
     * 从 SAF 返回的 content:// URI 导入。Android 上拿不到真实文件路径
     * （[import] 那条按路径走的通路在真机上基本用不到），必须走 ContentResolver 流式拷贝。
     *
     * 文件名从 [OpenableColumns.DISPLAY_NAME] 取；取不到就退回 URI 末段。
     * 判定口径与 [import] 完全一致：扩展名不支持算失败、目标已存在算跳过。
     */
    fun importFromUris(uris: List<Uri>): ImportResult {
        musicDir.mkdirs()
        var imported = 0
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val resolver = context.applicationContext.contentResolver

        uris.forEach { uri ->
            val name = displayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
            if (name.isEmpty()) {
                failed.add(uri.toString())
                return@forEach
            }
            if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) {
                failed.add(name)
                return@forEach
            }
            val target = File(musicDir, name)
            if (target.exists()) {
                skipped.add(name)
                return@forEach
            }
            val ok = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (ok) {
                imported++
            } else {
                // 半截文件不能留在库里，否则会成为一个点不开的坏条目。
                target.takeIf { it.exists() }?.delete()
                failed.add(name)
            }
        }

        if (imported > 0) refresh()
        return ImportResult(imported, skipped, failed)
    }

    /** 查 SAF 文档的展示名。查不到返回 null（调用方会退回 URI 末段）。 */
    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()

    /** 删掉一首本地曲目（连文件一起）。 */
    fun removeTrack(id: Long) {
        val track = _tracks.value.firstOrNull { it.id == id } ?: return
        if (track.path.isNotEmpty()) File(track.path).takeIf { it.exists() }?.delete()
        refresh()
    }

    /** 清空整个本地库。 */
    fun clear() {
        musicDir.listFiles()?.forEach { it.delete() }
        durations.clear()
        saveIndex()
        refresh()
    }

    /** 重新扫目录。不存在的文件的时长缓存会被清掉。 */
    fun refresh() {
        musicDir.mkdirs()
        val files = musicDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS } ?: emptyList()
        val present = files.map { it.name }.toSet()
        durations.keys.retainAll(present)

        val tracks = files.sortedBy { it.name }.map { f ->
            val ext = f.extension.uppercase()
            val seconds = durations[f.name] ?: 0f
            Track(
                id = idFor(f.name),
                name = f.nameWithoutExtension,
                artist = "本地文件",
                album = ext,
                cover = "",
                // 毫秒；为 0 时界面显示 0:00（时长靠 Media3 探测后回填）。
                duration = (seconds * 1000f).toInt(),
                local = true,
                path = f.absolutePath,
            )
        }
        _tracks.value = tracks
        saveIndex()
    }

    /** 由播放器探测到真实时长后回填，避免列表一直显示 0:00。 */
    fun setDuration(fileName: String, seconds: Float) {
        if (seconds <= 0f) return
        durations[fileName] = seconds
        _tracks.value = _tracks.value.map { if (File(it.path).name == fileName) it.copy(duration = (seconds * 1000f).toInt()) else it }
        saveIndex()
    }

    /**
     * 由文件名算稳定 id：同一文件名每次启动都得到同一个负数。
     */
    private fun idFor(fileName: String): Long =
        ID_BASE - (kotlin.math.abs(fileName.hashCode().toLong()) % ID_SPAN)

    private fun loadIndex() {
        val obj = JsonFile.read(indexFile) ?: return
        val d = obj.optJSONObject("durations") ?: return
        d.keys().forEach { k -> durations[k] = d.optDouble(k, 0.0).toFloat() }
    }

    private fun saveIndex() {
        val d = JSONObject()
        durations.forEach { (k, v) -> d.put(k, v.toDouble()) }
        JsonFile.write(indexFile, JSONObject().put("durations", d))
    }
}

/** 导入结果。 */
data class ImportResult(
    val imported: Int,
    val skipped: List<String>,
    val failed: List<String>,
)
