# 校验 release APK 的 dex 里确实含有关键中文文案（dex 字符串用 MUTF-8 存，UTF-8 字节可直接命中）
import glob, sys

keys = [
    "今晚，听点什么？", "搜索歌曲、歌手或专辑", "搜索歌曲、歌手、专辑", "刷新歌单", "新建歌单",
    "我的歌单", "YOUR SOUND, YOUR SPACE", "每日推荐加载失败", "日推都被黑名单挡住了",
    "再按一次返回键退出", "正在播放", "选择一首歌开始播放", "音乐已就绪", "暂无歌词",
    "歌词加载中…", "当前音质", "变速会同时变调", "到点自动停止播放", "该功能暂未实现",
    "确认删掉我吗", "还会见面吗", "我后悔了！", "把这首放进哪个歌单？",
    "加入黑名单 · 点一下屏蔽或取消", "最近搜索", "清除全部", "还没有听歌记录",
    "还没有本地音乐", "支持 mp3 / ogg / wav", "外观设置", "口味偏好", "作者：XFZC",
    "免责声明", "禁止倒卖", "页面被吃了", "找点什么？", "没找到", "音乐库",
    "今日推荐歌单", "我的收藏", "立即聆听", "PULSE SELECT", "已按口味排序",
]

blobs = []
for p in sorted(glob.glob(r"D:\PullUp\_verify\*.dex")):
    with open(p, "rb") as f:
        blobs.append((p, f.read()))

miss = 0
for k in keys:
    b = k.encode("utf-8")
    where = [p for p, data in blobs if b in data]
    if where:
        print("FOUND  " + k)
    else:
        miss += 1
        print("MISS   " + k)
print("----")
print(f"total={len(keys)} missing={miss}")
sys.exit(0)
