#!/usr/bin/env python3
"""
把 ExileCore 部署到主機商的伺服器（2026-10）：編譯 → SFTP 上傳 jar（與資料檔）→ 透過面板 API 重開或 /ec reload。

連線資訊放在 tools/deploy.local.json（不進版控，見 .gitignore；範例在 tools/deploy.local.example.json）：
  {
    "sftp_host": "sftp.主機商.org", "sftp_port": 2024, "sftp_user": "面板帳號.伺服器代號", "sftp_password": "面板密碼",
    "remote_plugins": "plugins",                       # 伺服器根目錄底下的 plugins 資料夾（SFTP 登入後看到的根就是伺服器根）
    "panel_url": "https://面板網址", "server_id": "伺服器代號（網址 /server/ 後面那 8 碼）", "api_key": "面板的 Client API 金鑰"
  }
用法（在專案根目錄）：
  python tools/deploy.py                 編譯、上傳 jar，然後重開伺服器
  python tools/deploy.py --reload        編譯、上傳 jar，但只送 /ec reload（只改資料檔或網頁時；改 Java 一定要重開）
  python tools/deploy.py --data          連 data/ 底下的資料檔一起上傳（沒改過的資料檔重開時本來就會自動同步，平常不用）
  python tools/deploy.py --no-build      不重新編譯，直接上傳 build/libs 裡現成的 jar
  python tools/deploy.py --no-restart    上傳完不重開、不 reload
  python tools/deploy.py --command "say 伺服器 5 分鐘後重開"   只送一行主控台指令
需要：pip install paramiko
"""
import argparse
import glob
import json
import os
import posixpath
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "tools", "deploy.local.json")
DATA_DIR = os.path.join(ROOT, "src", "main", "resources", "data")


def load_config():
    if not os.path.exists(CONFIG):
        sys.exit("找不到 tools/deploy.local.json：把 tools/deploy.local.example.json 複製一份改名，填入 SFTP 與面板 API 的資訊")
    with open(CONFIG, encoding="utf-8") as f:
        config = json.load(f)
    for key in ("sftp_host", "sftp_user", "sftp_password"):
        if not config.get(key):
            sys.exit(f"tools/deploy.local.json 缺 {key}")
    config.setdefault("sftp_port", 22)
    config.setdefault("remote_plugins", "plugins")
    # 主機只要網域：有人會把 FileZilla 的 sftp://帳號@主機:port 整串貼進來，這裡拆開
    host = str(config["sftp_host"]).strip()
    if "://" in host:
        host = host.split("://", 1)[1]
    if "@" in host:
        user, host = host.rsplit("@", 1)
        if config.get("sftp_user") in (None, "", "面板帳號.伺服器代號"):
            config["sftp_user"] = user
    host = host.rstrip("/")
    if ":" in host:
        host, port = host.rsplit(":", 1)
        if port.isdigit():
            config["sftp_port"] = int(port)
    config["sftp_host"] = host
    if "主機商" in host or "." not in host:
        sys.exit(f"sftp_host 要填主機商給的 SFTP 主機網域（FileZilla 標題列 @ 後面、冒號前面那一段），現在是「{host}」")
    return config


def build():
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    print("▶ 編譯（gradlew jar）…")
    result = subprocess.run([gradlew, "jar", "-q"], cwd=ROOT)
    if result.returncode != 0:
        sys.exit("編譯失敗，沒有上傳")


def find_jar():
    jars = [p for p in glob.glob(os.path.join(ROOT, "build", "libs", "ExileCore-*.jar")) if not p.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        sys.exit("build/libs 裡沒有 ExileCore-*.jar（先編譯，或不要用 --no-build）")
    return max(jars, key=os.path.getmtime)


def connect(config):
    try:
        import paramiko
    except ImportError:
        sys.exit("需要 paramiko：pip install paramiko")
    import socket
    try:
        transport = paramiko.Transport((config["sftp_host"], int(config["sftp_port"])))
    except socket.gaierror:
        sys.exit(f"找不到主機「{config['sftp_host']}」：檢查 tools/deploy.local.json 的 sftp_host 是不是打錯（只要網域，不要 sftp:// 和 :port）")
    except OSError as e:
        sys.exit(f"連不上 {config['sftp_host']}:{config['sftp_port']}：{e}（port 對嗎？FileZilla 用的是哪個 port）")
    try:
        transport.connect(username=config["sftp_user"], password=config["sftp_password"])
    except paramiko.AuthenticationException:
        sys.exit("SFTP 登入失敗：使用者或密碼不對（使用者是「面板帳號.伺服器代號」，密碼跟面板登入一樣）")
    return transport, paramiko.SFTPClient.from_transport(transport)


def ensure_dir(sftp, path):
    parts = [p for p in path.split("/") if p]
    current = ""
    for part in parts:
        current = posixpath.join(current, part) if current else part
        try:
            sftp.stat(current)
        except IOError:
            sftp.mkdir(current)


def upload(sftp, local, remote):
    size = os.path.getsize(local)
    done = [0]

    def progress(sent, total):
        pct = int(sent * 100 / max(1, total))
        if pct // 10 != done[0] // 10:
            done[0] = pct
            print(f"\r  {os.path.basename(local)} {pct}%", end="", flush=True)
    # 先傳到暫存名再改名：傳到一半斷線不會留下半個 jar 讓伺服器載入失敗
    tmp = remote + ".uploading"
    sftp.put(local, tmp, callback=progress)
    try:
        sftp.remove(remote)
    except IOError:
        pass
    sftp.rename(tmp, remote)
    print(f"\r  {os.path.basename(local)} 完成（{size // 1024} KB）")


def upload_data(sftp, remote_plugins):
    base = posixpath.join(remote_plugins, "ExileCore", "data")
    count = 0
    for root, _, names in os.walk(DATA_DIR):
        for name in names:
            if name == "index.txt":
                continue
            local = os.path.join(root, name)
            rel = os.path.relpath(local, DATA_DIR).replace(os.sep, "/")
            remote = posixpath.join(base, rel)
            ensure_dir(sftp, posixpath.dirname(remote))
            sftp.put(local, remote)
            count += 1
    print(f"  資料檔 {count} 個 → {base}")


def panel(config, path, payload):
    for key in ("panel_url", "server_id", "api_key"):
        if not config.get(key):
            print(f"（沒有 {key}，跳過面板操作；請到面板手動重開）")
            return False
    url = f"{config['panel_url'].rstrip('/')}/api/client/servers/{config['server_id']}/{path}"
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), method="POST", headers={
        "Authorization": f"Bearer {config['api_key']}", "Accept": "application/json", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            response.read()
        return True
    except urllib.error.HTTPError as e:
        sys.exit(f"面板 API 失敗（{e.code}）：{e.read().decode('utf-8', 'replace')[:300]}")
    except urllib.error.URLError as e:
        sys.exit(f"連不到面板：{e.reason}")


def main():
    parser = argparse.ArgumentParser(description="部署 ExileCore")
    parser.add_argument("--no-build", action="store_true", help="不重新編譯")
    parser.add_argument("--no-upload", action="store_true", help="不上傳（只做面板操作）")
    parser.add_argument("--data", action="store_true", help="連資料檔一起上傳")
    parser.add_argument("--reload", action="store_true", help="上傳後送 /ec reload 而不是重開")
    parser.add_argument("--no-restart", action="store_true", help="上傳後什麼都不做")
    parser.add_argument("--command", help="只送一行主控台指令")
    args = parser.parse_args()
    config = load_config()

    if args.command:
        if panel(config, "command", {"command": args.command}):
            print(f"✔ 已送出：{args.command}")
        return

    if not args.no_upload:
        if not args.no_build:
            build()
        jar = find_jar()
        print(f"▶ 連線 {config['sftp_host']}:{config['sftp_port']}…")
        transport, sftp = connect(config)
        try:
            ensure_dir(sftp, config["remote_plugins"])
            upload(sftp, jar, posixpath.join(config["remote_plugins"], "ExileCore.jar"))
            if args.data:
                upload_data(sftp, config["remote_plugins"])
        finally:
            sftp.close()
            transport.close()

    if args.no_restart:
        print("✔ 上傳完成（沒有重開；記得重開伺服器才會生效）")
    elif args.reload:
        if panel(config, "command", {"command": "ec reload"}):
            print("✔ 上傳完成，已送 /ec reload（改了 Java 的話還是要重開）")
    else:
        if panel(config, "power", {"signal": "restart"}):
            print("✔ 上傳完成，伺服器重開中")


if __name__ == "__main__":
    main()
