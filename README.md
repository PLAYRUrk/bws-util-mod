# BWS Util Mod

Клиентский Forge-мод (`modId`: `bws_util`) для BedWars и лука в `Minecraft 1.21.11`.

Текущая версия: `1.4.1`.

## Что умеет мод

- Баллистический прицел для лука с zeroing.
- Режимы прицела `MANUAL` и `AUTO` (с упреждением).
- BedWars overlay: угрозы, враги/тиммейты, TEAM POWER, статус и тип защиты кроватей.
- Тактический вращающийся радар в правом верхнем углу.
- Контекстные помощники:
  - `Fireball Threat`,
  - `Bridge Fight Helper`,
  - `Safe Retreat Vector` (ранний вектор безопасного отхода в невыгодном файте).
- Опциональная интеграция с внешним автокликером [PLAYRUrk/AUT-CLK](https://github.com/PLAYRUrk/AUT-CLK).

## Совместимость

- Minecraft: `1.21.11`
- Forge: `61.1.5` (диапазон `61+`)
- Java: `21`
- Тип: client-side
- Конфиг: `config/bws_util-client.toml`

## Установка

1. Собрать мод:
   - Windows: `.\gradlew.bat build`
2. Взять JAR из `build/libs`.
3. Положить JAR в папку `mods` Forge-клиента.
4. Запустить игру и настроить бинды/параметры.

## Клавиши по умолчанию

- `Z` — открыть **General Settings** (общие настройки мода).
- `O` — включить/выключить Scope Overlay.
- `M` — переключить `MANUAL` / `AUTO`.
- `B` — включить/выключить BedWars overlay.
- `=` / `-` — контекстное действие:
  - при активном прицеле в `MANUAL` меняют `zero_distance` (`+/-5`, диапазон `10..200`);
  - при включенном BedWars overlay и `AUTO` (или при неактивном scope input) меняют масштаб радара.

## Экран настроек (`Z`)

Экран стабильный, без blur-crash, и содержит настройки прицела + BedWars helper-блока + звуков.

### Scope

- `Zero Distance` (`10..200`)
- `Reticle Color`
- `Show only while drawing`
- `Show stadia marks`
- `Show rangefinder`
- `Show charge bar`

### BedWars helper + sound

- `Fireball Threat` (on/off)
- `Bridge Fight Helper` (on/off)
- `Warning Sound` (master on/off)
- `Fireball Warning Sound` (on/off)
- `Void Warning Sound` (on/off)
- `Fireball Warning Volume` (`0..100`)
- `Void Warning Volume` (`0..100`)

## AUT-CLK integration

Мод поддерживает bridge-протокол `AUT-CLK` через `127.0.0.1:25566`:

- отправляет `ac_control` (включая `lmb_enabled` / `rmb_enabled`);
- читает `ac_status` и синхронизирует состояние каналов;
- использует защиту от stale-статусов и reconcile-цикл, чтобы каналы не "залипали" в выключенном состоянии.

Автоподавление каналов в моде:

- при открытом GUI подавляются LMB/RMB;
- если в руках лук — подавляются LMB/RMB (legacy-поведение);
- для consumable-предметов подавляется RMB по текущей логике в `ClientGameEvents`.

Чтобы интеграция работала:

1. Запустить `AUT-CLK`.
2. Включить `Control bridge` (`Enable`, не `OFF`).
3. Запустить Minecraft с модом.
4. Убедиться, что в `AUT-CLK` статус `Bridge: Connected`.

## Scope Overlay

### `MANUAL`

- Крест с учетом `zero_distance`.
- Stadia marks.
- Rangefinder (`◎ N.Nm`).
- Label пристрелки (`⊕ Xm`).
- Опциональный charge bar.

### `AUTO`

- Захват ближайшей цели в конусе при натяжении.
- Расчет lead-point по движению цели.
- Рендер ромба упреждения и подписи цели.

## BedWars Overlay (`B`)

Левая верхняя панель + отдельный радар справа сверху.

### Основная панель

- Заголовок `BEDWARS` + свежесть данных (`LIVE/CACHE/SCAN`).
- `ENEMIES` / `TEAM` списки (динамический layout без наложений текста).
- Приоритетные threat-строки с визуальным акцентом.
- TEAM POWER таблица:
  - сила команд,
  - теги `(you)`, `→HUNT`, `THRT`,
  - статус кровати и код защиты (`UNK`, `---`, `WOL`, `TER`, `END`, `OBS`).

### Формат строки игрока

- направление до цели (`↑ ↓ ← → ↗ ↘ ↖ ↙`);
- укороченный ник (с `!`, если игрок натягивает лук);
- дистанция, высота (`+N/-N`) и ETA;
- класс брони по **лучшей надетой части** (`N/D/I/C/G/L`).

### Радар (правый верх)

- вращается вместе со взглядом;
- показывает врагов и их вектор движения;
- показывает конструкции с цветовой категоризацией;
- кольца дальности;
- масштаб изменяется через `=` / `-` по контексту.

### Context helpers

- `Fireball Threat`:
  - текстовый статус,
  - мигающее предупреждение у центра экрана,
  - опциональный звук.
- `Bridge Fight Helper`:
  - предупреждение о риске падения **на упреждение** (по направлению движения),
  - опциональный звук.
- `Safe Retreat Vector`:
  - включается только в вероятно проигрышном бою (численность/HP/давление);
  - рисует заметное направление отхода под перекрестием;
  - учитывает anti-void безопасность направления и не перекрывает другие предупреждения.

### Логика кроватей

- Периодический скан области вокруг игрока.
- Обнаружение bed-head и оценка защиты локальной оболочки.
- Для типа защиты блоки **ниже уровня кровати не учитываются** (материал острова игнорируется).
- Есть стабилизация, чтобы уменьшить ложные скачки типа защиты при подгрузке чанков.

## Конфиг

Файл: `config/bws_util-client.toml`

Основные ключи:

- `zero_distance`
- `reticle_color`
- `show_only_while_drawing`
- `show_stadia_marks`
- `show_rangefinder`
- `show_charge_bar`
- `fireball_threat_enabled`
- `bridge_helper_enabled`
- `warning_sound_enabled`
- `fireball_warning_sound`
- `void_warning_sound`
- `fireball_warning_volume`
- `void_warning_volume`

## Архитектура (кратко)

- `ScopeOverlay` — рендер и логика прицела (`MANUAL/AUTO`).
- `BedWarsOverlay` — BedWars HUD, радар, context helpers, bed-scan.
- `ClientGameEvents` — клавиши, tick-логика, контекст `+/-`, управление bridge-подавлением.
- `AutoclickerBridgeClient` — TCP-клиент к `AUT-CLK`, sync/reconcile каналов.
- `ScopeConfigScreen` / `ScopeConfig` — GUI и клиентский конфиг.

## Быстрый чеклист

1. `O` включает/выключает scope.
2. `M` переключает `MANUAL/AUTO`.
3. В `MANUAL` проверить `=` / `-` (zeroing).
4. В BedWars (`B`) проверить `=` / `-` (масштаб радара) в нужном контексте.
5. `Z` открыть General Settings и проверить helper/sound toggles.
6. Проверить:
   - `Fireball Threat`,
   - `Bridge Fight Helper`,
   - `Safe Retreat Vector`.
7. Нажать `F1` и убедиться, что HUD/оверлеи скрываются корректно.

## Известные ограничения

- AUTO-захват работает в конусе и в плотной толпе может выбрать не идеальную цель.
- Расчеты ETA/угрозы основаны на клиентской дельте движения и чувствительны к лагам.
- Точность bed-аналитики зависит от сканируемой области и подгрузки чанков.

## Лицензия

MIT (см. `mods.toml`).
