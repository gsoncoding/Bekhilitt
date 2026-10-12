# Карта проекта

На 12 октября 2026 года добавлен технический боевой стенд 1.0.0. Полная кампания остаётся проектом.

## Реализованный боевой стенд

- `build.gradle`, `settings.gradle`, `gradle.properties`, wrapper — сборка Fabric, Java 21, отдельные main/client source sets.
- `src/main/java/dev/bekhilitt/BekhilittMod.java` — общая точка входа и серверные события.
- `src/main/java/dev/bekhilitt/combat/Duel.java` — чистые правила дуэли в тиках.
- `src/main/java/dev/bekhilitt/platform/PrototypeArena.java` — встроенный сервер, площадка, NPC, геометрия, постановка, очистка.
- `src/main/java/dev/bekhilitt/protocol/` — два payload намерений/состояния представления.
- `src/client/java/dev/bekhilitt/BekhilittClient.java` — переназначаемый ввод.
- `src/main/java/dev/bekhilitt/input/CombatInput.java` — тестируемые границы контекста и нажатий; `src/client/java/dev/bekhilitt/client/mixin/MinecraftInputMixin.java` — перехват ванильных эффектов только в дуэли.
- `src/main/resources/fabric.mod.json`, `assets/bekhilitt/lang/` — метаданные и локализация.
- `src/test/java/dev/bekhilitt/combat/DuelTest.java` — тесты правил.
- `src/test/java/dev/bekhilitt/input/CombatInputTest.java` — регрессии ввода; [LINUX_TESTING](LINUX_TESTING.md) — переносимый тестовый профиль.
- [COMBAT_PROTOTYPE](COMBAT_PROTOTYPE.md) — запуск, defaults, ограничения; [CHANGELOG](../CHANGELOG.md) — пользовательские итерации.
- [ADR 006](decisions/006-combat-prototype.md) — границы реализации стенда.


## Реальные документы и их ответственность

| Путь | Единственное основное место для |
|---|---|
| [README](../README.md) | Назначение и порядок чтения |
| [AGENTS](../AGENTS.md) | Правила восстановления контекста и работы |
| [CONSTITUTION](CONSTITUTION.md) | Требования пользователя, продуктовые гипотезы и открытые вопросы |
| [ARCHITECTURE](ARCHITECTURE.md) | Границы систем, зависимости, состояние, клиент и сервер |
| [STORY_DATA](STORY_DATA.md) | Контракт данных, валидация, восстановление и награды |
| [PROTOTYPE_PLAN](PROTOTYPE_PLAN.md) | Этапы, приёмочные сценарии и риски |
| [STATUS](STATUS.md) | Текущее положение и ближайшая задача |
| [ADR 001](decisions/001-stack.md) | Кандидат стека и технические источники |
| [ADR 002](decisions/002-state-and-scope.md) | Причины выбора единого состояния и малого среза |
| [ADR 003](decisions/003-chronology-and-controls.md) | Утверждённое начало, хронология и направление управления |
| [ADR 004](decisions/004-playable-character-boundary.md) | Разделение личности и управления, будущая привязка Player и NPC |
| [ADR 005](decisions/005-first-playable-episode.md) | Утверждённый первый эпизод: поединок с Гриффитом, вступление и границы обучения |
| [CHRONOLOGY](story/CHRONOLOGY.md) | Порядок событий и показа, эпизодные привязки и проверка канона |
| [CAMPAIGN](story/CAMPAIGN.md) | Предлагаемая структура истории |
| [CHARACTERS](story/CHARACTERS.md) | Художественные ориентиры персонажей |
| [GRIFFITH](story/GRIFFITH.md) | Личная линия второго главного персонажа, знания, отношения, занятия и переходы |
| [SCENES](story/SCENES.md) | Реестр сцен и их драматические задачи |
| [SOURCES](story/SOURCES.md) | Происхождение и проверка реплик |
| [BOSSES](story/BOSSES.md) | Кандидаты в боссы, формы, исходы, источники и конфликты охвата |

## Планируемые пути и точки входа

Ниже сохранён архитектурный план полной версии. Часть инфраструктурных путей уже реализована в стенде выше; campaign, сохранения и сюжетные данные ещё отсутствуют. Создавать их только вместе с нужной реализацией. Предлагаемый Java namespace `dev.bekhilitt` — внутреннее имя пакета, не утверждение о владении доменом.

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
