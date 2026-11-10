# IdleArc Druid Overlay — плавающий виджет-подсказчик

Аналог твоего проекта для Яндекс Доставки, только для IdleArc.

**Что делает:**
- Плавающий bubble поверх игры (SYSTEM_ALERT_WINDOW) — перетаскивается, сворачивается
- Сканит экран: MediaProjection + кроп области + on-device OCR (ML Kit Text Recognition / Tesseract)
- Парсит текст статов/итемов/скиллов и по правилам для друида подсказывает: что надеть, что качать, приоритет
- Ничего не жмет в игре, не лезет в `idlearc.com/api`, не патчит память — только читает пиксели, как человек

**Почему так:**
IdleArc на Flutter — нативные View почти не отдает в AccessibilityService, поэтому OCR через скриншот надежнее. Для Flutter-дерева accessibility почти пустое.

**Архитектура:**
- `FloatingService` — Service + WindowManager bubble
- `ScreenCapture` — MediaProjection API (запрос разрешения 1 раз)
- `OcrEngine` — ML Kit (offline) кропит 3 зоны: инвентарь, статы, дерево скиллов
- `DruidRuleEngine` — твой `companion/data/druid_skills.json` + приоритеты, выдает топ-3 подсказки

**Разрешения:** `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`. Для MediaProjection — диалог системы.

**Сборка:** Android Studio Hedgehog+, Gradle 8.5, minSdk 24 (из APK 1.3.15) targetSdk 36. `gradlew assembleDebug` -> APK.
