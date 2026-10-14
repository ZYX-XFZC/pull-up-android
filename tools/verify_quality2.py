import zipfile
apk = r"D:\PullUp\app\build\outputs\apk\release\app-release.apk"
z = zipfile.ZipFile(apk)
dexes = sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
blobs = b"".join(z.read(n) for n in dexes)
def find(s):
    b = s.encode("utf-8")
    i = blobs.find(b)
    return "FOUND@%d" % i if i >= 0 else "MISS"
checks = {
  "old hint 标准/极高/无损" : "标准 / 极高 / 无损",
  "Hi-Res"                  : "Hi-Res",
  "高清环绕声"               : "高清环绕声",
  "沉浸环绕声"               : "沉浸环绕声",
  "高清母带"                 : "高清母带",
  "未实现"                   : "未实现",
  "较高"                     : "较高",
}
for k, v in checks.items():
    print("%-24s %s" % (k, find(v)))
