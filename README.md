# FinanceConsultante

Личный Telegram-бот для учёта доходов, расходов и семейного бюджета на Kotlin / Spring Boot.

Реализованы сборка/CI и основа денежного учёта T02: PostgreSQL, миграции, транзакционная запись проводок с аудитом, защита от повторов, баланс и хранение дневных курсов. Доходы/расходы поддерживают KZT и RUB; основная валюта KZT, настройка отчёта — 08:00 Asia/Almaty. Загрузчик курсов и Telegram/OpenAI-адаптеры ещё не реализованы. Запуск применяет миграции, проверяет Spring-контекст и завершается: веб-сервера и фонового poller пока нет.

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
| Testcontainers | 2.0.5 |

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

Миграции находятся в `src/main/resources/db/migration`. Приложение само применяет их при запуске. Начальная схема не содержит финансовых данных владельца; настройки и начальный остаток будут вводиться через Telegram в T03. Не меняйте уже применённый SQL-файл — добавляйте следующую версию.

## Конфигурация

| Профиль | Назначение |
| --- | --- |
| `local` | Профиль по умолчанию; datasource использует `POSTGRES_*` из окружения либо явные `DATABASE_*` |
| `test` | Telegram, OpenAI и уведомления выключены; datasource тестовый и задаётся интеграционным набором |
| `prod` | Обязательны `DATABASE_URL` в формате JDBC, `DATABASE_USERNAME`, `DATABASE_PASSWORD`; интеграции по умолчанию выключены |

Будущие адаптеры должны учитывать `app.telegram.enabled`, `app.openai.enabled` и `app.notifications.enabled` до запуска своих клиентов/worker. Пока эти свойства подготавливают конфигурационный контракт; сами адаптеры появятся в T03/T07/T09.

`.env.example` содержит безопасный шаблон переменных. Spring Boot **не читает `.env` автоматически**. Укажите значения в окружении процесса или настройках Run Configuration в IDE. Для своего проверенного локального `.env` в bash/zsh:

```bash
cp .env.example .env
set -a
. ./.env
set +a
docker compose up -d --wait postgres
./gradlew bootRun
```

Не сохраняйте реальные ключи в `application*.yaml`, Gradle, CI или тестовых фикстурах. Файлы `.env` и `.env.*` исключены из Git, кроме `.env.example`. До реализации соответствующих адаптеров оставьте flags выключенными и поля credentials пустыми.

## Проверки

Основная команда, совпадающая с CI:

```bash
./gradlew --no-daemon clean check bootJar
```

При первой проверке нового Docker-окружения добавьте `--no-build-cache`: `./gradlew --no-daemon --no-build-cache clean check bootJar`. Это обеспечивает фактический запуск тестов после `clean`, а не восстановление сохранённых результатов.

| Команда | Назначение |
| --- | --- |
| `./gradlew test` | Unit-тесты денег, конвертации, настроек и баланса без Spring, Docker и сети |
| `./gradlew integrationTest` | PostgreSQL/Testcontainers: миграции, сохранение данных, JDBC, constraints, аудит, rollback, гонка повторной записи и Spring wiring |
| `./gradlew ktlintCheck` | Форматирование Kotlin, Gradle Kotlin DSL и всех Kotlin source sets |
| `./gradlew ktlintFormat` | Применение форматирования; после него снова запустить `check` |
| `./gradlew detekt` | Статический анализ production-, unit- и integration-кода |
| `./gradlew jacocoTestReport` | Тесты и общий HTML/XML-отчёт покрытия по двум наборам |
| `./gradlew check` | Все тесты, ktlint, Detekt и JaCoCo |
| `./gradlew bootJar` | Исполняемый Spring Boot JAR; сам по себе не запускает все проверки |

Gradle задаёт профиль `test` и выключает три флага интеграций через system properties для каждой задачи `Test`: унаследованные из shell переменные включения не активируют реальные API. При запуске тестов прямо из IDE используйте профиль `test` и отдельную конфигурацию без production-переменных; предпочтителен запуск через Gradle. Будущие контрактные тесты должны явно направлять клиенты на локальные заглушки.

Для диагностики без Docker можно **явно** выбрать отдельный локальный PostgreSQL 17: `./gradlew check bootJar -PtestPostgresUrl=jdbc:postgresql://127.0.0.1:55432/financeconsultante_test`. Заранее создайте изолированный тестовый кластер с ролью `test_owner`, паролем `synthetic-test-password` и пустой БД `financeconsultante_test`. Допускается только такой localhost URL с явным портом. Тесты применяют миграции и меняют данные/схему этой БД. Это дополнительный режим проверки SQL; он не подтверждает работу Compose/Testcontainers. Без параметра запускается только Testcontainers, без автоматического fallback. CI использует обычный режим Docker.

Отчёты: [integrationTest](build/reports/tests/integrationTest/index.html), [ktlint](build/reports/ktlint/), [Detekt](build/reports/detekt/detekt.html), [JaCoCo](build/reports/jacoco/test/html/index.html). Они появляются после соответствующих задач и не сохраняются в Git. Произвольный порог покрытия не задан: сначала появляются реальные доменные сценарии, затем оцениваются пробелы.

## CI

[GitHub Actions workflow](.github/workflows/ci.yml) запускается на push, pull request и вручную: устанавливает Java 17, использует Wrapper, проверяет Docker, выполняет `clean check bootJar`, сохраняет отчёты и JAR. Production-секреты не требуются. Публикация и deploy не выполняются.

Для этого локального проекта Git remote пока не настроен. Workflow начнёт выполняться после размещения репозитория в GitHub с включёнными Actions; успешная локальная сборка не является наблюдавшимся запуском GitHub Actions.

## Документация проекта

- [CONTEXT.md](CONTEXT.md) — продукт и фактическое состояние.
- [PLANS.md](PLANS.md) — задачи и критерии готовности.
- [ARCHITECTURE.md](ARCHITECTURE.md) — архитектура, code style и финансовые правила.
- [AGENTS.md](AGENTS.md) — инструкции Codex и ссылки на ролевые навыки.
