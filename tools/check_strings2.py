import zipfile, io, os, sys
apk = r"D:\PullUp\app\build\outputs\apk\release\app-release.apk"
z = zipfile.ZipFile(apk)
dexs = [n for n in z.namelist() if n.endswith(".dex")]
blob = b"".join(z.read(n) for n in dexs)
print("dex files:", ", ".join("%s(%d)" % (n, z.getinfo(n).file_size) for n in dexs))
checks = ["还会见面吗", "我后悔了！", "是...", "我点错了", "确认删掉我吗",
          "输入关键词开始搜索", "搜过的词会留在历史里", "最近搜索",
          "今日推荐歌单", "不能手动加歌", "再按一次返回键退出", "已按口味排序"]
for c in checks:
    print(("FOUND  " if c.encode("utf-8") in blob else "MISS   ") + c)
