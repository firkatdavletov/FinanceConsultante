# T03 — Firebase Authentication для Fillin

`POST /api/v4/auth/firebase` принимает `Authorization: Bearer <Firebase ID token>`.
Тело запроса не требуется. UID, имя и email из клиентского JSON не используются.
Ответ `200`: `{ "id": "<server UUID>", "firebaseUid": "<verified UID>", "displayName": null, "email": null }`.
Профиль содержит `Cache-Control: no-store`; сервер не выдаёт собственный JWT.

Firebase Admin Java SDK 9.11.0 вызывает `verifyIdToken(token, true)`. Проверяются
подпись, срок, issuer/audience проекта, отзыв токена, удаление/блокировка пользователя
и Google provider. Anonymous Firebase token не является локальным режимом приложения
и не принимается этим endpoint. Неверные/отозванные токены получают `401 unauthorized`,
сбои проверки Firebase — `503 auth_unavailable`. Ответы не содержат токен, SDK exception
или его cause. Заголовок `WWW-Authenticate: Bearer` возвращается при `401`.

Миграция V23 создаёт независимую таблицу `firebase_user`. Уникальный Firebase UID
и атомарный PostgreSQL upsert гарантируют прежний server UUID при повторе и
конкурентном первом входе; профиль обновляется из проверенных claims. Внешняя проверка
Firebase выполняется до SQL, без открытой DB-транзакции. Старые таблицы и привязки
не меняются. Членство/бюджет не создаются: доступ по UID и budgetId входит в T05.
Статический токен по-прежнему используется прежними budget endpoints до миграции;
для нового auth endpoint fallback на него отсутствует.

## Настройка

По умолчанию endpoint выключен. Для тестового сервера:

```bash
export FIREBASE_AUTH_ENABLED=true
export FIREBASE_PROJECT_ID=fillin-77e97
export GOOGLE_APPLICATION_CREDENTIALS="$HOME/.config/fillin/firebase-adminsdk.json"
```

Credentials берутся только из Application Default Credentials. JSON должен находиться
вне Git и Android, с правами `0600`; на хостинге используется secret file или workload
identity. Включённый адаптер требует непустой project ID и отказывается запускаться
с `FIREBASE_AUTH_EMULATOR_HOST`: тестовый unsigned token не должен попасть в рабочий путь.
Если Firebase выключен, ADC не загружаются и остальная локальная функциональность сервера
работает по прежним правилам. Обычные Gradle-тесты принудительно выключают адаптер;
целевые HTTP-тесты включают endpoint с явно подменённым verifier.

Для ручной проверки поднята отдельная локальная БД без production данных:

```bash
docker run -d --name fillin-t03-postgres \
  -e POSTGRES_DB=fillin_t03 -e POSTGRES_USER=t03_owner \
  -e POSTGRES_PASSWORD=synthetic-t03-local-password \
  -p 127.0.0.1:55433:5432 postgres:17.11-alpine
./gradlew bootJar
GOOGLE_APPLICATION_CREDENTIALS="$HOME/.config/fillin/firebase-adminsdk.json" \
java -jar build/libs/FinanceConsultante-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=test \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:55433/fillin_t03 \
  --spring.datasource.username=t03_owner \
  --spring.datasource.password=synthetic-t03-local-password \
  --app.firebase-auth.enabled=true --app.firebase-auth.project-id=fillin-77e97 \
  --app.mobile-api.enabled=false --server.address=127.0.0.1 --server.port=18080
```

Повторный запуск уже созданного контейнера: `docker start fillin-t03-postgres`.
Профиль test выключает Telegram, OpenAI, уведомления, валютный загрузчик и recurring scheduler.
Сервер слушает только loopback. Существующий мобильный budget API в этом запуске выключен.
После проверки завершить JVM и выполнить `docker stop fillin-t03-postgres`;
контейнер сохраняет тестовую БД для повторной приёмки.

В Fillin задать игнорируемое `FIREBASE_API_BASE_URL=http://127.0.0.1:18080/`
в `local.properties` и выполнить `adb reverse tcp:18080 tcp:18080`.
У Android отдельный Retrofit-клиент без redirects и без статического токена;
он получает `getIdToken(false)` перед запросом и один раз повторяет `401` с `getIdToken(true)`.
Debug разрешает HTTP только для localhost; release требует HTTPS.
Production-развёртывание не выполнялось.

## Результат проверки 2026-10-20

- `./gradlew test`, `./gradlew check bootJar` успешны: 315 unit и 265 integration-тестов,
  без skips; Docker/Testcontainers PostgreSQL 17.11, ktlint, Detekt и JaCoCo.
- Новые проверки: неверный Authorization до verifier, отказ без записи, проверенные
  claims, `checkRevoked=true`, отображение SDK ошибок expired/revoked/disabled,
  HTTP 200/401/503, отсутствие автоматического бюджета, повтор и конкурентный первый вход.
- Пустая БД мигрирует до V23. Отдельный тест V22→V23 сохраняет UUID и прежние member,
  account и mobile_budget_access; общая регрессия проверяет ledger/audit.
- Android: 176 unit и 28 тестов на Pixel 10 Pro XL прошли; `401`/повторный `401`/`503`
  проверены управляемыми HTTP-ответами с синтетическими токенами.
- Реальный локальный сервер запускается с существующим ADC; запрос без токена и
  неверный статический токен отклоняются с `401` через настоящий Admin SDK.
- Пользователь подтвердил настоящий Google-вход на Pixel и серверный профиль. Затем прошли
  2 отдельных opt-in live-теста: восстановленная сессия получает прежний server UUID при повторе;
  контролируемый первый 401 вызывает настоящий Firebase `getIdToken(true)`, повтор с обновлённым
  токеном успешно проходит проверку настоящего Admin SDK. В тестовой БД одна запись без дублей.
- Реальный отзыв Firebase-сессии не выполнялся; коды revoked/disabled проверены имитацией
  ошибок SDK с обязательным `checkRevoked=true`. Mock verifier не считается живой проверкой отзыва.

Для приёмки войти в тестовый Google-аккаунт в Fillin, дождаться «Аккаунт подтверждён сервером»,
повторить после перезапуска. Убедиться в единственной записи `firebase_user` с прежним UUID
без вывода UID/email в логи. Для проверки отзыва использовать только отдельного тестового
пользователя: старый токен должен дать 401, повторный Google-вход — тот же server UUID.
Живые тесты запускаются отдельно, только после Google-входа:

```bash
adb shell am instrument -w \
  -e class com.firkat.fillin.feature.auth.FirebaseServerLiveTest \
  -e liveFirebaseAuth true \
  com.firkat.fillin.test/com.firkat.fillin.FillinTestRunner
```

Обычный запуск без флага пропускает живые тесты. Чтобы проверить недоступность,
остановить JVM, перезапустить Fillin и открыть «Облако»;
после запуска JVM нажать «Повторить проверку».

## Источники

- [Firebase Admin setup](https://firebase.google.com/docs/admin/setup)
- [Verify ID tokens](https://firebase.google.com/docs/auth/admin/verify-id-tokens)
- [Manage sessions](https://firebase.google.com/docs/auth/admin/manage-sessions)
- [Firebase Admin Java releases](https://firebase.google.com/support/release-notes/admin/java)
