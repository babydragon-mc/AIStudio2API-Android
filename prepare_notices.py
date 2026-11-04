"""Prepare checksum-verified Go dependency sources and license notices before redistribution."""
import base64, concurrent.futures, hashlib, io, json, os, pathlib, re, subprocess, time, urllib.request, zipfile
from build import ROOT, VERSION

def fetch(url):
    proxy=os.environ.get('AIS2A_HTTP_PROXY')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({'https':proxy})) if proxy else urllib.request.build_opener()
    for attempt in range(4):
        try:
            with opener.open(url,timeout=40) as response: return response.read()
        except Exception:
            if attempt==3: raise
            time.sleep(1+attempt)

def escaped(module):
    return ''.join('!'+c.lower() if c.isupper() else c for c in module)

def main():
    cache=ROOT/'.local'/'sources'; cache.mkdir(parents=True,exist_ok=True)
    upstream=cache/'upstream.zip'
    if not upstream.exists(): upstream.write_bytes(fetch(f'https://codeload.github.com/Mag1cFall/AIStudio2API/zip/refs/tags/{VERSION}'))
    upstream_zip=zipfile.ZipFile(upstream)
    prefix=upstream_zip.namelist()[0]
    mod=upstream_zip.read(prefix+'go.mod').decode()
    sums=dict((f[0]+' '+f[1],f[2]) for line in upstream_zip.read(prefix+'go.sum').decode().splitlines() if len(f:=line.split())==3)
    modules=re.findall(r'^\s*([^\s()]+)\s+(v[^\s]+)',mod,re.M)
    notices=ROOT/'assets'/'licenses'; notices.mkdir(parents=True,exist_ok=True)
    def dependency(item):
        module,version=item
        path=cache/(module.replace('/','_')+'@'+version+'.zip')
        if not path.exists(): path.write_bytes(fetch('https://proxy.golang.org/'+escaped(module)+'/@v/'+version+'.zip'))
        data=path.read_bytes(); archive=zipfile.ZipFile(io.BytesIO(data))
        digest=hashlib.sha256()
        for name in sorted(x for x in archive.namelist() if not x.endswith('/')):
            digest.update((hashlib.sha256(archive.read(name)).hexdigest()+'  '+name+'\n').encode())
        actual='h1:'+base64.b64encode(digest.digest()).decode()
        if actual != sums.get(module+' '+version): raise RuntimeError('Go module checksum mismatch: '+module)
        license_files=[]
        for name in archive.namelist():
            basename=pathlib.PurePosixPath(name).name.lower()
            if re.match(r'^(licen[sc]e|copying|notice|copyright)([.\-_]|$)',basename) and not name.endswith('/'):
                relative='/'.join(name.split('/')[len((module+'@'+version).split('/')):])
                dest=notices/module/version/relative; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_bytes(archive.read(name)); license_files.append(relative)
        return {'module':module,'version':version,'go_sum':actual,'license_files':license_files,'archive':path.name}
    inventory=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for future in concurrent.futures.as_completed([pool.submit(dependency,m) for m in modules]):
            inventory.append(future.result())
            if len(inventory)%16==0: print('Verified modules:',len(inventory),'/',len(modules),flush=True)
    inventory.sort(key=lambda x:x['module'])
    (ROOT/'assets'/'DEPENDENCIES.json').write_text(json.dumps(inventory,indent=2),encoding='utf-8')
    # Mozilla's consolidated notices cover bundled C/C++/Rust dependencies in SpiderMonkey.
    firefox='https://raw.githubusercontent.com/Mag1cFall/firefox/v152.0.4-aistudio2api.1/'
    (ROOT/'assets'/'Mozilla-license.html').write_bytes(fetch(firefox+'toolkit/content/license.html'))
    for name in ('LICENSE','COPYING'):
        try: (ROOT/'assets'/('musl-'+name)).write_bytes(fetch('https://git.musl-libc.org/cgit/musl/plain/'+name))
        except urllib.error.HTTPError as e:
            if e.code != 404: raise
    source_bundle=ROOT/'dist'/'AIS2A-Android-0.1.0-alpha-core-sources.zip'; source_bundle.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(source_bundle,'w',compression=zipfile.ZIP_DEFLATED) as out:
        out.write(upstream,'upstream-AIStudio2API-v0.3.0.zip')
        for item in inventory: out.write(cache/item['archive'],'go-modules/'+item['archive'])
        out.writestr('BUILDING.txt','Extract upstream source, restore Go module archives using proxy.golang.org layout, and follow upstream .github/workflows/build.yml build-linux for the selected architecture.\nSpiderMonkey exact source: https://github.com/Mag1cFall/firefox/tree/v152.0.4-aistudio2api.1\nSpiderMonkey build instructions: see that repository and upstream .github/actions/spidermonkey/action.yml.\nThe Android wrapper runs the unmodified core as a separate executable; rebuild the core then replace the packaged libais2acore.so via build.py.\n')
    print('Verified dependency source archives:',len(inventory),flush=True)
    print('Source bundle:',source_bundle,flush=True)

if __name__=='__main__': main()
