# Управление Firebase-бюджетом

PATCH /api/v4/budgets/{budgetId} принимает {requestId: UUID, name: string} и возвращает
FirebaseBudgetSummary. DELETE /api/v4/budgets/{budgetId}?requestId=UUID возвращает 204.
Оба пути требуют проверенный Firebase ID token и OWNER; ответы запрещают HTTP-кэширование.
Название: trim, 1–80 символов, без управляющих символов. Нарушения дают 400; чужое или
отозванное членство — 403; изменённый payload с прежним requestId — 409 idempotency_conflict.

V31 добавляет независимые от household таблицы подтверждений и минимальные tombstones.
Удаление использует erase_firebase_budget, сохраняя аккаунты и другие бюджеты. UID shared
advisory lock предшествует exclusive budget lock и проверке членства. Финансовые запросы
удерживают shared budget lock до commit; импорт также входит в эту границу.

Повтор DELETE после потери ответа подтверждается сохранённым receipt без существующего
household. Повтор PATCH не записывает старое название поверх нового; возвращается текущий
каталог. Последнее принятое изменение определяет название. Tombstones блокируют повтор
первоначального создания и импорта удалённого UUID. Хранятся хеш UID и технические UUID,
без имени, токенов и финансовых данных.

Android хранит свою очередь отдельно от финансовой базы и получает изменения других
устройств через актуальный каталог. Выпуск: backend с V31, затем Android. Production в
рамках разработки не меняется. Проверки: ./gradlew check bootJar с PostgreSQL/Testcontainers.

Проверено 2026-11-03: ./gradlew check bootJar успешно; 322 unit-теста и 262
интеграционных теста с PostgreSQL/Testcontainers, включая конкурентные повторы удаления.
Production не развёртывался; реальная проверка Android с Google-входом выполняется отдельно.
