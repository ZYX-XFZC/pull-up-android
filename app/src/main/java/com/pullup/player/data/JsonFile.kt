package com.pullup.player.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 本地小库共用的 JSON 文件存储。
 *
 * Godot 版每个 autoload 都是「整份 ConfigFile 读进来 → 改内存 → 整份写回去」。
 * Kotlin 侧沿用同样的语义，只是把 ConfigFile 换成 JSON：
 * 这些库都很小（最多几百条），整份重写的成本可以忽略。
 *
 * 写盘走「临时文件 + rename」，避免半截内容被进程杀掉时损坏。
 */
internal object JsonFile {

    fun file(context: Context, name: String): File =
        File(context.applicationContext.filesDir, name)

    fun read(f: File): JSONObject? {
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun write(f: File, obj: JSONObject) {
        val text = obj.toString()
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
