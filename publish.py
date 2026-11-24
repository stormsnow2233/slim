# -*- coding: utf-8 -*-
"""
拾卷 (Shijuan) 全自动发版脚本
==================================
功能清单：
1. 自动从 app/build.gradle.kts 提取当前的 versionName 与 versionCode；
2. 校验 build 产物与 output-metadata.json，防止旧包混淆；
3. 复制并重命名为 Shijuan-v{versionName}.apk，计算文件体积与 SHA-256 校验和；
4. 自动在 slim 仓库执行 git add / commit / tag / push；
5. 免明文密码：通过 Windows 凭据管理器 (CredReadW) 自动提取 GitHub 访问令牌；
6. 自动调用 GitHub REST API 创建 Release 并上传 APK 资产（支持本地 50001 代理与直连自动切换）；
7. 自动同步更新 shijuanweb 官网 index.html 中的版本号、大小、文件名，并推送至 GitHub Pages。

用法：
    python publish.py               # 交互式或读取当前版本自动发版
    python publish.py --build       # 自动先执行 ./gradlew.bat assembleRelease 编译打包
"""

import sys
import os
import re
import json
import shutil
import hashlib
import ctypes
from ctypes import wintypes
import subprocess
import urllib.request
import urllib.error

# 项目路径配置
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
GRADLE_KTS = os.path.join(PROJECT_DIR, "app", "build.gradle.kts")
RELEASE_OUTPUT_DIR = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "release")
SRC_APK = os.path.join(RELEASE_OUTPUT_DIR, "app-release.apk")
METADATA_JSON = os.path.join(RELEASE_OUTPUT_DIR, "output-metadata.json")
WEB_DIR = r"c:\Users\Administrator\Desktop\shijuanweb"
REPO = "stormsnow2233/slim"
PROXY_URL = "http://127.0.0.1:50001"


# --- 1. Windows 凭据管理器读取 GitHub Token ---
class CREDENTIAL(ctypes.Structure):
    _fields_ = [
        ('Flags', wintypes.DWORD),
        ('Type', wintypes.DWORD),
        ('TargetName', wintypes.LPWSTR),
        ('Comment', wintypes.LPWSTR),
        ('LastWritten', wintypes.FILETIME),
        ('CredentialBlobSize', wintypes.DWORD),
        ('CredentialBlob', ctypes.POINTER(ctypes.c_byte)),
        ('Persist', wintypes.DWORD),
        ('AttributeCount', wintypes.DWORD),
        ('Attributes', ctypes.c_void_p),
        ('TargetAlias', wintypes.LPWSTR),
        ('UserName', wintypes.LPWSTR),
    ]

PCREDENTIAL = ctypes.POINTER(CREDENTIAL)
advapi32 = ctypes.WinDLL('advapi32', use_last_error=True)
CredReadW = advapi32.CredReadW
CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.POINTER(PCREDENTIAL)]
CredReadW.restype = wintypes.BOOL
CredFree = advapi32.CredFree
CredFree.argtypes = [ctypes.c_void_p]

def get_github_token():
    """从 Windows 凭据管理器提取 GitHub Token 或 PAT"""
    targets = [
        'git:https://stormsnow2233@github.com',
        'GitHub - https://api.github.com/stormsnow2233',
        'git:https://github.com'
    ]
    for target in targets:
        pcred = PCREDENTIAL()
        if CredReadW(target, 1, 0, ctypes.byref(pcred)):
            blob_size = pcred.contents.CredentialBlobSize
            raw_bytes = ctypes.string_at(pcred.contents.CredentialBlob, blob_size)
            CredFree(pcred)
            try:
                token = raw_bytes.decode('utf-16le').strip()
            except Exception:
                token = raw_bytes.decode('utf-8', errors='ignore').strip()
            if token and len(token) > 10:
                return token
    # 环境变量备选
    return os.environ.get("GITHUB_TOKEN")


def get_network_opener():
    """配置优先走本地梯子，降级走直连的 HTTP 请求器"""
    proxy_handler = urllib.request.ProxyHandler({
        'http': PROXY_URL,
        'https': PROXY_URL,
    })
    try:
        opener = urllib.request.build_opener(proxy_handler)
        req = urllib.request.Request("https://api.github.com", headers={"User-Agent": "Test"})
        opener.open(req, timeout=3)
        print(f"[网络] 成功连接本地代理 {PROXY_URL}")
        return opener
    except Exception:
        print("[网络] 本地代理不可用，自动切换为直连")
        return urllib.request.build_opener()


def parse_app_version():
    """解析 app/build.gradle.kts 中的 versionCode 和 versionName"""
    with open(GRADLE_KTS, "r", encoding="utf-8") as f:
        content = f.read()

    vc_match = re.search(r'versionCode\s*=\s*(\d+)', content)
    vn_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)

    if not vc_match or not vn_match:
        raise ValueError("无法在 app/build.gradle.kts 中解析到 versionCode 或 versionName！")

    return int(vc_match.group(1)), vn_match.group(1)


def build_apk():
    """调用 Gradle 构建正式包"""
    print("[编译] 正在执行 assembleRelease ...")
    gradlew = os.path.join(PROJECT_DIR, "gradlew.bat")
    res = subprocess.run([gradlew, "assembleRelease"], cwd=PROJECT_DIR)
    if res.returncode != 0:
        print("[错误] Gradle 构建失败！")
        sys.exit(1)


def main():
    auto_build = "--build" in sys.argv
    version_code, version_name = parse_app_version()
    tag_name = f"v{version_name}"
    target_apk_name = f"Shijuan-{tag_name}.apk"
    dst_apk = os.path.join(PROJECT_DIR, target_apk_name)

    print("=" * 60)
    print(f"  拾卷 (Shijuan) 发布工具 - 目标版本: {tag_name} (Build {version_code})")
    print("=" * 60)

    if auto_build or not os.path.exists(SRC_APK):
        build_apk()

    # 1. 验证 APK 与元数据
    if not os.path.exists(SRC_APK):
        print(f"[错误] 未找到构建产物: {SRC_APK}")
        sys.exit(1)

    if os.path.exists(METADATA_JSON):
        with open(METADATA_JSON, "r", encoding="utf-8") as f:
            meta = json.load(f)
            elements = meta.get("elements", [])
            if elements:
                build_vc = elements[0].get("versionCode")
                build_vn = elements[0].get("versionName")
                if build_vc != version_code or build_vn != version_name:
                    print(f"[警告] 构建包元数据 ({build_vn}/{build_vc}) 与 gradle.kts ({version_name}/{version_code}) 不一致！建议重新 build。")

    shutil.copy2(SRC_APK, dst_apk)
    with open(dst_apk, "rb") as f:
        apk_bytes = f.read()
    apk_size = len(apk_bytes)
    apk_sha256 = hashlib.sha256(apk_bytes).hexdigest()
    apk_mb = apk_size / (1024 * 1024)

    print(f"[产物] 已生成 {dst_apk}")
    print(f"       大小: {apk_size} 字节 ({apk_mb:.2f} MB)")
    print(f"       SHA-256: {apk_sha256}")

    # 2. 检查 Git 凭据
    token = get_github_token()
    if not token:
        print("[错误] 无法获取 GitHub Token，请确保 Windows 凭据管理器已保存 GitHub 凭据或设置 GITHUB_TOKEN 环境变量。")
        sys.exit(1)
    print(f"[凭据] 成功获取 GitHub 授权令牌 (长度: {len(token)})")

    # 3. 提交代码并打标签
    print(f"[Git] 提交并推送至远程分支与标签 {tag_name}...")
    subprocess.run(["git", "add", "-A"], cwd=PROJECT_DIR, check=True)
    subprocess.run(["git", "commit", "-m", f"chore(release): release {tag_name} (Build {version_code})"], cwd=PROJECT_DIR)
    subprocess.run(["git", "tag", "-f", tag_name], cwd=PROJECT_DIR, check=True)
    subprocess.run(["git", "push", "origin", "master"], cwd=PROJECT_DIR, check=True)
    subprocess.run(["git", "push", "origin", tag_name, "-f"], cwd=PROJECT_DIR, check=True)

    # 4. 创建或更新 GitHub Release
    opener = get_network_opener()
    release_name = f"{tag_name} (Build {version_code}) - 自建反代节点全面升级(*.us.ci) · 本地书籍导入极速解耦 · 磁盘缓存淘汰节流"
    release_body = f"""### 拾卷 {tag_name} (Build {version_code}) 更新日志

#### 1. 反代与网络加速全面统一升级（自建 `*.us.ci` 边缘高速节点）
- **主流书源节点全量迁移**：
  - **Z-Library** 默认反代线路全面升级为 `https://zlib-proxy.us.ci`；
  - **JMcomic** 核心代理节点与兜底线路升级为 `https://jm-proxy.us.ci`；
  - **轻小说文库 (Wenku8)** 官方直解 Pages 边缘加速节点升级为 `https://wenku8-proxy.us.ci`；
  - **ESJ Zone** 专属线路默认同步切换至 `https://esj-proxy.us.ci`。
- **历史旧配置平滑无感升级**：
  - `Store` 存储层全面注入旧域名自动迁移逻辑，历史遗留的 `*.pages.dev` 与 `*.workers.dev` 废弃域名自动平滑重定向至全新的 `*.us.ci` 节点，旧版本覆盖安装零需手动重置。
- **网络测试与体检同步对齐**：网络设置界面的各书源连通性探测接口同步切换为最新高速端点，测速与延迟更精准。

#### 2. 本地大书籍导入速度飞跃 & 冗余存储解耦
- **消除全量章节二次缓存冗余**：
  - 彻底移除 TXT 自动智能分章、固定分块切片、单章正文、EPUB 章节解包、PDF 页面渲染等所有导入分支中多余的 `NovelContentCache` 重复写入操作。
  - **极速省流**：本地导入书籍在应用私有存储中已有标准物理章节文件（`ch_*.txt`），阅读引擎原生通过 `file://` 直接秒开。移除导入时的二次落盘后，上千章超大长篇（如大部头网文/百万字轻小说）**导入耗时缩短数倍，且彻底杜绝了双倍磁盘空间浪费和对网络正文缓存池的挤压**。

#### 3. 磁盘正文缓存淘汰高频扫描节流
- **30 秒扫描窗口节流机制**：
  - 为正文缓存管理机制 (`trimDiskUsageIfNeeded`) 引入 30 秒冷却时间窗口，彻底解决大批量章节连续写入或后台缓存时高频调用 `listFiles()` 遍历磁盘累加大小导致的 CPU 占用与 I/O 阻塞掉帧，阅读翻页更加丝滑稳定。

#### 4. 生态拓展与工程化落地
- **iOS 独立版工程全功能推进**：`Shijuan-iOS` 工程完善 SwiftUI 架构，支持 EPUB/TXT 原生导入、规则书源引擎 MVP、JM 漫画及 Cloudflare 盾解算，新增远程自动化构建脚本 `sync_build_ios.py`。

#### 5. 安装包资产与安全校验
- **官方标准安装包**：`{target_apk_name}`
- **文件大小**：{apk_size} 字节 ({apk_mb:.2f} MB)
- **SHA-256 校验码**：`{apk_sha256}`
- **签名状态**：官方正式签名 (keystore/shijuan.jks)
"""

    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "ShijuanReleaseBot/1.0"
    }

    create_url = f"https://api.github.com/repos/{REPO}/releases"
    payload = {
        "tag_name": tag_name,
        "target_commitish": "master",
        "name": release_name,
        "body": release_body,
        "draft": False,
        "prerelease": False
    }

    upload_url_tmpl = None
    try:
        req = urllib.request.Request(create_url, data=json.dumps(payload).encode("utf-8"), headers={**headers, "Content-Type": "application/json"})
        resp = opener.open(req)
        rel_data = json.loads(resp.read().decode("utf-8"))
        print(f"[Release] 发布创建成功: {rel_data.get('html_url')}")
        upload_url_tmpl = rel_data.get("upload_url", "")
    except urllib.error.HTTPError as e:
        print(f"[Release] 创建接口返回 HTTP {e.code}，正在查询已有 Release...")
        get_req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/tags/{tag_name}", headers=headers)
        resp = opener.open(get_req)
        rel_data = json.loads(resp.read().decode("utf-8"))
        upload_url_tmpl = rel_data.get("upload_url", "")
        print(f"[Release] 已获取已有 Release: {rel_data.get('html_url')}")

    # 5. 上传 APK 文件资产
    if upload_url_tmpl:
        upload_base = upload_url_tmpl.split("{")[0]
        upload_url = f"{upload_base}?name={target_apk_name}"
        upload_headers = {
            **headers,
            "Content-Type": "application/vnd.android.package-archive",
            "Content-Length": str(apk_size)
        }
        upload_req = urllib.request.Request(upload_url, data=apk_bytes, headers=upload_headers)
        try:
            up_resp = opener.open(upload_req)
            asset_info = json.loads(up_resp.read().decode("utf-8"))
            print(f"[上传] 安装包上传成功: {asset_info.get('browser_download_url')}")
        except urllib.error.HTTPError as e:
            print(f"[警告] 资源上传接口返回 HTTP {e.code} (若已存在同名资源需先删除后重传)")

    # 6. 同步更新官方落地页 shijuanweb
    web_index = os.path.join(WEB_DIR, "index.html")
    if os.path.exists(web_index):
        print("[官网] 正在更新 shijuanweb...")
        with open(web_index, "r", encoding="utf-8") as f:
            html = f.read()

        html = re.sub(r'v\d+\.\d+', tag_name, html)
        html = re.sub(r'Shijuan-v\d+\.\d+\.apk', target_apk_name, html)
        html = re.sub(r'Build \d+', f'Build {version_code}', html)
        html = re.sub(r'<span>约 \d+(\.\d+)? MB</span>', f'<span>约 {apk_mb:.1f} MB</span>', html)
        html = re.sub(r'<span class="hash-code">[a-f0-9]{64}</span>', f'<span class="hash-code">{apk_sha256}</span>', html)

        with open(web_index, "w", encoding="utf-8") as f:
            f.write(html)

        subprocess.run(["git", "add", "index.html"], cwd=WEB_DIR, check=True)
        subprocess.run(["git", "commit", "-m", f"chore(website): update latest release info to {tag_name} (Build {version_code})"], cwd=WEB_DIR)
        subprocess.run(["git", "push", "origin", "main"], cwd=WEB_DIR, check=True)
        print("[官网] 官方落地页同步推送到 GitHub Pages 成功！")

    print("\n" + "=" * 60)
    print(f"🎉 全部发布流程执行完成！版本: {tag_name} (Build {version_code})")
    print("=" * 60)


if __name__ == "__main__":
    main()
