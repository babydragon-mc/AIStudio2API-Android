"""Build a standalone APK using official Android SDK tools; Python 3 + JDK 17+."""
import hashlib, os, pathlib, shutil, struct, subprocess, tarfile, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SDK = pathlib.Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT','')))
VERSION = 'v0.3.0'
BACKENDS = {
    'arm64-v8a': ('arm64', 'df05f0d90c78fb83d2a50c889b556d929332701950093730693ad7f7a662c605', 183),
    'x86_64': ('amd64', 'b4bc24f125977a32aa5d432434ddaf9f184470789ed0823818575b3686528f9a', 62),
}

def run(*args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)

def tool(name):
    suffix = '.bat' if os.name == 'nt' and name in ('d8','apksigner') else '.exe' if os.name == 'nt' else ''
    return SDK / 'build-tools' / os.environ.get('BUILD_TOOLS_VERSION','36.0.0') / (name+suffix)

def core(abi):
    arch, checksum, machine = BACKENDS[abi]
    cache = ROOT / '.local' / 'downloads'; cache.mkdir(parents=True,exist_ok=True)
    archive = cache / f'aistudio2api-{VERSION}-linux-{arch}.tar.gz'
    if not archive.exists():
        urllib.request.urlretrieve(f'https://github.com/Mag1cFall/AIStudio2API/releases/download/{VERSION}/{archive.name}', archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Upstream checksum mismatch; delete the corrupted archive and retry')
    with tarfile.open(archive) as tar:
        member = next(x for x in tar.getmembers() if x.isfile() and x.name.endswith('/aistudio2api'))
        data = tar.extractfile(member).read()
    if data[:5] != b'\x7fELF\x02' or struct.unpack_from('<H',data,18)[0] != machine:
        raise RuntimeError('Unexpected ELF architecture')
    header_offset = struct.unpack_from('<Q',data,32)[0]
    entry_size, count = struct.unpack_from('<HH',data,54)
    if any(struct.unpack_from('<I',data,header_offset+i*entry_size)[0] == 3 for i in range(count)):
        raise RuntimeError('Core is dynamically linked; this packaging requires a static executable')
    return data

def main():
    android = SDK / 'platforms' / 'android-36' / 'android.jar'
    if not android.is_file(): raise RuntimeError('Set ANDROID_HOME to an SDK with platforms;android-36 and build-tools;36.0.0')
    build = ROOT / '.local' / 'build'; build.mkdir(parents=True,exist_ok=True)
    classes = build / 'classes'; classes.mkdir(exist_ok=True)
    dex = build / 'dex'; dex.mkdir(exist_ok=True)
    java_home = pathlib.Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
    def java_tool(name):
        return java_home / 'bin' / (name+('.exe' if os.name=='nt' else '')) if java_home else name
    sources = sorted((ROOT/'src').rglob('*.java'))
    run(java_tool('javac'), '-encoding','UTF-8','--release','8','-classpath',android,'-d',classes,*sources)
    jar = build/'classes.jar'
    run(java_tool('jar'), '--create','--file',jar,'-C',classes,'.')
    run(tool('d8'), '--lib',android,'--min-api','29','--output',dex,jar)
    base = build/'base.apk'
    release = os.environ.get('AIS2A_RELEASE') == '1'
    manifest = build / 'AndroidManifest.xml'
    manifest.write_text((ROOT/'AndroidManifest.xml').read_text(encoding='utf-8').replace('<application ', '<application android:debuggable="'+str(not release).lower()+'" '), encoding='utf-8')
    run(tool('aapt2'),'link','-I',android,'--manifest',manifest,'-o',base)
    unsigned = build/'unsigned.apk'
    abis = os.environ.get('AIS2A_ABIS','arm64-v8a').split(',')
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(unsigned,'w') as output:
        for item in source.infolist(): output.writestr(item,source.read(item.filename))
        for item in dex.glob('*.dex'): output.writestr(item.name,item.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
        for item in sorted((ROOT/'assets').rglob('*')):
            if item.is_file(): output.writestr('assets/'+item.relative_to(ROOT/'assets').as_posix(),item.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
        for name in ('LICENSE','LICENSE.upstream','THIRD_PARTY_NOTICES.md'):
            if (ROOT/name).is_file(): output.writestr('assets/'+name,(ROOT/name).read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
        for abi in abis: output.writestr(f'lib/{abi}/libais2acore.so',core(abi),compress_type=zipfile.ZIP_STORED)
    aligned = build/'aligned.apk'
    run(tool('zipalign'),'-f','-P','16','4',unsigned,aligned)
    key = pathlib.Path(os.environ.get('AIS2A_KEYSTORE',str(ROOT/'.local'/'debug.keystore')))
    release = os.environ.get('AIS2A_RELEASE') == '1'
    if release and not os.environ.get('AIS2A_KEYSTORE'): raise RuntimeError('Release requires your persistent AIS2A_KEYSTORE')
    alias = os.environ.get('AIS2A_KEY_ALIAS','androiddebugkey')
    if not key.exists():
        if release: raise RuntimeError('Release keystore missing')
        run(java_tool('keytool'),'-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias',alias,
            '-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=AIS2A Development')
    env=os.environ.copy()
    if not release: env.setdefault('AIS2A_STORE_PASSWORD','android'); env.setdefault('AIS2A_KEY_PASSWORD','android')
    if release and not all(env.get(x) for x in ('AIS2A_STORE_PASSWORD','AIS2A_KEY_PASSWORD')):
        raise RuntimeError('Release passwords must be provided through environment variables')
    dist = ROOT/'dist'; dist.mkdir(exist_ok=True)
    apk = dist / ('AIS2A-Android-0.1.0-alpha-' + ('release' if release else 'debug') + '.apk')
    run(tool('apksigner'),'sign','--ks',key,'--ks-key-alias',alias,'--ks-pass','env:AIS2A_STORE_PASSWORD',
        '--key-pass','env:AIS2A_KEY_PASSWORD','--out',apk,aligned,env=env)
    run(tool('apksigner'),'verify','--verbose',apk)
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist/'SHA256SUMS.txt').write_text(f'{digest}  {apk.name}\n',encoding='ascii')
    print('Built:',apk)

if __name__ == '__main__': main()
