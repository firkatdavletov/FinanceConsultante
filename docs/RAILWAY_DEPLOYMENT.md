# Развёртывание FinanceConsultante на Railway через GitHub

> Исторический runbook первого Telegram-релиза. Telegram, отправка отчётов и
> inbox-распознавание удалены из приложения 2026-11-04; разделы об их включении,
> токенах, polling и smoke больше не применяются. Текущие параметры и команды —
> [README](../README.md), [Firebase API](firebase-authentication.md).
> Разделы резервного копирования и восстановления остаются применимы к сохранённой схеме.


Инструкция относится к первому релизу T11. Она подготавливает и проверяет инфраструктуру, но не подтверждает
состоявшийся релиз до прохождения production smoke и restore drill. Все примеры финансовых данных синтетические.

Актуальные первичные источники:

- [Railway: Spring Boot и Dockerfile](https://docs.railway.com/guides/spring-boot);
- [Railway: GitHub autodeploy и Wait for CI](https://docs.railway.com/deployments/github-autodeploys);
- [Railway: healthchecks](https://docs.railway.com/deployments/healthchecks);
- [Railway: PostgreSQL](https://docs.railway.com/databases/postgresql);
- [Railway: reference variables](https://docs.railway.com/variables/reference);
- [Railway: teardown и graceful draining](https://docs.railway.com/deployments/deployment-teardown);
- [Railway: backup и restore PostgreSQL](https://docs.railway.com/guides/postgres-backups-restores);
- [Railway: cost control](https://docs.railway.com/pricing/cost-control);
- [OpenAI Docs: `gpt-4o-mini`](https://developers.openai.com/api/docs/models/gpt-4o-mini).

## 1. Что будет развёрнуто

```text
GitHub main ──push──> GitHub Actions ──успех──> Railway app (1 replica, без public domain)
                                                      │
                                                      ├── private network ──> PostgreSQL 17
                                                      │
                                                      ├── HTTPS ──> Telegram Bot API
                                                      ├── HTTPS ──> Currencylayer
                                                      └── HTTPS ──> OpenAI (опционально)

GitHub ops/backup ──> Railway cron service ──pg_dump──> age encryption ──> private S3 bucket
```

Приложению не нужен входящий публичный трафик: Telegram используется через long polling. Не создавайте Railway
public domain для app-сервиса. Railway healthcheck обращается к контейнеру отдельно по выданному `PORT`.

Реплика приложения должна быть ровно одна. Блокировка poller также хранится в PostgreSQL и защищает короткое
перекрытие старого и нового контейнеров, однако горизонтальное масштабирование для MVP не поддерживается.

## 2. Что уже подготовлено в репозитории

- Корневой multi-arch `Dockerfile` собирает `bootJar` на точном Temurin `17.0.20+8` для Ubuntu Noble, а runtime
  запускается от UID/GID `10001`.
- Приложение слушает `${PORT:8080}` и корректно завершает Spring lifecycle по `SIGTERM` в течение 45 секунд.
- `/actuator/health/readiness` включает `readinessState` и реальное подключение `db`; подробности health скрыты.
- `/actuator/prometheus` содержит JVM/process/Hikari-метрики и безопасные агрегаты очередей без Telegram ID и
  финансовых labels. Остальные Actuator endpoints отключены.
- GitHub Actions выполняет `clean check bootJar` и затем собирает тот же deployment image.
- `ops/backup` содержит отдельный образ для зашифрованного логического backup с хранением 14 дней.
- Обработанный Telegram-текст удаляется сразу после обработки, а `(bot_id, update_id)` и связанные ссылки остаются
  в БД как ключи идемпотентности. Отдельная периодическая очистка финансовых записей не требуется.

## 3. Предварительные условия

Подготовьте до создания production-сервиса:

1. Railway workspace с подключённым GitHub-аккаунтом и доступом к репозиторию
   `firkatdavletov/FinanceConsultante`.
2. Включённые GitHub Actions и актуальная ветка `main`.
3. Отдельный Telegram-бот для первого smoke, его token и числовой Telegram ID владельца. Не используйте общий чат.
4. Currencylayer key, если требуется запись RUB в первый день.
5. OpenAI API key, если OpenAI включается в первом релизе.
6. Локально установленные `git`; для эксплуатационных команд также удобны Railway CLI, PostgreSQL client 17 и
   `age`. На macOS: `brew install railway libpq age awscli`.
7. Выбранный лимит расходов Railway и место хранения приватного `age` identity.

Никогда не помещайте token/key/password в GitHub Actions secrets: Railway сам собирает репозиторий, а CI не
нужны production-секреты. Не записывайте значения в `.env.example`, README, issue или build log.

## 4. Проверка и отправка кода в GitHub

Из корня репозитория:

```bash
git status --short
git remote -v
./gradlew --no-daemon --no-build-cache clean check bootJar
docker build --tag financeconsultante:t11 .
git diff --check
```

Просмотрите diff и убедитесь, что `.env` не отслеживается:

```bash
git ls-files .env
git diff --cached
```

Первая команда не должна вывести ничего. Затем самостоятельно создайте commit и отправьте его:

```bash
git add .
git commit -m "Подготовлен первый Railway-релиз"
git push origin main
```

Откройте GitHub → Actions → `CI` и дождитесь зелёного результата для commit SHA. Не переходите к production
deploy, если `check` или `Build deployment image` завершились ошибкой.

## 5. Создание Railway project и PostgreSQL

1. В Railway нажмите **New Project → Empty Project** и назовите проект `FinanceConsultante`.
2. Создайте environment `production`; PR environments для production-секретов не включайте.
3. Нажмите **Create → Database → PostgreSQL**. Назовите сервис `Postgres`.
4. Выберите один регион для PostgreSQL и приложения. Для владельца в Алматы стартовый практичный выбор —
   **Southeast Asia / Singapore**; перед оплатой проверьте доступность и тариф в своём workspace.
5. Не включайте Public Access/TCP Proxy для PostgreSQL. Приложение использует только private network.
6. Дождитесь состояния PostgreSQL `Active`.

Railway предоставляет `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` и `PGPASSWORD`. Его `DATABASE_URL` имеет вид
`postgresql://...`, а Spring JDBC ожидает `jdbc:postgresql://...`, поэтому app-переменная ниже собирается из
`PG*` reference variables.

## 6. Создание app-сервиса и переменных

1. В том же проекте создайте **Empty Service** с именем `financeconsultante-app`.
2. Откройте **Variables → Raw Editor** и добавьте сначала безопасный bootstrap-набор. Namespace `Postgres` должен
   совпадать с фактическим именем DB-сервиса; лучше выбирать переменные через Railway autocomplete.

```dotenv
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DATABASE_USERNAME=${{Postgres.PGUSER}}
DATABASE_PASSWORD=${{Postgres.PGPASSWORD}}
TELEGRAM_ENABLED=false
NOTIFICATIONS_ENABLED=false
EXCHANGE_RATES_ENABLED=false
OPENAI_ENABLED=false
OPENAI_MODEL=gpt-4o-mini-2024-07-18
OPENAI_REQUEST_TIMEOUT=10s
OPENAI_MAX_RETRIES=1
OPENAI_MAX_OUTPUT_TOKENS=256
OPENAI_DAILY_REQUEST_LIMIT=50
```

`PORT` не задавайте: Railway автоматически inject-ит его, а приложение читает `${PORT:8080}`.

3. Откройте **Settings → Source → Connect Repo**, выберите `firkatdavletov/FinanceConsultante` и ветку `main`.
   Railway обнаружит корневой `Dockerfile` автоматически.
4. Включите **Wait for CI**. Workflow содержит обязательный `push` для `main`; не отключайте этот trigger.
5. Задайте deployment settings:

| Настройка | Значение | Причина |
| --- | --- | --- |
| Replicas | `1` в одном регионе | Единственный long poller и предсказуемая стоимость |
| Serverless / App sleeping | `Off` | Утренние и периодические задания должны работать без входящего запроса |
| Healthcheck path | `/actuator/health/readiness` | Проверяет Spring readiness и PostgreSQL |
| Healthcheck timeout | `300` секунд | Даёт время pull образа, старта JVM и Flyway |
| Restart policy | `Always` | Постоянный worker восстанавливается после остановки |
| Deployment overlap | `0` секунд | Не держит старый poller после активации нового |
| Draining time | `60` секунд | Больше Spring shutdown phase и Telegram HTTP timeout |
| Memory limit | сначала `1 GiB` | После недели уменьшить только по фактическим JVM/RSS метрикам |
| App volume | отсутствует | Состояние хранится в PostgreSQL, filesystem контейнера эфемерен |
| Public networking | отсутствует | Bot API вызывается исходящим long polling |

На тарифе, где `Always` недоступен, `On Failure` ограничен числом рестартов. Для заявленной работы 24/7 выберите
подходящий paid plan или зафиксируйте это как непринятое эксплуатационное ограничение.

6. Примените staged changes. Первый deploy должен запустить Flyway V1–V9 и стать healthy с выключенными внешними
   интеграциями.

## 7. Проверка bootstrap deploy

В Railway проверьте:

- Git commit SHA deployment совпадает с зелёным SHA GitHub Actions;
- build log содержит использование корневого `Dockerfile`;
- runtime log содержит успешный старт Spring и Flyway без token, сообщений или финансовых payload;
- deployment имеет состояние `Active`, а healthcheck — `Healthy`;
- replica count равен 1, Serverless выключен, public domain отсутствует.

Для проверки из контейнера установите Railway CLI, выполните `railway login`, затем `railway link`. Команды не
показывают секреты:

```bash
railway ssh --service financeconsultante-app -- \
  sh -lc 'wget -qO- http://127.0.0.1:${PORT}/actuator/health/readiness'
```

Ожидается HTTP body `{"status":"UP"}` без состава БД и credentials. Проверьте, что закрытый endpoint недоступен:

```bash
railway ssh --service financeconsultante-app -- \
  sh -lc 'wget -S -O /dev/null http://127.0.0.1:${PORT}/actuator/env 2>&1 | grep "404"'
```

## 8. Включение Telegram, курса и OpenAI

Добавляйте секреты только в **Variables** app-сервиса. После сохранения выберите **Seal** для значений
`TELEGRAM_BOT_TOKEN`, `CURRENCYLAYER_ACCESS_KEY` и `OPENAI_API_KEY`.

```dotenv
TELEGRAM_BOT_TOKEN=<token отдельного release-бота>
TELEGRAM_OWNER_ID=<положительный числовой ID владельца>
CURRENCYLAYER_ACCESS_KEY=<key>
OPENAI_API_KEY=<key>
TELEGRAM_ENABLED=true
NOTIFICATIONS_ENABLED=true
EXCHANGE_RATES_ENABLED=true
OPENAI_ENABLED=true
```

Включайте только адаптеры, для которых задан валидный credential. Допустимый первый запуск без AI:
`OPENAI_ENABLED=false`; локальный parser, баланс и числовые отчёты продолжат работать. Без Currencylayer можно
вести KZT, но RUB-операция останется в ожидании курса.

Production model остаётся точным snapshot `gpt-4o-mini-2024-07-18`, проверенным корпусом T07. Официальная
документация OpenAI на дату подготовки T11 подтверждает Responses API и Structured Outputs для этого snapshot;
цены и доступность перепроверьте непосредственно перед deploy. Дневной application-limit — 50 request units.

Примените staged variable changes и дождитесь нового healthy deployment. В startup log не должно быть validation
ошибок `Telegram bot token is required`, `OPENAI_API_KEY is required` или `CURRENCYLAYER_ACCESS_KEY is required`.

## 9. Production smoke в отдельном Telegram-боте

Все шаги выполняются владельцем в private chat с release-ботом. Не используйте реальные суммы.

1. Отправьте `/start` и завершите настройку: начальный остаток `0`, текущая дата Asia/Almaty, `08:00`, резерв `0`.
2. Отправьте четыре операции:

```text
зарплата 1300000
супермаркет 15000
продукты 700
аптека 1300
```

3. `/balance` должен показать `1 283 000 KZT`, расходы `17 000`, продукты `15 700`, здоровье `1 300`.
4. Отправьте `/history`, выберите расход `700` и исправьте сумму на `900` через кнопку либо команду
   `/amount <id> <версия> 900`. Повтор старой кнопки/команды не должен изменить сумму второй раз.
5. `/balance` должен показать `1 282 800 KZT` и расходы `17 200`.
6. Проверьте `/today`, `/report week` и `/report month`. Пустой предыдущий период должен давать оговорку, а не
   ошибочный процент.
7. Отправьте неоднозначную фразу. При включённом OpenAI должен появиться подтверждаемый draft; без `/confirm`
   баланс не меняется.
8. Для fallback временно задайте `OPENAI_ENABLED=false`, дождитесь healthy redeploy и повторите неоднозначный ввод:
   бот должен запросить локальное уточнение, а ledger не измениться. Затем верните `OPENAI_ENABLED=true`.
9. Из второго Telegram-аккаунта отправьте `/balance`: бот не отвечает, OpenAI job и финансовое чтение не создаются.
10. Нажмите **Restart** только у app-сервиса. После возврата healthcheck повторите `/balance`, `/history`, `/today`:
    суммы, версии, offset и расписание должны сохраниться.

Не отправляйте токены, полный текст сообщений или финансовые результаты в issue и deploy logs. Для отчёта T11
достаточно commit SHA, времени шага, статуса и синтетических ожидаемых итогов.

## 10. Проверка эксплуатационных метрик

Prometheus endpoint доступен внутри контейнера, но не публикуется в интернет. Получите только имена и значения
агрегатов:

```bash
railway ssh --service financeconsultante-app -- \
  sh -lc 'wget -qO- http://127.0.0.1:${PORT}/actuator/prometheus | grep "^financeconsultante_"'
```

Контролируйте как минимум:

- `financeconsultante_telegram_inbox_pending_messages` и `...oldest_age_seconds`;
- `financeconsultante_telegram_outbox_pending_messages` / `...failed_messages`;
- `financeconsultante_report_jobs_pending_messages` / `...failed_messages` / `...overdue_age_seconds`;
- `financeconsultante_exchange_jobs_pending_messages` / `...overdue_age_seconds`;
- `financeconsultante_openai_jobs_pending_messages` / `...oldest_age_seconds`;
- стандартные `jvm_*`, `process_*`, `hikaricp_*`.

Для постоянного alerting добавьте отдельный private Prometheus-compatible collector либо внешний монитор с
аутентифицированным tunnel. Не создавайте публичный `/actuator/prometheus` без access control. Railway healthcheck
проверяет endpoint только во время deploy, поэтому отдельно настройте уведомления о crashed/failed deployments.

## 11. Backup с 14-дневным хранением и шифрованием

### 11.1. Три уровня защиты

1. В `Postgres → Backups` включите daily и weekly volume backups. Текущая политика Railway хранит daily 6 дней,
   поэтому сама по себе не выполняет требование проекта 14 дней.
2. Если тариф допускает, включите PITR. Восстановление создаёт новый sibling Postgres и не изменяет source.
3. Настройте ежедневный logical dump из `ops/backup` в отдельный S3-compatible bucket. Именно он даёт переносимую
   копию, client-side encryption и 14-дневное хранение вне рабочего DB volume.

Railway Bucket пока не поддерживает server-side encryption и lifecycle rules. Поэтому dump шифруется `age` до
upload, а cron сам удаляет объекты старше `BACKUP_RETENTION_DAYS`. Для защиты от удаления всего Railway project
выберите bucket у независимого S3-compatible provider; остальная конфигурация не меняется.

### 11.2. Ключ шифрования

Создайте identity локально, не в Railway:

```bash
age-keygen -o financeconsultante-backup.agekey
age-keygen -y financeconsultante-backup.agekey
```

Первый файл храните офлайн и в password manager с резервной копией. Никогда не добавляйте его в Git. Вторая
команда печатает публичный recipient `age1...`; только его значение попадёт в `BACKUP_AGE_RECIPIENT`.

### 11.3. Cron service из GitHub

1. Создайте private Railway Bucket `financeconsultante-backups` либо внешний S3 bucket.
2. Создайте Empty Service `financeconsultante-backup` и подключите тот же GitHub repo/ветку `main`.
3. В Settings укажите **Root Directory** `/ops/backup`. Railway соберёт `ops/backup/Dockerfile`.
4. Задайте cron `0 21 * * *`: Railway использует UTC, поэтому это 03:00 следующего дня в Asia/Almaty.
5. App sleeping для cron не имеет смысла; сервис должен запускаться по cron и завершаться с кодом `0`.
6. Добавьте variables, используя autocomplete фактических namespaces:

```dotenv
DATABASE_URL=${{Postgres.DATABASE_URL}}
AWS_ENDPOINT_URL=${{financeconsultante-backups.ENDPOINT}}
AWS_ACCESS_KEY_ID=${{financeconsultante-backups.ACCESS_KEY_ID}}
AWS_SECRET_ACCESS_KEY=${{financeconsultante-backups.SECRET_ACCESS_KEY}}
AWS_S3_BUCKET_NAME=${{financeconsultante-backups.BUCKET}}
AWS_DEFAULT_REGION=${{financeconsultante-backups.REGION}}
BACKUP_AGE_RECIPIENT=age1...
BACKUP_RETENTION_DAYS=14
BACKUP_PREFIX=financeconsultante
```

`DATABASE_URL` здесь намеренно обычный `postgresql://...`: его использует `pg_dump`, а не JDBC.

7. Запустите cron service вручную один раз. Успех содержит только `BACKUP_STARTED`, `BACKUP_UPLOADED` и
   `BACKUP_COMPLETED`; ошибка завершается non-zero и пишет `BACKUP_FAILED stage=...` без credentials.
8. Настройте Railway notification/webhook на failed/crashed deployment этого сервиса. Ежедневно должен быть виден
   один новый `.dump.age`; отсутствие успешного запуска более 24 часов — инцидент.

## 12. Обязательный restore drill

Наличие файла не доказывает восстановимость. Выполните drill до признания T11 завершённой.

1. Зафиксируйте UTC-время начала и контроль production **до** восстановления:

```bash
railway connect Postgres --tunnel-only --port 55440
```

В другом terminal используйте напечатанный tunnel URL, не сохраняйте пароль в shell history. Запишите вывод:

```bash
psql '<production-tunnel-url>' -X --csv \
  -f ops/backup/restore-check.sql > /tmp/financeconsultante-production-check.csv
```

2. Скачайте последний `.dump.age` из bucket в локальный временный каталог и расшифруйте:

```bash
age --decrypt --identity financeconsultante-backup.agekey \
  --output /tmp/financeconsultante-restore.dump \
  /tmp/financeconsultante-YYYYMMDD-HHMMSS.dump.age
```

3. Создайте отдельный Railway PostgreSQL service `Postgres-restore-drill` в том же регионе. Не меняйте references
production app. Откройте отдельный tunnel:

```bash
railway connect Postgres-restore-drill --tunnel-only --port 55441
```

4. Восстановите только в новую пустую БД:

```bash
pg_restore --dbname='<restore-tunnel-url>' --no-owner --no-acl --exit-on-error \
  /tmp/financeconsultante-restore.dump
```

Не применяйте `--clean` и не направляйте команду на production URL.

5. Снимите контроль восстановленной БД:

```bash
psql '<restore-tunnel-url>' -X --csv \
  -f ops/backup/restore-check.sql > /tmp/financeconsultante-restored-check.csv
diff -u /tmp/financeconsultante-production-check.csv /tmp/financeconsultante-restored-check.csv
```

Ожидается пустой diff для числа миграций, household, ledger, revisions, idempotency keys, report jobs, числа
проводок и баланса KZT. Если production изменилась после создания dump, сравнивайте с контрольным снимком,
сделанным непосредственно перед тем же dump, а не с текущей БД.

6. Удалите локальный расшифрованный dump безопасным способом и затем удалите только временный
`Postgres-restore-drill`. Source Postgres, его volume и backups не изменяйте.
7. Зафиксируйте:

| Показатель | Как считать | Цель |
| --- | --- | --- |
| RPO | время начала drill минус timestamp восстановленного dump | ≤ 24 часа |
| RTO | время начала drill до успешного сравнения контрольных данных | ≤ 4 часа |

До измерения обе величины являются целями, а не гарантиями.

## 13. Стоимость и лимиты

Перед включением production запишите в release note:

| Статья | Что зафиксировать |
| --- | --- |
| Railway app | регион, replica limit, memory/CPU limit, фактический forecast |
| PostgreSQL | compute, размер volume, volume backups и PITR |
| Backup | размер encrypted dumps, bucket/provider, retention 14 дней, egress |
| OpenAI | точный model ID, текущая цена из OpenAI Docs, `OPENAI_DAILY_REQUEST_LIMIT=50` |
| Currencylayer | название тарифа, месячная квота и alert поставщика |

В Railway Workspace Usage задайте email alert на ожидаемом месячном уровне и осознанный hard limit с запасом.
Hard limit останавливает workloads, поэтому слишком низкое значение нарушит ежедневные отчёты. Через 24 часа и
через 7 дней снимите фактический forecast и скорректируйте memory/CPU limit.

## 14. Deploy, rollback и инциденты

Обычный deploy: pull request → зелёный GitHub Actions → merge в `main` → Railway Wait for CI → healthcheck →
активация новой версии. Перед миграцией создайте manual DB backup.

Для rollback кода выберите предыдущий Railway deployment. Не выполняйте `flyway clean`, `DROP`, down-миграции
или восстановление поверх production. Текущие V1–V9 рассчитаны на forward-only запуск; если будущая миграция
делает старый код несовместимым, rollback кода не считается безопасным и требует отдельного forward-fix.

Минимальный runbook инцидента:

1. Остановить autodeploy, не удаляя app/DB service или volume.
2. Зафиксировать commit SHA, UTC-время, health, безопасный класс ошибки и состояние агрегатных метрик.
3. При ошибке приложения — rollback/redeploy предыдущего image, если схема совместима.
4. При ошибке данных — создать sibling restore через PITR либо restore drill DB; source не изменять до сверки.
5. После восстановления проверить `/balance`, число ledger entries, pending jobs и Telegram offset.
6. Возобновить autodeploy только после документированного результата.

## 15. Чек-лист завершения T11

- [ ] `clean check bootJar` и Docker build прошли локально и в GitHub Actions.
- [ ] App и PostgreSQL работают в одном регионе; DB не опубликована.
- [ ] Ровно одна app replica, Serverless выключен, restart `Always`.
- [ ] Readiness `UP`, `/actuator/env` закрыт, агрегатные metrics доступны только приватно.
- [ ] Telegram setup и четыре синтетические операции дали контрольный баланс.
- [ ] Исправление/повтор не продублировали деньги.
- [ ] `/today`, `/report week`, `/report month` отработали.
- [ ] OpenAI draft и выключенный OpenAI fallback не изменили ledger без подтверждения.
- [ ] Посторонний Telegram ID не получил ответ и не создал AI job.
- [ ] Restart сохранил баланс, историю, offset и report jobs.
- [ ] Daily/weekly volume backup и ежедневный encrypted logical dump включены.
- [ ] Restore drill на отдельной БД дал совпадающие counts/balance; RPO/RTO измерены.
- [ ] Railway/OpenAI/Currencylayer расходы и лимиты записаны.
- [ ] Проверена совместимость rollback кода с текущей Flyway-схемой.

Только после всех пунктов T11 можно переводить из `TODO` в `DONE`.
