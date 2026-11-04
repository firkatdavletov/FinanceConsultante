# Полное редактирование правил — сервер

Рабочий контракт — Firebase `POST /api/v4/budgets/{budgetId}/sync/commands`
и `GET /api/v4/budgets/{budgetId}/sync/changes`. Нужны активный OWNER,
совпадающие householdId/memberId и Firebase ID token. Миграция V33 сохраняет
существующие правила, ожидаемые доходы, экземпляры, UUID и связи с проводками.
Статические HTTP v1–v3 остаются закрытыми.

## Расходы

`CREATE_RULE` принимает необязательное `ruleName`: после trim от 1 до 160 символов.
`RECURRING_RULE.record.ruleName` возвращает сохранённое название. Старые записи
и очищенное название имеют null; Android отображает название категории.

`UPDATE_RULE` с `replaceDetails: true` полностью заменяет categoryId, amount,
frequency, scheduleValue, startsOn и ruleName. Пропущенное/null название очищает
его. Остальные поля определения обязательны, кроме scheduleValue для ONCE.

```json
{
  "requestId": "request-uuid",
  "householdId": "budget-uuid",
  "memberId": "owner-member-uuid",
  "type": "UPDATE_RULE",
  "entityId": "rule-uuid",
  "authoredOn": "2026-11-04",
  "replaceDetails": true,
  "categoryId": "expense-category-uuid",
  "amount": "100.00",
  "frequency": "MONTHLY",
  "scheduleValue": 31,
  "startsOn": "2026-11-04",
  "ruleName": "Пример платежа"
}
```

UUID в примерах условные. Категория должна быть активной категорией расходов
этого бюджета. ONCE требует отсутствующий/null scheduleValue; MONTHLY — 1–31,
WEEKLY — ISO 1–7. Месячный день ограничивается последним днём месяца.
Новая дата начала не раньше authoredOn и начала учёта. Прежний startsOn может
остаться в прошлом, если он не изменён. Новое правило v4 не начинается раньше authoredOn.

Без replaceDetails или при false/null сохраняется прежняя правка только суммы:
categoryId, frequency, scheduleValue, startsOn и ruleName полного снимка не меняют
определение. Переданное ruleName всё равно должно быть допустимым.
Fingerprint прежнего запроса без новых полей не меняется.

## Доходы

Контракт сверён с локальным проектом Android Saldomi. Поля `incomeName`,
`incomeFrequency`, `incomeActive` принимаются командами CREATE/UPDATE_INCOME
для отдельного ONCE и CREATE/UPDATE_INCOME_RULE для правила.
Название нормализуется trim и ограничено 160 символами. Пропущенная метаинформация
сохраняется при правке; для нового правила используются название категории,
ONCE и active=true. Категория должна быть активной категорией доходов бюджета.

```json
{
  "requestId": "request-uuid",
  "householdId": "budget-uuid",
  "memberId": "owner-member-uuid",
  "type": "UPDATE_INCOME_RULE",
  "entityId": "income-rule-or-legacy-income-uuid",
  "authoredOn": "2026-11-04",
  "categoryId": "income-category-uuid",
  "amount": "100.00",
  "dueOn": "2026-11-30",
  "incomeName": "Пример дохода",
  "incomeFrequency": "MONTHLY",
  "incomeActive": true
}
```

Расписание начинается с dueOn: ONCE, раз в неделю WEEKLY, раз в две недели BIWEEKLY
либо MONTHLY с исходным числом месяца, без сдвига якоря после короткого февраля.
Новая dueOn ограничена authoredOn и началом учёта; неизменённая прежняя дата допустима.
DELETE_INCOME_RULE деактивирует правило и отменяет будущие несвязанные экземпляры.

Срез содержит `INCOME_RULE` с categoryId, amount, dueOn, incomeName,
incomeFrequency, incomeActive, status и version. Конкретные платежи остаются
`EXPECTED_INCOME`, дополнительно содержат ruleId, scheduledOn и метаинформацию
своего определения. Отдельный доход без правила сохраняет null ruleId/scheduledOn.
`INCOME_MATCH` и ledger UUID не меняются.

UPDATE_INCOME_RULE выделяет из прежнего отдельного EXPECTED_INCOME правило с тем же
ID, даже для ONCE. Исходный экземпляр получает ruleId и scheduledOn, сохраняя ID,
финансовое определение и связи; допустимые будущие неоплаченные экземпляры
затем обновляются по общим правилам. Это же действует при переходе ONCE → регулярный
доход и обратно. Прямой UPDATE_INCOME сохраняет прежнюю семантику правки отдельного
экземпляра; редактор правила должен выбирать UPDATE_INCOME_RULE.

## Даты, сохранность и повторная доставка

authoredOn — местная дата Asia/Almaty между началом учёта и текущим днём.
Полная правка обновляет только несвязанные SCHEDULED экземпляры с этой даты:
совпадение даты сохраняет ID, исчезнувшая дата получает CANCELLED, новая создаётся.
Сохранённая граница генерации запрещает создание новых дат до authoredOn при
чтении и работе планировщика. Прошлые экземпляры, любые связи с проводками,
частичные/полные оплаты, PAID и ручная отмена сохраняются.

Отмена из-за смены расписания отмечается отдельно от ручной отмены. При возвращении
такой будущей неоплаченной даты восстанавливается тот же ID. Уникальность расхода —
(rule_id,due_on), дохода — (rule_id,scheduled_on). Повторная материализация использует
ON CONFLICT; [семантика PostgreSQL 17](https://www.postgresql.org/docs/17/sql-insert.html).

Правка, журнал среза и receipt фиксируются одной транзакцией под блокировкой счёта.
Генератор читает определения под FOR SHARE SKIP LOCKED и пропускает изменяемые правила
до следующего вызова; изменение определения ждёт уже начатую генерацию. Повтор requestId возвращает
сохранённый receipt; иной payload с тем же ключом отклоняется. План не создаёт проводок
и не увеличивает баланс. Подтверждённая связь дохода поддерживает planId либо ruleId/dueOn,
включая материализацию будущего периода в существующем горизонте пяти лет.

## Проверки и граница облачного выпуска

Автотесты проверяют Firebase HTTP → PostgreSQL → sync JSON, legacy-правку суммы,
валидацию и откат ошибочной правки, историю/связи, совпадающие и исчезнувшие даты,
переходы ONCE/регулярного дохода, отключение/включение, восстановление системно отменённых
дат, месячные границы, конкурентный replay и миграцию V32 → V33.

`GET /api/v4/budgets/{budgetId}` объявляет серверную поддержку редактора доходов:

```json
{"scope":{"householdId":"…","memberId":"…","role":"OWNER","accountingStartedAt":"…","timeZone":"Asia/Almaty","currency":"KZT"},"capabilities":["confirmedPlanLinkingV1","incomeRuleEditingV1"]}
```

Этот список не зависит от возможностей клиента. Android передаёт
`X-Fillin-Capabilities: confirmedPlanLinkingV1,incomeRuleEditingV1,dailyLimitPlanningV1`
в запросах бюджета, changes и commands; сервер принимает такой заголовок,
но не копирует его в ответ. Редактор также работает с заголовком
`confirmedPlanLinkingV1,incomeRuleEditingV1`, без dailyLimitPlanningV1.

`incomeRuleEditingV1` покрывает incomeName/incomeFrequency/incomeActive,
CREATE/UPDATE/DELETE_INCOME_RULE и их round-trip через sync changes. Прежние команды
CREATE/UPDATE_INCOME без новой метаинформации не требуют этой capability.
Связи доходов, в том числе ruleId/scheduledOn, требуют `confirmedPlanLinkingV1`.
`CONFIRM_REMAINDER_INCOME` и поля remainderAmount/remainderDueOn/remainderReceived
не реализованы. Сервер не объявляет `dailyLimitPlanningV1`; наличие редактора
не разрешает клиенту отправлять остатки. HTTP v4 сам по себе не доказывает поддержку редактора.

По согласованному контракту Android использует один ответ чтения бюджета за обмен,
проверяет его перед подготовкой отправки и запрашивает заново при следующем обмене.
Отсутствие нужной capability даёт backend_update_required с сохранением requestId,
intent и уже зафиксированного wirePayload. Полная dailyLimitPlanningV1 также допускает
редактирование доходов для совместимости; сервер пока реализует отдельную incomeRuleEditingV1.

Интеграционные проверки покрывают объявление возможностей с заголовком и без него,
метаданные отдельного дохода, создание/правку/удаление правила через Firebase HTTP,
round-trip с обоими заголовками, replay и совместимость прежних команд.

Проверка реальным Android, изменение Android-кода и развёртывание не выполнялись.

Проверка согласования с Android 2026-11-04: `./gradlew check bootJar`
успешна — 292 unit- и 198 интеграционных тестов без ошибок и пропусков,
PostgreSQL/Testcontainers, ktlint, Detekt, JaCoCo и сборка JAR. Архив JAR не содержит
классов Telegram и удалённых delivery/recognition workers.
