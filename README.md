# AnixQ

Нативный iOS-клиент для просмотра аниме: каталог и плеер AnimeLib, расписание и синхронизация с Shikimori.

**Сайт:** https://qmurzik.github.io/AnixQ/

## Возможности

- Каталог с фильтрами, поиск, расписание выхода серий
- Плеер без рекламы: жесты, пропуск опенинга, автопереход к следующей серии, субтитры, картинка в картинке, AirPlay
- Загрузка серий для просмотра офлайн, автозагрузка новых серий по Wi-Fi
- Синхронизация закладок и прогресса с AnimeLib и Shikimori
- Поиск аниме по скриншоту (в том числе из TikTok / Shorts) — по персонажам
- Виджеты, Live Activity, команды Siri, уведомления о новых сериях в вашей озвучке

## Установка

Через **GBox**: добавьте источник

```
https://raw.githubusercontent.com/qmurzik/AnixQ/gbox-source/gbox.json
```

Или скачайте IPA из [последнего релиза](https://github.com/qmurzik/AnixQ/releases/latest) и установите любым удобным способом (AltStore, SideStore, Sideloadly, TrollStore).

Требуется iOS 16.1 или новее.

## Сборка

Каждый push в `main` собирается в GitHub Actions: выходит релиз `v1.0.N` с `AnixQ.ipa`, источник GBox обновляется автоматически.

Локально (macOS):

```bash
brew install xcodegen
xcodegen generate
open AnixQ.xcodeproj
```

## Структура

```
AnixQ/          приложение (SwiftUI)
AnixQWidget/    виджеты и Live Activity
AnixQShare/     расширение «Что за аниме?» для меню «Поделиться»
Shared/         общий код приложения и расширений
site/           сайт на GitHub Pages
scripts/        генерация источника GBox
```

AnixQ не хранит и не распространяет видео — он показывает содержимое сторонних сервисов.

### YummyAnime (Android / Android TV)

**Based on YummyTV — https://github.com/Helandy/YummyTV**.
Adapted extractor protocols and API mapping are licensed under **CC BY-NC 4.0**;
upstream LICENSE and NOTICE are retained in
`android/core/src/main/assets/licenses/YummyTV-LICENSE` and `YummyTV-NOTICE`.
Changes: AnixQ models, cancellable OkHttp transport, per-stream Media3 headers,
conservative matching, source identity and existing phone/TV controls.
These adapted portions are for noncommercial use under that license.

Sign in to your own YummyAnime account in Settings. AnixQ attempts to reuse or
register your own `AnixQ` application via the official API and obtain its public
`X-Application` key automatically. If the service denies automatic setup, the
Advanced section accepts your public key from https://yummyani.me/dev/applications
(or configure `YANI_APPLICATION_TOKEN` at build time). User and application
authorization are separate: unauthenticated video endpoints can return placeholders.
Tokens use Android Keystore encryption, are excluded from Gist/room messages/backups,
and are sent only to `api.yani.tv`. Phone and TV offer separate source, balancer
and voice controls, retaining the selected studio and playback position.
No third-party application or user tokens are bundled.

See [Android integration and verification](docs/yummyanime-android.md) for behavior,
source limitations and reproducible checks.
