# Подтверждение связи операции с планом

Firebase API v4 поддерживает `confirmedPlanLinkingV1`. `GET /api/v4/budgets/{budgetId}`
возвращает `scope` и `capabilities`. Клиент передаёт
`X-Fillin-Capabilities: confirmedPlanLinkingV1` при обмене.

В `POST /api/v4/budgets/{budgetId}/sync/commands` добавлены:

- `CREATE_OPERATION` с `planMatchingMode: CONFIRM_REQUIRED`: только факт и nullable
  `suggestedPlan` в receipt. Предложение не резервирует деньги и сохраняется для повторов.
- `SET_OPERATION_PLAN_LINK`: `entityId` операции, `expectedToken`, `planTarget`,
  `expectedPlanVersion`. Цель дохода: `type=INCOME`, `planId`, `dueOn`;
  цель расхода: `type=EXPENSE`, `ruleId`, `dueOn`, необязательный серверный `planId`.
- `REMOVE_OPERATION_PLAN_LINK`: `entityId`, `expectedToken`; отсутствие связи успешно.

Предложение содержит `target`, `categoryId`, `categoryName`, `plannedAmount`,
`remainingAmount`, `version`. Деньги передаются десятичными строками KZT.
Предпочтение: самый старый открытый срок; при равенстве UUID дохода либо ruleId расхода.
Проекции активных правил рассматриваются до текущей даты Asia/Almaty + 5 лет.

Новые SET/REMOVE меняют только версию операции и журнал. До активации сохранено
прежнее увеличение версий в legacy-сценариях оплаты. После активации изменение
производного исполнения сохраняет версию определения плана. Остаток рассчитывается из текущих
POSTED-проводок; возвраты уменьшают вклад расхода. Переплата не переносится.
Частичный доход остаётся в прогнозе в размере остатка; OWNER может менять его сумму
и срок. Для смены категории связанного плана/операции сначала нужны отвязки.
Явное закрытие расхода PAID сохраняет силу после возврата.

Ошибки: 409 `version_conflict`, `plan_changed`, `plan_unavailable`,
`scope_changed`, `idempotency_conflict`; 422 `invalid_plan_link`,
`unlink_before_category_change`; 426 `client_upgrade_required`.
Ошибка связи не отменяет ранее созданную операцию. Повторять нужно исходный requestId/payload.

Первое успешное новое создание или SET/REMOVE активирует контракт для всего бюджета.
После этого команды и sync без capability отклоняются. Метаданные бюджета доступны
для согласования возможностей. Принятые receipts воспроизводятся после проверки доступа,
до новых ограничений; старые fingerprints сохраняются.

События остаются `OCCURRENCE_PAYMENT`/`INCOME_MATCH` с ключом `id:ledgerId`.
Замена публикует tombstone прежней пары и новую пару; receipt revision покрывает всю
транзакцию. Клиент импортирует все страницы фиксированного среза перед снятием overlay.

## Выпуск и аудит

Сначала backend с V29, затем Android. До миграции на копии БД выполнить
[аудит](sql/audit-operation-plan-links.sql). Противоречия требуют явного исправления:
миграция повторяет проверки под блокировками и останавливается без удаления данных.
Оба старых хранилища заменены представлениями единого `operation_plan_links`;
отдельных независимо записываемых таблиц связей больше нет. UUID, старый журнал и receipts
сохраняются. Реестр не хранит денежный вклад.

Production-аудит, резервное копирование, выпуск и проверка Android на реальном сервере
выполняются отдельно. Обычные тесты используют синтетические данные и Testcontainers.
