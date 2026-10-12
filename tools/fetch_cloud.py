#!/usr/bin/env python3
"""
把雲端伺服器的紀錄抓回本機分析（2026-10）：只下載、不改雲端任何東西。
連線資訊跟 deploy.py 一樣用 tools/deploy.local.json（SFTP）。

抓回來放在專案根目錄的 cloud/ 底下（跟 run/ 分開，不會蓋掉本機測試伺服器的東西）：
  cloud/logs/          最近幾天的伺服器紀錄（latest.log 與 *.log.gz；「[Boss 紀錄]」在這裡面）
  cloud/backups/       最新一份資料庫備份（db-*.json.gz：角色、進度、訓練假人紀錄…）

用法（在專案根目錄）：
  python tools/fetch_cloud.py            最近 3 天的紀錄＋最新的資料庫備份
  python tools/fetch_cloud.py --days 7   最近 7 天
需要：pip install paramiko
"""
import argparse
import os
import posixpath
import stat
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deploy  # noqa: E402  共用 deploy.py 的設定讀取與 SFTP 連線

ROOT = deploy.ROOT
OUT = os.path.join(ROOT, "cloud")


def listing(sftp, path):
    try:
        return sftp.listdir_attr(path)
    except IOError:
        return []


def download(sftp, remote, local):
    os.makedirs(os.path.dirname(local), exist_ok=True)
    tmp = local + ".part"
    sftp.get(remote, tmp)
    os.replace(tmp, local)
    print(f"  {posixpath.basename(remote)}（{os.path.getsize(local) // 1024} KB）")


def main():
    parser = argparse.ArgumentParser(description="下載雲端伺服器的紀錄與資料庫備份")
    parser.add_argument("--days", type=int, default=3, help="抓最近幾天的紀錄（預設 3）")
    args = parser.parse_args()

    config = deploy.load_config()
    transport, sftp = deploy.connect(config)
    try:
        cutoff = time.time() - args.days * 86400
        print(f"▶ 紀錄（最近 {args.days} 天）")
        logs = [f for f in listing(sftp, "logs") if stat.S_ISREG(f.st_mode)
                and (f.filename == "latest.log" or f.filename.endswith(".log.gz")) and f.st_mtime >= cutoff]
        for f in sorted(logs, key=lambda f: f.st_mtime):
            download(sftp, posixpath.join("logs", f.filename), os.path.join(OUT, "logs", f.filename))
        if not logs:
            print("  （沒有找到）")

        print("▶ 最新的資料庫備份")
        backups_dir = posixpath.join(config["remote_plugins"], "ExileCore", "backups")
        backups = [f for f in listing(sftp, backups_dir) if f.filename.startswith("db-") and f.filename.endswith(".json.gz")]
        if backups:
            latest = max(backups, key=lambda f: f.st_mtime)
            download(sftp, posixpath.join(backups_dir, latest.filename), os.path.join(OUT, "backups", latest.filename))
        else:
            print("  （沒有找到）")
    finally:
        sftp.close()
        transport.close()
    print(f"完成：放在 {OUT}")


if __name__ == "__main__":
    main()
