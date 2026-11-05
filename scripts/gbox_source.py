#!/usr/bin/env python3
"""Writes the GBox source (gbox.json) for the latest AnixQ release.

Usage: gbox_source.py <out.json> <version> <ipa_url> <icon_url> <size_bytes> <notes>
"""
import json
import sys
from datetime import datetime, timezone

out, version, ipa_url, icon_url, size, notes = sys.argv[1:7]
now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+0000")

source = {
    "version": "1.0",
    "sourceName": "AnixQ",
    "sourceAuthor": "qmurzik",
    "sourceExportEnable": True,
    "sourceLinkTitle": "Сайт",
    "sourceLinkUrl": "https://qmurzik.github.io/AnixQ/",
    "sourceImage": icon_url,
    "sourceUpdateTime": now,
    "sourceDescription": "AnixQ — аниме с AnimeLib: каталог, плеер без рекламы, синхронизация с Shikimori.",
    "appCategories": ["Приложения"],
    "appRepositories": [
        {
            "appType": "SELF_SIGN",
            "appCateIndex": 0,
            "appUpdateTime": now,
            "appName": "AnixQ",
            "appVersion": version,
            "appImage": icon_url,
            "appPackage": ipa_url,
            "appSize": int(size),
            "appDescription": notes.strip()[:1500] or "Обновление AnixQ",
        }
    ],
}

with open(out, "w", encoding="utf-8") as f:
    json.dump(source, f, ensure_ascii=False, indent=2)
print(f"wrote {out}: AnixQ {version}")
