#!/usr/bin/env python3
"""Builds the GitHub Pages site from site/ into an output folder.

- copies static files;
- renders guide pages from site/_pages/*.html (meta in a leading HTML comment);
- renders /changelog/ from GitHub releases (RELEASES_JSON file or GitHub API);
- fills the screenshot gallery from site/screens/*.(png|jpg|webp);
- injects Yandex.Metrika when site/metrika-id.txt holds a counter id;
- writes sitemap.xml.

Usage: build_site.py <out_dir>
"""
import datetime
import html
import json
import os
import re
import shutil
import sys
import urllib.request

SITE = "https://qmurzik.github.io/AnixQ/"
REPO = os.environ.get("GITHUB_REPOSITORY", "qmurzik/AnixQ")
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site")

out = sys.argv[1]
today = datetime.date.today().isoformat()
if os.path.exists(out):
    shutil.rmtree(out)
shutil.copytree(ROOT, out, ignore=shutil.ignore_patterns("_pages", "metrika-id.txt", "*.md"))

metrika_id = ""
mpath = os.path.join(ROOT, "metrika-id.txt")
if os.path.exists(mpath):
    metrika_id = open(mpath).read().strip()


def metrika():
    if not metrika_id.isdigit():
        return ""
    return f"""<script>(function(m,e,t,r,i,k,a){{m[i]=m[i]||function(){{(m[i].a=m[i].a||[]).push(arguments)}};
m[i].l=1*new Date();for(var j=0;j<document.scripts.length;j++){{if(document.scripts[j].src===r){{return;}}}}
k=e.createElement(t),a=e.getElementsByTagName(t)[0],k.async=1,k.src=r,a.parentNode.insertBefore(k,a)}})
(window,document,"script","https://mc.yandex.ru/metrika/tag.js","ym");
ym({metrika_id},"init",{{clickmap:true,trackLinks:true,accurateTrackBounce:true}});</script>
<noscript><div><img src="https://mc.yandex.ru/watch/{metrika_id}" style="position:absolute;left:-9999px" alt=""></div></noscript>"""


HEADER = """<header class="top"><div class="wrap">
<a class="brand" href="{base}"><img src="{base}favicon.png" alt="" width="32" height="32">AnixQ</a>
<a class="pill" href="{base}#install">Скачать</a></div></header>"""

FOOTER = """<footer><div class="wrap">
<span>© AnixQ · <a href="{base}">Главная</a> · <a href="{base}changelog/">Что нового</a> · <a href="https://github.com/qmurzik/AnixQ">GitHub</a></span>
<span>AnixQ не хранит и не распространяет видео — он показывает содержимое сторонних сервисов.</span>
</div></footer>"""

CTA = """<div class="cta"><img src="{base}icon-512.png" alt="AnixQ" width="72" height="72">
<h2>AnixQ — аниме на iPhone без рекламы</h2>
<p>Бесплатно · iOS 16.1+ · iPhone и iPad</p>
<a class="btn" href="{base}#install">Установить AnixQ</a></div>"""


def page(slug, title, description, h1, lead, body, crumb, extra_ld=None):
    url = SITE + slug + "/"
    base = "../"
    ld = [{
        "@context": "https://schema.org", "@type": "BreadcrumbList",
        "itemListElement": [
            {"@type": "ListItem", "position": 1, "name": "AnixQ", "item": SITE},
            {"@type": "ListItem", "position": 2, "name": crumb, "item": url},
        ],
    }]
    if extra_ld:
        ld.append(extra_ld)
    ld_html = "\n".join(f'<script type="application/ld+json">{json.dumps(x, ensure_ascii=False)}</script>' for x in ld)
    return f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(description)}">
<meta name="robots" content="index, follow, max-image-preview:large">
<meta name="theme-color" content="#0b0912">
<meta name="color-scheme" content="dark">
<link rel="canonical" href="{url}">
<link rel="icon" type="image/png" href="{base}favicon.png">
<link rel="apple-touch-icon" href="{base}apple-touch-icon.png">
<meta property="og:type" content="article">
<meta property="og:locale" content="ru_RU">
<meta property="og:site_name" content="AnixQ">
<meta property="og:title" content="{html.escape(h1)}">
<meta property="og:description" content="{html.escape(description)}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{SITE}og.png">
<meta name="twitter:card" content="summary_large_image">
<link rel="stylesheet" href="{base}assets/page.css">
{ld_html}
</head>
<body>
{HEADER.format(base=base)}
<main class="wrap">
<nav class="crumbs" aria-label="Навигация"><a href="{base}">AnixQ</a> › {html.escape(crumb)}</nav>
<h1>{html.escape(h1)}</h1>
<p class="lead">{html.escape(lead)}</p>
{body}
{CTA.format(base=base)}
{{MORE}}
</main>
{FOOTER.format(base=base)}
{metrika()}
</body>
</html>
"""


# Guide pages
guides = []
pages_dir = os.path.join(ROOT, "_pages")
for name in sorted(os.listdir(pages_dir)):
    if not name.endswith(".html"):
        continue
    slug = name[:-5]
    raw = open(os.path.join(pages_dir, name), encoding="utf-8").read()
    m = re.match(r"\s*<!--(.*?)-->(.*)", raw, re.S)
    meta = dict(
        (k.strip(), v.strip()) for k, v in
        (line.split(":", 1) for line in m.group(1).strip().splitlines() if ":" in line)
    )
    guides.append((slug, meta, m.group(2).strip()))

for slug, meta, body in guides:
    more = "".join(
        f'<a href="../{s}/">{html.escape(mt["h1"])}<span>{html.escape(mt["description"][:110])}…</span></a>'
        for s, mt, _ in guides if s != slug
    )
    doc = page(slug, meta["title"], meta["description"], meta["h1"], meta["lead"], body, meta["h1"],
               {"@context": "https://schema.org", "@type": "Article", "headline": meta["h1"],
                "description": meta["description"], "inLanguage": "ru", "dateModified": today,
                "author": {"@type": "Person", "name": "qmurzik"},
                "image": SITE + "og.png", "mainEntityOfPage": SITE + slug + "/"})
    doc = doc.replace("{MORE}", f'<h2>Ещё по теме</h2><div class="more">{more}</div>')
    os.makedirs(os.path.join(out, slug), exist_ok=True)
    open(os.path.join(out, slug, "index.html"), "w", encoding="utf-8").write(doc)


# Changelog
def load_releases():
    path = os.environ.get("RELEASES_JSON")
    if path and os.path.exists(path):
        return json.load(open(path))
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases?per_page=60",
                                 headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        return json.load(urllib.request.urlopen(req, timeout=30))
    except Exception as e:  # the site still builds without the list
        print("releases:", e)
        return []


releases = [r for r in load_releases() if not r.get("draft")]
items = []
for r in releases:
    date = (r.get("published_at") or "")[:10]
    body = (r.get("body") or "").strip() or "Исправления и улучшения."
    body = re.sub(r"\n{3,}", "\n\n", body)
    items.append(f"""<article class="release"><h2>{html.escape(r.get('name') or r['tag_name'])}</h2>
<time datetime="{date}">{date}</time>
<div class="body">{html.escape(body)}</div>
<p><a href="{html.escape(r['html_url'])}">Страница релиза</a></p></article>""")
changelog = page("changelog", "Что нового в AnixQ — история версий",
                 "Список изменений AnixQ по версиям: новые функции, улучшения и исправления приложения для просмотра аниме на iPhone.",
                 "Что нового в AnixQ", "Все версии приложения и что в них изменилось. Обновляйтесь через GBox или скачайте IPA.",
                 "\n".join(items) or "<p>Список версий скоро появится.</p>", "Что нового")
changelog = changelog.replace("{MORE}", "")
os.makedirs(os.path.join(out, "changelog"), exist_ok=True)
open(os.path.join(out, "changelog", "index.html"), "w", encoding="utf-8").write(changelog)


# Home page: gallery, guides list, latest version, metrika
index_path = os.path.join(out, "index.html")
index = open(index_path, encoding="utf-8").read()

shots_dir = os.path.join(ROOT, "screens")
shots = sorted(f for f in (os.listdir(shots_dir) if os.path.isdir(shots_dir) else [])
               if f.lower().endswith((".png", ".jpg", ".jpeg", ".webp")))
if shots:
    figs = "".join(
        f'<figure class="shot"><img src="screens/{f}" alt="Скриншот AnixQ: {html.escape(os.path.splitext(f)[0].split("-", 1)[-1].replace("-", " "))}" loading="lazy"></figure>'
        for f in shots
    )
    gallery = f"""<section id="screens"><div class="wrap"><div class="rv">
<div class="eyebrow">Скриншоты</div><h2>Как это выглядит</h2>
<p class="sub">Главная, страница тайтла, плеер и медиатека — в стилях Классика, Liquid Glass, Material You и OLED.</p></div></div>
<div class="gallery">{figs}</div></section>"""
    index = index.replace("<!--GALLERY-->", gallery)

guide_links = "".join(
    f'<a class="guide rv" href="{s}/"><b>{html.escape(mt["h1"])}</b><span>{html.escape(mt["lead"][:120])}…</span></a>'
    for s, mt, _ in guides
)
index = index.replace("<!--GUIDES-->", guide_links)
if releases:
    index = index.replace("<!--VERSION-->", html.escape(releases[0]["tag_name"].lstrip("v")))
index = index.replace("</body>", metrika() + "\n</body>")
open(index_path, "w", encoding="utf-8").write(index)


# Sitemap
urls = [("", "1.0", "weekly"), ("changelog/", "0.6", "weekly")] + [(s + "/", "0.8", "monthly") for s, _, _ in guides]
sitemap = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">']
for path, prio, freq in urls:
    sitemap.append(f"  <url><loc>{SITE}{path}</loc><lastmod>{today}</lastmod>"
                   f"<changefreq>{freq}</changefreq><priority>{prio}</priority></url>")
sitemap.append("</urlset>")
open(os.path.join(out, "sitemap.xml"), "w").write("\n".join(sitemap) + "\n")
print(f"site: {len(guides)} guides, {len(releases)} releases, {len(shots)} screenshots, metrika={'on' if metrika() else 'off'}")
