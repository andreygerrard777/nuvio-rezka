# HDRezka для Nuvio (нативний плагін)

Повноцінний нативний `.cs3`-плагін HDRezka для **Nuvio TV (full-збірка з підтримкою
CloudStream-розширень)**. Зібрано з двох напрацювань:

- логіка сайту — з `nuvio-uk-providers/providers/rezka.js` (Anubis, пошук, озвучки,
  `get_cdn_series`, формат потоків);
- запуск у Nuvio — з прототипу `nuvio-rezka-native` / `native-probe-2`
  (власний OkHttp із cookie-jar, SHA-256 proof-of-work, пакування `.cs3` + `repo.json`).

## Що вміє

| | |
|---|---|
| Типи | фільми, серіали, мультфільми, аніме |
| Озвучки | **усі**, українські першими; кожна озвучка = окремі посилання |
| Якість | 360p … 1080p, 1080p Ultra, 4K (де є на сайті) |
| Субтитри | так (укр/рос/англ, коли сайт їх віддає) |
| Захист сайту | Anubis proof-of-work розв'язується на пристрої, cookie зберігаються між запитами |
| Дзеркала | `rezka.ag`, резерв `rezka-tv.org` (пошук і сторінки автоматично перемикаються) |
| Стійкість | повтор при 503/429, до 2 проходжень Anubis, кеш сторінки 10 хв |

### Як Nuvio його викликає

Nuvio бере назву з TMDB (українську, оригінальну та латинські альтернативні) →
`search()` → обирає результат за схожістю назви, роком (±1) і типом → `load()` →
епізод із точною парою сезон/серія → `loadLinks()` (ліміт 60 с; посилання, отримані
до ліміту, зберігаються). Тому плагін:

- повертає кожен запис під **усіма** його назвами (російська, оригінальна, аліаси) з
  одним URL — Nuvio сам вибирає ту, що збігається з TMDB;
- для серіалів повертає список епізодів із номерами сезону й серії;
- запитує озвучки паралельно (по 4) і віддає посилання одразу, щойно вони готові.

Перевірено наживо (тест `NuvioMatchLiveTest` відтворює алгоритм зіставлення Nuvio):
«Матриця», «Пуститися берега», «Рік і Морті», «Віднесені привидами», «Термінатор»,
«Холоп» — усі знайдені правильно. «Матриця»: 23/23 озвучки з посиланнями (114 потоків,
субтитри); «Пуститися берега» S5E16: 9/9 озвучок, українська перша.

## Встановлення в Nuvio

1. Створіть GitHub-репозиторій (напр. `nuvio-rezka`) і завантажте туди вміст цієї папки
   (`native/`, `.github/`, `README.md`, `.gitignore`).
2. Workflow **Build and publish HDRezka plugin** запускається на push у `main`
   (або вручну: Actions → Run workflow). Він проганяє тести, збирає `Rezka.cs3` і
   публікує `Rezka.cs3`, `plugins.json`, `repo.json` у реліз з тегом `builds`.
3. У Nuvio → Plugins додайте репозиторій:
   `https://github.com/<ваш-логін>/<репозиторій>/releases/download/builds/repo.json`
   (точна адреса друкується в підсумку workflow).
4. Увімкніть плагін **HDRezka** і відкрийте будь-який фільм/серіал.

Адреса встановлення не змінюється між збірками. Щоб Nuvio оновив плагін, збільшіть
`version` у `native/Rezka/build.gradle.kts`.

`dist/` містить локально зібрану версію для репозиторію `andreygerrard777/nuvio-rezka`
(лише для довідки/ручного завантаження; workflow генерує ці файли сам під ваш репозиторій).

## Локальна збірка

Потрібні JDK 17 і Android SDK (platform 35, build-tools 35) у `local.properties`
(`sdk.dir=...`). З папки `native`:

```bash
./gradlew :Rezka:testDebugUnitTest :Rezka:make
```

Результат — `native/Rezka/build/Rezka.cs3`. Живі тести проти сайту (не запускаються в CI):

```bash
REZKA_LIVE=1 ./gradlew :Rezka:testDebugUnitTest --tests '*Live*'
```

## Діагностика

Помилки мають префікс `REZKA stage=... result=...` і показуються у звіті тесту провайдера
в Nuvio, наприклад `REZKA stage=anubis result=CHALLENGED` (сайт посилив захист) або
`REZKA stage=links result=NO_PLAYABLE_URLS` (для цієї серії немає озвучок). Cookie,
токени та HTML у звіти не потрапляють.

## Структура

```
native/Rezka/src/main/kotlin/ua/nuvio/rezka/
  RezkaProvider.kt   MainAPI для Nuvio: search / load / loadLinks + @CloudstreamPlugin
  RezkaSession.kt    HTTP-сесія: дзеркала, Anubis, ретраї, кеш, ajax get_cdn_series
  RezkaParser.kt     розбір пошуку, сторінки, озвучок, епізодів, потоків, субтитрів
  Anubis.kt          SHA-256 proof-of-work
  NativeHttp.kt      OkHttp + власний cookie-jar
  LinkData.kt        дані, що передаються з load() у loadLinks()
native/scripts/package_repo.py   перевірка .cs3 і генерація repo.json/plugins.json
.github/workflows/build.yml      тести → збірка → реліз `builds`
```

## Походження та ліцензія

Структура Gradle-збірки й wrapper адаптовані з
[CakesTwix/cloudstream-extensions-uk](https://github.com/CakesTwix/cloudstream-extensions-uk)
(GPL-3.0). Код плагіна також під GPL-3.0 (`native/LICENSE`).
