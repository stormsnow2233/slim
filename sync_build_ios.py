import os, paramiko
from pathlib import Path
host=os.environ.get('SHIJUAN_MAC_HOST')
user=os.environ.get('SHIJUAN_MAC_USER')
password=os.environ.get('SHIJUAN_MAC_PASSWORD')
if not host or not user:
    raise SystemExit('Set SHIJUAN_MAC_HOST and SHIJUAN_MAC_USER; configure SSH keys or SHIJUAN_MAC_PASSWORD.')
local=Path('Shijuan-iOS'); remote=os.environ.get('SHIJUAN_MAC_PROJECT',f'/Users/{user}/Shijuan-iOS')
c=paramiko.SSHClient(); c.load_system_host_keys(); c.connect(host,username=user,password=password,timeout=15)
s=c.open_sftp()
for p in local.rglob('*'):
    if not p.is_file() or 'Shijuan-unsigned.ipa' in str(p): continue
    rel=p.relative_to(local).as_posix(); rp=remote+'/'+rel
    parent=rp.rsplit('/',1)[0]
    try: s.stat(parent)
    except: c.exec_command('mkdir -p '+repr(parent))
    s.put(str(p),rp)
s.close()
cmd='''set -e
cd ~/Shijuan-iOS
rm -rf build build-ipa
xcodebuild -project Shijuan.xcodeproj -scheme Shijuan -sdk iphoneos -configuration Release -derivedDataPath build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
mkdir -p build-ipa/Payload
cp -R build/Build/Products/Release-iphoneos/Shijuan.app build-ipa/Payload/
cd build-ipa
/usr/bin/zip -qry ../Shijuan-unsigned.ipa Payload
cd ..
file build/Build/Products/Release-iphoneos/Shijuan.app/Shijuan
stat -f '%z bytes' Shijuan-unsigned.ipa
'''
_,o,e=c.exec_command(cmd); print(o.read().decode()); print(e.read().decode()); print('exit',o.channel.recv_exit_status())
s=c.open_sftp(); s.get(remote+'/Shijuan-unsigned.ipa','Shijuan-iOS/Shijuan-unsigned.ipa'); s.close(); c.close()
