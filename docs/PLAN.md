# PocketRun — статус, архитектура и что осталось сделать

Дата: 26 сентября 2026
Репозиторий: `sj0404-collab/pocketrun` (приватный)
CI: `.github/workflows/android.yml` — сборка APK и тесты в GitHub Actions, локальная сборка не нужна.

---

## 1. Что это

Упрощённый оригинал (не аналог того, что есть в маркетплейсах) — Android-приложение
на Kotlin, внутри которого живут:

| Слой | Реализация | Состояние |
|---|---|---|
| Python | настоящий CPython 3.13 через Chaquopy | бутстрап + Kotlin-мост `PythonRuntime` готовы |
| Node.js | Rhino + собственный Node-совместимый слой | не начато |
| npx | свой: реестр npm + распаковка tar + запуск `bin` | не начато |
| opencode | переписан на Kotlin: агент + инструменты + LLM-клиент | не начато |
| Релиз-ключ | Ed25519, офлайн-проверка, утилита keygen | **готово** |
| Интерфейс | Jetpack Compose | MVP: активация → проекты → редактор+терминал → инфо |

Ключевое ограничение, определившее архитектуру: **это Android без Termux**, то есть
нет Linux-пространства пользователя. Отсюда решения ниже.

---

## 2. Ключевые технические решения

### 2.1 Почему не nodejs-mobile

`nodejs-mobile` даёт настоящий Node 18 (`libnode.so`, 62 МБ на ABI), но:

* требует JNI-слоя с встраиванием `node::` API и прокруткой uv-цикла событий —
  непроверяемый нативный код без устройства;
* `libnode.so` в комплекте не содержит npm, значит ещё ~12 МБ ассетов;
* APK раздувается втрое.

Поэтому JS-слой — это Rhino (чистая Java, работает везде) плюс написанный нами
Node-совместимый слой: `require` с резолвом `node_modules`, `module`/`exports`,
`__dirname`/`__filename`, `process`, `console`, `Buffer` (подмножество), `path`, `fs`,
`os`, таймеры. Этого хватает для чистых JS-пакетов с npm.

`docs/fetch-node.md` описывает, куда денется настоящий Node, если понадобится.

### 2.2 Python

Chaquopy 17.0.0 (плагин), CPython 3.13. Версия Python выбирается блоком
`chaquopy { defaultConfig { version = "3.13" } }` в `android/app/build.gradle.kts`;
зависимость `com.chaquo.python:python` для этого не работает — Chaquopy 17 её
игнорирует и молча собирает дефолтный 3.10 (так было до сентября 2026).
Плагин не публикует marker-артефакт, поэтому в `settings.gradle.kts` есть
`resolutionStrategy.eachPlugin` с `useModule("com.chaquo.python:gradle:...")`.

Пакеты Python ставятся на этапе сборки, а не во время работы:

```kotlin
// android/app/build.gradle.kts
chaquopy {
    defaultConfig { /* ... */ }
}
dependencies { /* ... */ }
// pip { install("requests") }
```

Предупреждение сборки `Failed to compile to .pyc format: Couldn't find Python 3.10`
безвредно: на машине сборки нет CPython 3.10, Chaquopy просто не предкомпилирует
*.pyc* и интерпретирует исходники. На устройство это не влияет. С сентября 2026 в CI
стоит `actions/setup-python` с 3.13, совпадающим с версией приложения, поэтому
*.pyc* компилируется и это предупреждение ушло.

### 2.3 Формат лицензионного ключа

Ключ выглядит так:

```
PRK1.<base64url(payload)>.<base64url(ed25519-подпись)>
```

`payload` — канонический текстовый документ (не JSON, чтобы подпись была
побитово воспроизводимой):

```
PRK1
name=Jane Doe
seat=work-laptop
plan=pro
nbf=1750000000
exp=1760000000
```

Подпись покрывает **байты payload**, а не пересериализованный объект. Поэтому
проверка идёт в строгом порядке: разделить ключ → проверить подпись по байтам →
только потом парсить поля.

Отслеживания нет: срок задаётся в ключе, отзыв невозможен by design. Смена
параметров — это смена встроенного в APK публичного ключа.

Публичный ключ вшивается в сборку из `android/license.pubkey`:

```
./tools/keygen/gradlew -p tools/keygen run --args="genkey --out android"
```

Если файла нет, в APK попадает заглушка `DEMO…`, и приложение честно отказывает
любому ключу с сообщением об этом.

### 2.4 Две разные вещи под словом «релиз-ключ»

1. **Ключ активации** — лицензия пользователя, проверяется офлайн (выше).
2. **Ключ подписи APK** — `android/keystore.properties` + `release` signingConfig.
   Без него `packageRelease` падает с понятной ошибкой. В CI подпись берётся из
   секретов `ANDROID_KEYSTORE_B64` / `_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`;
   если секретов нет, собирается только debug APK.

### 2.5 Вывод в терминал

Рантаймы не пишут в stdout процесса. Они пишут в два файла в
`workspace/cache/runs/<id>/`, а `OutputTailer` следит за ними и отдаёт строки
в UI. Причины: пайп блокирует интерпретатор при медленном читателе, файл
переживает падение UI, и stderr можно красить отдельно. Декодирование
инкрементальное — многобайтный UTF-8 не рвётся на границе буфера.

### 2.6 Границы песочницы

`core/Workspace.kt` — единственное место, где живут пути. Корень:
`filesDir/workspace`. Внутри: `projects/`, `packages/`, `cache/`, `logs/`.
`Workspace.resolve()` возвращает `null`, если путь уходит наружу; вызывающий код
трактует это как «путь отклонён», а не «файла нет».

---

## 3. Что уже написано

```
android/app/src/main/java/dev/pocketrun/
  MainActivity.kt                     заглушка
  core/Workspace.kt                   песочница, защита от выхода за корень
  license/LicenseFormat.kt            формат ключа (копия в tools/keygen)
  license/Ed25519.kt                  чистая Java Ed25519 для API 26+
  license/LicenseVerifier.kt          чистая проверка ключа: подпись + окно времени
  license/LicenseManager.kt           SharedPreferences + StateFlow, фичи по плану
  runtime/Exec.kt                     модель выполнения, интерфейс рантайма
  runtime/OutputTailer.kt             живой вывод из файлов
android/app/src/main/python/
  pocketrun.py                        argv, sys.path, потоки, трейсбеки, пакеты
android/app/src/test/java/dev/pocketrun/license/
  LicenseFormatTest.kt                золотой тест-вектор, общий с keygen
tools/keygen/                         отдельный JVM-проект: genkey/issue/verify/inspect
.github/workflows/android.yml         сборка APK + тесты в GitHub
```

Тест-вектор (детерминированный сид → фиксированный ключ) зафиксирован в обоих
проектах: `tools/keygen/.../LicenseFormatTest.kt` и
`android/app/src/test/.../LicenseFormatTest.kt`. Расхождение формата валит одну
из сборок.

---

## 4. Что осталось

Порядок именно такой — каждый пункт опирается на предыдущий.

### 4.1 Python-мост (следующий шаг) ✅ готово (v1.1.0)

`runtime/python/PythonRuntime.kt`:
* `initAsync()` стартует `Python.start(AndroidPlatform(context))` один раз, из
  `PocketRunApplication.onCreate` на фоновом потоке — старт занимает около секунды;
* `execute()` создаёт `out_path`/`err_path` в `workspace/cache/runs/<id>`,
  поднимает два `OutputTailer`, вызывает `pocketrun.run(script, argsJson, stdin,
  out, err, cwd)`, возвращает `ExecResult`;
* `isAvailable()` и `version()` читаются из `pocketrun.interpreter_info()`;
* **ограничение отмены:** Chaquopy не умеет прерывать вызов. `cancel()` ставит
  флаг, и по возвращению выдаётся код 130. Убийство процесса невозможно —
  это честно показано в UI.

### 4.2 Node-совместимый слой

* `runtime/js/NodeFs.kt` — примитивы для JS: `readFile`, `writeFile`, `exists`,
  `readdir`, `stat`, `mkdir`, `unlink`, `rename`, всё через `Workspace.resolve`;
* `runtime/js/JsRuntime.kt` — Rhino `Context` в ES6-режиме, безопасные
  `initSafeStandardObjects`, лимит стека, `require`;
* `assets/node/boot.js` — `path`, `process` (`argv`, `env`, `platform`, `cwd`,
  `exit`), `console`, `os`, `util`, `Buffer` (на чистых JS-массивах, без
  зависимости от `Uint8Array`), `url`;
* резолв `node_modules`: вверх по `node_modules`, затем встроенные модули,
  затем явный путь;
* ограничение: нет нативных аддонов и worker_threads — только чистый JS.

### 4.3 npx

* `runtime/npm/TarReader.kt` — распаковка `.tar.gz` на `GZIPInputStream` +
  свой Tar-ридер (ustar), с защитой от выхода за пределы каталога;
* `runtime/npm/NpmRegistry.kt` — `https://registry.npmjs.org/<name>`, выбор
  версии через `dist-tags.latest` или явную версию, скачивание `dist.tarball`;
* `runtime/npm/NpxRuntime.kt` — разбор спецификатора (`pkg`, `pkg@1.2.3`,
  `@scope/pkg`, локальный путь), установка в `packages/`, чтение поля `bin` из
  `package.json`, запуск с `process.argv[1] = <bin>`.

### 4.4 Агент (переписанный opencode)

* `agent/LlmClient.kt` — OpenAI-совместимый `/chat/completions` со стримингом
  и поддержкой Anthropic-стиля tool calls;
* `agent/Tools.kt` — `read_file`, `write_file`, `list_dir`, `run_python`,
  `run_node`, `run_npx`, `search`;
* `agent/Agent.kt` — цикл: сообщение → tool calls → результаты → повтор,
  с лимитом на итерации;
* доступен только для планов `pro`/`team`/`lifetime` — проверка в
  `LicenseManager.LicenseState.Active.canUseAgent`.

### 4.5 Интерфейс

Экраны: активация → список проектов → редактор + терминал → чат агента →
пакеты. `MainActivity` сейчас заглушка.

---

## 5. Как проверять

Всё собирается в CI, локальный Android SDK не нужен:

```bash
gh run watch
gh run download --name pocketrun-apk
```

Только лицензионная логика (JVM, без Android):

```bash
tools/keygen/gradlew -p tools/keygen test
android/gradlew -p android :app:testDebugUnitTest
```

Выпуск ключа:

```bash
tools/keygen/gradlew -p tools/keygen run --args="issue --name 'Jane Doe' --seat laptop --plan pro --days 30"
```

## 6. Известные ограничения

* **Нет отзыва лицензий.** Только срок действия и смена публичного ключа в сборке.
* **Нет отмены запуска Python.** Chaquopy не прерывает вызов.
* **Нет нативных JS-модулей и worker_threads** в собственном Node-слое.
* **npm-пакеты с нативными аддонами или postinstall-скриптами** через npx не
  заработают.
* **APK большой** — CPython 3.13 весит ~30–40 МБ на ABI; сборка ограничена
  `arm64-v8a` и `x86_64`.
* Локальная сборка Android в этом окружении прервана на середине; весь APK
  собирается в GitHub Actions.
