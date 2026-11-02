# Email/password: контракт для Android Fillin

Сервер принимает Firebase ID token от Google (`google.com`) и Email/Password (`password`).
Регистрация, пароль, письма и Firebase-сессия обслуживаются Firebase SDK в приложении.
Пароль и refresh token не передаются FinanceConsultante. Сервер не выдаёт собственный JWT.
Android-реализация и настройка Firebase Console в эту серверную поставку не входят.

## Подготовка Firebase

1. В существующем Firebase-проекте включить Authentication → Sign-in method → Email/Password.
   Google оставить включённым. Вход по email-ссылке без пароля для этого сценария не нужен.
2. Проверить парольную политику в Authentication Settings. SDK должен отображать отказ
   Firebase при слабом пароле; отдельной серверной политики паролей нет.
3. Проверить, что включена email enumeration protection. Не строить интерфейс на предварительном
   поиске способов входа по адресу: это ненадёжно при включённой защите.
4. В Templates проверить письма подтверждения email и сброса пароля. Использовать стандартный
   Firebase action handler в браузере. Custom deep links и собственный обработчик не требуются.
   Перед отправкой письма можно установить `FirebaseAuth.setLanguageCode("ru")`.
5. Android и сервер должны использовать один Firebase project ID. Серверные ADC и флаги описаны
   в [Firebase Authentication](firebase-authentication.md). Admin credentials никогда не включаются в Android.

## Регистрация и подтверждение

Последовательность вызовов SDK (каждый следующий шаг — после успешного завершения предыдущего):

1. `FirebaseAuth.createUserWithEmailAndPassword(email, password)` создаёт Firebase-пользователя
   и сессию, но ещё не открывает серверный доступ.
2. У текущего пользователя вызвать `sendEmailVerification()`. Если отправка не удалась,
   аккаунт уже может существовать: сохранять сессию и предложить повторную отправку, не повторять регистрацию.
3. Пользователь открывает письмо и подтверждает адрес на стандартной странице Firebase в браузере.
4. При возврате в приложение или по кнопке «Я подтвердил email» вызвать `currentUser.reload()`,
   заново получить `currentUser`, затем `getIdToken(true)`. Одного локального признака `isEmailVerified`
   недостаточно для сервера: подтверждение должно попасть в новый токен.
5. С этим токеном вызвать `POST /api/v4/auth/firebase`, затем `GET /api/v4/budgets`.

Пока адрес не подтверждён, все Firebase endpoints сервера возвращают `403 email_not_verified`.
Пользователь не записывается в `firebase_user`, членство и бюджет не создаются. Локальный offline-режим
Fillin остаётся доступным; несинхронизированные данные нужно сохранить до завершения облачного входа.
Повторная отправка письма выполняется через SDK и может ограничиваться Firebase.

## Вход и API

Выполнить `signInWithEmailAndPassword(email, password)`. Получить `currentUser.getIdToken(false)`
и передать ID token в Bearer-заголовке по HTTPS. Google и password используют один контракт.
Email, UID, имя и признак подтверждения из клиентского JSON не являются источником доверия.

Пример запроса (значения синтетические; placeholder токена не является рабочим credential):

```http
POST /api/v4/auth/firebase HTTP/1.1
Host: api.example.test
Authorization: Bearer <Firebase ID token>
```

Тело запроса не требуется. Успешный ответ:

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Content-Type: application/json

{
  "id": "10000000-0000-0000-0000-000000000001",
  "firebaseUid": "synthetic-firebase-uid",
  "displayName": null,
  "email": "user@example.test"
}
```

`id` — UUID серверного профиля; `firebaseUid` — проверенный UID. `displayName` может быть null,
в частности при регистрации по паролю; не требовать имя для входа. `email` также nullable в общем
контракте, но password-вход требует непустой подтверждённый адрес. Профиль не является бюджетом.

```http
GET /api/v4/budgets HTTP/1.1
Host: api.example.test
Authorization: Bearer <Firebase ID token>
```

У нового пользователя без бюджетов ответ `200 []`. Для облачного подключения приложение явно выбирает
[создание или импорт бюджета](firebase-budgets.md); дальнейший обмен использует
`/api/v4/budgets/{budgetId}/…`. Семейный доступ описан в
[приглашениях](firebase-budget-invitations.md) и [участниках](firebase-family-budgets.md).
Вызов auth endpoint не заменяет Bearer-заголовок в последующих запросах: каждый проверяется отдельно.

## Ошибки сервера

Все перечисленные ответы содержат `Cache-Control: no-store` и JSON `{"code":"…"}`.

| HTTP / code | Значение и действие Android |
| --- | --- |
| 401 / `unauthorized` | Нет допустимого токена, истёк/отозван, пользователь отключён/удалён либо провайдер не поддержан. Один раз вызвать `getIdToken(true)` и повторить запрос. При повторном 401 или невосстановимой ошибке обновления предложить повторный вход. 401 содержит `WWW-Authenticate: Bearer`. |
| 403 / `email_not_verified` | Password-токен не содержит boolean `email_verified=true`. Показать подтверждение/повторную отправку письма. После подтверждения выполнить reload и обновить токен. Не запускать бесконечные повторы запроса. |
| 403 / `access_denied` | Нет активного членства или нужной роли в бюджете. Обновление токена само по себе не выдаёт права. |
| 503 / `auth_unavailable` | Firebase-проверка временно недоступна на `/api/v4/auth/firebase`; предложить повтор позже, сохранить сессию. |
| 503 / `server_unavailable` | Временная ошибка проверки/привязки на budget endpoints; предложить повтор позже, сохранить сессию. |

Пример отказа: `HTTP 403`, `Cache-Control: no-store`, тело `{"code":"email_not_verified"}`.
Пустой email в password-токене приводит к 401. Другие провайдеры, включая anonymous/custom,
не поддерживаются. Для Google не добавлено нового требования подтверждения email.
Для повторяемой записи бюджета сохранять исходные requestId и тело согласно budget API.

## Ошибки Firebase SDK

Они возникают до вызова FinanceConsultante и не являются HTTP-кодами нашего API.
Обрабатывать типы исключений и доступные SDK error codes, не сравнивать текст exception.

| Ситуация / типичные коды | Поведение |
| --- | --- |
| Неверный email, слабый пароль / `ERROR_INVALID_EMAIL`, `ERROR_WEAK_PASSWORD` | Показать ошибку поля и требования политики Firebase. |
| Неверные credentials / `ERROR_INVALID_CREDENTIAL`, `ERROR_WRONG_PASSWORD`, `ERROR_USER_NOT_FOUND` | Единое сообщение «Не удалось войти. Проверьте email и пароль». Конкретность ошибок зависит от enumeration protection. |
| Email занят / `ERROR_EMAIL_ALREADY_IN_USE` | Предложить вход, восстановление или привязку после входа в существующий аккаунт; не создавать обходной профиль. |
| Credentials заняты / `ERROR_CREDENTIAL_ALREADY_IN_USE`, `ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL` | Не объединять аккаунты автоматически; предложить вход существующим способом. |
| Провайдер уже связан / `ERROR_PROVIDER_ALREADY_LINKED` | Обновить профиль/список провайдеров, не повторять привязку вслепую. |
| Нужен свежий вход / `ERROR_REQUIRES_RECENT_LOGIN` | Выполнить `reauthenticate` с существующими credentials и повторить действие. |
| Пользователь отключён / `ERROR_USER_DISABLED` | Остановить повторы и сообщить о невозможности входа. |
| Провайдер выключен / `ERROR_OPERATION_NOT_ALLOWED` | Ошибка настройки выпуска; повтор ввода пароля её не исправит. |
| Сеть / `FirebaseNetworkException` | Сохранить состояние и дать повторить позже. |
| Ограничение частоты / `FirebaseTooManyRequestsException` | Дать повторить позже, не отправлять письма и запросы циклически. |

## Привязка Google ↔ email/password

Идентификатор доступа — Firebase UID. Совпадение адресов не является основанием выдачи доступа
или переноса финансовых данных. Для сохранения бюджетов нужно связать credentials с текущим UID:

- **Google → пароль:** войти в существующий Google-аккаунт, получить текущего FirebaseUser,
  создать `EmailAuthProvider.getCredential(email, password)` для адреса этого аккаунта и вызвать
  `currentUser.linkWithCredential(credential)`. Не вызывать createUser для добавления пароля.
- **Пароль → Google:** войти в существующий email/password аккаунт, получить Google credential
  существующим Google-сценарием (`GoogleAuthProvider.getCredential(googleIdToken, null)`)
  и передать его в `currentUser.linkWithCredential(credential)`, вместо отдельного Firebase sign-in.

При требовании свежей сессии использовать `reauthenticate` с уже привязанным способом входа.
После успешной привязки обновить пользователя и токен. Проверить, что UID сохранился; следующий
password-вход должен иметь подтверждённый email. Google обычно уже подтверждает адрес, но клиент
не должен самостоятельно считать его подтверждённым вместо Firebase claims.

Если credentials принадлежат другому UID, остановить привязку и предложить существующий вход.
Слияние двух UID, перенос бюджетов и отвязка провайдеров не входят в этот контракт.
Для одного UID server UUID, права и история сохраняются при смене способа входа.

## Восстановление пароля

Вызвать `FirebaseAuth.sendPasswordResetEmail(email)`. Письмо открывает стандартную страницу Firebase
в браузере. После смены пароля пользователь возвращается в Fillin и входит с новым паролем.
Учитывать возможную недействительность прежней сессии по обычным правилам 401.
Для результата запроса использовать нейтральный текст: «Если для адреса доступно восстановление,
вы получите письмо». Не сообщать, существует ли пользователь; ошибки сети/лимиты позволяют повтор позже.

## Выпуск и проверка

Сначала выпустить совместимый сервер, затем включить сценарий в Android. Сервер не требует миграции БД.
В этой задаче настройки Console не изменяются. Обычные тесты используют подмену Firebase SDK;
реальная отправка писем и вход требуют отдельно выбранной ручной проверки на тестовом аккаунте.
Проверить регистрацию, отказ до подтверждения, новый токен после письма, повторный вход,
привязку с сохранением бюджета и восстановление пароля.

Официальные источники:
[Email/Password](https://firebase.google.com/docs/auth/android/password-auth),
[управление пользователями и письмами](https://firebase.google.com/docs/auth/android/manage-users),
[привязка провайдеров](https://firebase.google.com/docs/auth/android/account-linking),
[проверка ID tokens](https://firebase.google.com/docs/auth/admin/verify-id-tokens).
