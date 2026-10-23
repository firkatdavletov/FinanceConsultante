# Firebase T12 — отключение статического токена

## Рабочий контракт

Только Google/Firebase ID token: POST /api/v4/auth/firebase, GET/POST /api/v4/budgets,
POST /api/v4/budgets/import, адресные /api/v4/budgets/{budgetId}/… и принятие
приглашения. Старый токен не является Firebase credential и получает 401.

MOBILE_API_TOKEN убран из application.yaml и .env.example. MobileApiProperties больше
не хранит секрет. Старая app.mobile-api.token игнорируется, даже если оставлена в
окружении. Прежние v1–v3, GET /api/v4/budget, POST /api/v4/legacy/budgets и
/api/v4/sync/… безусловно отклоняют авторизацию; валидный Firebase token также не
разрешает эти unscoped endpoints. При выключенном MOBILE_API_ENABLED endpoints
могут отсутствовать; это не включает fallback. Новые scoped endpoints проверяют
членство по UID/budgetId и не выводят его из email или Telegram ID.

## Данные

Flyway остаётся V27, схема и данные не меняются. mobile_budget_access и прежние
receipts сохраняются; хеш статического токена больше не открывает HTTP-доступ.
Для существующего бюджета используется явная пара FIREBASE_LEGACY_UID и
FIREBASE_LEGACY_MEMBER_ID. Сервер проверяет занятость и активность прежнего member;
UUID и авторство не меняются. Поддержка HTTP v1–v3 не требуется по уточнению
пользователя; старые очереди этого формата не преобразуются и не удаляются.
V4 команды и fingerprint сохраняются, включая повтор после потерянного ответа.

## Обновление тестового сервера и выпуск

1. Сделать backup PostgreSQL и проверить восстановление в отдельную БД.
2. Собрать и установить текущий JAR с V27. Оставить MOBILE_API_ENABLED=true,
   FIREBASE_AUTH_ENABLED=true, FIREBASE_PROJECT_ID и GOOGLE_APPLICATION_CREDENTIALS.
   Service account JSON остаётся в закрытом файле/Volume, вне Git и APK.
3. Сначала обновить JAR, затем удалить MOBILE_API_TOKEN в Railway. Новый JAR
   игнорирует переменную; старый JAR мог требовать её на старте, поэтому порядок важен.
4. Проверить Firebase-профиль, список бюджетов и v4 обмен. Прежний Bearer должен
   получить 401. Не помещать реальные credentials в логи/историю shell.
5. Установить Android T12 поверх приложения. Проверить локальный режим, Google-вход,
   перенос локального бюджета, второй бюджет, семейный код, выход и восстановление.
6. Для Android release добавить сертификат подписи SHA-1/SHA-256 в Firebase и проверить
   настоящий Google-вход на подписанном APK. Успешный assembleRelease не доказывает
   настройку release certificate. Production-развёртывание — отдельная задача.

## Проверки

В FirebaseBudgetApiTests добавлена матрица отказа старому токену при явно оставленном
app.mobile-api.token: v1/v2/v3, старое чтение/синхронизация v4, bootstrap, scoped v4,
профиль и создание. Валидный Firebase token сохраняет scoped доступ; прежние команды
с прежними UUID/requestId продолжают получать один receipt.

Семь HTTP-suite прежнего v1 удалены как тесты снятого контракта, а не пропущены через
условия или флаги. Финансовые unit/application/persistence, миграции, конкуренция и
общие v4/Firebase тесты сохраняются. В смешанных старых suite проверки финансовых
правил оставлены на уровне доверенного application-сервиса; HTTP assertions теперь
проверяют отказ. Новая конфигурация не имеет тестового выключателя для статического токена.

`./gradlew test check bootJar` — успешно: 315 unit и 232 integration без ошибок
и пропусков, ktlint/Detekt/JaCoCo/bootJar успешны. Число integration уменьшилось
относительно T11 из-за удаления семи v1 HTTP-suite (62 проверки) и добавления новой
матрицы отказа старому токену. Финансовые проверки общего application-кода и v4
не отключались; в смешанных suite прямые вызовы сохраняют проверки сумм, версий,
повторов и атомарности. Firebase-тест дополнительно фиксирует receipt через прежнюю
trusted v4 binding, затем повторяет ту же команду через scoped Firebase и HTTP:
проводка и сумма остаются одними.

Android: 242 unit, полный Pixel OK (156 tests): 153 успешных и 3 opt-in пропущенных;
отдельно 2 real Firebase live-теста текущей Google-сессии и forceRefresh успешны.
Debug и unsigned release APK собраны и проверены на отсутствие прежнего секрета.
Исходные данные/очереди Pixel сохранены. Живые профильные запросы шли к ранее
развёрнутому тестовому серверу без финансовых команд; отказ старому токену доказан
локальными HTTP/PostgreSQL тестами нового кода. Для такого же поведения в Railway
нужно отдельно развернуть текущий JAR, затем выполнить семейную ручную приёмку.
Production и тестовый сервер автоматически не развёртывались.
