$ErrorActionPreference = 'Continue'

function Measure-FirstByte($name, $url, $follow = $false) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Method = 'GET'
        $req.AllowAutoRedirect = $follow
        $req.Timeout = 60000
        $resp = $req.GetResponse()
        $stream = $resp.GetResponseStream()
        $buf = New-Object byte[] 64
        $n = $stream.Read($buf, 0, 64)
        $sw.Stop()
        $ct = $resp.ContentType
        $loc = $resp.Headers['Location']
        $host2 = ''
        if ($loc) { try { $host2 = ([uri]$loc).Host } catch {} }
        "{0,-40} {1,6} ms  first={2,3}B  status={3}  type={4}  ->{5}" -f $name, $sw.ElapsedMilliseconds, $n, [int]$resp.StatusCode, $ct, $host2
        $stream.Close(); $resp.Close()
    } catch {
        $sw.Stop()
        "{0,-40} {1,6} ms  FAIL {2}" -f $name, $sw.ElapsedMilliseconds, $_.Exception.Message
    }
}

$id = 1330348068
"=== 第一字节耗时（TTFB，不下载正文） ==="
Measure-FirstByte 'meting type=url (proxy)'      "https://api.qijieya.cn/meting/?server=netease&type=url&id=$id"
Measure-FirstByte 'meting type=url&level=standard' "https://api.qijieya.cn/meting/?server=netease&type=url&id=$id&level=standard"
Measure-FirstByte 'meting type=url&br=128000'    "https://api.qijieya.cn/meting/?server=netease&type=url&id=$id&br=128000"
Measure-FirstByte 'old /song/url br=320000'      "https://msapi.awup.cn/song/url?id=$id&br=320000"
Measure-FirstByte 'v1 level=exhigh'              "https://msapi.awup.cn/song/url/v1?id=$id&level=exhigh"

""
"=== 同一个端点连打 3 次，看抖动 ==="
foreach ($i in 1..3) { Measure-FirstByte "old /song/url  #$i" "https://msapi.awup.cn/song/url?id=$id&br=320000" }
foreach ($i in 1..3) { Measure-FirstByte "v1 exhigh       #$i" "https://msapi.awup.cn/song/url/v1?id=$id&level=exhigh" }
foreach ($i in 1..3) { Measure-FirstByte "meting proxy    #$i" "https://api.qijieya.cn/meting/?server=netease&type=url&id=$id" }

""
"=== MeTing 代理是否认 level / br（看它 302 到哪个文件） ==="
function Meting-Target($name, $extra) {
    $u = "https://api.qijieya.cn/meting/?server=netease&type=url&id=1330348068$extra"
    $req = [System.Net.HttpWebRequest]::Create($u)
    $req.AllowAutoRedirect = $false
    $req.Timeout = 60000
    try {
        $resp = $req.GetResponse()
        $loc = $resp.Headers['Location']
        $resp.Close()
        if ($loc) {
            $leaf = ($loc -split '/')[-1]
            "{0,-28} -> {1}" -f $name, $leaf
        } else { "{0,-28} -> (no redirect)" -f $name }
    } catch { "{0,-28} -> FAIL {1}" -f $name, $_.Exception.Message }
}
Meting-Target 'no extra'      ''
Meting-Target 'level=standard' '&level=standard'
Meting-Target 'level=lossless' '&level=lossless'
Meting-Target 'br=128000'      '&br=128000'
Meting-Target 'br=999000'      '&br=999000'
