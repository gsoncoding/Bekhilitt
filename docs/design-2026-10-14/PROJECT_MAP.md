> **Область документа.** Это документационный снимок локального проекта, подготовленный 13 октября 2026 года, и новое направление разработки. Код этой GitHub-ветки остаётся на публичной базе **1.2.0-epic.4** (`010eb714`); код локальных 1.3–1.5 этой публикацией не добавлен. Слова «текущий», «реализовано», названия классов, команды запуска и результаты QA ниже относятся к соответствующему локальному комплекту, а не доказывают их наличие/проверку в этой ветке. Исторические решения не заменяют новые требования. [Граница публикации и порядок чтения](README.md).

# Актуальная документация двух центральных героев

- [Обзор](TWO_HEROES_REVIEW.md): понятное разделение обязательного, готового, предложенного и невыбранного.
- [Конституция](CONSTITUTION.md): требования пользователя, включая собственный боевой профиль Гриффита.
- [Архитектура](ARCHITECTURE.md): текущий NeoForge/Epic Fight runtime и отдельно будущие контракты.
- [Архив архитектуры 1.0](ARCHITECTURE_1_0_HISTORICAL.md): исторический Fabric-план, не текущая реализация.
- [ADR 014](decisions/014-two-central-heroes.md): новая авторская граница и отношение к ADR 004/005.

Далее сохранена карта реализованных итераций и исторических проектных путей. Старые Fabric-файлы и будущие модули нельзя принимать за фактический runtime 1.5.

# Новый лагерный срез 1.5

- `presentation/CampSliceScene` — серверный немодальный тайминг.
- `platform/FirstEpisode` — slice/demo команды и интеграция прежних verified leases.
- `platform/persistence/CampSliceStore` — отдельный последний результат без наград.
- `platform/persistence/SliceDemoFixture` — явный synthetic setup только нового demo-мира.
- `client/render/GutsSheath*` — original косметический меч без inventory операций.
- ADR013 (источник локального комплекта: `docs/decisions/013-world-space-camp-slice.md`; в эту публикацию не включён), приёмка (источник локального комплекта: `docs/QA_GAMEPLAY_SLICE.md`; в эту публикацию не включён).

# В разработке: первое поручение

- `mission/FirstRaidSnapshot`, `FirstRaidService`, `FirstRaidCodec` — отдельные durable этапы и однократное признание.
- `platform/persistence/FirstRaidStore` — ограниченное versioned хранилище.
- `platform/raid` — native бой, bounded склад, safety и placement transaction.
- `client/render/RaidSoldierRenderer` — собственные модели двух сторон.
- `client/compat/RaidTargetingScope`, `EpicFightRaidTargetFilterMixin` — узкий client-only фильтр союзников для закреплённого Epic Fight; вне рейда native поведение.
- Первое поручение (источник локального комплекта: `docs/FIRST_HAWKS_RAID.md`; в эту публикацию не включён), проверенные кандидаты и ограничения QA (источник локального комплекта: `docs/QA_FIRST_HAWKS_RAID.md`; в эту публикацию не включён).
- ADR 011 (источник локального комплекта: `docs/decisions/011-first-hawks-raid.md`; в эту публикацию не включён) — первичные источники, границы постановки и сохранения.

# Новое в epic.3–4

- `platform/CampSafetyEvents`, `platform/story/CampSafetyRules` — bounded free-camp safety; native event adapters и регрессионные тесты.
- Зависимости/лицензии (источник локального комплекта: `docs/DEPENDENCIES_AND_LICENSES.md`; в эту публикацию не включён), QA epic.4 (источник локального комплекта: `docs/QA_CAMP_SAFETY.md`; в эту публикацию не включён).
- `platform/EpisodeAdvanceRouting` — проверяемый порядок строгой ревизии и CANCEL перед END/camp, регрессионные тесты.
- [Утверждённый маршрут](PLAYABLE_ROADMAP.md) — последовательные следующие playable этапы; не перечень уже реализованных функций.

# Новое в epic.2

- `presentation/DialogueLayout`, `DialoguePager` — логическая геометрия и защита повторного ввода.
- `client/ui/EpisodeScreen` — компактная панель и страницы; `EpisodePresentation` — ожидаемые серверные переходы END/camp.
- `campaign/CampVisit` — терминальный read-only доступ и повторное обращение к персонажам.
- `platform/CampPlayerLease` — durable параметры прогулки; `TrainingLeaseVerifier.verifyCamp` — проверка сохранения.
- ADR 010 (источник локального комплекта: `docs/decisions/010-compact-dialogue-free-camp.md`; в эту публикацию не включён), запуск (источник локального комплекта: `docs/INSTALL_CAMP.md`; в эту публикацию не включён), QA (источник локального комплекта: `docs/QA_CAMP.md`; в эту публикацию не включён).

# Карта проекта

## Экспериментальный порт Epic Fight, 13 октября 2026

- `BekhilittMod` — NeoForge events; `platform/networking/Network` — story-only payload transport
- `combat/EpicDuelAttempt` — server-owned native combat result boundary, не симуляция механик
- `platform/EpicFightTrainingLoadout` — temporary item/skills/state lease
- `combat/SkillPracticeAttempt`, `platform/PracticeCommandGuard` — отдельная практика native Leap/Slam после END и защита игрового правила
- `platform/persistence/TrainingLeaseVerifier` — строгая lease дополнительных слотов и mobGriefing; см. практика (источник локального комплекта: `docs/NATIVE_SKILL_PRACTICE.md`; в эту публикацию не включён)
- `client/render` — собственные модели/текстуры Гатса, Гриффита и Каски, ограниченные активным эпизодом
- `client/BekhilittNeoClient`, `client/ui` — NeoForge presentation, camera return и фактические EF bindings
- `platform/story/WarCampPlan` — pure seeded scenery, deterministic clearance tests
- `platform/story/WarCampFoundation`, `platform/persistence/CampPlacementStore` — ограниченный фундамент и durable исходные высоты без повторного подъёма лагеря
- `platform/persistence/TrainingLeaseVerifier` — fail-closed проверка сохранённой аренды снаряжения
- `scripts/fetch-official-mods.py` и установка (источник локального комплекта: `docs/INSTALL_EPIC_PROFILE.md`; в эту публикацию не включён) — отдельные официальные зависимости с hashes
- `platform/story/WarCampEnvironment` — vanilla-block adapter; см. окружение (источник локального комплекта: `docs/WAR_CAMP_ENVIRONMENT.md`; в эту публикацию не включён)
- `META-INF/neoforge.mods.toml` — pinned required dependencies
- ADR 008 (источник локального комплекта: `docs/decisions/008-epic-fight-experimental.md`; в эту публикацию не включён) — границы переноса и лицензирования
- ADR 009 (источник локального комплекта: `docs/decisions/009-native-practice-after-end.md`; в эту публикацию не включён) — отдельная обратимая практика после END
- RELEASE_EPIC (источник локального комплекта: `docs/RELEASE_EPIC.md`; в эту публикацию не включён), QA_EPIC_FIGHT (источник локального комплекта: `docs/QA_EPIC_FIGHT.md`; в эту публикацию не включён) — состав экспериментального комплекта и фактическая матрица
- `scripts/package-epic-release.py` — проверяемая упаковка без сторонних JAR; правила (источник локального комплекта: `docs/PACKAGING_EPIC_RELEASE.md`; в эту публикацию не включён)

Следующая часть фиксирует историческую структуру 1.1.0, а не текущий Fabric runtime.


## Кандидат первого эпизода, 12 октября 2026

- `campaign/` — immutable snapshot, строгий codec, серверные переходы и один принадлежащий попытке Duel
- `platform/FirstEpisode.java` — интеграция сцен, кампании, сети, боя и восстановления игрока
- `platform/persistence/EpisodeStore.java` — atomic versioned world snapshot и recovery lease
- `platform/persistence/OwnerReturnVerifier.java` — подтверждение фактического owner в level.dat перед снятием lease
- `platform/story/` — безопасный небольшой лагерь, проверяемые данные постановки, непостоянный region lease и bounded readiness gate
- `protocol/Episode*Payload.java`, `client/ui/` — серверные намерения и читаемый фиксированный диалог
- `data/bekhilitt/story/first_episode.json` — данные восьми согласованных этапов
- `campaign/*Test` — проверки снимков, прерываний, ledger и файлового сохранения
- QA_FIRST_EPISODE (источник локального комплекта: `docs/QA_FIRST_EPISODE.md`; в эту публикацию не включён) — проверенная матрица сборки/игровых сценариев и оставшиеся ограничения
- FIRST_EPISODE (источник локального комплекта: `docs/FIRST_EPISODE.md`; в эту публикацию не включён) — запуск кандидата; ADR 007 (источник локального комплекта: `docs/decisions/007-first-episode-checkpoints.md`; в эту публикацию не включён) — принятые технические границы

Старая отдельная `PrototypeArena` удалена, боевое ядро и input tests сохранены. Следующие разделы описывают предыдущий стенд и долгосрочный архитектурный план; они не объявляют будущие системы реализованными.

На 12 октября 2026 года добавлен технический боевой стенд 1.0.0. Полная кампания остаётся проектом.

## Реализованный боевой стенд

- `build.gradle`, `settings.gradle`, `gradle.properties`, wrapper — сборка Fabric, Java 21, отдельные main/client source sets.
- `src/main/java/dev/bekhilitt/BekhilittMod.java` — общая точка входа и серверные события.
- `src/main/java/dev/bekhilitt/combat/Duel.java` — чистые правила дуэли в тиках.
- Прежний адаптер `PrototypeArena` заменён `FirstEpisode`; история стенда доступна в Git.
- `src/main/java/dev/bekhilitt/protocol/` — два payload намерений/состояния представления.
- `src/client/java/dev/bekhilitt/BekhilittClient.java` — переназначаемый ввод.
- `src/main/java/dev/bekhilitt/input/CombatInput.java` — тестируемые границы контекста и нажатий; `src/client/java/dev/bekhilitt/client/mixin/MinecraftInputMixin.java` — перехват ванильных эффектов только в дуэли.
- `src/main/resources/fabric.mod.json`, `assets/bekhilitt/lang/` — метаданные и локализация.
- `src/test/java/dev/bekhilitt/combat/DuelTest.java` — тесты правил.
- `src/test/java/dev/bekhilitt/input/CombatInputTest.java` — регрессии ввода; LINUX_TESTING (источник локального комплекта: `docs/LINUX_TESTING.md`; в эту публикацию не включён) — переносимый тестовый профиль.
- COMBAT_PROTOTYPE (источник локального комплекта: `docs/COMBAT_PROTOTYPE.md`; в эту публикацию не включён) — запуск, defaults, ограничения; CHANGELOG (источник локального комплекта: `CHANGELOG.md`; в эту публикацию не включён) — пользовательские итерации.
- ADR 006 (источник локального комплекта: `docs/decisions/006-combat-prototype.md`; в эту публикацию не включён) — границы реализации стенда.


## Реальные документы и их ответственность

| Путь | Единственное основное место для |
|---|---|
| [README](LOCAL_ARTIFACT_README.md) | Назначение и порядок чтения |
| AGENTS (источник локального комплекта: `AGENTS.md`; в эту публикацию не включён) | Правила восстановления контекста и работы |
| [CONSTITUTION](CONSTITUTION.md) | Требования пользователя, продуктовые гипотезы и открытые вопросы |
| [ARCHITECTURE](ARCHITECTURE.md) | Границы систем, зависимости, состояние, клиент и сервер |
| STORY_DATA (источник локального комплекта: `docs/STORY_DATA.md`; в эту публикацию не включён) | Контракт данных, валидация, восстановление и награды |
| PROTOTYPE_PLAN (источник локального комплекта: `docs/PROTOTYPE_PLAN.md`; в эту публикацию не включён) | Этапы, приёмочные сценарии и риски |
| [STATUS](STATUS.md) | Текущее положение и ближайшая задача |
| ADR 001 (источник локального комплекта: `docs/decisions/001-stack.md`; в эту публикацию не включён) | Кандидат стека и технические источники |
| ADR 002 (источник локального комплекта: `docs/decisions/002-state-and-scope.md`; в эту публикацию не включён) | Причины выбора единого состояния и малого среза |
| ADR 003 (источник локального комплекта: `docs/decisions/003-chronology-and-controls.md`; в эту публикацию не включён) | Утверждённое начало, хронология и направление управления |
| [ADR 004](decisions/004-playable-character-boundary.md) | Разделение личности и управления, будущая привязка Player и NPC |
| ADR 005 (источник локального комплекта: `docs/decisions/005-first-playable-episode.md`; в эту публикацию не включён) | Утверждённый первый эпизод: поединок с Гриффитом, вступление и границы обучения |
| CHRONOLOGY (источник локального комплекта: `docs/story/CHRONOLOGY.md`; в эту публикацию не включён) | Порядок событий и показа, эпизодные привязки и проверка канона |
| [CAMPAIGN](story/CAMPAIGN.md) | Предлагаемая структура истории |
| [CHARACTERS](story/CHARACTERS.md) | Художественные ориентиры персонажей |
| [GRIFFITH](story/GRIFFITH.md) | Личная линия второго главного персонажа, знания, отношения, занятия и переходы |
| SCENES (источник локального комплекта: `docs/story/SCENES.md`; в эту публикацию не включён) | Реестр сцен и их драматические задачи |
| SOURCES (источник локального комплекта: `docs/story/SOURCES.md`; в эту публикацию не включён) | Происхождение и проверка реплик |
| BOSSES (источник локального комплекта: `docs/story/BOSSES.md`; в эту публикацию не включён) | Кандидаты в боссы, формы, исходы, источники и конфликты охвата |

## Планируемые пути и точки входа

Ниже сохранён архитектурный план полной версии. Инфраструктура, campaign, файловые сохранения и ограниченные данные первого эпизода уже реализованы в перечисленных выше файлах. Остальные подсистемы и вложенные пакеты остаются планом; создавать их только вместе с нужной реализацией. Предлагаемый Java namespace `dev.bekhilitt` — внутреннее имя пакета, не утверждение о владении доменом.

| Планируемый путь | Назначение и точка входа |
|---|---|
| `build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew`, `gradle/wrapper/` | Один проект, закреплённые версии и wrapper |
| `src/main/resources/fabric.mod.json` | Регистрация общей и клиентской точек входа |
| `src/main/java/dev/bekhilitt/BekhilittMod.java` | Общая регистрация; подключение серверных событий через адаптеры |
| `src/main/java/dev/bekhilitt/platform/` | Интеграция, сеть, загрузка контента, сохранение, регионы, события Minecraft |
| `src/main/java/dev/bekhilitt/campaign/` | CampaignService, задания, сцены, отношения, условия, переходы и модели сохранения |
| `src/main/java/dev/bekhilitt/combat/` | Серверные правила удара, выносливости и поединка |
| `src/main/java/dev/bekhilitt/combat/ActionIntent.java`, `src/main/java/dev/bekhilitt/combat/ActionGate.java` | Будущий общий контракт действий AI и игрока; имена проектные, файлов нет |
| `src/main/java/dev/bekhilitt/platform/control/` | Будущая привязка героя к источнику управления и адаптеру сущности; передача не реализована |
| `src/client/java/dev/bekhilitt/BekhilittClient.java` | Клиентская регистрация |
| `src/client/java/dev/bekhilitt/client/` | UI, камера, модели NPC, анимация и звук; представления снимков сервера |
| `src/main/resources/data/bekhilitt/story/` | JSON собственного загрузчика сюжетных данных; это не встроенный формат Minecraft |
| `src/main/resources/assets/bekhilitt/` | Встроенный кастомный ресурспак: локализация, модели, текстуры, звук |
| `src/test/java/dev/bekhilitt/` | Проверки переходов, ссылок, миграций и повторного исполнения |

Регионы, лагерь, диалоги и персонажи сначала являются данными и небольшими классами в этих пакетах. Отдельные пустые подпроекты не нужны. Фактические имена классов и методов Minecraft уточнять по выбранным mappings на этапе реализации, не трактовать эту таблицу как готовый API.

## Предлагаемое дерево исходников

Ниже целевое дерево полной версии; фактически существующие пути перечислены выше. Назначение, публичные границы и запреты каждого пакета описаны в [архитектуре](ARCHITECTURE.md). Подпакеты создаются по необходимости в пределах одного проекта.

```text
src/
  main/
    java/dev/bekhilitt/
      BekhilittMod.java
      campaign/
        quests/
        dialogue/
        cinematics/
        progression/
        characters/
      combat/
        stats/
        effects/
        bosses/
      protocol/
      platform/
        characters/
        world/
        persistence/
        networking/
        control/
    resources/
      fabric.mod.json
      data/bekhilitt/story/
      assets/bekhilitt/
        lang/
        models/
        textures/
        sounds/
  client/java/dev/bekhilitt/
    BekhilittClient.java
    client/
      input/
      control/
      camera/
      ui/
      rendering/
      audio/
  test/java/dev/bekhilitt/
```

`campaign.characters` хранит сюжетные записи, а `platform.characters` управляет физическими NPC и AI. `campaign.cinematics` исполняет серверные шаги, `client.camera` управляет камерой, а `client.rendering` показывает позы. `protocol` содержит общие DTO; транспорт — в `platform.networking` и клиентских обработчиках. Это разделение предотвращает два владельца одной сцены или отношений.

Будущие `client.input`, `client.control`, `client.camera` и `platform.control` добавляются на стенде P0C; границы подробно заданы в архитектуре. Камерой единолично управляет CameraController, рендер и кат-сцены используют его интерфейс.

- `presentation/CombatHudLayout`: чистый расчёт компактного боевого HUD; `CombatHudLayoutTest` проверяет границы и нижний резерв.

## Лагерный этап 1.4 (основной цикл проверен)

- `combat/CampSparringAttempt` — отдельная эфемерная попытка обычного спарринга, реальные hit/guard/dodge и завершение без сюжетного исхода.
- `platform/camp/CampSparringRuntime` — собственный временный партнёр Epic Fight, локальная безопасность и очищение.
- `platform/camp/CampLifePlan`, `CampLifeRuntime` — ограниченные занятия и маршруты бойцов после завершённого рейда; подключение выполняется через владельца `FirstEpisode`.
- Клиентский `camp_training` и Arena state 4 — компактные счётчики и камера, без владения игровым результатом.
- ADR 012 (источник локального комплекта: `docs/decisions/012-post-raid-camp-life.md`; в эту публикацию не включён), приёмка (источник локального комплекта: `docs/QA_CAMP_LIFE.md`; в эту публикацию не включён), запуск (источник локального комплекта: `docs/INSTALL_CAMP_LIFE.md`; в эту публикацию не включён).
