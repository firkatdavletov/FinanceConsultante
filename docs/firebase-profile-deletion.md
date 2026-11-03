# Удаление профиля: API для Android

Сервер удаляет профиль приложения и аккаунт Firebase. Все бюджеты, в которых пользователь
является OWNER, удаляются целиком, включая операции остальных участников. Аккаунты этих
участников сохраняются. В чужих бюджетах удаляется доступ и персональная привязка пользователя;
его финансовые операции, технический UUID автора и баланс сохраняются. Их описания также
остаются частью общей истории. Автоматической передачи владения и отмены удаления нет.

## Перед запросом

Покажите подтверждение: «Аккаунт и все ваши облачные бюджеты будут удалены без возможности
восстановления. Общие бюджеты будут удалены для всех участников». Можно показать перечень
собственных бюджетов из `GET /api/v4/budgets`; он не ограничивает серверное удаление:
сервер удаляет все собственные бюджеты на момент выполнения транзакции.

Нужен Google- или password-вход за последние **5 минут**. Сервер проверяет `auth_time`
из проверенного Firebase ID token; `getIdToken(true)` обновляет токен, но не дату входа.
При `recent_login_required` выполните `currentUser.reauthenticate(credential)` для того же
аккаунта и запросите новый токен. Для Google credential получается через существующий
Google-вход, для password — через `EmailAuthProvider.getCredential(email, password)`.
Пароль и Google credential передаются только Firebase SDK, не нашему серверу.
Неподтверждённый email разрешён для удаления, но по-прежнему запрещён для финансовых API.

До HTTP сохраните отдельный секрет операции в защищённом хранилище, привязанном к аккаунту.
Секрет — 32 криптографически случайных байта в Base64URL без padding (43 символа).
При каждом повторе используйте тот же секрет. Не записывайте его в логи, аналитику и URL.
Остановите фоновые задания и новые отправки синхронизации этого аккаунта.

## HTTP

```http
DELETE /api/v4/profile
Authorization: Bearer <Firebase ID token>
X-Deletion-Token: <43-character Base64URL secret>
```

Тело не требуется; UID из JSON не принимается. Ответ:

```http
HTTP/1.1 202 Accepted
Cache-Control: no-store
Content-Type: application/json

{"status":"PENDING"}
```

`202` означает: SQL-транзакция завершена, данные приложения удалены, доступ закрыт;
удаление Firebase принято в надёжную очередь. Это ещё не подтверждение завершения Firebase.
Недоступность Firebase после приёма не отменяет удаление: сервер продолжает попытки.

Статус доступен по секрету без Firebase-токена, в том числе после выхода из аккаунта:

```http
GET /api/v4/profile/deletion
X-Deletion-Token: <same secret>
```

Ответ `200 {"status":"PENDING"}` либо `200 {"status":"COMPLETED"}`.
Повтор DELETE с известным секретом не требует Firebase-токена и возвращает
`202 PENDING` либо `200 COMPLETED`. Секрет предоставляет только доступ к результату
одного удаления и не позволяет выбрать другой UID. Все ответы API имеют `no-store`.

| HTTP / code | Действие клиента |
| --- | --- |
| 400 `invalid_deletion_token` | Исправить формат секрета; не генерировать новый для уже принятого удаления |
| 401 `unauthorized` | Токен отсутствует, неверен, отозван или аккаунт недоступен; сначала проверить результат по сохранённому секрету |
| 403 `recent_login_required` | Повторно подтвердить вход и отправить запрос с тем же секретом |
| 404 `deletion_not_found` (GET) | Принятое удаление по этому секрету не найдено; повторить исходный DELETE с действующим токеном |
| 409 `account_deletion_in_progress` | Аккаунт уже удаляется с другим секретом; использовать исходную сохранённую операцию, не возобновлять sync |
| 503 `auth_unavailable` | Проверка Firebase недоступна; повторить с задержкой |
| 503 `deletion_unavailable` | Сервер не смог принять запрос; проверить статус, затем повторить исходный запрос |
| Тайм-аут / обрыв связи | Результат неизвестен: GET по прежнему секрету, затем при необходимости повтор DELETE |

Обычный `401` не подтверждает удаление. Не вызывайте `FirebaseUser.delete()` на клиенте:
удалением Firebase управляет сервер. Не включайте эти запросы в универсальный interceptor,
который автоматически выполняет повторный вход или удаляет сохранённый секрет при `401`.

## Kotlin: последовательность вызовов

Пример использует существующий HTTP-клиент приложения; Retrofit-модели приведены ниже.
Хранилище и остановка sync должны переживать перезапуск процесса.

```kotlin
data class DeletionStatus(val status: String)

interface ProfileApi {
    @retrofit2.http.DELETE("api/v4/profile")
    suspend fun deleteProfile(
        @retrofit2.http.Header("Authorization") authorization: String?,
        @retrofit2.http.Header("X-Deletion-Token") secret: String,
    ): retrofit2.Response<DeletionStatus>

    @retrofit2.http.GET("api/v4/profile/deletion")
    suspend fun deletionStatus(
        @retrofit2.http.Header("X-Deletion-Token") secret: String,
    ): retrofit2.Response<DeletionStatus>
}

fun newDeletionSecret(): String {
    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    return android.util.Base64.encodeToString(
        bytes,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    )
}

// После подтверждения пользователем и при необходимости reauthenticate(credential):
val user = requireNotNull(firebaseAuth.currentUser)
val secret = deletionStore.load(user.uid) ?: newDeletionSecret().also {
    deletionStore.save(user.uid, it) // до HTTP, отдельно от очищаемых баз бюджета
}
sync.pauseAccount(user.uid)
val token = requireNotNull(user.getIdToken(true).await().token)
val response = profileApi.deleteProfile("Bearer $token", secret)
if (response.isSuccessful) {
    // Маркер принятого удаления сохраняется до локальной очистки: она повторяется после сбоя.
    deletionStore.markAccepted(user.uid)
    localData.eraseAccount(user.uid) // включая архивы и очередь; автономные бюджеты сохранить
    firebaseAuth.signOut()
    // GET по secret до COMPLETED, затем удалить секрет и маркер.
}
```

`await()` — из `kotlinx.coroutines.tasks.await`. Псевдоинтерфейсы `deletionStore`, `sync`
и `localData` заменяются компонентами Android-приложения. Обработку ошибок выполнять
по таблице; исключение транспорта после отправки переводит сценарий в проверку статуса.
Проверять статус при открытии приложения и с задержкой, например 10 секунд, пока экран
открыт; при отсутствии сети увеличить интервал. Показывать «Удаление выполняется» до
`COMPLETED`. После принятия удаление не отменяется, синхронизация аккаунта не возобновляется.

## Другие устройства и границы очистки

Другие устройства удаляемого пользователя должны обработать закрытие доступа и выход.
Другие участники при исчезновении бюджета из актуального успешного списка бюджетов должны
остановить обмен и очистить его локальную облачную копию, включая очередь и архивы.
Одиночный сетевой сбой или общий `403` не является достаточным подтверждением удаления
бюджета. Старую очередь нельзя автоматически импортировать или отправлять в новый аккаунт.

Сервер не может немедленно стереть копии на отключённых устройствах. Независимые локальные
бюджеты сохраняются. Резервные копии сервера этой операцией не переписываются; после
восстановления backup требуется повторно применить удаления, произошедшие после его даты.

## Эксплуатация

Нужны существующие `FIREBASE_AUTH_ENABLED=true`, project ID и ADC с разрешением удаления
пользователей Firebase (`firebaseauth.users.delete`). Используется один SDK-сеанс проверки
и удаления. Новых клиентских Firebase-ключей или зависимостей серверу не требуется.
Worker проверяет очередь каждые 10 секунд (`app.firebase-auth.deletion-poll-delay`),
использует lease на минуту и повторяет ошибки с задержкой от 10 секунд до часа.
`USER_NOT_FOUND` означает успех. После завершения UID очищается из задания. Хеш UID,
хеш секрета, технический результат и timestamps сохраняются для повтора и запрета
восстановления старого UID; имя, email и финансовые данные в receipt отсутствуют.

Метрики: `profile.deletion.pending`, `profile.deletion.errors`,
`profile.deletion.oldest.seconds`. Рост очереди и её возраста требует проверки прав ADC
и доступности Firebase. Исправление инфраструктуры позволяет worker продолжить работу.
Сначала выпускается сервер с V30, затем мобильный сценарий. При принятом удалении откат
сервера на версию без блокировки удалённых UID недопустим.

Источники: [Admin SDK: delete user](https://firebase.google.com/docs/auth/admin/manage-users#delete_a_user),
[Android: повторный вход](https://firebase.google.com/docs/auth/android/manage-users#re-authenticate_a_user),
[проверка отзыва токенов](https://firebase.google.com/docs/auth/admin/manage-sessions).
