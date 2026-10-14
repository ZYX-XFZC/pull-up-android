import re, io, sys

p = r"D:\PullUp\app\src\main\java\com\pullup\player\data\Preferences.kt"
src = io.open(p, encoding="utf-8").read()

# 1) save() 的形参改成自有的 Write 持有类型，并新增 helper w()。彻底绕开
#    kotlin.Pair 与 DataStore 嵌套 Pair 的名字冲突。
old_save = '''    private fun save(vararg pairs: kotlin.Pair<DsPrefs.Key<*>, Any?>) {
        scope.launch {
            store.edit { p ->
                pairs.forEach { (k, v) ->
                    when (v) {
                        null -> p.remove(k)
                        is String -> p[k] = v
                        is Int -> p[k] = v
                        is Float -> p[k] = v
                        is Boolean -> p[k] = v
                    }
                }
            }
        }
    }
'''
new_save = '''    /**
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
                writes.forEach { (k, v) ->
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
'''
assert old_save in src, "save() block not found"
src = src.replace(old_save, new_save)

lines = src.split("\n")
out = []
n_single = n_multi = 0
for ln in lines:
    m = re.match(r'^(\s*)save\((KEY_[A-Z_]+) to (.+)\)$', ln)
    if m:
        out.append("%ssave(w(%s, %s))" % (m.group(1), m.group(2), m.group(3)))
        n_single += 1
        continue
    m = re.match(r'^(\s*)(KEY_[A-Z_]+) to (.+),$', ln)
    if m:
        out.append("%sw(%s, %s)," % (m.group(1), m.group(2), m.group(3)))
        n_multi += 1
        continue
    out.append(ln)
src = "\n".join(out)

io.open(p, "w", encoding="utf-8", newline="\n").write(src)
print("single-line save rewrites:", n_single)
print("multi-line  save rewrites:", n_multi)
leftover = [l for l in src.split("\n") if re.search(r'\bKEY_[A-Z_]+\s+to\b', l)]
print("leftover 'to' at save sites:", len(leftover))
for l in leftover:
    print("   ", l.strip())
