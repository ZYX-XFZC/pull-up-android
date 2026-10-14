$ErrorActionPreference = 'Continue'
function Time-Url($name, $url) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest -Uri $url -TimeoutSec 30 -UseBasicParsing
        $sw.Stop()
        $len = $r.RawContentLength
        "{0,-34} {1,7} ms   {2,8} B   HTTP {3}" -f $name, $sw.ElapsedMilliseconds, $len, $r.StatusCode
    } catch {
        $sw.Stop()
        "{0,-34} {1,7} ms   FAIL {2}" -f $name, $sw.ElapsedMilliseconds, $_.Exception.Message
    }
}

$id = 1330348068
"-- 播放地址 --"
Time-Url 'v1 level=exhigh'  "https://msapi.awup.cn/song/url/v1?id=$id&level=exhigh"
Time-Url 'v1 level=standard' "https://msapi.awup.cn/song/url/v1?id=$id&level=standard"
Time-Url 'old /song/url br'  "https://msapi.awup.cn/song/url?id=$id&br=320000"
Time-Url 'meting url'        "https://api.qijieya.cn/meting/?server=netease&type=url&id=$id"
Time-Url 'vkeys id (dead?)'  "https://api.vkeys.cn/v2/music/netease?id=$id"
"-- 元数据 --"
Time-Url 'song/detail'       "https://msapi.awup.cn/song/detail?ids=$id"
Time-Url 'recommend/songs'   "https://msapi.awup.cn/recommend/songs"
Time-Url 'meting search'     "https://api.qijieya.cn/meting/?server=netease&type=search&id=%E8%B5%B7%E9%A3%8E%E4%BA%86&limit=30"
Time-Url 'old search'        "https://msapi.awup.cn/search?keywords=%E8%B5%B7%E9%A3%8E%E4%BA%86&limit=30"
"-- 歌词 --"
Time-Url 'meting lrc'        "https://api.qijieya.cn/meting/?server=netease&type=lrc&id=$id"
Time-Url 'old /lyric'        "https://msapi.awup.cn/lyric?id=$id"
