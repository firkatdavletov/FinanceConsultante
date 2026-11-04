# FinanceConsultante

Firebase-вход мобильного клиента (T03), отдельный тестовый сервер и контракт профиля:
[Firebase Authentication](docs/firebase-authentication.md).
Регистрация, подтверждение email, вход по паролю и привязка способов входа для Android:
[Email/Password API](docs/firebase-email-password.md).
Подключение прежнего бюджета (T05), создание Firebase-бюджета (T06) и импорт локального (T07):
[Firebase budgets](docs/firebase-budgets.md).
Создание, копирование и отзыв семейного кода (Firebase T09):
[Firebase invitations](docs/firebase-budget-invitations.md).
Вступление MEMBER, список участников и отзыв доступа (Firebase T10):
[Firebase family budgets](docs/firebase-family-budgets.md).

Сервер личного и семейного бюджета для Android Saldomi на Kotlin / Spring Boot.
Основной интерфейс — Firebase HTTP API v4; Android работает локально и подключает
облачную синхронизацию явно. Деньги и прогнозы рассчитываются Kotlin-кодом,
PostgreSQL хранит проводки, аудит, правила, экземпляры, цели и членство.
Основная валюта — KZT, часовой пояс — Asia/Almaty; прежние RUB-записи сохраняются.

Telegram-интеграция удалена: poller, HTTP-клиент, обработчики, диалоги и рассылки
не входят в приложение. Старые миграции и идентификаторы сохранены для существующих
бюджетов. Переменные TELEGRAM_* и NOTIFICATIONS_ENABLED больше не включают фоновые процессы.

## Требования и версии

| Компонент | Версия |
| --- | --- |
| JDK / целевой bytecode | 17 |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.1 |
| Gradle Wrapper | 9.5.1, с проверкой SHA-256 |
| ktlint Gradle plugin / ktlint | 14.2.0 / 1.8.0 |
| Detekt | 2.0.0-alpha.3 |
| JaCoCo | 0.8.15 |
| PostgreSQL / Compose и Testcontainers image | 17.11 / `postgres:17.11-alpine` |
| Flyway | 12.4.0 |
| PostgreSQL JDBC / HikariCP | 42.7.13 / 7.0.2 |
| OpenAI Java SDK | 4.61.0 |
| Testcontainers | 2.0.5 |
| WireMock | 3.13.2 standalone, только тесты |

Используйте установленный JDK 17 и Gradle Wrapper; отдельно устанавливать Gradle не нужно. Первый запуск скачивает Gradle и зависимости, поэтому требует доступа к Maven Central, Gradle Plugin Portal и серверам дистрибутивов Gradle. Сборка не задаёт локальные абсолютные пути JDK и не загружает JDK автоматически.

Detekt закреплён на alpha.3, поскольку он собран с Kotlin 2.3.21, а стабильный 1.23.8 — с Kotlin 2.0.21. Это осознанная зависимость инструмента разработки от предварительного релиза; библиотека не входит в JAR приложения. Совместимость подтверждается запуском проверок. При обновлении Kotlin нужно повторно сверить [таблицу Detekt](https://detekt.dev/docs/introduction/compatibility/).

Flyway, JDBC, HikariCP и Testcontainers закреплены BOM Spring Boot 4.1.1. Подключены `spring-boot-starter-jdbc`, `spring-boot-starter-flyway` и модуль `flyway-database-postgresql` согласно [документации Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html). Автоматическая инициализация SQL отключена; production-схема меняется только Flyway-миграциями, `clean` запрещён, checksum проверяется при запуске.

Для обычного `integrationTest` / `check` нужен работающий Docker. Testcontainers запускает отдельный PostgreSQL 17.11 без подключения к пользовательской БД и без пропуска тестов при отсутствии Docker. Для Compose требуется команда `docker compose` (v2 или новее). В CI доступность Docker проверяется явно.

Если Docker Desktop установлен на macOS с CLI в пользовательском каталоге, а текущий терминал пишет `docker: command not found`, откройте новое окно терминала либо выполните `export PATH="$HOME/.docker/bin:$PATH"`. Проверьте `docker version`: нужны секции Client и Server. Testcontainers автоматически обнаруживает сокет Docker Desktop в `~/.docker/run/docker.sock`.

## Запуск

Из корня проекта сначала подготовьте `.env` из `.env.example`, замените пример локального пароля и загрузите переменные в окружение (см. ниже), затем:

```bash
java -version
./gradlew --version
docker compose up -d --wait postgres
./gradlew bootRun
```

На macOS при нескольких JDK можно выбрать установленный JDK 17:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
```

На Windows используйте `gradlew.bat` вместо `./gradlew` и задайте `JAVA_HOME` на JDK 17.

Для запуска собранного приложения:

```bash
./gradlew bootJar
java -jar build/libs/FinanceConsultante-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

БД доступна только на loopback-порту `${POSTGRES_PORT:-5432}`; данные сохраняются в именованном томе `postgres-data`. `docker compose stop postgres` останавливает БД, `docker compose up -d --wait postgres` запускает её снова с теми же данными. Если порт 5432 занят, задайте свободный `POSTGRES_PORT` в `.env`.

Миграции находятся в `src/main/resources/db/migration`. Приложение само применяет их при запуске. Начальная схема не содержит финансовых данных владельца; бюджет и начальный остаток создаются через Firebase API v4. Не меняйте уже применённый SQL-файл — добавляйте следующую версию.

## Конфигурация

| Профиль | Назначение |
| --- | --- |
| `local` | Профиль по умолчанию; datasource использует `POSTGRES_*` из окружения либо явные `DATABASE_*` |
| `test` | Загрузчик курса, mobile API и Firebase выключены; datasource тестовый и задаётся интеграционным набором |
| `prod` | Обязательны `DATABASE_URL` в формате JDBC, `DATABASE_USERNAME`, `DATABASE_PASSWORD`; интеграции по умолчанию выключены |

Рабочий вход использует Firebase ID tokens. Telegram и отправка уведомлений удалены.
OpenAI-провайдер и синтетический корпус сохранены для отдельно выбранной оценки;
очередь распознавания Telegram и её фоновые worker удалены. Ключ не нужен для обычного запуска.

Загрузчик курса выключен по умолчанию. Для локального запуска задайте ключ только в окружении и включите worker:

```bash
export CURRENCYLAYER_ACCESS_KEY='<локальный ключ>'
export EXCHANGE_RATES_ENABLED=true
./gradlew bootRun
```

Адаптер вызывает документированный Currencylayer endpoint [`/live`](https://currencylayer.com/documentation) только с валютами RUB и KZT. Ответ имеет базу USD, поэтому приложение детерминированно вычисляет количество KZT за 1 RUB как `USDKZT / USDRUB`, округляя снимок до 8 знаков через `HALF_UP`. В БД сохраняются локальная дата `Asia/Almaty`, код источника `CURRENCYLAYER_LIVE_USD_CROSS`, provider timestamp `quoted_at` и время получения `fetched_at`; access key, URL запроса и тело ответа не логируются.

Задание на каждый календарный день, включая выходные, становится доступным в 07:00 Asia/Almaty. Worker проверяет очередь каждую минуту, захватывает запись с lease, выполняет HTTP вне транзакции и фиксирует курс в отдельной короткой транзакции. Timeout, 5xx, ошибка провайдера, устаревший или некорректный ответ оставляют задание в `RETRY`; задержка растёт от 5 минут до 6 часов. После рестарта создаются пропущенные даты, а незавершённые и просроченные lease продолжаются. Повторная фиксация не меняет существующий снимок.

Выбран именно live endpoint. Поэтому для пропущенного дня после простоя или явно запрошенной прошлой даты сохраняется первый успешно полученный после восстановления live-снимок, а не ретроспективная котировка того дня. Это видно по `quoted_at`/`fetched_at` и коду источника. RUB-операция ждёт снимок своей локальной даты; произвольный старый курс к ней не подставляется.

`.env.example` содержит безопасный шаблон переменных. Spring Boot **не читает `.env` автоматически**. Укажите значения в окружении процесса или настройках Run Configuration в IDE. Для своего проверенного локального `.env` в bash/zsh:

```bash
cp .env.example .env
set -a
. ./.env
set +a
docker compose up -d --wait postgres
./gradlew bootRun
```

Не сохраняйте реальные ключи в `application*.yaml`, Gradle, CI или тестовых фикстурах. Файлы `.env` и `.env.*` исключены из Git, кроме `.env.example`. Неиспользуемые интеграции оставляйте выключенными, а их credentials — пустыми.

## Мобильный API

Действующий мобильный API использует Firebase Authentication: Google или Email/Password
с подтверждённым email. Включение:

```bash
export MOBILE_API_ENABLED=true
export FIREBASE_AUTH_ENABLED=true
export FIREBASE_PROJECT_ID='<Firebase project ID>'
export GOOGLE_APPLICATION_CREDENTIALS='<absolute path to the service account JSON>'
```

JSON-ключ находится вне репозитория и Android. Сервер проверяет Firebase ID token;
собственный JWT не выдаёт. Каждый запрос бюджета адресуется через budgetId, а роль
определяется по проверенному UID и активному членству.

- POST /api/v4/auth/firebase — серверный профиль текущего пользователя.
- GET /api/v4/budgets — доступные бюджеты; POST — создание с requestId.
- POST /api/v4/budgets/import — перенос локального бюджета с исходными UUID.
- GET /api/v4/budgets/{budgetId} и GET …/sync/changes — бюджет и снимок/изменения.
- POST …/sync/commands — команды v4 с неизменным requestId/payload и receipt.
- POST …/invitations, GET …/invitations/current и DELETE …/invitations/{id} — код OWNER.
- POST /api/v4/budgets/invitations/accept — вступление MEMBER.
- GET …/members и DELETE …/members/{memberId} — участники и отзыв доступа OWNER.

С Firebase T12 статический мобильный токен отключён. Прежние v1–v3 и unscoped v4
не принимают credentials; оставленная переменная MOBILE_API_TOKEN игнорируется.
История, UUID, прежние binding rows и финансовые receipts сохраняются. Существующие
v4-команды отправляются через Firebase без преобразования requestId или payload.

[Настройка, миграция и выпуск T12](docs/firebase-static-token-retirement.md),
[Firebase budgets](docs/firebase-budgets.md),
[семейный доступ](docs/firebase-family-budgets.md).

## Финансовые сценарии

- Фактические доходы, расходы, исправления и возвраты записываются транзакционно с аудитом.
- Плановые расходы и доходы не изменяют баланс до фактической проводки.
- Правила расходов поддерживают ONCE, WEEKLY и MONTHLY; доходов — также BIWEEKLY.
- Связи планов с операциями используют confirmedPlanLinkingV1 и сохраняют финансовую историю.
- Цели резервируют имеющиеся средства; Kotlin рассчитывает дневной лимит и прогноз.

Контракты: [полное редактирование правил](docs/full-rule-editing.md),
[одноразовые расходы](docs/one-time-expense-rules.md),
[связи с планами](docs/mobile-operation-plan-links.md),
[категории](docs/category-icons.md), [управление бюджетом](docs/budget-management.md),
[удаление профиля](docs/firebase-profile-deletion.md).

GET `/api/v4/budgets/{budgetId}` объявляет `incomeRuleEditingV1` для редактора доходов
и `confirmedPlanLinkingV1` для связей. Android может передавать также `dailyLimitPlanningV1`
в заголовке запроса; сервер не объявляет её, поскольку подтверждение остатка дохода
и поля remainder* не реализованы. Подробности — в контракте редактирования.

## Отдельная оценка распознавания

SDK OpenAI, validator и тестовый корпус доступны через `./gradlew openAiModelEvaluation`.
Живой платный прогон выполняется только при явном выборе и соответствующих переменных
из тестового evaluation harness. Он не входит в test/check, не запускает фоновых worker
и не связывается с production-бюджетом.

## Проверки

Основная команда, совпадающая с CI:

```bash
./gradlew --no-daemon clean check bootJar
```

При первой проверке нового Docker-окружения добавьте `--no-build-cache`: `./gradlew --no-daemon --no-build-cache clean check bootJar`. Это обеспечивает фактический запуск тестов после `clean`, а не восстановление сохранённых результатов.

| Команда | Назначение |
| --- | --- |
| `./gradlew test` | Unit-тесты денег, parser, OpenAI-контракта, конвертации, бюджета, прогноза, целей, recurring-детектора; внешние API заменены WireMock |
| `./gradlew integrationTest` | PostgreSQL/Testcontainers: миграции, JDBC, аудит, очереди, цели, прогноз, recurring-анализ, атомарность, конкуренция и Spring wiring |
| `./gradlew ktlintCheck` | Форматирование Kotlin, Gradle Kotlin DSL и всех Kotlin source sets |
| `./gradlew ktlintFormat` | Применение форматирования; после него снова запустить `check` |
| `./gradlew detekt` | Статический анализ production-, unit- и integration-кода |
| `./gradlew jacocoTestReport` | Тесты и общий HTML/XML-отчёт покрытия по двум наборам |
| `./gradlew check` | Все тесты, ktlint, Detekt и JaCoCo |
| `./gradlew bootJar` | Исполняемый Spring Boot JAR; сам по себе не запускает все проверки |

Gradle задаёт профиль test и выключает внешние Firebase/mobile/курсовые адаптеры
через system properties. Тесты отдельных HTTP endpoints включают только нужные флаги,
Firebase verifier заменяется заглушкой. Внешние OpenAI/Currencylayer HTTP-контракты
проверяются локальным WireMock; mobile API — MockMvc и PostgreSQL/Testcontainers.

Для диагностики без Docker можно **явно** выбрать отдельный локальный PostgreSQL 17: `./gradlew check bootJar -PtestPostgresUrl=jdbc:postgresql://127.0.0.1:55432/financeconsultante_test`. Заранее создайте изолированный тестовый кластер с ролью `test_owner`, паролем `synthetic-test-password` и пустой БД `financeconsultante_test`. Допускается только такой localhost URL с явным портом. Тесты применяют миграции и меняют данные/схему этой БД. Это дополнительный режим проверки SQL; он не подтверждает работу Compose/Testcontainers. Без параметра запускается только Testcontainers, без автоматического fallback. CI использует обычный режим Docker.

Отчёты: [integrationTest](build/reports/tests/integrationTest/index.html), [ktlint](build/reports/ktlint/), [Detekt](build/reports/detekt/detekt.html), [JaCoCo](build/reports/jacoco/test/html/index.html). Они появляются после соответствующих задач и не сохраняются в Git. Произвольный порог покрытия не задан: сначала появляются реальные доменные сценарии, затем оцениваются пробелы.

## CI

[GitHub Actions workflow](.github/workflows/ci.yml) запускается для push в `main`, pull request и вручную:
устанавливает Java 17, использует Wrapper, проверяет Docker, выполняет `clean check bootJar`
и собирает deployment image из корневого `Dockerfile`. Загрузка отчётов и JAR в GitHub Artifacts отключена. Production-секреты не требуются. Публикация и deploy
самим workflow не выполняются; Railway может ждать его успеха через Wait for CI.

Git remote `origin` указывает на GitHub. Успешная локальная сборка не является наблюдавшимся запуском GitHub
Actions или Railway deployment.

## Развёртывание на Railway (T11)

Проект подготовлен к сборке Railway из GitHub: приложение запускается в non-root контейнере на точном Java 17
runtime, слушает предоставленный `PORT`, имеет DB-aware readiness, graceful shutdown и приватные Prometheus
метрики очередей. Отдельный GitHub-root `ops/backup` создаёт зашифрованные `age` logical dumps и удаляет объекты
старше 14 дней.

Полная последовательность создания app/PostgreSQL, reference variables, секретов, Wait for CI,
backup, smoke, restore drill, RPO/RTO и cost limits описана в
[`docs/RAILWAY_DEPLOYMENT.md`](docs/RAILWAY_DEPLOYMENT.md). Подготовленные файлы не означают, что Railway-услуги
уже подключены или T11 завершена.

## Документация проекта

- [CONTEXT.md](CONTEXT.md) — продукт и фактическое состояние.
- [ARCHITECTURE.md](ARCHITECTURE.md) — архитектура, code style и финансовые правила.
- [AGENTS.md](AGENTS.md) — инструкции Codex и ссылки на ролевые навыки.
- [docs/RAILWAY_DEPLOYMENT.md](docs/RAILWAY_DEPLOYMENT.md) — подробный production deploy/runbook для Railway.


## Синхронизация мобильных категорий и планов

Используется Firebase v4: GET /api/v4/budgets/{budgetId}/sync/changes и
POST /api/v4/budgets/{budgetId}/sync/commands. Снимок содержит scope, changes,
nextCursor, hasMore и snapshotRevision. Категории и планы передаются тем же журналом;
денежные поля остаются десятичными строками. Подтверждение содержит requestId,
revision и entityToken. Повтор после потерянного ответа сохраняет финансовую
идемпотентность; memberId/householdId команды проверяются независимо от URL.
Прежние публичные HTTP v1–v3 отключены. [Переход T12](docs/firebase-static-token-retirement.md).
