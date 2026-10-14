import json,subprocess
ids=['1330348068','569213220','347230']
levels=['standard','higher','exhigh','lossless','hires','jyeffect','sky','jymaster']
for i in ids:
    print('#### id='+i)
    for lv in levels:
        u='https://msapi.awup.cn/song/url/v1?id=%s&level=%s'%(i,lv)
        try:
            r=subprocess.run(['curl.exe','-s','--max-time','20',u],capture_output=True,timeout=30)
            d=json.loads(r.stdout.decode('utf-8','replace'))
            x=d.get('data'); x=x[0] if isinstance(x,list) and x else x
            if isinstance(x,dict):
                print('  %-9s -> served=%-9s br=%-8s enc=%-5s %s'%(lv,x.get('level'),x.get('br'),x.get('encodeType'),'URL' if x.get('url') else 'null'))
            else:
                print('  %-9s -> EMPTY'%lv)
        except Exception as e:
            print('  %-9s -> ERR %s'%(lv,e))
