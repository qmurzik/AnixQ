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
