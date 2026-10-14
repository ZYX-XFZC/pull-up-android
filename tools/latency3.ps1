$ErrorActionPreference = 'Continue'

function Probe($name, $url, $gzip = $false) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Method = 'GET'
        $req.Timeout = 60000
        $req.UserAgent = 'Mozilla/5.0 (Linux; Android 13) PullUp/1.0'
        if ($gzip) { $req.AutomaticDecompression = [System.Net.DecompressionMethods]::GZip }
        $resp = $req.GetResponse()
        $stream = $resp.GetResponseStream()
        $ms = New-Object System.IO.MemoryStream
        $buf = New-Object byte[] 16384
        while (($n = $stream.Read($buf, 0, $buf.Length)) -gt 0) { $ms.Write($buf, 0, $n) }
        $sw.Stop()
        $enc = $resp.Headers['Content-Encoding']
        "{0,-38} {1,6} ms  {2,8} B  enc={3}" -f $name, $sw.ElapsedMilliseconds, $ms.Length, ($(if ($enc) { $enc } else { 'none' }))
        $stream.Close(); $resp.Close()
    } catch {
        $sw.Stop()
        "{0,-38} {1,6} ms  FAIL {2}" -f $name, $sw.ElapsedMilliseconds, $_.Exception.Message
    }
}

"=== recommend/songs 抖动 + gzip ==="
foreach ($i in 1..3) { Probe "recommend/songs  #$i" "https://msapi.awup.cn/recommend/songs" }
foreach ($i in 1..2) { Probe "recommend/songs gzip #$i" "https://msapi.awup.cn/recommend/songs" $true }

""
"=== song/detail 批量（enrich 用） ==="
foreach ($i in 1..2) { Probe "song/detail ids=5  #$i" "https://msapi.awup.cn/song/detail?ids=1330348068,569213220,347230,1901371647,186016" }

""
"=== 搜索对比 ==="
foreach ($i in 1..2) { Probe "meting search #$i" "https://api.qijieya.cn/meting/?server=netease&type=search&id=%E8%B5%B7%E9%A3%8E%E4%BA%86&limit=30" }
foreach ($i in 1..2) { Probe "old search    #$i" "https://msapi.awup.cn/search?keywords=%E8%B5%B7%E9%A3%8E%E4%BA%86&limit=30" }

""
"=== 首页 personalized / top playlist ==="
Probe "personalized?limit=6" "https://msapi.awup.cn/personalized?limit=6"
Probe "top/playlist?limit=20" "https://msapi.awup.cn/top/playlist?limit=20"
Probe "meting playlist" "https://api.qijieya.cn/meting/?server=netease&type=playlist&id=3778678"

""
"=== 各种情况下的 v1（含无版权歌） ==="
Probe "v1 晴天 186016"   "https://msapi.awup.cn/song/url/v1?id=186016&level=exhigh"
Probe "v1 起风了 exhigh"  "https://msapi.awup.cn/song/url/v1?id=1330348068&level=exhigh"
