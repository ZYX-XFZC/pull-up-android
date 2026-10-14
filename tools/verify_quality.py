import zipfile, io, sys
apk = r"D:\PullUp\app\build\outputs\apk\release\app-release.apk"
needles = ["未实现","标准","较高","exhigh","无损","Hi-Res","高清环绕声","沉浸环绕声","高清母带",
           "当前音质：","歌曲 ID 无效","拿不到"]
z = zipfile.ZipFile(apk)
dexes = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
blobs = b"".join(z.read(n) for n in sorted(dexes))
print("dex files:", ", ".join("%s(%d)" % (n, z.getinfo(n).file_size) for n in sorted(dexes)))
print("total dex bytes:", len(blobs))
for s in needles:
    b = s.encode("utf-8")
    print(("FOUND   " if b in blobs else "MISS    ") + s)
# 关键：旧的音质表标签不应再以 QUALITY_LEVELS 的旧形态出现
for s in ["极高"]:
    b = s.encode("utf-8")
    print(("STILL-PRESENT " if b in blobs else "GONE          ") + s)
