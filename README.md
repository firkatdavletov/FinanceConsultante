# FinanceConsultante

Личный Telegram-бот для учёта доходов, расходов и семейного бюджета на Kotlin / Spring Boot.

На этапе T01 подготовлены сборка, профили, проверки качества и CI. Telegram/OpenAI-адаптеры, БД и финансовые функции ещё не реализованы. Поэтому запуск пока проверяет старт Spring-контекста и завершается: веб-сервера и фонового poller ещё нет.

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

Используйте установленный JDK 17 и Gradle Wrapper; отдельно устанавливать Gradle не нужно. Первый запуск скачивает Gradle и зависимости, поэтому требует доступа к Maven Central, Gradle Plugin Portal и серверам дистрибутивов Gradle. Сборка не задаёт локальные абсолютные пути JDK и не загружает JDK автоматически.

Detekt закреплён на alpha.3, поскольку он собран с Kotlin 2.3.21, а стабильный 1.23.8 — с Kotlin 2.0.21. Это осознанная зависимость инструмента разработки от предварительного релиза; библиотека не входит в JAR приложения. Совместимость подтверждается запуском проверок. При обновлении Kotlin нужно повторно сверить [таблицу Detekt](https://detekt.dev/docs/introduction/compatibility/).

Docker пока не нужен для имеющегося Spring smoke-теста. Начиная с T02 PostgreSQL-тесты будут использовать Testcontainers и требовать работающий Docker; они не должны пропускаться при его отсутствии. В CI доступность Docker проверяется явно.

## Запуск

Из корня проекта:

```bash
java -version
./gradlew --version
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

## Конфигурация

| Профиль | Назначение |
| --- | --- |
| `local` | Профиль по умолчанию; интеграции включаются только явными environment flags |
| `test` | Telegram, OpenAI и уведомления выключены; ключи и внешние аккаунты не требуются |
| `prod` | Явный профиль для размещения; banner отключён, логирование INFO, интеграции по умолчанию выключены |

Будущие адаптеры должны учитывать `app.telegram.enabled`, `app.openai.enabled` и `app.notifications.enabled` до запуска своих клиентов/worker. Пока эти свойства подготавливают конфигурационный контракт; сами адаптеры появятся в T03/T07/T09.

`.env.example` содержит безопасный шаблон переменных. Spring Boot **не читает `.env` автоматически**. Укажите значения в окружении процесса или настройках Run Configuration в IDE. Для своего проверенного локального `.env` в bash/zsh:

```bash
cp .env.example .env
set -a
. ./.env
set +a
./gradlew bootRun
```

Не сохраняйте реальные ключи в `application*.yaml`, Gradle, CI или тестовых фикстурах. Файлы `.env` и `.env.*` исключены из Git, кроме `.env.example`. До реализации соответствующих адаптеров оставьте flags выключенными и поля credentials пустыми.

## Проверки

Основная команда, совпадающая с CI:

```bash
./gradlew --no-daemon clean check bootJar
```

| Команда | Назначение |
| --- | --- |
| `./gradlew test` | Unit-тесты в `src/test/kotlin`; бизнес-кода пока нет, поэтому набор пока пуст |
| `./gradlew integrationTest` | Отдельный набор в `src/integrationTest/kotlin`; сейчас проверяет запуск Spring и выключенные внешние интеграции |
| `./gradlew ktlintCheck` | Форматирование Kotlin, Gradle Kotlin DSL и всех Kotlin source sets |
| `./gradlew ktlintFormat` | Применение форматирования; после него снова запустить `check` |
| `./gradlew detekt` | Статический анализ production-, unit- и integration-кода |
| `./gradlew jacocoTestReport` | Тесты и общий HTML/XML-отчёт покрытия по двум наборам |
| `./gradlew check` | Все тесты, ktlint, Detekt и JaCoCo |
| `./gradlew bootJar` | Исполняемый Spring Boot JAR; сам по себе не запускает все проверки |

Gradle задаёт профиль `test` и выключает три флага интеграций через system properties для каждой задачи `Test`: унаследованные из shell переменные включения не активируют реальные API. При запуске тестов прямо из IDE используйте профиль `test` и отдельную конфигурацию без production-переменных; предпочтителен запуск через Gradle. Будущие контрактные тесты должны явно направлять клиенты на локальные заглушки.

Отчёты: [integrationTest](build/reports/tests/integrationTest/index.html), [ktlint](build/reports/ktlint/), [Detekt](build/reports/detekt/detekt.html), [JaCoCo](build/reports/jacoco/test/html/index.html). Они появляются после соответствующих задач и не сохраняются в Git. Произвольный порог покрытия не задан: сначала появляются реальные доменные сценарии, затем оцениваются пробелы.

## CI

[GitHub Actions workflow](.github/workflows/ci.yml) запускается на push, pull request и вручную: устанавливает Java 17, использует Wrapper, проверяет Docker, выполняет `clean check bootJar`, сохраняет отчёты и JAR. Production-секреты не требуются. Публикация и deploy не выполняются.

Для этого локального проекта Git remote пока не настроен. Workflow начнёт выполняться после размещения репозитория в GitHub с включёнными Actions; успешная локальная сборка не является наблюдавшимся запуском GitHub Actions.

## Документация проекта

- [CONTEXT.md](CONTEXT.md) — продукт и фактическое состояние.
- [PLANS.md](PLANS.md) — задачи и критерии готовности.
- [ARCHITECTURE.md](ARCHITECTURE.md) — архитектура, code style и финансовые правила.
- [AGENTS.md](AGENTS.md) — инструкции Codex и ссылки на ролевые навыки.
