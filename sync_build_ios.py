import os, paramiko
from pathlib import Path
host='192.168.127.133'; user='admin'; password='adm1n5'; local=Path('Shijuan-iOS'); remote='/Users/admin/Shijuan-iOS'
c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy()); c.connect(host,username=user,password=password,timeout=15)
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
