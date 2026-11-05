# CubicChunks3 Vibecoded Edition

[English](README.md) | [Русский](README.ru.md)

Экспериментальный форк OpenCubicChunks/CubicChunks3 для Minecraft Java Edition 26.3.
Поддерживаются Fabric, Forge и NeoForge. У каждого загрузчика свой JAR, регистрация сети и запуск; игровая логика общая.

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
    forge/src/main/        # точки входа Forge, сеть, сервисы и метаданные
    neoforge/src/main/     # точки входа NeoForge, сеть, сервисы и метаданные
    neoforge/src/test/     # интеграционный тест загрузки FML и кубов
    loader-smoke/          # проверка настоящего клиента Forge/NeoForge
    neoforge/legacy/       # архивные адаптеры, исключённые из сборки
  dasm/
    mod.properties
    common/src/main/       # интеграция преобразований DASM с Mixin
    fabric/src/main/       # обнаружение конфигураций и запуск через Fabric
    fabric/src/test/       # тесты байткода и конфигураций
    forge/src/main/        # обнаружение конфигураций DASM через Forge
    neoforge/src/main/     # обнаружение конфигураций DASM через NeoForge
libraries/
  cubicchunks-core/        # включённые в репозиторий исходники Core с исправлениями для 26.3
gradle/                     # общие правила сборки
buildSrc/                   # генераторы конфигураций Mixin/DASM и Java headers
tools/                      # дополнительные проверки
legacy/                     # исторические исходники, исключённые из сборки
docs/port-history.md        # подробности предыдущих этапов порта
```

Gradle подключает `common`, `fabric`, `forge` и `neoforge` для обоих модов.
Проверка `checkLoaderIndependence` запрещает обращения к API загрузчиков в общем production-коде.

Общий Minecraft-код компилируется отдельно для каждого загрузчика через Loom, ForgeGradle или ModDevGradle.
Сервисы `Platform` и `ClientPlatform` предоставляются через Java ServiceLoader.
Клиентские обработчики регистрируются только клиентской точкой входа.
Тесты игрового кода из обоих каталогов выполняются через Fabric Loader в задаче `:cubicchunks:fabric:test`.
Задача `:cubicchunks:neoforge:test` дополнительно проверяет мод через настоящий загрузчик NeoForge. `checkLoaderJar` проверяет метаданные, сервисы и вложенные зависимости каждого установочного JAR.

## Сборка

Нужны JDK 25 и доступ к репозиториям зависимостей при первой сборке.
Gradle Wrapper включён в проект. Подмодули Git для сборки не требуются.

| Команда в Windows | Результат |
| --- | --- |
| `.\dev.cmd listMods` | Моды, версии и подключённые загрузчики |
| `.\dev.cmd build` | Сборка и проверки всех подключённых модулей |
| `.\dev.cmd :cubicchunks:fabric:build` | CubicChunks для Fabric |
| `.\dev.cmd :cubicchunks:forge:build` | CubicChunks для Forge |
| `.\dev.cmd :cubicchunks:neoforge:build` | CubicChunks для NeoForge |
| `.\dev.cmd :dasm:fabric:build` | Адаптер DASM для Fabric |
| `.\dev.cmd runClient` | Отдельный клиент разработки |
| `.\dev.cmd :cubicchunks:fabric:runServer` | Сервер разработки |
| `.\dev.cmd spotlessApply` | Форматирование кода CubicChunks |

В Linux/macOS используется `./gradlew` с теми же задачами.
Готовые сборки всех модов собираются в корневой `build/libs`, сборки отдельного мода — в `mods/<id>/build/libs`.
Файлы `*-sources.jar` содержат исходники.
Устанавливайте один `cubicchunks-<loader>-*.jar`, соответствующий загрузчику. DASM и Core уже включены; отдельный DASM рядом устанавливать не нужно.
Для Fabric дополнительно нужен Fabric API для Minecraft 26.3. Forge и NeoForge предоставляют библиотеки конфигурации сами и не требуют Fabric-зависимостей.
Базовые версии: Fabric Loader 0.20.1, Forge 66.1.5 и NeoForge 26.3.0.101; Minecraft 26.3 и JDK 25.
Из-за версии Mixin в Forge байткод его мода собирается для Java 21, но сам Minecraft запускается на JDK 25.
Для разработки используйте `:cubicchunks:forge:runClient` / `runServer` или `:cubicchunks:neoforge:runClient` / `runServer`. Их файлы изолированы в `run/<loader>/<client|server>`.

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

Для проверки входа в мир через Forge или NeoForge запустите `:cubicchunks:forge:runClient` либо `:cubicchunks:neoforge:runClient` с параметром `-PloaderSmoke`.
Проверка создаёт новый кубический мир, проверяет отображение ландшафта и передачу изменения блока от сервера клиенту, выводит `CC_LOADER_SMOKE_PASSED` и завершает игру.
Перед распространением пересоберите мод без `-PloaderSmoke`: проверка артефактов запрещает публикацию JAR с тестовым сценарием.

## Происхождение

Основной проект основан на [OpenCubicChunks/CubicChunks3](https://github.com/OpenCubicChunks/CubicChunks3).
Core основан на [OpenCubicChunks/CubicChunksCore](https://github.com/OpenCubicChunks/CubicChunksCore) и хранится локально вместе с изменениями порта.
DASM использует ядро NotStirred 3.2.0 и адаптированные исходники dasm-mod. Атрибуция и лицензия находятся в `mods/dasm/NOTICE.txt`.
Лицензия проекта — MIT, см. `LICENSE`.
