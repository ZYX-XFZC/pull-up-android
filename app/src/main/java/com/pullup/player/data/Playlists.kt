package com.pullup.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 歌单库：一个自动重建的「今日推荐歌单」+ 用户自建歌单（新建/改名/换封面/设私密+密码/删除），
 * 外加一个永不落盘的「我的收藏」投影。
 *
 * 移植自 Godot 版 scripts/Playlists.gd（原存储 user://playlists.cfg + user://playlist_covers/）。
 */
class Playlists(private val context: Context, private val favorites: Favorites) {

    companion object {
        const val DAILY_ID = "daily"
        const val DAILY_NAME = "今日推荐歌单"
        const val DAILY_CREATOR = "每日推荐"
        const val FAVORITES_ID = "favorites"
        const val FAVORITES_NAME = "我的收藏"
        const val DEFAULT_NAME = "未命名歌单"

        /** 封面最长边上限，超过就等比缩下来再存。 */
        const val MAX_EDGE = 1440

        /** 名字硬截断到 24 个字。 */
        const val MAX_NAME = 24

        private const val COVER_DIR = "playlist_covers"
    }

    private val file = JsonFile.file(context, "playlists.json")
    private val coverDir: File = File(context.applicationContext.filesDir, COVER_DIR)

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())

    /** 全部歌单（日推在最前），界面订阅它。 */
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    /** 本会话内已解锁的私密歌单 id —— **不落盘**，重启即重新上锁。 */
    private val unlocked = mutableSetOf<String>()

    /** 最近一次导入/添加失败的原因（给界面直接显示）。 */
    var lastError: String = ""
        private set

    private var daily: LocalPlaylist? = null
    private var user: MutableList<LocalPlaylist> = mutableListOf()

    init {
        load()
    }

    // -----------------------------------------------------------------------
    // 读
    // -----------------------------------------------------------------------

    /** 日推在前，自建在后。 */
    fun all(): List<LocalPlaylist> {
        val d = daily
        return (if (d != null && d.songs.isNotEmpty()) listOf(d) else emptyList()) + user
    }

    fun userPlaylists(): List<LocalPlaylist> = user.toList()

    fun daily(): LocalPlaylist = daily ?: LocalPlaylist(
        id = DAILY_ID,
        kind = PlaylistKind.DAILY,
        name = DAILY_NAME,
        creator = DAILY_CREATOR,
    )

    fun hasDaily(): Boolean = daily?.songs?.isNotEmpty() == true

    /** 「我的收藏」投影：永不落盘，歌来自 [Favorites]。 */
    fun favoritesPlaylist(): LocalPlaylist {
        val songs = favorites.list()
        return LocalPlaylist(
            id = FAVORITES_ID,
            kind = PlaylistKind.FAVORITES,
            name = FAVORITES_NAME,
            cover = songs.firstOrNull()?.cover ?: "",
            songs = songs,
            trackCount = songs.size,
        )
    }

    fun isFavorites(id: String): Boolean = id == FAVORITES_ID

    fun find(id: String): LocalPlaylist? = when (id) {
        FAVORITES_ID -> favoritesPlaylist()
        DAILY_ID -> daily?.takeIf { it.songs.isNotEmpty() }
        else -> user.firstOrNull { it.id == id }
    }

    /** 自建歌单数量。 */
    fun count(): Int = user.size

    /** 「加入歌单」选择器只列自建歌单。 */
    fun addable(): List<LocalPlaylist> = user.toList()

    fun needsPassword(id: String): Boolean {
        val pl = user.firstOrNull { it.id == id } ?: return false
        return pl.isPrivate && pl.password.isNotEmpty()
    }

    fun isUnlocked(id: String): Boolean = id in unlocked

    fun coverPath(id: String): String = File(coverDir, "$id.png").absolutePath

    // -----------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------

    /** 重建今日推荐歌单（每次刷新推荐都会整份替换）。 */
    fun ensureDaily(songs: List<Track>) {
        if (songs.isEmpty()) return
        daily = LocalPlaylist(
            id = DAILY_ID,
            kind = PlaylistKind.DAILY,
            name = DAILY_NAME,
            cover = songs.firstOrNull()?.cover ?: "",
            songs = songs,
            trackCount = songs.size,
            creator = DAILY_CREATOR,
            updatedAt = System.currentTimeMillis(),
        )
        save()
    }

    /** 新建歌单，返回新 id；失败返回空串并把原因写进 [lastError]。 */
    fun create(name: String, coverSrc: String = "", isPrivate: Boolean = false, password: String = ""): String {
        val clean = cleanName(name)
        val id = newId()
        val cover = if (coverSrc.isNotEmpty()) {
            val r = copyCover(id, coverSrc)
            if (r.isNotEmpty()) {
                lastError = r
                return ""
            }
            coverPath(id)
        } else {
            ""
        }
        val pl = LocalPlaylist(
            id = id,
            kind = PlaylistKind.USER,
            name = clean,
            cover = cover,
            isPrivate = isPrivate,
            // 私密但没留密码 = 不锁（Godot Playlists.gd:175 同口径）：
            // 空串也被 sha256 的话会得到一个非空摘要，needsPassword() 就会误判成「要密码」。
            password = if (isPrivate && password.isNotEmpty()) sha256(password) else "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        user.add(pl)
        if (isPrivate) unlocked.add(id)
        save()
        return id
    }

    /** 往自建歌单里加一首。成功返回 true，失败把中文原因写进 [lastError]。 */
    fun addSong(id: String, song: Track): Boolean {
        // 与原版同口径（Godot Playlists.gd:193-196）：id ≤ 0 一律拒 —— 本地文件用负 id，
        // 它们不在接口体系里，加进歌单后没法回查封面/时长。
        if (song.id <= 0L) {
            lastError = "这首歌没有 id，加不进去"
            return false
        }
        // daily / favorites 不是用户歌单。用固定名字而不是 id：日推还没建起来时
        // `find(DAILY_ID)` 是 null，退回 id 会让文案变成「「daily」不能手动加歌」。
        if (id == DAILY_ID || id == FAVORITES_ID) {
            lastError = "「${if (id == DAILY_ID) DAILY_NAME else FAVORITES_NAME}」不能手动加歌"
            return false
        }
        val pl = user.firstOrNull { it.id == id }
        if (pl == null) {
            lastError = "找不到这个歌单"
            return false
        }
        if (pl.songs.any { it.id == song.id && song.id != 0L }) {
            lastError = "「${pl.name}」里已经有这首了"
            return false
        }
        replace(pl.copy(songs = pl.songs + song, trackCount = pl.songs.size + 1, updatedAt = System.currentTimeMillis()))
        return true
    }

    fun removeSong(id: String, songId: Long) {
        val pl = user.firstOrNull { it.id == id } ?: return
        val next = pl.songs.filterNot { it.id == songId }
        if (next.size == pl.songs.size) return
        replace(pl.copy(songs = next, trackCount = next.size, updatedAt = System.currentTimeMillis()))
    }

    /** 换封面。返回空串表示成功。 */
    fun setCover(id: String, coverSrc: String): String {
        val pl = user.firstOrNull { it.id == id } ?: return "找不到这个歌单"
        if (coverSrc.isEmpty()) {
            deleteCover(id)
            replace(pl.copy(cover = ""))
            return ""
        }
        val err = copyCover(id, coverSrc)
        if (err.isNotEmpty()) return err
        replace(pl.copy(cover = coverPath(id)))
        return ""
    }

    fun rename(id: String, name: String) {
        val pl = user.firstOrNull { it.id == id } ?: return
        replace(pl.copy(name = cleanName(name), updatedAt = System.currentTimeMillis()))
    }

    fun setPrivate(id: String, isPrivate: Boolean, password: String = "") {
        val pl = user.firstOrNull { it.id == id } ?: return
        val hash = if (isPrivate && password.isNotEmpty()) sha256(password) else pl.password
        replace(pl.copy(isPrivate = isPrivate, password = if (isPrivate) hash else ""))
        if (isPrivate) unlocked.add(id) else unlocked.remove(id)
    }

    /** 删除自建歌单（顺手删封面）。日推与收藏拒绝。 */
    fun remove(id: String): Boolean {
        if (id == DAILY_ID || id == FAVORITES_ID) return false
        val pl = user.firstOrNull { it.id == id } ?: return false
        user.remove(pl)
        deleteCover(id)
        unlocked.remove(id)
        save()
        return true
    }

    /** 校验密码。没设密码时恒为 true。 */
    fun verify(id: String, password: String): Boolean {
        val pl = user.firstOrNull { it.id == id } ?: return true
        if (!pl.isPrivate || pl.password.isEmpty()) return true
        return pl.password == sha256(password)
    }

    fun unlock(id: String, password: String): Boolean {
        if (!verify(id, password)) return false
        unlocked.add(id)
        return true
    }

    fun lock(id: String) {
        unlocked.remove(id)
    }

    fun lockAll() {
        unlocked.clear()
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun replace(pl: LocalPlaylist) {
        val i = user.indexOfFirst { it.id == pl.id }
        if (i < 0) return
        user[i] = pl
        save()
    }

    private fun cleanName(name: String): String {
        val t = name.trim()
        val base = t.ifEmpty { DEFAULT_NAME }
        return if (base.length > MAX_NAME) base.substring(0, MAX_NAME) else base
    }

    /** `pl<unix秒>`，撞了就加 `_1`、`_2`… */
    private fun newId(): String {
        val base = "pl" + (System.currentTimeMillis() / 1000L)
        if (user.none { it.id == base }) return base
        var n = 1
        while (user.any { it.id == "${base}_$n" }) n++
        return "${base}_$n"
    }

    private fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** 把用户选的图缩到 MAX_EDGE 以内并存成 `<id>.png`。返回空串表示成功。 */
    private fun copyCover(id: String, src: String): String {
        val source = File(src)
        if (!source.exists()) return "文件不存在"
        val bmp = runCatching { BitmapFactory.decodeFile(src) }.getOrNull() ?: return "这张图读不出来"
        val scaled = downscale(bmp)
        return runCatching {
            coverDir.mkdirs()
            FileOutputStream(File(coverDir, "$id.png")).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            ""
        }.getOrElse { "写入失败，换一张图试试" }
    }

    private fun downscale(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_EDGE) return bmp
        val scale = MAX_EDGE.toFloat() / longest.toFloat()
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** 只删自己目录下的封面文件 —— 进来的路径必须落在 COVER_DIR 里。 */
    private fun deleteCover(id: String) {
        val f = File(coverDir, "$id.png")
        if (f.parentFile?.canonicalPath == coverDir.canonicalPath && f.exists()) f.delete()
    }

    private fun save() {
        val obj = JSONObject()
        daily?.let { obj.put("daily", it.toJson()) }
        obj.put("user", JSONArray().also { arr -> user.forEach { arr.put(it.toJson()) } })
        JsonFile.write(file, obj)
        // 落盘之后必须把 StateFlow 刷新一次：界面（首页歌单架、加入歌单选择器）全靠订阅它
        // 重铺，之前只在 load()/notifyChanged() 里刷，新建/加歌/删除都不会让界面动。
        _playlists.value = all()
    }

    fun load() {
        val obj = JsonFile.read(file) ?: return
        daily = obj.optJSONObject("daily")?.let { LocalPlaylist.fromJson(it) }
        val arr = obj.optJSONArray("user") ?: return
        user = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { LocalPlaylist.fromJson(it) }
        }.toMutableList()
        _playlists.value = all()
    }

    /** 供 StateFlow 观察者刷新（内部状态变化时调用）。 */
    internal fun notifyChanged() {
        _playlists.value = all()
    }
}
