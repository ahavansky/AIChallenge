# AGENTS.md

Инструкции для Codex и других агентных инструментов, работающих с этим проектом.

## Базовый контекст

- Проект: Android-приложение для AI Challenge.
- Корень проекта: `/Users/khavanskii/StudioProjects/aichallenge`.
- Основной package/namespace: `com.akhavanskii.aichallenge`.
- Язык: Kotlin.
- UI: Jetpack Compose + Material 3.
- DI: Hilt + KSP.
- Навигация: Navigation 3.
- Сеть: OkHttp + kotlinx.serialization, прямой REST-вызов Gemini API.
- Сборка: Android Gradle Plugin 9.x с built-in Kotlin.

Все shell-команды в этом проекте запускай через `rtk`.

Примеры:

```bash
rtk ./gradlew assembleDebug
rtk ./gradlew testDebugUnitTest
rtk ./gradlew ktlintCheck lintDebug
```

## Документация и проверка API

- При изменениях Android API, Compose, Hilt, Navigation, Gradle, screenshot testing или emulator workflow проверяй актуальную документацию через Android CLI:

```bash
rtk android docs search "<query>"
rtk android docs fetch "<doc-id-or-url>"
```

- Не полагайся на память для быстро меняющихся Android/Gradle API.
- Если документация противоречит текущему коду, сначала проверь версию зависимости в `gradle/libs.versions.toml`.

## Архитектура модулей

Текущая структура:

- `:app` - application shell, Hilt setup, app theme, root navigation.
- `:core:designsystem` - reusable Compose UI, theme, tokens, previews.
- `:core:mvvm` - shared MVI/MVVM primitives.
- `:core:network` - Gemini REST client, DTO, serialization, network errors.
- `:core:utils` - small shared utilities.
- `:feature:home` - home/chat feature, ViewModel, UI, tests.

Правила:

- Не добавляй `:core:domain`, use cases, repository layer, mapper layer или generic base classes без реальной необходимости.
- Не создавай пустые архитектурные слои ради шаблона.
- Держи изменения в модуле, которому они принадлежат.
- Shared-код выноси в core-модули только если он реально переиспользуется или снижает дублирование.
- UI-состояние должно быть immutable и удобно тестироваться.
- ViewModel отвечает за state transitions и side effects, Composable-функции не должны содержать сетевую или доменную логику.

## Compose и UI

- Используй Compose + Material 3 и существующую тему проекта.
- Сохраняй edge-to-edge поведение, корректную работу status/navigation bars и IME.
- Проверяй portrait и landscape для экранов, которые меняешь.
- Для новых публичных Composable-функций добавляй preview, если это практично.
- Не вставляй hardcoded цвета/размеры, если уже есть token/theme API.
- Сохраняй accessibility: content descriptions, readable labels, достаточный contrast, корректные semantics в тестируемых элементах.

## Gemini API и секреты

- Gemini вызывается напрямую через REST.
- Текущая цель: `gemini-3.5-flash`.
- Endpoint:

```text
https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent
```

- API key должен приходить только из `GEMINI_API_KEY` через `local.properties`, Gradle property или environment variable.
- Никогда не коммить реальные ключи, `local.properties`, `.env*`, `secrets.properties`, keystore-файлы или другие секреты.
- Тесты не должны ходить в реальную сеть.
- Для сетевого слоя покрывай как минимум: missing API key, empty prompt, non-2xx response, malformed/empty Gemini response, network exception.

## Зависимости и Gradle

- Версии зависимостей меняй через `gradle/libs.versions.toml`.
- AGP 9 использует built-in Kotlin: не добавляй `org.jetbrains.kotlin.android` без явной миграционной причины.
- Используй KSP, не kapt, если нет сильной причины.
- Не добавляй новую библиотеку, если задача решается стандартным Android/Kotlin API или уже подключенными зависимостями.
- После изменения build logic запускай как минимум:

```bash
rtk ./gradlew assembleDebug
```

## Тесты и quality gates

Для обычных изменений выбирай минимальный релевантный набор проверок, но перед завершением значимых изменений используй полный набор.

Основные команды:

```bash
rtk ./gradlew assembleDebug
rtk ./gradlew testDebugUnitTest
rtk ./gradlew :koverXmlReport :koverVerify
rtk ./gradlew ktlintCheck lintDebug
```

UI/instrumented tests, если доступен emulator/device:

```bash
rtk ./gradlew connectedDebugAndroidTest
```

Screenshot tests:

```bash
rtk ./gradlew :feature:home:validateDebugScreenshotTest
```

Обновление эталонных screenshot-файлов делай только осознанно:

```bash
rtk ./gradlew :feature:home:updateDebugScreenshotTest
```

Coverage:

- Минимальный порог проекта: 70%.
- Текущий baseline после первичной реализации: около 79.6% line coverage.
- Если покрытие падает, добавь focused unit tests вместо снижения порога.

## Android emulator workflow

- Для проверки устройства используй Android CLI/ADB через `rtk`.
- Если connected tests нужны, но emulator недоступен, явно зафиксируй это в ответе пользователю.
- Не запускай GUI-инструменты без необходимости.

Полезные команды:

```bash
rtk adb devices
rtk android run --type ACTIVITY --activity com.akhavanskii.aichallenge.MainActivity --apks app/build/outputs/apk/debug/app-debug.apk
rtk android layout --pretty
```

## Форматирование

- Для поиска используй `rg`/`rg --files`.
- Для ручных правок предпочитай `apply_patch`.
- Не трогай unrelated user changes.
- Для форматирования Kotlin используй:

```bash
rtk ./gradlew ktlintFormat
```

- Сохраняй ASCII в новых файлах, если нет причины использовать другой набор символов. Этот файл намеренно на русском.

## README и документация проекта

- Если меняются модули, команды сборки, способ задания API key, модель Gemini или testing workflow, обнови `README.md`.
- Не дублируй большие блоки документации в коде. Комментарии должны объяснять только нетривиальное решение.

## Перед финальным ответом

- Сообщи, какие файлы изменены.
- Сообщи, какие проверки запускались.
- Если проверки не запускались, кратко объясни почему.
- Не утверждай, что emulator/instrumented tests прошли, если они не запускались в текущей сессии.
