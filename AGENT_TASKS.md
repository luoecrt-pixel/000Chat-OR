# AGENT_TASKS — задачи и планы 000Chat-OR (Integral LLM · OpenRouter)

Единственный рабочий план. Правила — [NEXT_AGENT_HANDOFF.md](NEXT_AGENT_HANDOFF.md), причины прежних решений — [PROJECT_HISTORY.md](PROJECT_HISTORY.md). Номера сохранены для связи с поручениями; выполненные части исключены из активных задач.

Область форка `000Chat-OR` — только OpenRouter/Claude. Задачи прямых API OpenAI, DeepSeek и других провайдеров ведутся в [исходном `000Chat`](https://github.com/luoecrt-pixel/000Chat) и исключены из этого плана по поручению владельца после разделения репозиториев. Это перенос области ответственности, а не отметка об их выполнении. Локальное чтение прежних сообщений OpenAI, metadata, usage, fingerprint v1, checkpoint, экспорт и доступ к сохранённым originals по старым file IDs остаются обязательными; сетевое исполнение OpenAI не возобновлять.

## Текущие задачи

### 33. [P1/P2] Замечания владельца и проверка присланных решений

- В `ChatDatabase.clearActiveResponse()` не менять `updated_at` и порядок чатов, если активного запроса уже нет. Проверить no-op и повторную очистку через SQLite, включая reopen; сохранить существующее поведение реального append/title/terminal и позднего runtime tail.
- Все темы и app-owned dialogs/buttons/cards используют согласованную контрастную палитру; scientific/rich поверхности отличаются осмысленными оттенками и мягкими рамками. Убрать обычные source toggles, сохранив точный source/copy и диагностический fallback/retry, reattach и sandbox.
- Причину исходного immutable-thinking 400 установить доказательствами. Не считать первую signature/data дельту закрытием блока; сохранять фрагменты, индексы, порядок и raw replay. Не удалять автоматически «битые» unsigned/unknown blocks без подтверждённого upstream контракта и явного безопасного сценария.
- Для web age не скрывать поле и не утверждать неподтверждённое происхождение. Исследовать provider dates/cache/URL eligibility/шум по реальным ответам; отличать tool evidence от заявлений модели. Инструкции используют captured enabled-tool flags, не обещают отключённый search, не обрезают originals.

Присланные full-file replacements проверять относительно актуального кода; не откатывать bounded saved state, renderer lifecycle и ownership ради старых verifier snapshots. Это связка с #5/#12/#16/#31/#32, без автоматического расширения transport/миграций.

### 12. [C · P1/P2, остаток] Claude route/cache и сохранение сброса signed reasoning

- Wire matrix `AUTO/ANTHROPIC × 1h/5m/off × tools/system/history`; согласовать top-level automatic cache с реально поддерживаемым маршрутом Auto, не смешивать несовместимые nested 5m/top-level 1h. Сохранить explicit breakpoints, историю и route/fallback policy.
- После `checkBinding()` сохранять в SQLite факт безопасного сброса несовместимых `reasoning_details`, чтобы warning не повторялся каждый turn; не менять оригинальную хронологию/подписи без причины. Тестировать model/key/tools/route/system mismatch и reopen.
- При signed-thinking/provider HTTP 400 предлагать совместимый маршрут/новую ветку без молчаливого удаления истории и платного автоповтора. Mock не доказывает live cache hits/цену.

### 13. [D · P1] PDF annotations и лимиты медиа OpenRouter

Сохранять серверные PDF `annotations[].file.hash/content` и citations в protocol tail в актуальном поддерживаемом wire-формате, с привязкой к исходнику, без потери originals и исполнения недоверенных данных. Mock двух turn: правильный parsed payload переиспользуется; отсутствующий/подменённый hash не позволяет взять чужой результат. Live отсутствие повторной оплаты отдельно не доказано.

В `OpenRouterAttachmentPreflight` считать изображения/PDF pages **по всей отправляемой истории**, с пределами модели/маршрута и явной ошибкой вместо усечения. Проверить границы и unreadable PDF, не вводить единый выдуманный лимит. Для native/auto/mistral-ocr/cloudflare-ai ясно сообщать о возможной цене OCR; не подменять engine незаметно.

### 14. [E · P1/P2, остаток] Capabilities и reasoning-only progress

Расширить `/api/v1/models` сверх `input_modalities`: `supported_parameters`, reasoning capabilities и `reasoning.mandatory`, если поле действительно есть в текущей схеме. Статический alias не доказывает возможности модели. Матрица: mandatory/optional reasoning, effort, допустимые sampling/forced tool параметры, отсутствие неподдерживаемого assistant prefill, missing/stale metadata. Ручной effort молча не снижать.

При `reasoning_details` без visible content показывать прогресс/ожидание tools без раскрытия hidden thinking и без превращения reasoning в assistant text. Проверить Stop, пустой visible ответ и existing `length/content_filter/error → incomplete`; эту обработку не реализовывать заново.

### 16. [D · P2, остаток] Web citations, engine и dev-only диагностика OpenRouter

Добавить хранение/отображение web_search/web_fetch annotations/citations (согласовать с #13), явный выбор поддерживаемого engine и совместимость route/tools/images.

`debug.echo_upstream_body` — только отдельный явно разрешённый dev-only режим на synthetic/контролируемых данных, off по умолчанию; production/обычная диагностика не получает сырые keys/messages/paths. Флаг не нужен основной пользовательской функции citations.

### 18. [D · P2, остаток #18.3] Актуальные тарифы и политика оценок

- Получать HTTPS цены OpenRouter `/models` и подходящего `/models/{author}/{slug}/endpoints`, хранить TTL/дату/статус оценки. Неизвестные tool/cache-write тарифы не выдумывать.
- Заменить захардкоженные оценки Activity/runtime на model/route-aware policy; тарифы разных моделей и маршрутов не смешивать. Оценка не заменяет подтверждённый расход, включая фактический ноль.

**Приёмка остатка:** cache read/write, tiers/tools и отсутствующие тарифы; stale/corrupt metadata, offline, 401/402/429, reopen. Новый preflight или ошибка получения цен не стирают подтверждённые предыдущие раунды и не превращают unknown в ноль. Live сверка тарифа/счёта остаётся в #9.

### 22. [D · P2, остаток] Bounded recovery resources

- Ограничить cached thread pool `OpenRouterResponseRuntime`, с явной очередью/отменой, без потери terminal/usage и отмены другого чата.

После потери OpenRouter SSE сохранять partial/checkpoint/known-or-unknown cost; новый inference-запрос не считать возобновлением той же генерации и не запускать скрыто второй платный запрос. Статусы «генерация / связь потеряна / получение результата / неполный / готово» соответствуют реальности. Existing Stop/Delete/Edit/notification fixes не переделывать без причины.

### 5. [G · P2, остаток] Длинные научные ответы и визуальные критерии

На Claude/OpenRouter fixtures довести/проверить компактные typography/copy actions, таблицы со всеми rows/columns и признаком горизонтального scroll, реальные structured tool cards и file card/Save. Auto-preview и адаптивные колонки не доказывают весь дизайн. Tests: narrow screen/font scale, 32K boundary, invalid/incomplete/large source, reopen, sandbox. Физическая visual acceptance — #9.

На физическом телефоне принять плавность открытия/закрытия шторки и отсутствие сдвига viewport при первом touch selection (#9).

### 31. [M · P2] Остальные диалоги и видимый бренд

1. **Темы, остаток #6.** Переименование/создание/удаление чата, diagnostics, local files/errors, GitHub confirmation, encoding, app-owned attachment/export dialogs и другие окна приложения. Системные Photo Picker/SAF оформляет Android, а не helper приложения. Оформлять custom content **и** window/title/list/action buttons/spinner/dropdown, selected/disabled states. Один `applyDialogControlTheme(box)` не покрывает весь dialog chrome. Проверять фактические foreground/background и контраст в dark/light/gray + font scale; отсутствие WHITE текста недостаточно. Theme switch сохраняет draft/attachments/anchor/request.
2. **Бренд, остаток #25.** Убрать видимые `Astra diagnostics`, `Astra Files` и временную подпись `Astra` у активной карточки ответа до появления объекта сообщения. Проверить header во время генерации и после terminal. Не переименовывать legacy model IDs, классы/package/БД/старые файлы/compatibility IDs.

### 32. [N · P2, остаток] Файл результата инструмента в экспорте OpenRouter

Сохранять безопасную связь конкретного вызова OpenRouter с именем созданного файла и экспортировать `tool → status → result file` в `.md` и `.txt`. Сейчас `ToolTraceSummary` не сохраняет результат, а отдельный список вложений не связывает файл с вызовом. Не выводить raw arguments/output, paths, hidden reasoning или секреты; не выдумывать неизвестный результат.

Проверить несколько вызовов/файлов, failed/cancelled/unknown results и реальное сохранение обоих форматов через UI OpenRouter/Claude. Сохранить raw source, порядок сообщений и экспорт существующей legacy-связи инструментов OpenAI по `call_id`.

### 9. [H-Release, по отдельному поручению] Приёмка APK и live provider

Предоставить APK с точным Build SHA/успешным соответствующим CI. На физическом телефоне принять themes/dialogs, math/Mermaid/SVG/source, cards/tables, drawer/gestures/Back/IME, reading anchor/двухступенчатый scroll, multi-photo, tools Claude, attachments/cache, Stop/recovery/notifications. Для точного визуального сравнения нужны original references; их нет в репозитории.

Отдельно проверить live OpenRouter/Claude multi-turn, provider route/cache и фактический usage/cost в согласованных сценариях. Фиксировать устройство/Android/Build SHA, действия/результат/negative case. Mock/эмулятор/CI не заменяют live/device evidence; невыполненное — not verified.

### 10. [H-Release, по отдельному поручению] Production signing и публикация

Определить канал и signing identity обновления существующей установки. Подготовить согласованный подписанный package, проверить version/signature и фактическую ссылку публикации. CI artifact/unsigned Release не равен production release; secrets не включать в repo/logs.

### 26. [E · P2/P3, отложено] Claude Messages через OpenRouter

Возвращаться при доказанной потребности в Messages-only возможностях. При возобновлении: существующий OpenRouter key/баланс, provider Anthropic default/Auto явный; transport на уровне чата, старую signed историю не конвертировать молча. Прямой Anthropic API/отдельная оплата не входят в требование. Не обещать OpenAI-подобный resume той же генерации.

### 27. [E · P2/P3, отложено с #26] Матрица native tools Claude

До подключения проверить модель × Messages × provider × capability flag, отличие native/server/local tools и цену. `openrouter:image_generation` исключён владельцем, не внедрять как fallback.

## Планы на потом

Идеи не являются scope следующего PR. Проверять существующий код и доступность API выбранному аккаунту/transport. Номера сохранены для связи с прежними пожеланиями.

### 1. [P2] Версии ответа и обратимость
Альтернативы regenerate/edit и возврат к branch point; сначала pure snapshot/branch contract + DB test.

### 3. [P2] Поиск по истории
Оценить SQLite FTS5, индексацию/удаление и переход к точному сообщению.

### 4. [P2] Повторное прикрепление вложений
UX повторного выбора сохранённых локальных originals и восстановления привязки вложения; original не подменяется производной копией.

### 5. [P2] Per-chat configuration profile
Snapshot model/tools/settings в SQLite, без незаметной смены условий при переключении чатов.

### 6. [P2] Продолжить/починить ответ
Pure policy состояний/действий: продолжить, повторить, открыть checkpoint; платный новый request явный.

### 7. [P2] Fork чата
Local snapshot до сообщения с новым chatId, сохранностью originals/shared references.

### 8. [P2] Настройки генерации по capabilities
ModelCapabilities/GenerationSettings для OpenRouter builder с учётом модели и маршрута; temperature отдельно от reasoning effort.

### 11. [P2] Приватность и бюджет OpenRouter
Opt-in data_collection=deny/ZDR, бюджет/отказ, resolved model. Capabilities/цены — #14/#18.

### 13. [P3] Android 15/Xiaomi и долгие запросы
По поручению на устройстве: notification visibility, FGS budget, process death/reboot, история/drafts.

### 14. [P2, зависит от #26] Messages-only возможности Claude
Citations/context management/per-turn effort/deferred tools после подтверждения поддержки; без скрытого изменения истории.

### 15. [P3] Дополнительные server tools OpenRouter
Datetime/advisor/subagent/tool_search после проверки доступности и явного выбора платных функций. Image generation исключён.

### 16. [P3] Управление маршрутами OpenRouter
Дополнительные routing/privacy options по выбору.

### 17. [P2, направление одобрено] Долговременная память
Неизменяемая хронология + индекс выбранных фактов/заметок с первоисточником + durable stages агентной работы, без тихой обрезки истории.

### 22. [P2, Android-only] Jetpack Compose
Выборочно ComposeView после стабилизации: settings/themes/cards, затем лента по прототипу. Без зависимости от iOS/KMP.

### 23. [P3, отложено] KMP-ready ядро и опциональный iOS
Постепенно чистая бизнес-логика/типизированные модели/repository/view-model без Android; commonMain/iOS — отдельные решения после стабилизации.

## Карта визуальных референсов

Оригиналов в репозитории нет. Имена — карта наблюдений, не доказательство принятого UI.

| Файлы | Критерий |
| --- | --- |
| 1000269189.jpg, 1000269190.jpg | Широкая таблица в двух позициях, все колонки доступны |
| 1000269191.jpg, 1000269192.jpg | Code/nested fences, copy actions, LaTeX и $45.50, checklist/blockquote |
| 1000269193.jpg, 1000269194.jpg | Mermaid/HTML/SVG source+preview, реальные file/tool cards |
| 1000269200.jpg | Typography Markdown/code/table |
| 1000269202.jpg, 1000269204.jpg | Формулы/диаграммы/безопасный preview, статусы/actions tools/files |
| 1000269206.jpg | Gray: фон #CED8DA, шапка #8199A5 |
