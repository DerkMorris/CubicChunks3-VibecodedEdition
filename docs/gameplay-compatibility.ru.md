# Проверка совместимости игровых механик

Дата аудита: 20 ноября 2026. Целевая версия: Minecraft 26.3; Fabric, Forge и NeoForge.

**Полная совместимость всех механик пока не доказана.** Этот документ связывает механику с конкретной проверкой и фиксирует пробелы. Наличие теста в таблице означает наличие сценария в исходниках; успешный запуск подтверждается отдельным отчётом Gradle или маркером smoke-сценария. Количество зарегистрированных блоков, предметов или мобов не является количеством проверенных игровых механик.

В production кубическим является Overworld. Nether и End остаются обычными измерениями (`CubicWorldMode.isCubic`). Поэтому переходы через портал должны проверять также смену режима мира на клиенте и сервере. Тестовые фикстуры с `IS_IN_TEST` могут включать кубический режим иначе, чем настоящий клиент.

## Что считается доказательством

| Уровень | Что проверяется | Чего этот уровень не доказывает |
| --- | --- | --- |
| Компонент | Сериализация, индексы, координаты, алгоритмы и очереди с контролируемыми входами | Живой игровой цикл, загрузчик, сеть, GUI |
| Преобразованный Minecraft-код | Fabric Loader JUnit с настоящими mixin/DASM; отдельные нативные методы и реальные кубы | Полноценный сервер: часть `ServerLevel`, времени, света, диска или сетевых сервисов может быть замокана |
| Интеграция хранения/генерации | Полный выбранный маршрут генерации или файлового хранения | Все механики в игровом мире; две generation-фикстуры используют scoped in-memory NBT-хранилище |
| Настоящий клиент | Отдельный `worldSmoke`-мод, production-код, integrated server, обычные server/client ticks и пакеты | Выделенный сервер, удалённый multiplayer, остальные загрузчики и поведение вне конкретной сцены |
| Перезапуск процесса | Повторное открытие ранее сохранённого мира отдельным запуском | Сохранение объектов и очередей, которых не было в сцене |

`TestVanillaCubicParity` сравнивает API/имена методов, а не все игровые результаты. Stub/disabled-тесты не считаются доказательством. Стандартная задача Fabric исключает `longRunTest`; несколько старых client-mixin-тестов и server-тестов помечены `@Disabled`.

## Результаты проверки

Полная сборка, JUnit-проверки, расширенный gameplay-сценарий и дополнительный live-прогон мобов завершились успешно. Результаты относятся к перечисленным сценариям; непроверенные механики и известные ограничения остаются отмечены отдельно.

| Запуск | Наблюдаемый результат | Что именно подтверждено |
| --- | --- | --- |
| Полная сборка, `build-gameplay-build-final.log` | **BUILD SUCCESSFUL, exit code 0**, 5 мин 15 с; Fabric: **843 сценария = 833 PASS + 10 SKIP**; NeoForge: **2 PASS** | Сборка и проверки всех подключённых модулей на последнем состоянии кода. Полные Fabric XML: `build/reports/gameplay/fabric-full-final`; сводка: `build/reports/gameplay/fabric-full-final.json` |
| Новые JUnit-проверки в финальной полной сборке | **138 новых сценариев в 12 классах — PASS**, 0 FAIL, 0 SKIP | Включены поздние регрессии delayed save/unload и native consume teleport; 10 SKIP относятся к другим существующим сценариям |
| Registry storage, финальная полная сборка | 1286 зарегистрированных блоков / 35723 BlockStates, 1657 non-AIR items, файловый roundtrip на Y=±32000 | Маркеры сохранены в финальных XML. Блоки/жидкости и default item components сохраняются и читаются; поведение каждого ID отдельно не проверялось |
| Entity registry, финальная полная сборка | 157 сериализуемых entity types на Y=96, ±32000, ±32000000: **785 type/height roundtrips** | Маркеры сохранены в финальных XML. Production `CubeEntityData` сохраняет базовое состояние объектов; специальные AI, spawn и boss mechanics этим не доказаны |
| Linked Core, `build-gameplay-core.log` | 12 PASS | Core/header linkage выбранных тестов |
| Настоящий Fabric client/integrated server, `build-gameplay-smoke-final2.log` | **PASS**, exit code 0, 1 мин 42 с: три `CC_GAMEPLAY_ENTITIES`, три `CC_GAMEPLAY_PHASE`, `CC_GAMEPLAY_COMPLETE` | Y=96, +32000, −32000, `randomTickSpeed=128`, `testBypass=false`. Redstone ON/OFF, вода, random growth, печь→воронка→сундук и клиентское подтверждение; generic teleport, consume teleport effect, настоящий falling sand/landing и falling entity после 100 собственных ticks. Штатное сохранение и выход |
| Настоящие entity packets/client ticks, `build-gameplay-mobs.log` | **PASS**, exit code 0, 1 мин 16 с: два `CC_MOB_TICK_PHASE`, `CC_MOB_TICK_COMPLETE` | Y=96 и +32000; по семь мобов: шесть типов с управляемыми сервером позициями и одна свинья с native navigation. Подтверждены клиентское движение/анимации, истечение damage timer, реальная атака игрока и итоговое согласование позиций. Штатное сохранение и выход; полная AI каждого типа не проверялась |

В 138 новых успешных сценариев входят `TestCubeRandomTicks` (19), `TestCubeScheduledTicks` (15), `TestCubeTickPersistenceDirty` (14), `TestCubeHopperMechanics` (11), `TestCubeRegistryStorage` (4), `TestCubePistonMechanics` (12), `TestCubeBlockHeightMechanics` (16), `TestCubeEntityMechanics` (25), `TestCubeEntityRegistry` (5), `TestCubeFallingBlockMechanics` (5), `TestCubeWitherSummoning` (5), `TestCubeConsumeTeleport` (7). Цифры относятся к JUnit test cases, включая параметры; внутренние переборы registry objects указаны отдельно.

Успешный общий запуск `.\dev.cmd build --continue` включает защиту выгрузки pending queues от постоянной повторной записи и исправление Y-ограничения consume-эффекта chorus fruit. Полное подтверждение всех игровых механик из этого результата не следует: ограничения приведены ниже.

Финальный live-мир — `gameplay-audit-20261120-final2`. Сцены завершились за 219, 225 и 230 server scene ticks соответственно. Возраст проверяемой falling entity составил 110, 101 и 101 собственных ticks; её активация проверяется независимо от возраста сцены. Во всех трёх маркерах подтверждены `genericTeleport=true`, `consumeTeleportEffect=true`, `fallingSourceAndLanding=true`, `aliveAfter100Ticks=true`. Во всех фазах сервер передал ровно один выплавленный слиток в сундук, клиент подтвердил состояния блоков, `testBypass=false`.

Live-проверка мобов зафиксировала 154 клиентских ticks на Y=96 и 126 на Y=32000. Итоговое расхождение server/client позиций составило около 0,0000968 и 0,00000535 блока, native navigator прошёл около 16,79 и 11,83 блока. В обеих сценах `damageExpired=true` и `playerHit=true`; итоговый маркер содержит `heights=2 controlledTypes=6 nativeNavigator=1`.

## Исправленные маршруты

| Маршрут | Изменение и проверка |
| --- | --- |
| Случайные block/fluid ticks | `cc_tickCube` вызывает native random callbacks во всех восьми секциях куба. Проверены рост, жидкость, освещение и нулевая скорость |
| Scheduled block/fluid ticks | Контейнеры регистрируются по трём координатам куба; vanilla priority, deduplication, budget и copy/clear сохраняются. Проверены границы, выгрузка/регистрация и disk reopen |
| Сохранение очереди тиков | Accepted/polled/cleared ticks и уменьшающиеся относительные задержки помечают куб dirty. Проверены неизменность старого snapshot, оставшаяся задержка и отсутствие повторного появления исполненного тика. При выгрузке старение очереди останавливается после финального snapshot, чтобы задержанная запись могла закончиться; проверки с `CubeStorageBarrier` и повторной активацией прошли |
| Поршни | Height guards используют кубические пределы; native planner сохраняет лимит 12 блоков и правила immovable blocks. Анимация/полный block-event цикл остаются отдельной задачей |
| Двери и double plants | Native placement допускает кубические высоты и сохраняет ограничения на верхней границе |
| Chorus flower и лианы | Исправлены вертикальные ограничения роста; native density scan лиан ещё не проверен end-to-end и относится к незакрытым проблемам ниже |
| Падающие блоки | Исправлены height guards запуска падения и premature expiry падающей сущности за пределами обычной высоты измерения; сохранены native сроки истечения и обычный мир |
| Generic random teleport | Поиск пола и проверки целевого объёма используют загруженные кубы, полный Y и native bounding box; сохранены forbidden/liquid/collision checks и отказ для незагруженного участка |
| Consume teleport, включая chorus fruit | Исправлен clamp целевой Y в `TeleportRandomlyConsumeEffect.apply`: кубический мир использует свои пределы, обычный сохраняет dimension logical height. Семь успешных проверок вызывают native consume-effect body вместе с generic teleport и проверяют пять высот, bounds, 16 неудачных попыток и success effects |
| Призыв Wither | Height guards структуры используют кубический минимум. Проверены native pattern, расход блоков, создание сущности и Peaceful guard; бой босса этим не покрыт |

## Известные незакрытые проблемы

Это выводы по текущим исходникам мода и локальному байткоду Minecraft 26.3. Для перечисленных ниже механизмов отдельного воспроизведения в настоящем клиенте пока нет; предполагаемое игровое проявление отделено от подтверждённого пути кода.

- **Погодные тики кубов:** `MixinServerLevel.cc_tickCube` вызывает `CubeRandomTicks.tick`, который реализует случайные тики блоков/жидкостей. Кубический аналог погодной части vanilla `tickChunk` — осадки, образование снега/льда и локальная обработка грозы/молнии — ещё отсутствует. Проверки дня/ночи и lava ignition этого не покрывают; они не доказывают полноценную погоду.
- **Пирамида маяка ниже обычного дна измерения:** native `BeaconBlockEntity.updateBase` прекращает проверку слоёв при `y < Level.getMinY()`. Мод сохраняет обычные размеры/section counts измерения, отдельной адаптации этого метода пока нет. Это подтверждённое ограничение маршрута проверки основания; работу луча, эффектов и GUI маяка на больших высотах требуется проверить отдельным сценарием.
- **Общее сканирование области блоков:** native `BlockScanUtils.findBlocks` ограничивает Y через `getMinSectionY/getMaxSectionY` и читает `getChunk(x,z)` как колонку. Общий маршрут пока не адаптирован к кубам; специальная обработка structure templates его не заменяет. Это риск для использующих его vanilla area/density scans. В `TestCubeBlockHeightMechanics` проверка плотности лиан подменяется фикстурой, поэтому тест вертикального роста не доказывает правильный подсчёт окружающих лиан в реальном мире.

## Матрица покрытия

Названия классов однозначно находятся через `rg --files mods/cubicchunks | rg <имя>`. Общие игровые тесты выполняются задачей Fabric, а не `:cubicchunks:common:test` (она отключена).

| Механика | Существующие проверки | Границы доказательств и оставшаяся работа |
| --- | --- | --- |
| Создание кубического мира, сохранённый режим, вход | `TestCubicWorldMode`, `TestCubeWorldEntry`, `WorldEntryProbe` | Проверить переходы измерений, обычный мир после кубического в той же сессии, multiplayer reconnect |
| Высота, координаты, границы кубов | `TestCubePos`, `TestCloPos`, `TestCubicLevelReader`, Core `CoordsTest`/`CubePosTest` | Корректная координата в одном API не доказывает её сохранение во всех vanilla-кэшах и пакетах |
| Чтение/запись блоков и жидкостей | `TestCubicLevel`, `TestLevelCube`, `TestProtoCube` | В основном API routing и данные; каждый блок имеет свои callbacks, shapes и соседние обновления |
| Разрушение/установка игроком, подтверждение действий | `TestCubePlayerActionPackets`, `TestCubePlayerActionRouting`, `TestCubePredictions`, `WorldLightDigProbe`, `WorldLightDecreaseProbe` | Проверены выбранные creative-действия и ограничения native validation; нужны survival mining, durability, loot, adventure predicates и все специальные предметы |
| Двери и высокие растения | `TestCubeBlockHeightMechanics`: native placement дверей и double plants, вертикальные границы | Проверяет height guards и результат placement-метода в фикстуре; не все native item-use действия, обе половины через сеть, соседние изменения и сохранение |
| Случайные тики: рост, трава, листва, огонь | `TestCubeRandomTicks`: рост культур во всех восьми секциях, тростник на шве, lava ignition, свет/зрелость/нулевая скорость; `WorldGameplayProbe`: сахарный тростник с `AGE_15=15` и `randomTickSpeed=128` | При начале аудита `MixinServerLevel.cc_tickCube` был пуст. Тростник проверяет исполнение маршрута, но не все культуры/деревья/болезни/листву/лёд; повышенная скорость не проверяет баланс обычного мира |
| Chorus flower и лианы | `TestCubeBlockHeightMechanics`: native рост chorus и вертикальное распространение лиан через шов | Изменения height guards проходят отдельную проверку; плотность лиан замокана и общий `BlockScanUtils` остаётся незакрытым. Не считать всю механику лиан проверенной |
| Отложенные тики блоков | `TestCubeScheduledTicks`: priority/budget/dedupe, unload/register, copy/clear, disk reopen; `TestCubeTickPersistenceDirty`: queue mutations, remaining delay, frozen aging, delayed disk write и повторная активация; `WorldGameplayProbe`: repeater/lamp ON и OFF | При начале аудита `LevelCube.registerTickContainerInLevel` и `unregisterTickContainerFromLevel` были пусты. Нужны дополнительные native block behaviors, piston/observer/TNT и реальные `clone`/`fill` команды |
| Вода и лава | `TestCubeMobNavigation.nativeFluidInteractionUsesCubeSectionsAndResetsAfterLeavingWater`, `WorldGameplayProbe`: падение воды через вертикальный шов | Коллизия с водой не равна её течению. Нужны горизонтальные потоки, лава/вода/обсидиан, водонасыщение, пузырьковые колонны, вёдра, замерзание и сохранение тиков |
| Redstone и соседние обновления | `WorldGameplayProbe`: lever → repeater → lamp через горизонтальный шов, включение/выключение | Подготовка рычага программная. Не проверены вся пыль, comparator, observer, slime/honey, rails, quasi-connectivity, update order и unload схем |
| Поршни | `TestCubePistonMechanics`: native `PistonStructureResolver`, push/pull через шов, 12-block limit, immovable blocks и границы мира | Подтверждает планирование, не реальное исполнение block events, анимацию moving piston, клиентские пакеты, sticky side branches и сохранение движущейся конструкции |
| Block entities: хранение, сеть, жизненный цикл | `TestSerializableCubeData`, `TestClientCubeEntitySnapshots`, `TestCubeBlockEntitySnapshotCodec`, `TestCubeBellEvents` | Копирование NBT/пакета не доказывает ticking каждого block entity; нужны все типы, GUI/menu и load/unload |
| Печь и воронка | `TestCubeHopperMechanics`: native bound ticker, transfer/cooldown/conservation, enabled/status/freeze; `WorldGameplayProbe`: raw iron → furnace → hopper → chest через вертикальный шов; сервер проверяет один слиток, клиент — горящую печь | Инвентарь проверяется на сервере: он не обязан синхронизироваться без открытого меню. Нужны input hopper, sided slots, crafting recipes, fuel, XP, comparator, containers across reload |
| Сундуки, crafting и инвентари | `TestSerializableCubeData`, `TestClientCubeEntitySnapshots`: выбранные содержимые chest | Не проверены double chest, shulker box, ender chest, crafting/table/anvil/grindstone/enchanting/brewing/merchant UI, shift-click и выброс предметов |
| Полный registry: блоки и предметы как данные | `TestCubeRegistryStorage`: все зарегистрированные BlockStates/fluids и все non-AIR Items с default components через файловое хранилище | Количество state/item IDs выводится в runtime-маркерах. Это сохранение/чтение данных, не функциональная проверка каждого блока/предмета |
| Колокол и block events | `TestCubeBellEvents`, `TestCubeVillagerEvents` | Нативная очередь, неактивный куб, радиус 3D, клиентские события; другие block events (поршни и т. п.) требуют отдельных сцен |
| Свет и изменение прозрачности | `TestCubeLightVanillaParity`, `TestBoundedCubeLightSolver`, `TestCubeWorldLighting`, `TestCubeSkyOcclusion`, `WorldLightProbe` | Широкое покрытие выбранных геометрий; нужны сочетания со всеми динамическими механиками. На ±32 млн проверка численного света не доказывает отображение геометрии |
| Предсказание света и рассинхронизация | `TestClientCubeLightPrediction`, `TestCubeLightRefreshPackets`, `WorldLightDigProbe`, `WorldLightDecreaseProbe` | Положительная/отрицательная высота, задержки и коррекции; remote latency/reconnect и modded packets отдельно |
| Генерация биомов, руд, пещер | `TestCubeTerrainGeneration`, `TestDeepCaveTerrain`, `TestOreProfiles`, `BiomeProbe`, `DeepCavesProbe`, `OreProfileProbe` | Выбранные seeds/биомы/распределения; не полный seed-space и не совместимость со всеми datapacks/worldgen-модами |
| Структуры и шаблоны | `TestNativeStructureLayout`, `TestCubeStructureTemplates`, `TestCubeTemplateEntities`, `TestCubeStructureCorners`, `StructureGenerationProbe` | Не доказывает работу всех loot tables, спавнеров, trial chambers, vaults, structure blocks и команд `place`/`locate` |
| Естественный спавн | `TestCubeNaturalSpawning`, `TestCubeStructureSpawning` | Native selected mobs, caps, свет, 3D-дистанция, структура/биом; не индивидуальные правила каждого mob type и не длительный баланс populations |
| Специальный спавн | `TestCubeSpecialSpawning` | Phantom/insomnia, patrol, cat, trader/llamas, cooldown и gamerules в фикстурах; raid lifecycle и все редкие spawn events отдельно |
| Тики мобов, остановка, пассажиры | `TestCubeMobTicking`, `TestClientCubeEntityTicking`, `WorldMobTickProbe` | Probe: pig, cow, zombie, enderman, bat, cod и ещё pig с native navigation; это не проверка всех сущностей |
| Навигация, коллизии и движение | `TestCubeMobNavigation`, `TestBlockCollisions`, `WorldMobTickProbe` | Ground/flying/swimming paths, missing cubes, packed-position aliases; нужны climbing, doors, jumping, leash, multipart hitboxes и вся уникальная AI |
| Урон и клиентские анимации | `WorldMobTickProbe`: реальная атака игроком, hurt timer, ходьба, server/client positions | Не проверены все damage types, броня, эффекты, смерть/loot/XP, combat attributes и PvP |
| Видимость и сетевое отслеживание сущностей | `TestCubeEntityVisibility`, `TestCubeEntityTracking`, `WorldMobTickProbe` | Полные координаты, unload, oversized bounds, aliases; нужен удалённый multiplayer с несколькими игроками и большой latency |
| Сохранение сущностей и пассажиров | `TestCubeEntityPersistence`, `TestCubeEntityRegistry`: сериализуемые registry types через production `CubeEntityData` | Реальные файлы, UUID, разные высоты, passenger trees, ошибки диска; registry roundtrip проверяет базовое состояние, не AI каждого типа и не все component/NBT комбинации |
| Предметы-сущности и projectile query | `TestCubeEntityMechanics`: gravity/seam, pickup delay/expiry/pause, nearest projectile ray | Native методы с подменёнными посторонними world services; не полный цикл pickup/loot/projectile impact и все специальные projectile types |
| Телепортация существ | `TestCubeEntityMechanics`: Enderman и generic `LivingEntity.randomTeleport`; `GameplayEntityChecks`: успешный pig random teleport в настоящем мире на трёх высотах | Пол/жидкость/стена/незагруженный куб проверяются отдельно; не все teleport items, межизмеренческие переходы и условия конкретных мобов |
| Плод хоруса и consume teleport | `TestCubeConsumeTeleport`: native `TeleportRandomlyConsumeEffect.apply` → generic teleport с обычным diameter=16 на пяти высотах; bounds/vanilla clamp, 16 failed attempts, fall distance/impulse и success events. `GameplayEntityChecks`: успешный тот же эффект с diameter=0 на свинье в настоящем мире | JUnit и live effect-проверки прошли. Diameter=0 изолирует Y-clamp без случайного выхода из комнаты; unit-сценарий проверяет также смещение X на 4 блока. Полноценное поедание игроком, stack/cooldown/food, client animation и сетевой цикл не проверяются |
| Падающие блоки | `TestCubeEntityMechanics`: falling entity expiry; `TestCubeFallingBlockMechanics`: девять типов источников на пяти высотах; `GameplayEntityChecks`: sand `onPlace` → scheduled fall → landing, suspended entity живёт больше 100 ticks | Source-тесты и runtime helper дополняют expiry-тест. Не проверены все особенности anvils/concrete powder/archaeology, попадание по сущности, вода и сохранение падающих объектов |
| Жители и POI | `TestCubePoiManager`, `TestCubePoiBehaviors`, `TestCubeVillagerLife` | Native home/job/meeting, профессия farmer, сон, restock, cooldown, memory persistence; нужны breeding, curing, trading UI, gossip/reputation и полный дневной цикл в игре |
| Железные големы | `TestCubeGolemSpawning` | Panic/gossip quorum, сон/cooldown, полный 3D bounding box, sensors; ручное построение голема и snow golem отдельно |
| Полёт и фейерверки | `WorldEntityTravelProbe` | Реальный rocket tick и подъём от Y=-400 до поверхности; не все виды транспорта, elytra durability и межизмеренческие поездки |
| Смерть, кровать, respawn и spawn tickets | `TestPlayerRespawnLogic`, `TestServerPlayer`, `TestMinecraftServer` | Часть старых тестов отключена; нужны survival death, bed/anchor obstruction, inventory/XP, End return и сохранённая точка на большой высоте |
| Погода, снег/лёд/молния, день/ночь | `DaylightProbe`, вычисления света/локальной сложности | Daylight не доказывает native weather ticks. Нужны thunder/lightning rod, precipitation, freeze/melt и влияние крыши на разных высотах |
| Маяк | Отдельного поведенческого теста пока нет | Native `updateBase` использует обычный minimum Y; основание, луч, эффекты, GUI и синхронизация требуют проверки |
| Game events/vibrations: sculk, Warden, allay | Нет полноценного сценария | `LevelCube.addGameEventListener` преобразован, но dispatch/section lookup, vibration travel и слух надо проверять отдельно |
| Порталы и измерения | `TestCubicWorldMode` проверяет выбор режима | Нет end-to-end Nether portal/End portal/gateway, поиска выхода и возврата; большие Y, POI портала, пассажиры, player data и world border особенно важны |
| Призыв Wither | `TestCubeWitherSummoning`: native pattern eligibility/consumption, создание Wither и Peaceful guard на пяти высотах | Проверяет структуру и начальное создание в фикстуре; не реальное player placement/network и не combat/invulnerability/explosion/phases |
| Боссы и сложные AI | Нет полного сценария боя и жизненного цикла | Ender Dragon/End fight, Wither combat, Warden, raids, guardians, bees, breeze, projectile/explosion interactions; базовый entity tick и тест призыва не заменяют эти сценарии |
| Рейды | Debug-код `LevelCube.registerDebugValues` читает raid centers; patrol проверен в `TestCubeSpecialSpawning` | Debug display и patrol не доказывают запуск/волны/центр/победу/поражение рейда |
| Предметы и специальные действия | Пакеты `useItemOn` и выбранные native creative actions | Нужны все категории: еда/эффекты, projectile weapons, ender pearl/eye, fishing rod, bucket, map/compass, books/signs, tools, armor, vehicles, spawn eggs и components |
| Команды, достижения и статистика | Нет полного сценария | `setblock`, `fill`, `clone`, `execute`, `tp`, `summon`, `locate`, `forceload`, predicates/advancements/statistics при большом Y; проверить командные лимиты и координатные codecs |
| Сохранение кубов/POI, длительное путешествие | `IntegrationTestCubePersistence`, `TestCubeSaveQueue`, `TestCubePoiManager`, `WorldEntryProbe -PsmokeAsyncSaves` | Файловые ошибки и async races покрываются отдельно; shutdown после игрового gameplay-сценария не является fresh-process reopen этого сценария |
| Отрисовка и вертикальная загрузка | `TestVerticalRenderWindow`, `TestCubicRenderGraph`, `StreamingProbe`, `CameraTurnProbe` | Известное ограничение packed render section на экстремальных высотах; GUI, particles, sounds, fog и biome effects требуют визуальной проверки |
| Загрузчики | Fabric JUnit; `LoaderBootstrapTest` для NeoForge; `LoaderSmokeProbe` для Forge/NeoForge | Loader smoke проверяет создание мира/ландшафт/изменение блока. Полный gameplay probe пока Fabric-only; общая production-логика не заменяет три реальных прогона |

## Повторяемые команды

Из корня репозитория, JDK 25. Использовать свежие имена smoke-миров: сценарии программно изменяют только свой тестовый мир в `run/world-smoke`.

```powershell
# Полный обычный набор и проверки загрузчиков/артефактов.
.\dev.cmd build

# Общая transformed Minecraft suite через Fabric.
.\dev.cmd :cubicchunks:fabric:test

# Основные существующие поведенческие группы.
.\dev.cmd :cubicchunks:fabric:test --tests '*TestCubeMob*' --tests '*TestCubeNaturalSpawning' --tests '*TestCubeSpecialSpawning' --tests '*TestCubeStructureSpawning' --tests '*TestCubeGolemSpawning'
.\dev.cmd :cubicchunks:fabric:test --tests '*TestCubePoi*' --tests '*TestCubeVillager*' --tests '*TestCubeBellEvents'
.\dev.cmd :cubicchunks:fabric:test --tests '*TestCubePlayerAction*' --tests '*TestCubePredictions' --tests '*TestClientCubeEntity*'
.\dev.cmd :cubicchunks:fabric:test --tests '*IntegrationTestCubePersistence' --tests '*TestCubeSaveQueue' --tests '*TestCubeEntityPersistence'

# Новый настоящий gameplay-сценарий: одна изолированная проверка на запуск.
.\dev.cmd -PworldSmoke -PsmokeGameplay -PsmokeWorld=gameplay-audit-20261120 runWorldSmoke

# Реальные мобы и переход между высотами.
.\dev.cmd -PworldSmoke -PsmokeMobTicks -PsmokeWorld=mobs-audit-20261120 runWorldSmoke
.\dev.cmd -PworldSmoke -PsmokeMobTravel -PsmokeWorld=mob-travel-audit-20261120 runWorldSmoke
.\dev.cmd -PworldSmoke -PsmokeEntityTravel -PsmokeWorld=travel-audit-20261120 runWorldSmoke

# Свет на отображаемых высотах, затем отдельный процесс с тем же миром.
.\dev.cmd -PworldSmoke -PsmokeLighting -PsmokeLightHeight=32000 -PsmokeLightRender -PsmokeWorld=light-audit-20261120 runWorldSmoke
.\dev.cmd -PworldSmoke -PsmokeLighting -PsmokeLightHeight=32000 -PsmokeLightRender -PsmokeWorld=light-audit-20261120 -PsmokeReopen runWorldSmoke

# Bootstrap каждого другого загрузчика, в отдельных run directories.
.\dev.cmd :cubicchunks:neoforge:test
.\dev.cmd -PloaderSmoke :cubicchunks:forge:runClient
.\dev.cmd -PloaderSmoke :cubicchunks:neoforge:runClient
```

Для повторного выполнения up-to-date JUnit-задачи использовать `--rerun-tasks`. Это также повторит её зависимости; не нужно повторять всё без новой причины. Полные XML: `mods/cubicchunks/fabric/build/test-results/test`; HTML: `mods/cubicchunks/fabric/build/reports/tests/test/index.html`. Отчёт последнего выборочного запуска содержит только выбранные классы: сохраняйте результаты полного прогона до выборочного.

Сценарий `smokeGameplay` программно подготавливает комнату на Y=96, +32000, -32000. Тростник пересекает вертикальный шов, вода течёт через него, печь передаёт слиток через воронку в сундук, repeater/lamp пересекают горизонтальный шов. Прогресс создают обычные server ticks; вызовы `randomTick`, `tick`, `serverTick` объектов из probe отсутствуют. Клиент подтверждает состояния блоков/воды и горящую печь, сервер — содержимое сундука. `GameplayEntityChecks` дополнительно вызывает native random teleport свиньи и `TeleportRandomlyConsumeEffect(0, false).apply` с chorus fruit, ставит песок для обычного scheduled falling/landing и проверяет, что подвешенная falling entity не исчезает после 100 ticks. Нулевой диаметр consume-эффекта изолирует Y-clamp без случайного выхода из комнаты; это не полное поедание предмета игроком. Эти дополнительные результаты проверяются на сервере; отдельного клиентского подтверждения позиции свиньи helper не делает. Успех сценария требует трёх `CC_GAMEPLAY_ENTITIES` с `consumeTeleportEffect=true`, трёх `CC_GAMEPLAY_PHASE` и `CC_GAMEPLAY_COMPLETE`; `testBypass` должен быть `false`. Снимки: `run/world-smoke/screenshots/gameplay-<height>.png`.

Этот сценарий не проверяет естественный рост с возрастом 0, survival-действие рычагом, GUI инвентаря, multiplayer или reopen. Не объединять его с другими probe-флагами: каждый probe сам управляет игроком и завершением клиента. Один только `BUILD SUCCESSFUL` после раннего ручного закрытия клиента не заменяет маркер завершения.

## Следующие обязательные сценарии для полной совместимости

1. Повторить gameplay через сохранение, выгрузку/загрузку и новый процесс: pending block/fluid ticks, hopper cooldown, furnace progress, предметы и POI. Отдельно проверить мир без игрока рядом и pause/freeze/tick-rate.
2. Добавить категории блоков: piston/observer/comparator, falling sand/anvil, TNT/explosion, fire, все группы культур, дерево/листва, portal, rail/cart, sculk/vibrations, жидкости и waterlogging. Для каждой — шов по X, Y и Z, оба знака Y, отложенная загрузка соседа.
3. Пройти survival lifecycle и категории предметов: добыча/дроп/XP, crafting, все menu types, еда/статусы/броня, смерть/respawn, транспорт, бой/projectiles, fishing и tame/breed/cure/trade.
4. Проверить Nether/End roundtrip и bosses/raids в настоящем мире. Наличие объектов в registry — только инвентарь предстоящих кейсов; у каждого поведения нужен свой ожидаемый результат.
5. Повторить ключевые сценарии в Forge/NeoForge и на выделенном сервере с двумя настоящими клиентами: разные высоты при одинаковом X/Z, выход/возврат, задержка пакетов и reconnect.
6. Вести результаты по комбинациям «версия сборки → загрузчик → сценарий → координаты → ожидаемый результат → фактический результат → лог/снимок», фиксируя FAIL/BLOCKED/NOT RUN отдельно. Не превращать непроверенное в PASS по сходству с прошедшим кейсом.
