package com.pullup.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pullup.player.data.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 身份码有效性实测（需求 6：`测试身份码功能是否真实有效`）。
 *
 * 之前 DataLayerTest 里那条「身份码往返」只验了「自己导出的码自己能吃回去」，而且**改完就断言、
 * 只查返回串** —— 字段一个都不查。空往返（导出读默认值、导入啥也不干）照样能过。这里把「有效」
 * 拆成五个可证伪的判据：
 *
 * 1. **真的有内容**：导出的码解出来必须是 JSON，且带 `v` 版本闸门与 `weights`；
 * 2. **真的能改状态**：先把偏好推离默认值，导出；再 `reset()` 回默认；导入后**每个字段都要回到
 *    导出时的值** —— 这才是「换机恢复」的真实语义；
 * 3. **真的会落盘**：导入后重开一个 [Preferences]（相当于杀进程重来），值仍在；
 * 4. **坏码被拒且不篡改**：各类坏码都要给出中文原因，且**一个字段都不许动**；
 * 5. **别人的码能覆盖我的**：导入是「覆盖」而非「合并」，否则换机会残留自己的旧偏好。
 *
 * 覆盖范围与 Godot 原版（`Preferences.gd:421 _code_payload()` = `v/weights/motion/tags/languages`）
 * 的差异也钉在这里：Kotlin 版**多带** `source/bitrate/speed/panel/dark`。两边互为超集，旧码能被
 * 新端读（缺的字段走 `optXxx` 默认值），新码也能被旧端读（多余的键被忽略）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityCodeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 身份码实际承载的字段。
     *
     * 刻意**不含** `volume` / `nightMode` / `bootAnimation` —— 它们不在 `exportCode()` 的载荷里，
     * 放进来会让断言在「导入什么都没做」时也成立，属于假绿。
     */
    private data class Snapshot(
        val speed: Float,
        val bitrate: Int,
        val source: Int,
        val rhythm: Int,
        val tags: Set<String>,
    ) {
        companion object {
            fun of(p: Preferences) = Snapshot(
                speed = p.speed(),
                bitrate = p.bitrateIndex(),
                source = p.sourceIndex(),
                rhythm = p.weight("rhythm"),
                tags = p.tags().toSet(),
            )
        }
    }

    /** 把偏好推离默认值，返回这组期望值。字段名用 `KINDS` 里的键（rhythm/melody/vocal）。 */
    private fun mutate(prefs: Preferences): Snapshot {
        prefs.setSpeed(1.75f)
        prefs.setBitrateIndex(2)
        prefs.cycleSource()
        prefs.setWeight("rhythm", 88)
        prefs.tags().toList().forEach { prefs.removeTag(it) }
        prefs.addTag("深夜")
        prefs.addTag("通勤")
        return Snapshot.of(prefs)
    }

    /** 等异步写盘落地：`save()` 走 `Dispatchers.IO` 的 `launch`，不阻塞调用方。 */
    private fun awaitPersisted(expected: Snapshot, timeoutMs: Long = 5_000): Preferences {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = Preferences(context)
        while (System.currentTimeMillis() < deadline) {
            if (Snapshot.of(last) == expected) return last
            Thread.sleep(25)
            last = Preferences(context)
        }
        return last
    }

    @Test
    fun `身份码 导出的是结构化数据 不是空壳`() {
        val prefs = Preferences(context)
        val code = prefs.exportCode()
        assertTrue("应以 PU1- 开头", code.startsWith("PU1-"))

        val body = code.removePrefix("PU1-")
        val json = String(android.util.Base64.decode(body, android.util.Base64.DEFAULT), Charsets.UTF_8)
        assertTrue("应是 JSON 对象，实际：$json", json.trimStart().startsWith("{"))
        // v 是版本闸门：缺了它导入端一律拒绝
        assertTrue("应带版本号 v", json.contains("\"v\""))
        assertTrue("应带权重 weights", json.contains("\"weights\""))
        assertTrue("应带标签 tags", json.contains("\"tags\""))
    }

    @Test
    fun `身份码 换机恢复 每个字段都真的被改回去`() {
        val prefs = Preferences(context)
        val expected = mutate(prefs)
        val code = prefs.exportCode()

        // 抹掉：回默认值，并确认确实和导出时不同（否则这测试是假绿）
        prefs.reset()
        assertNotEquals("reset 后应与导出时不同，否则测不出东西", expected, Snapshot.of(prefs))

        val reason = prefs.importCode(code)
        assertEquals("导入应成功，实际：$reason", "", reason)
        assertEquals("每个字段都应回到导出时的值", expected, Snapshot.of(prefs))
    }

    @Test
    fun `身份码 导入后重开仍在`() {
        val prefs = Preferences(context)
        val expected = mutate(prefs)
        val code = prefs.exportCode()
        prefs.reset()
        assertEquals("", prefs.importCode(code))

        // 重开一个实例＝模拟杀进程重来：值必须从磁盘回来，不能只活在内存镜像里
        val reopened = awaitPersisted(expected)
        assertEquals("重开后应等于导出时的值", expected, Snapshot.of(reopened))
    }

    @Test
    fun `身份码 坏码被拒且不篡改既有偏好`() {
        val prefs = Preferences(context)
        val before = mutate(prefs)
        val code = prefs.exportCode()

        val versionNine = android.util.Base64.encodeToString(
            "{\"v\":9}".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )
        listOf(
            "" to "空串",
            "   " to "纯空白",
            "NOPE-123" to "前缀不对",
            "PU1-@@@@" to "非法字符",
            "PU1-abcdefgh" to "是 base64 但不是 JSON",
            "PU1-$versionNine" to "版本号不认得",
        ).forEach { (bad, label) ->
            val reason = prefs.importCode(bad)
            assertTrue("坏码（$label）应被拒绝，却返回了空串", reason.isNotEmpty())
            assertEquals("坏码（$label）不该改动任何偏好", before, Snapshot.of(prefs))
        }

        // 而且原本的码仍然可用 —— 坏码没把状态搞坏
        assertEquals("", prefs.importCode(code))
        assertEquals(before, Snapshot.of(prefs))
    }

    @Test
    fun `身份码 别人分享的码能覆盖我的偏好`() {
        val prefs = Preferences(context)

        // 「别人」：一组值，导出成码
        val theirs = mutate(prefs)
        val code = prefs.exportCode()

        // 「我」：换成完全不同的一组
        prefs.reset()
        prefs.setSpeed(0.5f)
        prefs.setBitrateIndex(0)
        prefs.setWeight("rhythm", 5)
        prefs.tags().toList().forEach { prefs.removeTag(it) }
        assertNotEquals(theirs, Snapshot.of(prefs))

        // 导入对方的码 → 应被完全覆盖成对方那一组
        assertEquals("", prefs.importCode(code))
        assertEquals("导入后应等于对方的值", theirs, Snapshot.of(prefs))
    }
}
