# Firebase T09 — семейный код бюджета

## Запуск

Нужен текущий JAR с Flyway V26. Существующие MOBILE_API_ENABLED=true,
FIREBASE_AUTH_ENABLED=true, FIREBASE_PROJECT_ID и ADC остаются прежними.
Новых секретов и переменных окружения нет. Firebase Admin credentials не должны
попадать в Android, git или логи. Развёртывание Railway выполняется отдельно.

V26 добавляет firebase_budget_invitation, не меняя household/member UUID,
начальные балансы, историю, firebase_budget_access и receipts V25.

## HTTP-контракт

Firebase ID token передаётся в Authorization: Bearer. Все ответы no-store.
UID из тела, email и старый статический токен не используются для доступа.

| Метод и путь | Ответ |
| --- | --- |
| POST /api/v4/budgets/{budgetId}/invitations | 200 {id,budgetId,expiresAt,code} |
| GET /api/v4/budgets/{budgetId}/invitations/current | 200 {budgetId,current:{id,budgetId,expiresAt}} или current:null |
| DELETE /api/v4/budgets/{budgetId}/invitations/{invitationId} | 204, включая повтор отзыва |

Код возвращается только из POST. В GET его нет; после потери ответа клиент должен
обновить статус, отозвать активное приглашение или создать новое. Повтор POST создаёт
новый код и отменяет предыдущий, а не воспроизводит утраченный plaintext.
Отзыв старого invitationId не отменяет более новое приглашение или приглашение другого бюджета.

Недействительный/отозванный Firebase ID token даёт 401. Каждый endpoint требует
активного OWNER по проверенному UID/budgetId; MEMBER и посторонний UID получают 403.
Admin проверяет токен до DB-транзакции. Внутри неё FirebaseBudgets удерживает shared lock
членства; создание и отзыв сериализуются блокировкой household. Роль клиента не даёт прав.

## Код и хранение

SecureRandom создаёт 20 символов алфавита Crockford Base32 (100 бит случайности),
сгруппированных XXXXX-XXXXX-XXXXX-XXXXX. SHA-256 от точной строки сохраняется в
code_hash; plaintext не хранится и не логируется. Код действует ровно 24 часа
от времени создания по Clock сервера. На границе expiresAt приглашение уже не активно.

Частичный уникальный индекс по household_id допускает максимум одно приглашение
с revoked_at IS NULL и accepted_at IS NULL. Новый POST в одной транзакции отзывает
прежний неиспользованный код и создаёт следующий. При ошибке вся замена откатывается.
Истёкшие строки сохраняются как история; следующий POST освобождает их место.
GET учитывает expires_at, revoked_at и accepted_at. Отзыв идемпотентен и ограничен
одновременно household_id и invitationId.

Принятие кода, привязка MEMBER и атомарное расходование приглашения реализованы в
[Firebase T10](firebase-family-budgets.md). V27 добавляет receipt accepted_by_uid /
accepted_member_id для повтора тем же активным членством.
Прежние Telegram-приглашения family_invitation независимы и не меняются.

## Проверка

```bash
./gradlew integrationTest --tests '*FirebaseBudgetInvitationTests' --tests '*FirebaseUserMigrationTests'
./gradlew test check bootJar
```

Тесты используют поддельный Firebase verifier и настоящий PostgreSQL 17.11 через
Testcontainers. Проверяются OWNER/MEMBER и чужой UID, статические/неверные токены,
создание/статус/отзыв HTTP, хеш без plaintext, 24 часа и граница срока, подмена бюджета,
конкурентное создание, откат замены и сохранность существующих данных V25→V26.
Реальные Google/Telegram/OpenAI не вызываются. Ручная приёмка Android требует
обновлённого тестового сервера. Production автоматически не развёртывался.


## Результаты 2026-10-23

Полный test/check/bootJar прошёл: 315 unit и 287 PostgreSQL/Testcontainers integration-тестов
без ошибок и пропусков. Пройдены ktlint, Detekt, JaCoCo и сборка JAR. Новые 6 тестов T09
покрывают HTTP, хеш/срок, OWNER/MEMBER, конкуренцию, откат и миграцию V25→V26.
Существующие migration-тесты учитывают V26 и сохраняют проверки UUID, денег и receipts.
Реальная приёмка Google и установка обновлённого сервера остаются отдельными действиями.
