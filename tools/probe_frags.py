# 在 release dex 里定位关键片段的上下文（输出写文件，避免控制台编码干扰）
import glob, re, io

blobs = []
for p in sorted(glob.glob(r"D:\PullUp\_verify\*.dex")):
    with open(p, "rb") as f:
        blobs.append((p, f.read()))

frags = ["搜索歌曲", "还会见面", "后悔", "口味排序", "按口味", "确认删掉", "我点错了", "是..."]

out = io.StringIO()
for frag in frags:
    b = frag.encode("utf-8")
    out.write(f"### {frag}\n")
    found = False
    for p, data in blobs:
        start = 0
        while True:
            i = data.find(b, start)
            if i < 0:
                break
            found = True
            s = max(0, i - 40)
            e = min(len(data), i + 80)
            # 只保留可打印 UTF-8 片段
            seg = data[s:e]
            try:
                txt = seg.decode("utf-8", errors="replace")
            except Exception:
                txt = repr(seg)
            txt = txt.replace("\n", " ")
            out.write(f"  [{p.split(chr(92))[-1]}] ...{txt}...\n")
            start = i + 1
            if start - i > 0 and out.tell() > 200000:
                break
    if not found:
        out.write("  (not found)\n")

with open(r"D:\PullUp\_verify\frag.txt", "w", encoding="utf-8") as f:
    f.write(out.getvalue())
print("written")
