# CubicChunks3 Vibecoded Edition

[English](README.md) | [Русский](README.ru.md)

Экспериментальный форк OpenCubicChunks/CubicChunks3 для Minecraft Java Edition 26.3.
Рабочая платформа — Fabric. Код организован по модам и загрузчикам, с общими частями для последующих портов.

## Структура

```text
mods/
  cubicchunks/
    mod.properties         # идентификатор, версия и группа
    common/src/main/       # мир, кубы, генерация, свет, сущности, GUI, пакеты и mixin-код
    common/src/test/       # общие тестовые сценарии
    fabric/src/main/       # точки входа, регистрация сети, сервисы Fabric и метаданные
    fabric/src/test/       # тесты интеграции с Fabric
    fabric/world-smoke/    # отдельный тестовый мод с проверками настоящего клиента
    forge/                 # место для будущего порта
    neoforge/legacy/       # сохранённые исходные адаптеры NeoForge для дальнейшего переноса
  dasm/
    mod.properties
    common/src/main/       # интеграция преобразований DASM с Mixin
    fabric/src/main/       # обнаружение конфигураций и запуск через Fabric
    fabric/src/test/       # тесты байткода и конфигураций
libraries/
  cubicchunks-core/        # включённые в репозиторий исходники Core с исправлениями для 26.3
gradle/                     # общие правила сборки
buildSrc/                   # генераторы конфигураций Mixin/DASM и Java headers
tools/                      # дополнительные проверки
legacy/                     # исторические исходники, исключённые из сборки
docs/port-history.md        # подробности предыдущих этапов порта
```

Сейчас Gradle подключает `common` и `fabric`. Каталоги Forge и NeoForge описывают будущую работу.
Для подключения нового загрузчика добавляются его `build.gradle`, точки входа, метаданные и реализации сервисов.
Проверка `checkLoaderIndependence` запрещает обращения к API загрузчиков в общем production-коде.

Общий Minecraft-код компилируется отдельно. Loom используется для получения Minecraft и доступа к его классам при сборке.
Сервисы `Platform` и `ClientPlatform` предоставляются через Java ServiceLoader.
Клиентские обработчики регистрируются только клиентской точкой входа.
Тесты игрового кода из обоих каталогов выполняются через Fabric Loader в задаче `:cubicchunks:fabric:test`.

## Сборка

Нужны JDK 25 и доступ к репозиториям зависимостей при первой сборке.
Gradle Wrapper включён в проект. Подмодули Git для сборки не требуются.

| Команда в Windows | Результат |
| --- | --- |
| `.\dev.cmd listMods` | Моды, версии и подключённые загрузчики |
| `.\dev.cmd build` | Сборка и проверки всех подключённых модулей |
| `.\dev.cmd :cubicchunks:fabric:build` | CubicChunks для Fabric |
| `.\dev.cmd :dasm:fabric:build` | Адаптер DASM для Fabric |
| `.\dev.cmd runClient` | Отдельный клиент разработки |
| `.\dev.cmd :cubicchunks:fabric:runServer` | Сервер разработки |
| `.\dev.cmd spotlessApply` | Форматирование кода CubicChunks |

В Linux/macOS используется `./gradlew` с теми же задачами.
Готовые сборки всех модов собираются в корневой `build/libs`, сборки отдельного мода — в `mods/<id>/build/libs`.
Файлы `*-sources.jar` содержат исходники.
Основной JAR CubicChunks включает адаптер DASM, Core и библиотеки конфигурации.
В игровом клиенте нужен Fabric API для Minecraft 26.3. Для обычной установки достаточно основного JAR CubicChunks и Fabric API.

Версии модов задаются в `mods/<id>/mod.properties`, версии инструментов и Minecraft — в корневом `gradle.properties`.
Идентификатор `cubicchunks`, формат сохранений и идентификаторы сетевых пакетов сохранены.

## Проверки в игре

```powershell
.\dev.cmd -PworldSmoke -PsmokeDeepCaves -PsmokeCaveCurves -PsmokeOreProfiles -PsmokeWorld=layout-check runWorldSmoke
.\dev.cmd -PworldSmoke -PsmokeDeepCaves -PsmokeCaveCurves -PsmokeOreProfiles -PsmokeWorld=layout-check -PsmokeReopen runWorldSmoke
```

Первый запуск создаёт отдельный мир через игровые меню и проверяет генерацию, руды и синхронизацию.
Второй проверяет сохранённый мир. Файлы находятся в `run/world-smoke`.
Тестовый мод исключён из распространяемых JAR.
Другие сценарии описаны в `tools/verify/README.md`.

## Происхождение

Основной проект основан на [OpenCubicChunks/CubicChunks3](https://github.com/OpenCubicChunks/CubicChunks3).
Core основан на [OpenCubicChunks/CubicChunksCore](https://github.com/OpenCubicChunks/CubicChunksCore) и хранится локально вместе с изменениями порта.
DASM использует ядро NotStirred 3.2.0 и адаптированные исходники dasm-mod. Атрибуция и лицензия находятся в `mods/dasm/NOTICE.txt`.
Лицензия проекта — MIT, см. `LICENSE`.
