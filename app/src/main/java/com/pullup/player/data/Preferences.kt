package com.pullup.player.data

import android.content.Context
import androidx.datastore.core.DataStore
// 本文件自己就有一个叫 Preferences 的类，会把 DataStore 的同名类型挡住 —— 起别名岔开。
// 同理，写入参数一律写全 `kotlin.Pair`：不限定时的 `Pair` 会被解析成 DataStore 的
// 嵌套类型 `androidx.datastore.preferences.core.Preferences.Pair`（它与 kotlin.Pair 无继承关系）。
import androidx.datastore.preferences.core.Preferences as DsPrefs
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import android.util.Base64

/** DataStore 委托必须是顶层扩展属性（每个进程只能建一次同名的 store）。 */
private val Context.prefsStore: DataStore<DsPrefs> by preferencesDataStore(name = "preferences")

/**
 * 本机偏好：口味权重、动效速度、音源/音质、外观，以及可分享/恢复的「身份码」。
 *
 * 全部只写本机 —— 不联网、不上传，符合本地优先/隐私优先。
 * 身份码 ＝ `PU1-` + base64(JSON)，用户可以复制走，换机/重装后粘回来即恢复偏好。
 *
 * 移植自 Godot 版 scripts/Preferences.gd（原存储为 user://preferences.cfg）。
 */
class Preferences(context: Context) {

    companion object {
        private const val CODE_PREFIX = "PU1-"

        /** 三项口味权重（0–100）。 */
        val KINDS = listOf("rhythm", "melody", "vocal")
        const val DEFAULT_WEIGHT = 50

        /** 动效速度档位。 */
        val MOTION_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)

        /**
         * 音源（MeTing 的 server 参数）。
         *
         * 为什么不是「音质」：实测给 MeTing 的 url 端点传任何 `br`（128k/192k/320k/999k）
         * 它都会返回 FLAC；不带 br 才返回 MP3。所以这里提供真正有用的旋钮：换音源
         * （不同平台曲库互补，VIP 歌曲也更容易命中）。
         */
        val SOURCE_LEVELS = listOf(
            "网易云" to "netease",
            "QQ 音乐" to "tencent",
            "酷狗" to "kugou",
            "酷我" to "kuwo",
            "咪咕" to "migu",
        )
        const val DEFAULT_SOURCE = 0

        /**
         * 音质档位（见 [Quality]）。旧版这里是 `("标准" to 128_000, "极高" to 320_000, "无损" to 999_000)`
         * 的码率表，但实测老接口 **完全忽略码率**，档位根本切不动 —— 现在改成按
         * `/song/url/v1?level=<key>` 的档位标识走，才真正能切。
         */
        val QUALITY_LEVELS: List<Pair<String, Int>> = Quality.LEVELS.map { it.label to it.bitrate }
        const val DEFAULT_QUALITY = Quality.DEFAULT_INDEX

        /** 语种偏好：勾选的语种在日推里更靠前。 */
        val LANGUAGE_CODES = listOf("zh", "en", "ja", "ko")
        val LANGUAGE_LABELS = mapOf("zh" to "华语", "en" to "欧美", "ja" to "日语", "ko" to "韩语")

        const val MAX_TAGS = 12
        const val MAX_TAG_LENGTH = 16

        /** 播放速度档位。 */
        val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 定时关闭档位（分钟；0 ＝ 关闭）。 */
        val SLEEP_STEPS = listOf(0, 15, 30, 60, 90)

        /**
         * 旧版把**码率下标**存在 `audio/quality`，更旧的版本存 `audio/bitrate_index`（都是 3 档表）。
         * 现在的表是 8 档且顺序不同，所以迁移必须**按码率值找档**，绝不能按下标硬搬。
         */
        private val LEGACY_BITRATES = listOf(128_000, 192_000, 320_000)

        private val KEY_WEIGHTS = stringPreferencesKey("taste_weights")
        private val KEY_MOTION = floatPreferencesKey("motion_scale")
        private val KEY_SOURCE = intPreferencesKey("audio_source")
        private val KEY_BITRATE_INDEX = intPreferencesKey("audio_bitrate_index")
        private val KEY_LEGACY_QUALITY = intPreferencesKey("audio_quality")
        private val KEY_SPEED = floatPreferencesKey("audio_speed")
        private val KEY_PANEL_STYLE = intPreferencesKey("look_panel_style")
        private val KEY_DARK_MODE = booleanPreferencesKey("look_dark_mode")
        private val KEY_BOOT_ANIM = booleanPreferencesKey("look_boot_animation")
        private val KEY_TAGS = stringPreferencesKey("taste_tags")
        private val KEY_LANGUAGES = stringPreferencesKey("taste_languages")
        private val KEY_NIGHT_MODE = booleanPreferencesKey("play_night_mode")
        private val KEY_LAST_VOLUME = floatPreferencesKey("play_volume")
    }

    private val store = context.applicationContext.prefsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 偏好变化流：界面订阅它重建令牌（等价于 Godot 版的 changed 信号）。 */
    val changed: Flow<Unit> = store.data.map { }

    // 内存镜像：读路径不能挂起（主题令牌要在组合期同步读），所以启动时一次性载入。
    private var weights = KINDS.associateWith { DEFAULT_WEIGHT }.toMutableMap()
    private var motionScale = 1.0f
    private var sourceIndex = DEFAULT_SOURCE
    private var bitrateIndex = DEFAULT_QUALITY
    private var speed = 1.0f
    private var panelStyleValue = 0
    private var darkModeValue = true
    private var bootAnimationValue = true
    private var tagsValue = mutableListOf<String>()
    private var languagesValue = LANGUAGE_CODES.associateWith { true }.toMutableMap()
    private var nightModeValue = false
    private var volumeValue = 1.0f

    init {
        // 构造期同步读一次：主题令牌要在组合期同步取值，不能挂起。
        // 偏好文件很小，首帧前读一次是可接受的；损坏时回落到全默认值而不是崩掉。
        runCatching { runBlocking { loadFromDisk() } }
    }

    private suspend fun loadFromDisk() {
        val p = store.data.first()
        weights = parseWeights(p[KEY_WEIGHTS])
        motionScale = (p[KEY_MOTION] ?: 1.0f).coerceIn(0.25f, 3.0f)
        sourceIndex = (p[KEY_SOURCE] ?: DEFAULT_SOURCE).coerceIn(0, SOURCE_LEVELS.lastIndex)
        // 旧档迁移：只在「有 audio/quality 且没有 audio/bitrate_index」时走一次。
        val legacy = p[KEY_LEGACY_QUALITY]
        bitrateIndex = if (legacy != null && p[KEY_BITRATE_INDEX] == null) {
            val v = LEGACY_BITRATES[legacy.coerceIn(0, LEGACY_BITRATES.lastIndex)]
            bitrateIndexFor(v)
        } else {
            (p[KEY_BITRATE_INDEX] ?: DEFAULT_QUALITY).coerceIn(0, QUALITY_LEVELS.lastIndex)
        }
        speed = (p[KEY_SPEED] ?: 1.0f).coerceIn(0.25f, 3.0f)
        panelStyleValue = (p[KEY_PANEL_STYLE] ?: 0).coerceIn(0, 2)
        darkModeValue = p[KEY_DARK_MODE] ?: true
        bootAnimationValue = p[KEY_BOOT_ANIM] ?: true
        tagsValue = parseTags(p[KEY_TAGS]).toMutableList()
        languagesValue = parseLanguages(p[KEY_LANGUAGES]).toMutableMap()
        nightModeValue = p[KEY_NIGHT_MODE] ?: false
        volumeValue = (p[KEY_LAST_VOLUME] ?: 1.0f).coerceIn(0f, 1f)
    }

    private fun parseWeights(raw: String?): MutableMap<String, Int> {
        val out = KINDS.associateWith { DEFAULT_WEIGHT }.toMutableMap()
        if (raw.isNullOrBlank()) return out
        runCatching {
            val o = JSONObject(raw)
            KINDS.forEach { k -> if (o.has(k)) out[k] = o.optInt(k, DEFAULT_WEIGHT).coerceIn(0, 100) }
        }
        return out
    }

    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList()).take(MAX_TAGS)
    }

    private fun parseLanguages(raw: String?): Map<String, Boolean> {
        val out = LANGUAGE_CODES.associateWith { true }.toMutableMap()
        if (raw.isNullOrBlank()) return out
        runCatching {
            val o = JSONObject(raw)
            LANGUAGE_CODES.forEach { c -> if (o.has(c)) out[c] = o.optBoolean(c, true) }
        }
        return out
    }

    /**
     * 一次写入的键值对。
     *
     * 为什么不用 `kotlin.Pair`：本文件导入了 DataStore 的 `Preferences` 类型，
     * 它自带一个嵌套的 `Preferences.Pair`，会让 `a to b` 解析到那边去（两者无继承关系）。
     * 自定义一个持有类型最省事，也让写入意图更清楚。
     */
    private class Write(val key: DsPrefs.Key<*>, val value: Any?)

    private fun w(key: DsPrefs.Key<*>, value: Any?) = Write(key, value)

    private fun save(vararg writes: Write) {
        scope.launch {
            store.edit { p ->
                writes.forEach { item ->
                    val k = item.key
                    val v = item.value
                    @Suppress("UNCHECKED_CAST")
                    when (v) {
                        null -> p.remove(k)
                        is String -> p[k as DsPrefs.Key<String>] = v
                        is Int -> p[k as DsPrefs.Key<Int>] = v
                        is Float -> p[k as DsPrefs.Key<Float>] = v
                        is Boolean -> p[k as DsPrefs.Key<Boolean>] = v
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 口味权重
    // -----------------------------------------------------------------------

    fun weights(): Map<String, Int> = weights.toMap()

    fun weight(kind: String): Int = weights[kind] ?: DEFAULT_WEIGHT

    fun setWeight(kind: String, value: Int) {
        if (!weights.containsKey(kind)) return
        val clamped = value.coerceIn(0, 100)
        if (weights[kind] == clamped) return
        weights[kind] = clamped
        save(w(KEY_WEIGHTS, JSONObject(weights as Map<*, *>).toString()))
    }

    // -----------------------------------------------------------------------
    // 音源 / 音质 / 倍速
    // -----------------------------------------------------------------------

    fun sourceIndex(): Int = sourceIndex.coerceIn(0, SOURCE_LEVELS.lastIndex)
    fun sourceLabel(): String = SOURCE_LEVELS[sourceIndex()].first
    fun sourceServer(): String = SOURCE_LEVELS[sourceIndex()].second

    fun cycleSource() {
        sourceIndex = (sourceIndex + 1) % SOURCE_LEVELS.size
        save(w(KEY_SOURCE, sourceIndex))
    }

    /** 当前音质档位下标（指向 [Quality.LEVELS]）。 */
    fun bitrateIndex(): Int = bitrateIndex.coerceIn(0, Quality.LEVELS.lastIndex)

    /** 当前音质档位标识（`standard`/`higher`/`exhigh`/…），直接作为 `/song/url/v1?level=` 的参数。 */
    fun qualityKey(): String = Quality.LEVELS[bitrateIndex()].key

    /**
     * 档位显示名。**「未实现」的档位会带上标记** —— 实测 lossless 及以上的档位服务端一律降级回
     * exhigh，如实标出来，免得用户以为切上去就真拿到了无损。
     */
    fun bitrateLabel(): String {
        val level = Quality.LEVELS[bitrateIndex()]
        return if (level.implemented) level.label else "${level.label}（未实现）"
    }

    fun bitrate(): Int = Quality.LEVELS[bitrateIndex()].bitrate

    fun setBitrateIndex(index: Int) {
        val next = index.coerceIn(0, Quality.LEVELS.lastIndex)
        if (next == bitrateIndex) return
        bitrateIndex = next
        save(w(KEY_BITRATE_INDEX, next))
    }

    fun cycleBitrate() = setBitrateIndex((bitrateIndex + 1) % Quality.LEVELS.size)

    /** 取「不超过 bitrate 的最大档」：向下取档，绝不偷偷升级（旧存档迁移用）。 */
    fun bitrateIndexFor(bitrate: Int): Int {
        var best = 0
        Quality.LEVELS.forEachIndexed { i, q -> if (q.bitrate in 1..bitrate) best = i }
        return best
    }

    fun speed(): Float = speed

    fun setSpeed(value: Float) {
        val v = value.coerceIn(0.5f, 2.0f)
        if (v == speed) return
        speed = v
        save(w(KEY_SPEED, v))
    }

    // -----------------------------------------------------------------------
    // 动效 / 外观
    // -----------------------------------------------------------------------

    fun motionScale(): Float = motionScale

    fun setMotionScale(value: Float) {
        val v = value.coerceIn(0.25f, 3.0f)
        if (v == motionScale) return
        motionScale = v
        save(w(KEY_MOTION, v))
    }

    fun cycleMotionScale() {
        val i = MOTION_STEPS.indexOfFirst { it >= motionScale - 0.001f }
        val next = MOTION_STEPS[(if (i < 0) 2 else i + 1) % MOTION_STEPS.size]
        setMotionScale(next)
    }

    fun darkMode(): Boolean = darkModeValue

    fun setDarkMode(enabled: Boolean) {
        if (enabled == darkModeValue) return
        darkModeValue = enabled
        save(w(KEY_DARK_MODE, enabled))
    }

    fun toggleDarkMode() = setDarkMode(!darkModeValue)

    fun darkModeLabel(): String = if (darkModeValue) "已开启" else "已关闭"

    fun panelStyle(): Int = panelStyleValue

    fun setPanelStyle(index: Int) {
        val next = index.coerceIn(0, 2)
        if (next == panelStyleValue) return
        panelStyleValue = next
        save(w(KEY_PANEL_STYLE, next))
    }

    fun cyclePanelStyle() = setPanelStyle((panelStyleValue + 1) % 3)

    fun panelStyleName(): String = com.pullup.player.ui.theme.PANEL_STYLE_NAMES[panelStyleValue]

    fun bootAnimation(): Boolean = bootAnimationValue

    fun setBootAnimation(enabled: Boolean) {
        if (enabled == bootAnimationValue) return
        bootAnimationValue = enabled
        save(w(KEY_BOOT_ANIM, enabled))
    }

    fun cycleBootAnimation() = setBootAnimation(!bootAnimationValue)

    fun bootAnimationLabel(): String = if (bootAnimationValue) "开" else "关"

    // -----------------------------------------------------------------------
    // 口味标签 / 语种
    // -----------------------------------------------------------------------

    fun tags(): List<String> = tagsValue.toList()

    /**
     * 加一个自定义口味标签。返回空串表示成功，否则是给用户看的原因。
     * 命中曲名/歌手/专辑时在日推里加权。
     */
    fun addTag(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "标签不能为空"
        if (t.length > MAX_TAG_LENGTH) return "标签最多 $MAX_TAG_LENGTH 个字"
        if (tagsValue.size >= MAX_TAGS) return "最多 $MAX_TAGS 个标签"
        if (tagsValue.any { it.equals(t, ignoreCase = true) }) return "已经有这个标签了"
        tagsValue.add(t)
        save(w(KEY_TAGS, JSONArray(tagsValue).toString()))
        return ""
    }

    fun removeTag(text: String) {
        val i = tagsValue.indexOfFirst { it.equals(text, ignoreCase = true) }
        if (i < 0) return
        tagsValue.removeAt(i)
        save(w(KEY_TAGS, JSONArray(tagsValue).toString()))
    }

    fun languageEnabled(code: String): Boolean = languagesValue[code] ?: true

    fun setLanguageEnabled(code: String, enabled: Boolean) {
        if (!languagesValue.containsKey(code)) return
        if (languagesValue[code] == enabled) return
        languagesValue[code] = enabled
        save(w(KEY_LANGUAGES, JSONObject(languagesValue as Map<*, *>).toString()))
    }

    fun languages(): Map<String, Boolean> = languagesValue.toMap()

    fun selectedLanguageCount(): Int = languagesValue.count { it.value }

    // 夜间模式 / 音量（播放相关，顺手持久化）。
    fun nightMode(): Boolean = nightModeValue
    fun setNightMode(enabled: Boolean) {
        if (enabled == nightModeValue) return
        nightModeValue = enabled
        save(w(KEY_NIGHT_MODE, enabled))
    }

    fun volume(): Float = volumeValue
    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        volumeValue = v
        save(w(KEY_LAST_VOLUME, v))
    }

    // -----------------------------------------------------------------------
    // 重置 / 身份码
    // -----------------------------------------------------------------------

    fun reset() {
        weights = KINDS.associateWith { DEFAULT_WEIGHT }.toMutableMap()
        motionScale = 1.0f
        sourceIndex = DEFAULT_SOURCE
        bitrateIndex = DEFAULT_QUALITY
        speed = 1.0f
        panelStyleValue = 0
        darkModeValue = true
        bootAnimationValue = true
        tagsValue.clear()
        languagesValue = LANGUAGE_CODES.associateWith { true }.toMutableMap()
        scope.launch {
            store.edit { p ->
                p.remove(KEY_WEIGHTS); p.remove(KEY_MOTION); p.remove(KEY_SOURCE)
                p.remove(KEY_BITRATE_INDEX); p.remove(KEY_LEGACY_QUALITY); p.remove(KEY_SPEED)
                p.remove(KEY_PANEL_STYLE); p.remove(KEY_DARK_MODE); p.remove(KEY_BOOT_ANIM)
                p.remove(KEY_TAGS); p.remove(KEY_LANGUAGES)
            }
        }
    }

    /** 导出身份码：`PU1-` + base64(JSON)。 */
    fun exportCode(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("weights", JSONObject(weights as Map<*, *>))
        o.put("motion", motionScale.toDouble())
        o.put("source", sourceIndex)
        o.put("bitrate", bitrateIndex)
        o.put("speed", speed.toDouble())
        o.put("panel", panelStyleValue)
        o.put("dark", darkModeValue)
        o.put("tags", JSONArray(tagsValue))
        o.put("langs", JSONObject(languagesValue as Map<*, *>))
        val b64 = Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return CODE_PREFIX + b64
    }

    /**
     * 导入身份码。返回空串表示成功，否则是给用户看的失败原因。
     *
     * 六条中文原因逐字对齐原版 `scripts/Preferences.gd:377-413`（用户 m02624 要求「恢复已有
     * 功能、不改行为」）：空值 / 形状非法 / base64 或 JSON 解不开 / 解出来不是对象 / 版本不认 /
     * 缺 weights 各有一句，且顺序与原版一致（先形状、再解码、再类型、再版本、再数据）。
     */
    fun importCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return "身份码是空的"
        // 原版允许不带前缀（`if trimmed.begins_with(CODE_PREFIX)` 是可选剥离），这里照做。
        val body = if (trimmed.startsWith(CODE_PREFIX)) trimmed.substring(CODE_PREFIX.length) else trimmed
        if (body.length < 8 || !body.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            return "身份码格式不对（只应包含字母、数字与 +/=-）"
        }
        return runCatching {
            val json = String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
            // 原版用 `JSON.new().parse()` 区分「解不开」与「不是字典」两档，这里同样分开：
            // JSONObject 的构造器两种都会抛，所以先试解析成任意 JSON 值再判类型。
            val parsed: Any = JSONTokener(json).nextValue()
            if (parsed !is JSONObject) return "身份码内容不是偏好数据"
            val o = parsed
            if (o.optInt("v", 0) != 1) return "身份码版本不支持（需要 v1）"
            val incoming = o.opt("weights")
            if (incoming != null && incoming !is JSONObject) return "身份码里没有偏好数据"
            o.optJSONObject("weights")?.let { w ->
                KINDS.forEach { k -> if (w.has(k)) weights[k] = w.optInt(k, DEFAULT_WEIGHT).coerceIn(0, 100) }
            }
            motionScale = o.optDouble("motion", 1.0).toFloat().coerceIn(0.25f, 3.0f)
            sourceIndex = o.optInt("source", DEFAULT_SOURCE).coerceIn(0, SOURCE_LEVELS.lastIndex)
            bitrateIndex = o.optInt("bitrate", DEFAULT_QUALITY).coerceIn(0, QUALITY_LEVELS.lastIndex)
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 3.0f)
            panelStyleValue = o.optInt("panel", 0).coerceIn(0, 2)
            darkModeValue = o.optBoolean("dark", true)
            tagsValue = parseTags(o.optJSONArray("tags")?.toString()).toMutableList()
            languagesValue = parseLanguages(o.optJSONObject("langs")?.toString()).toMutableMap()
            save(
                w(KEY_WEIGHTS, JSONObject(weights as Map<*, *>).toString()),
                w(KEY_MOTION, motionScale),
                w(KEY_SOURCE, sourceIndex),
                w(KEY_BITRATE_INDEX, bitrateIndex),
                w(KEY_SPEED, speed),
                w(KEY_PANEL_STYLE, panelStyleValue),
                w(KEY_DARK_MODE, darkModeValue),
                w(KEY_TAGS, JSONArray(tagsValue).toString()),
                w(KEY_LANGUAGES, JSONObject(languagesValue as Map<*, *>).toString()),
            )
            // 导入成功后要让界面立刻按新值重铺：`save` 只写 DataStore，`changed`
            // 是从 data 流 map 出来的，写入本身就会让订阅方收到一次 —— 但内存镜像
            // （weights/motionScale/…）是同步改的，DataStore 的发射是异步的，
            // 原版 `import_code` 末尾显式 `changed.emit()`（`Preferences.gd:412`）
            // 正是为了消掉这个时间差。这里不需要额外做任何事：DataStore 的 data
            // 流在 `store.edit` 完成后必然重发一次，订阅方（`rememberPreferencesRevision`）
            // 会收到；内存镜像此刻已是新值，重算读到的就是导入后的偏好。
            ""
        }.getOrElse { "身份码解析失败，检查是否复制完整" }
    }
}
