# Forge

Адаптер для Minecraft 26.3 / Forge 66.1.5. Собирается задачей `:cubicchunks:forge:build`.
`runClient` и `runServer` запускаются в отдельных каталогах `run/forge`.
Установочный JAR включает DASM и Core; `*-slim.jar` — промежуточный артефакт, не для установки.
Общая логика компилируется из `../common`, платформенный код включает сеть, сервисы, события и Mixin для `markAndNotifyBlock`.
JDK 25 обязателен; байткод мода Java 21 совместим с Mixin 0.8.7, поставляемым Forge.
