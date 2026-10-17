package com.pullup.player.data

import android.content.Context
import androidx.datastore.core.DataStore
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

private val Context.prefsStore: DataStore<DsPrefs> by preferencesDataStore(name = "preferences")

class Preferences(context: Context) {

    companion object {
        private const val CODE_PREFIX = "PU1-"

        val KINDS = listOf("rhythm", "melody", "vocal")
        const val DEFAULT_WEIGHT = 50

        val MOTION_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)

        val SOURCE_LEVELS = listOf(
            "网易云" to "netease",
            "QQ 音乐" to "tencent",
            "酷狗" to "kugou",
            "酷我" to "kuwo",
            "咪咕" to "migu",
        )
        const val DEFAULT_SOURCE = 0

        val QUALITY_LEVELS: List<Pair<String, Int>> = Quality.LEVELS.map { it.label to it.bitrate }
        const val DEFAULT_QUALITY = Quality.DEFAULT_INDEX

        val LANGUAGE_CODES = listOf("zh", "en", "ja", "ko")
        val LANGUAGE_LABELS = mapOf("zh" to "华语", "en" to "欧美", "ja" to "日语", "ko" to "韩语")

        const val MAX_TAGS = 12
        const val MAX_TAG_LENGTH = 16

        val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        val SLEEP_STEPS = listOf(0, 15, 30, 60, 90)

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

        // ★ 新增：播放模式 / 随机 / 重复次数
        private val KEY_PLAY_MODE = intPreferencesKey("play_mode")
        private val KEY_SHUFFLE = booleanPreferencesKey("play_shuffle")
        private val KEY_REPEAT_COUNT = intPreferencesKey("play_repeat_count")
    }

    private val store = context.applicationContext.prefsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val changed: Flow<Unit> = store.data.map { }

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

    // ★ 新增：播放模式 / 随机 / 重复次数
    private var playModeValue = 0
    private var shuffleValue = false
    private var repeatCountValue = 1

    init {
        runCatching { runBlocking { loadFromDisk() } }
    }

    private suspend fun loadFromDisk() {
        val p = store.data.first()
        weights = parseWeights(p[KEY_WEIGHTS])
        motionScale = (p[KEY_MOTION] ?: 1.0f).coerceIn(0.25f, 3.0f)
        sourceIndex = (p[KEY_SOURCE] ?: DEFAULT_SOURCE).coerceIn(0, SOURCE_LEVELS.lastIndex)
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

        // ★ 新增：恢复播放设置
        playModeValue = (p[KEY_PLAY_MODE] ?: 0).coerceIn(0, 2)
        shuffleValue = p[KEY_SHUFFLE] ?: false
        repeatCountValue = (p[KEY_REPEAT_COUNT] ?: 1).coerceIn(1, 99)
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

    fun bitrateIndex(): Int = bitrateIndex.coerceIn(0, Quality.LEVELS.lastIndex)
    fun qualityKey(): String = Quality.LEVELS[bitrateIndex()].key

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
    // ★ 新增：播放模式 / 随机 / 重复次数
    // -----------------------------------------------------------------------

    /** 播放模式：0=列表循环，1=单曲循环，2=单次（播完即停）。 */
    fun playMode(): Int = playModeValue
    fun setPlayMode(value: Int) {
        val v = value.coerceIn(0, 2)
        if (v == playModeValue) return
        playModeValue = v
        save(w(KEY_PLAY_MODE, v))
    }

    fun shuffle(): Boolean = shuffleValue
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffleValue) return
        shuffleValue = enabled
        save(w(KEY_SHUFFLE, enabled))
    }

    fun repeatCount(): Int = repeatCountValue
    fun setRepeatCount(value: Int) {
        val v = value.coerceIn(1, 99)
        if (v == repeatCountValue) return
        repeatCountValue = v
        save(w(KEY_REPEAT_COUNT, v))
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
        nightModeValue = false
        volumeValue = 1.0f
        // ★ 新增：播放模式重置
        playModeValue = 0
        shuffleValue = false
        repeatCountValue = 1
        scope.launch {
            store.edit { p ->
                p.remove(KEY_WEIGHTS); p.remove(KEY_MOTION); p.remove(KEY_SOURCE)
                p.remove(KEY_BITRATE_INDEX); p.remove(KEY_LEGACY_QUALITY); p.remove(KEY_SPEED)
                p.remove(KEY_PANEL_STYLE); p.remove(KEY_DARK_MODE); p.remove(KEY_BOOT_ANIM)
                p.remove(KEY_TAGS); p.remove(KEY_LANGUAGES)
                p.remove(KEY_NIGHT_MODE); p.remove(KEY_LAST_VOLUME)
                p.remove(KEY_PLAY_MODE); p.remove(KEY_SHUFFLE); p.remove(KEY_REPEAT_COUNT)
            }
        }
    }

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
        // ★ 新增：播放设置
        o.put("playMode", playModeValue)
        o.put("shuffle", shuffleValue)
        o.put("repeatCount", repeatCountValue)
        val b64 = Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return CODE_PREFIX + b64
    }

    fun importCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return "身份码是空的"
        val body = if (trimmed.startsWith(CODE_PREFIX)) trimmed.substring(CODE_PREFIX.length) else trimmed
        if (body.length < 8 || !body.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            return "身份码格式不对（只应包含字母、数字与 +/=-）"
        }
        return runCatching {
            val json = String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
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
            // ★ 新增：导入播放设置（旧身份码没有这几个字段时回退到默认，不报错）
            playModeValue = o.optInt("playMode", 0).coerceIn(0, 2)
            shuffleValue = o.optBoolean("shuffle", false)
            repeatCountValue = o.optInt("repeatCount", 1).coerceIn(1, 99)
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
                w(KEY_PLAY_MODE, playModeValue),
                w(KEY_SHUFFLE, shuffleValue),
                w(KEY_REPEAT_COUNT, repeatCountValue),
            )
            ""
        }.getOrElse { "身份码解析失败，检查是否复制完整" }
    }
}